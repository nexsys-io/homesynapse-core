/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.ProbeAnsweredEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recovery-card projection v1 (AMD-103 R-F, R-G; AVAIL-API-1 §4.5) — a second projection
 * subscriber beside the state projection, materializing per device the newest
 * {@code probe_answered}, the newest {@code availability_contract_declared} and the newest
 * {@code availability_changed}'s instant across the device's entities (the entity → device
 * mapping read from the registry's LIVE view). Keyed by {@link DeviceId}; the read interface
 * is {@link RecoveryCardQueryService}; the bound is {@link StaleRecordBound}'s.
 *
 * <h2>Path (b): the type-scoped catch-up, then the bus</h2>
 *
 * <p>REGISTRY-COLD-1's four steps, reused: (1) H = {@code latestPosition()}; (2) the three
 * types read through {@code readByType} — the per-type cursors MERGED by
 * {@code global_position} and cut at H — applied through {@link #apply}, the projection's
 * OWN path, which never drops in COLD; (3) the checkpoint written as H, <strong>durable and
 * coupled</strong>: {@link AtomicCheckpointSink#writeAtomicCheckpoint} writes the subscriber
 * checkpoint and the view bytes in ONE transaction under {@link #SUBSCRIBER_ID} (AMD-45 §2.2);
 * (4) the composition root subscribes with {@link #subscriptionFilter()} and
 * {@code atomicCheckpoint = true}, under which the bus writes no checkpoint of its own — the
 * projection writes the same coupled checkpoint after EVERY applied row. At boot
 * {@link #restoreFromCheckpoint()} restores the view from the persisted bytes and SKIPS the
 * catch-up, so it runs ONCE and a restart resumes from the checkpoint.</p>
 *
 * <h2>The view bytes</h2>
 *
 * <p>A {@code java.base} binary form ({@link DataOutputStream}: a magic, a version, the
 * position, the rows) — this module carries no Jackson edge and the instruction bars a new
 * one; the persistence layer stores the bytes opaquely.</p>
 *
 * <h2>LIVE and the null state</h2>
 *
 * <p>{@link #isLive()} is true from the bus's {@link #onCaughtUp()} until a SUSPENDED mode;
 * while not LIVE the renderer serves every v1.1.7 key {@code null} (AMD-103 §3). A failed
 * catch-up is the composition root's arm: logged once, the projection left not LIVE, never a
 * failed boot.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #onEvent} runs on the subscriber's single virtual thread, {@link #catchUp} on the
 * boot thread before the subscription — one writer; reads come from any thread. A
 * {@link ReentrantLock} guards the map (LTD-11); the checkpoint's I/O happens OUTSIDE the lock
 * on the writer's thread, the bytes taken under it.</p>
 */
public final class RecoveryCardProjection implements Subscriber, RecoveryCardQueryService {

    /** The stable subscriber id — the key of both coupled checkpoint rows. */
    public static final String SUBSCRIBER_ID = "recovery-card-projection";

    /** The view format version written into the checkpoint bytes. */
    public static final int VIEW_VERSION = 1;

    /** The three types, in the order the cursors are opened (the filter builds from this list). */
    public static final List<String> EVENT_TYPES = List.of(
            EventTypes.PROBE_ANSWERED,
            EventTypes.AVAILABILITY_CONTRACT_DECLARED,
            EventTypes.AVAILABILITY_CHANGED);

    /** The catch-up's page — the bus's {@code ReplayDriver.MAX_REPLAY_PAGE} value. */
    static final int CATCH_UP_PAGE = 500;

    private static final int MAGIC = 0x52434152;

    private static final Logger log = LoggerFactory.getLogger(RecoveryCardProjection.class);

    private final ViewCheckpointStore viewStore;
    private final AtomicCheckpointSink sink;
    private final Supplier<EntityRegistry> registry;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<DeviceId, RecoveryCardRecord> view = new HashMap<>();
    private final AtomicBoolean live = new AtomicBoolean(false);
    private final AtomicReference<SubscriberMode> mode = new AtomicReference<>(SubscriberMode.COLD);
    private volatile long position;
    private volatile int askedCount;

    /**
     * Creates the projection; no I/O until {@link #restoreFromCheckpoint()} or
     * {@link #catchUp}.
     *
     * @param viewStore the view checkpoint store the restore reads; never {@code null}
     * @param sink      the coupled subscriber+view checkpoint sink (AMD-45 §2.1); never
     *                  {@code null} — {@link AtomicCheckpointSink#viewOnly} for an in-memory
     *                  deployment
     * @param registry  the LIVE entity registry, supplied at apply time (the entity → device
     *                  mapping of {@code availability_changed}); a {@code null} supply maps
     *                  nothing; never {@code null}
     * @param clock     the injected clock (the catch-up's duration); never {@code null}
     */
    public RecoveryCardProjection(ViewCheckpointStore viewStore, AtomicCheckpointSink sink,
            Supplier<EntityRegistry> registry, Clock clock) {
        this.viewStore = Objects.requireNonNull(viewStore, "viewStore");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The bus filter: the three types, type-only.
     *
     * @return the filter
     */
    public static SubscriptionFilter subscriptionFilter() {
        return SubscriptionFilter.forTypes(EVENT_TYPES.toArray(String[]::new));
    }

    /**
     * Restores the view from the persisted coupled checkpoint, when one exists.
     *
     * @return {@code true} when a checkpoint was restored (the catch-up is skipped)
     * @throws IllegalStateException when the checkpoint's bytes are not this projection's
     *         view (the composition root falls back to the catch-up, which rewrites them)
     */
    public boolean restoreFromCheckpoint() {
        Optional<CheckpointRecord> latest = viewStore.readLatestCheckpoint(SUBSCRIBER_ID);
        if (latest.isEmpty()) {
            return false;
        }
        CheckpointRecord record = latest.get();
        ViewSnapshot snapshot = decodeView(record.data());
        if (snapshot.position() != record.position()) {
            throw new IllegalStateException("recovery-card checkpoint position mismatch: row="
                    + record.position() + " view=" + snapshot.position());
        }
        int devices;
        lock.lock();
        try {
            view.clear();
            view.putAll(snapshot.view());
            position = record.position();
            askedCount = countAsked();
            devices = view.size();
        } finally {
            lock.unlock();
        }
        log.info("RecoveryCardProjection restored at position {}; devices={} asked={}",
                record.position(), devices, askedCount);
        return true;
    }

    /**
     * The catch-up (path (b)): H, the three cursors merged up to H through {@link #apply}, the
     * coupled checkpoint at H. Runs once, on the boot thread, before the subscription.
     *
     * @param store the event store the bus will replay from
     * @return the rows passed to the apply path (the three types' rows ≤ H)
     * @throws IllegalStateException on a typed read that returns another type or two cursors
     *         at one position; a store read that throws propagates
     */
    public long catchUp(EventStore store) {
        Objects.requireNonNull(store, "store");
        Instant started = clock.instant();
        long head = store.latestPosition();
        SubscriptionFilter filter = subscriptionFilter();
        List<TypeCursor> cursors = new ArrayList<>(EVENT_TYPES.size());
        for (String type : EVENT_TYPES) {
            cursors.add(new TypeCursor(store, type, head));
        }
        long applied = 0L;
        while (true) {
            TypeCursor next = null;
            for (TypeCursor cursor : cursors) {
                EventEnvelope candidate = cursor.peek();
                if (candidate == null) {
                    continue;
                }
                if (next == null) {
                    next = cursor;
                    continue;
                }
                long candidatePosition = candidate.globalPosition();
                long lowest = next.peek().globalPosition();
                if (candidatePosition == lowest) {
                    throw new IllegalStateException("recovery-card catch-up: two type cursors at "
                            + "one position: " + cursor.type + " and " + next.type
                            + " at position=" + candidatePosition);
                }
                if (candidatePosition < lowest) {
                    next = cursor;
                }
            }
            if (next == null) {
                break;
            }
            EventEnvelope envelope = next.take();
            if (!filter.matches(envelope)) {
                throw new IllegalStateException("recovery-card catch-up: the type read for "
                        + next.type + " returned type=" + envelope.eventType() + " at position="
                        + envelope.globalPosition());
            }
            applied++;
            lock.lock();
            try {
                apply(envelope);
            } finally {
                lock.unlock();
            }
        }
        byte[] bytes;
        int devices;
        lock.lock();
        try {
            position = head;
            bytes = encodeView(view, head);
            devices = view.size();
        } finally {
            lock.unlock();
        }
        sink.writeAtomicCheckpoint(SUBSCRIBER_ID, head, bytes);
        long durationMs = Math.max(0L, Duration.between(started, clock.instant()).toMillis());
        log.info("RecoveryCardProjection caught up at position {}; "
                        + "projection.replay.duration_ms={} events_replayed={}",
                head, durationMs, applied);
        log.debug("recovery_card.catchup: devices={} asked={}", devices, askedCount);
        return applied;
    }

    /**
     * The bus's delivery: the row applied, the position advanced, the coupled checkpoint
     * written — after EVERY delivered row (the volume is R-B's).
     *
     * @param event the delivered envelope; never {@code null}
     */
    @Override
    public void onEvent(EventEnvelope event) {
        Objects.requireNonNull(event, "event");
        byte[] bytes;
        long at;
        lock.lock();
        try {
            apply(event);
            position = event.globalPosition();
            at = position;
            bytes = encodeView(view, at);
        } finally {
            lock.unlock();
        }
        sink.writeAtomicCheckpoint(SUBSCRIBER_ID, at, bytes);
    }

    /** The bus's REPLAY → LIVE handoff: the keys are served from here. */
    @Override
    public void onCaughtUp() {
        live.set(true);
        log.info("recovery_card.live: position={} devices={} asked={}", position, deviceCount(),
                askedCount);
    }

    @Override
    public void setMode(SubscriberMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        this.mode.set(mode);
        if (mode == SubscriberMode.SUSPENDED) {
            live.set(false);
        }
    }

    @Override
    public Optional<RecoveryCardRecord> recordFor(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        lock.lock();
        try {
            return Optional.ofNullable(view.get(deviceId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int askedCount() {
        return askedCount;
    }

    @Override
    public boolean isLive() {
        return live.get();
    }

    @Override
    public long position() {
        return position;
    }

    /**
     * The whole view, copied — the comparison tests' read.
     *
     * @return an immutable copy of the view
     */
    Map<DeviceId, RecoveryCardRecord> view() {
        lock.lock();
        try {
            return Map.copyOf(view);
        } finally {
            lock.unlock();
        }
    }

    private int deviceCount() {
        lock.lock();
        try {
            return view.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The apply path, under the lock: the newest record of each kind wins by its own instant
     * (a row with an older instant at a later position never overwrites); a degraded row is
     * skipped with one WARN; a row of an unexpected shape is skipped with one WARN.
     */
    private void apply(EventEnvelope event) {
        switch (event.payload()) {
            case ProbeAnsweredEvent probe -> deviceOf(event).ifPresent(deviceId -> {
                RecoveryCardRecord current = view.getOrDefault(deviceId,
                        RecoveryCardRecord.empty(deviceId));
                if (current.probeAt() == null || !probe.at().isBefore(current.probeAt())) {
                    view.put(deviceId, current.withProbe(probe));
                }
            });
            case AvailabilityContractDeclaredEvent contract -> deviceOf(event).ifPresent(deviceId -> {
                RecoveryCardRecord current = view.getOrDefault(deviceId,
                        RecoveryCardRecord.empty(deviceId));
                Instant at = stamp(event);
                if (current.contractAt() == null || !at.isBefore(current.contractAt())) {
                    view.put(deviceId, current.withContract(contract, at));
                    askedCount = countAsked();
                }
            });
            case AvailabilityChangedEvent changed -> owningDevice(event).ifPresent(deviceId -> {
                RecoveryCardRecord current = view.getOrDefault(deviceId,
                        RecoveryCardRecord.empty(deviceId));
                Instant at = stamp(event);
                if (current.availabilityChangedAt() == null
                        || !at.isBefore(current.availabilityChangedAt())) {
                    view.put(deviceId, current.withAvailabilityChangedAt(at));
                }
            });
            case DegradedEvent degraded -> log.warn(
                    "recovery_card.degraded_event: type={} position={} reason={}",
                    degraded.eventType(), event.globalPosition(), degraded.failureReason());
            default -> log.warn("recovery_card.unexpected_payload: type={} position={} payload={}",
                    event.eventType(), event.globalPosition(),
                    event.payload().getClass().getSimpleName());
        }
    }

    /** The record's DEVICE subject — a non-DEVICE subject on a device record is a producer defect. */
    private static Optional<DeviceId> deviceOf(EventEnvelope event) {
        SubjectRef ref = event.subjectRef();
        if (ref.type() != SubjectType.DEVICE) {
            log.warn("recovery_card.not_a_device_subject: type={} position={} subject={}",
                    event.eventType(), event.globalPosition(), ref.type());
            return Optional.empty();
        }
        return Optional.of(new DeviceId(ref.id()));
    }

    /** The ENTITY subject's owning device from the LIVE registry; nothing when unknown or helper. */
    private Optional<DeviceId> owningDevice(EventEnvelope event) {
        SubjectRef ref = event.subjectRef();
        if (ref.type() != SubjectType.ENTITY) {
            return Optional.empty();
        }
        EntityRegistry entities = registry.get();
        if (entities == null) {
            return Optional.empty();
        }
        return entities.findEntity(new EntityId(ref.id())).map(Entity::deviceId);
    }

    /** The row's instant: {@code eventTime ?? ingestTime} (the AMD-53 stamp rule). */
    private static Instant stamp(EventEnvelope event) {
        return event.eventTime() != null ? event.eventTime() : event.ingestTime();
    }

    private int countAsked() {
        int count = 0;
        for (RecoveryCardRecord record : view.values()) {
            if (record.asked()) {
                count++;
            }
        }
        return count;
    }

    // ──────────────────────────────────────────────────────────────────
    // The view bytes
    // ──────────────────────────────────────────────────────────────────

    /**
     * A decoded view: the position the bytes were written at and the rows.
     *
     * @param position the position
     * @param view     the rows by device
     */
    record ViewSnapshot(long position, Map<DeviceId, RecoveryCardRecord> view) { }

    private static final int FLAG_PROBE = 1;
    private static final int FLAG_LINK = 1 << 1;
    private static final int FLAG_CONTRACT = 1 << 2;
    private static final int FLAG_REPORT = 1 << 3;
    private static final int FLAG_RECORD_INTERVAL = 1 << 4;
    private static final int FLAG_CHANGED = 1 << 5;

    /**
     * Encodes the view at a position.
     *
     * @param view     the rows by device
     * @param position the position
     * @return the bytes
     */
    static byte[] encodeView(Map<DeviceId, RecoveryCardRecord> view, long position) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + view.size() * 96);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VIEW_VERSION);
            out.writeLong(position);
            out.writeInt(view.size());
            for (RecoveryCardRecord record : view.values()) {
                out.write(record.deviceId().value().toBytes());
                int flags = (record.hasProbe() ? FLAG_PROBE : 0)
                        | (record.linkAt() != null ? FLAG_LINK : 0)
                        | (record.hasContract() ? FLAG_CONTRACT : 0)
                        | (record.reportIntervalSeconds() != null ? FLAG_REPORT : 0)
                        | (record.recordIntervalSeconds() != null ? FLAG_RECORD_INTERVAL : 0)
                        | (record.availabilityChangedAt() != null ? FLAG_CHANGED : 0);
                out.writeByte(flags);
                if (record.hasProbe()) {
                    writeInstant(out, record.probeAt());
                    writeInstant(out, record.probeWindowStart());
                    out.writeInt(record.probesAnswered());
                    out.writeInt(record.probesMissed());
                    if (record.linkAt() != null) {
                        out.writeInt(record.lqi());
                        out.writeInt(record.rssiDbm());
                        writeInstant(out, record.linkAt());
                    }
                }
                if (record.hasContract()) {
                    out.writeUTF(record.availabilityClass());
                    if (record.reportIntervalSeconds() != null) {
                        out.writeInt(record.reportIntervalSeconds());
                    }
                    out.writeInt(record.silenceLimitSeconds());
                    out.writeInt(record.missesToDark());
                    if (record.recordIntervalSeconds() != null) {
                        out.writeInt(record.recordIntervalSeconds());
                    }
                    writeInstant(out, record.contractAt());
                }
                if (record.availabilityChangedAt() != null) {
                    writeInstant(out, record.availabilityChangedAt());
                }
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("recovery-card view encoding failed", impossible);
        }
        return bytes.toByteArray();
    }

    /**
     * Decodes view bytes.
     *
     * @param bytes the bytes
     * @return the snapshot
     * @throws IllegalStateException when the bytes are not this projection's view
     */
    static ViewSnapshot decodeView(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) {
                throw new IllegalStateException("recovery-card view: not a recovery-card checkpoint");
            }
            int version = in.readInt();
            if (version != VIEW_VERSION) {
                throw new IllegalStateException("recovery-card view: unknown version " + version);
            }
            long position = in.readLong();
            int count = in.readInt();
            if (count < 0) {
                throw new IllegalStateException("recovery-card view: negative row count " + count);
            }
            Map<DeviceId, RecoveryCardRecord> rows = new HashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                byte[] id = new byte[16];
                in.readFully(id);
                DeviceId deviceId = DeviceId.of(Ulid.fromBytes(id));
                int flags = in.readByte();
                Instant probeAt = null;
                Instant windowStart = null;
                Integer answered = null;
                Integer missed = null;
                Integer lqi = null;
                Integer rssi = null;
                Instant linkAt = null;
                if ((flags & FLAG_PROBE) != 0) {
                    probeAt = readInstant(in);
                    windowStart = readInstant(in);
                    answered = in.readInt();
                    missed = in.readInt();
                    if ((flags & FLAG_LINK) != 0) {
                        lqi = in.readInt();
                        rssi = in.readInt();
                        linkAt = readInstant(in);
                    }
                }
                String availabilityClass = null;
                Integer report = null;
                Integer silence = null;
                Integer misses = null;
                Integer recordInterval = null;
                Instant contractAt = null;
                if ((flags & FLAG_CONTRACT) != 0) {
                    availabilityClass = in.readUTF();
                    if ((flags & FLAG_REPORT) != 0) {
                        report = in.readInt();
                    }
                    silence = in.readInt();
                    misses = in.readInt();
                    if ((flags & FLAG_RECORD_INTERVAL) != 0) {
                        recordInterval = in.readInt();
                    }
                    contractAt = readInstant(in);
                }
                Instant changedAt = (flags & FLAG_CHANGED) != 0 ? readInstant(in) : null;
                rows.put(deviceId, new RecoveryCardRecord(deviceId, probeAt, windowStart, answered,
                        missed, lqi, rssi, linkAt, availabilityClass, report, silence, misses,
                        recordInterval, contractAt, changedAt));
            }
            if (in.read() != -1) {
                throw new IllegalStateException("recovery-card view: trailing bytes");
            }
            return new ViewSnapshot(position, Map.copyOf(rows));
        } catch (IOException | IllegalArgumentException malformed) {
            throw new IllegalStateException("recovery-card view: unreadable bytes ("
                    + malformed.getMessage() + ")", malformed);
        }
    }

    private static void writeInstant(DataOutputStream out, Instant instant) throws IOException {
        out.writeLong(instant.getEpochSecond());
        out.writeInt(instant.getNano());
    }

    private static Instant readInstant(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        return Instant.ofEpochSecond(seconds, nanos);
    }

    // ──────────────────────────────────────────────────────────────────
    // The type cursor (REGISTRY-COLD-1's form)
    // ──────────────────────────────────────────────────────────────────

    /**
     * One type's read cursor over {@link EventStore#readByType}: at most one page in hand; the
     * cut at {@code head} is this cursor's (the read has no upper bound), and
     * {@code hasMore == false} ends it too.
     */
    private static final class TypeCursor {
        private final EventStore store;
        private final String type;
        private final long head;
        private List<EventEnvelope> page = List.of();
        private int index;
        private long after;
        private boolean started;
        private boolean hasMore;
        private boolean exhausted;

        TypeCursor(EventStore store, String type, long head) {
            this.store = store;
            this.type = type;
            this.head = head;
        }

        /** @return the next envelope at or below head, or {@code null} when this type is done */
        EventEnvelope peek() {
            while (!exhausted) {
                if (index < page.size()) {
                    EventEnvelope candidate = page.get(index);
                    if (candidate.globalPosition() > head) {
                        exhausted = true;
                        return null;
                    }
                    return candidate;
                }
                if (started && !hasMore) {
                    exhausted = true;
                    return null;
                }
                EventPage next = store.readByType(type, after, CATCH_UP_PAGE);
                started = true;
                page = next.events();
                index = 0;
                hasMore = next.hasMore();
                after = next.nextPosition();
                if (page.isEmpty()) {
                    exhausted = true;
                }
            }
            return null;
        }

        EventEnvelope take() {
            EventEnvelope envelope = peek();
            if (envelope == null) {
                throw new IllegalStateException("recovery-card catch-up: no row to take for " + type);
            }
            index++;
            return envelope;
        }
    }
}

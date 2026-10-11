/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DeviceRegisteredEvent;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.integration.CapabilityAdded;
import com.homesynapse.platform.identity.DeviceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The bus-facing wrapper of the {@link RegistryProjection} (AMD-99 §5 /
 * REG-INV-1): consumes the four registry event types (the three
 * registration/removal types and, since IR-67, {@code capability.added}) and
 * delegates every apply to the single projection-apply path. Because the
 * in-memory registries start empty every boot, the composition root rebuilds
 * them at Step 3.1 in four steps (REGISTRY-COLD-1, IR-148): (1) note the log's
 * head H; (2) {@link #rebuildFromIndex} — the four types read through the
 * store's type index up to H, merged into log order, every row to
 * {@link #onEvent}; (3) write H as this subscriber's checkpoint (was 0: the
 * reset made every boot walk the whole log through the bus's page walk);
 * (4) subscribe as before, so the bus replays only the rows after H. REG-INV-1
 * holds — the same types, in log order, through the same apply path; only the
 * read changed.
 *
 * <p>The subscriber NEVER publishes (INV-ES-09 — a pure projection: replay
 * and live delivery run the identical apply). The tombstone's device identity
 * rides the envelope's {@code SubjectRef.device(...)} (G-DUR7), extracted
 * here.</p>
 *
 * <p><strong>Positive evidence (anti-vacuous).</strong> On the AMD-42
 * TRANSITION&rarr;LIVE callback ({@link #onCaughtUp()}) this logs ONE
 * {@code registry.projection_live} INFO with device/entity counts and the
 * replay head position — the boot glance-point proving the rebuild ran (a
 * silently-succeedable arm ships positive evidence).</p>
 */
final class RegistryProjectionSubscriber implements Subscriber {

    /** The stable subscriber id — the checkpoint key Step 3.1 writes H to. */
    static final String SUBSCRIBER_ID = "registry_projection";

    /** The four registry event types — the filter and the rebuild read from ONE list. */
    private static final List<String> REGISTRY_EVENT_TYPES = List.of(
            EventTypes.DEVICE_REGISTERED,
            EventTypes.ENTITY_REGISTERED,
            EventTypes.DEVICE_REMOVED,
            EventTypes.CAPABILITY_ADDED);

    /** The rebuild's page: the bus's replay page ({@code ReplayDriver.MAX_REPLAY_PAGE} = 500). */
    static final int REBUILD_PAGE = 500;

    /**
     * The bus supervisor's circuit breaker, retyped ({@code SubscriberSupervisor
     * .CIRCUIT_BREAKER_THRESHOLD}, private to the bus): the fifth apply failure ends the rebuild.
     */
    static final int REBUILD_CRASH_THRESHOLD = 5;

    private static final Logger LOG =
            LoggerFactory.getLogger(RegistryProjectionSubscriber.class);

    private final RegistryProjection projection;
    private final DeviceRegistry deviceRegistry;
    private final EntityRegistry entityRegistry;
    private volatile long lastAppliedPosition;
    /** The throw arm's count during the rebuild (written and read on the boot thread). */
    private long skipped;

    /**
     * Creates the wrapper.
     *
     * @param projection the single apply path, never {@code null}
     * @param deviceRegistry the device registry (read-only here — count
     *        evidence for the LIVE INFO), never {@code null}
     * @param entityRegistry the entity registry (read-only here), never {@code null}
     */
    RegistryProjectionSubscriber(RegistryProjection projection,
            DeviceRegistry deviceRegistry, EntityRegistry entityRegistry) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
    }

    /** @return the type filter for the four registry event types. */
    static SubscriptionFilter subscriptionFilter() {
        return SubscriptionFilter.forTypes(REGISTRY_EVENT_TYPES.toArray(String[]::new));
    }

    /**
     * The boot rebuild (REGISTRY-COLD-1, IR-148): reads the four registry types
     * through the store's type index up to {@code head} inclusive, merges the four
     * per-type cursors into log order by {@code globalPosition} (per-type order is
     * not log order — a removal then a re-registration, the {@code capability.added}
     * orphan arm), and passes every envelope to {@link #onEvent} — the SAME method
     * the bus calls, so the degraded-row and unexpected-payload WARN arms, the
     * orphan WARN and {@code lastAppliedPosition} behave exactly as under the bus.
     *
     * <p>A {@link RuntimeException} out of {@code onEvent} is handled as the bus's
     * supervisor handles it: the row is skipped with ONE WARN
     * ({@code registry.projection_rebuild_apply_failed}) and counted
     * ({@link #skipped()}); the {@value #REBUILD_CRASH_THRESHOLD}th failure ends the
     * rebuild with an {@link IllegalStateException} naming the count and the
     * position — the boot then fails inside Step 3.1. An {@link Error} or a checked
     * exception is not caught (the supervisor SUSPENDs at the first); a store read
     * that throws propagates.</p>
     *
     * <p>Runs on the boot thread before {@code subscribeRuntime}; nothing else
     * touches the registries then. Positions are unique, so two cursors at one
     * position is a store defect and throws.</p>
     *
     * @param store the event store the bus will replay from; the registry's rows
     *              are read through {@link EventStore#readByType}
     * @param head  the log's last position as the boot read it; rows above it are
     *              the bus's
     * @return the envelopes passed to {@code onEvent}, the throw arm's rows included
     * @throws IllegalStateException at the {@value #REBUILD_CRASH_THRESHOLD}th apply
     *         failure, on a typed read that returns another type, or on two cursors
     *         at one position
     */
    long rebuildFromIndex(EventStore store, long head) {
        Objects.requireNonNull(store, "store");
        if (head < 0L) {
            throw new IllegalArgumentException("head must be >= 0, got " + head);
        }
        skipped = 0L;
        SubscriptionFilter filter = subscriptionFilter();
        List<TypeCursor> cursors = new ArrayList<>(REGISTRY_EVENT_TYPES.size());
        for (String type : REGISTRY_EVENT_TYPES) {
            cursors.add(new TypeCursor(store, type, head));
        }
        long passed = 0L;
        while (true) {
            // The four-way merge: the cursor whose next row has the lowest position.
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
                long position = candidate.globalPosition();
                long lowest = next.peek().globalPosition();
                if (position == lowest) {
                    throw new IllegalStateException("registry rebuild: two type cursors at "
                            + "one position: " + cursor.type + " and " + next.type
                            + " at position=" + position);
                }
                if (position < lowest) {
                    next = cursor;
                }
            }
            if (next == null) {
                return passed;
            }
            EventEnvelope envelope = next.take();
            if (!filter.matches(envelope)) {
                throw new IllegalStateException("registry rebuild: the type read for " + next.type
                        + " returned type=" + envelope.eventType() + " at position="
                        + envelope.globalPosition());
            }
            passed++;
            applyDuringRebuild(envelope);
        }
    }

    /** @return the throw arm's count of the last {@link #rebuildFromIndex}; 0 when nothing threw. */
    long skipped() {
        return skipped;
    }

    /**
     * The supervisor's RuntimeException arm, in the rebuild's own hands: the row is
     * skipped and counted with one WARN; the threshold ends the rebuild.
     */
    private void applyDuringRebuild(EventEnvelope envelope) {
        try {
            onEvent(envelope);
        } catch (RuntimeException failure) {
            skipped++;
            LOG.warn("registry.projection_rebuild_apply_failed: type={} position={} cause={}",
                    envelope.eventType(), envelope.globalPosition(), failure.toString());
            if (skipped >= REBUILD_CRASH_THRESHOLD) {
                throw new IllegalStateException("registry rebuild stopped: " + skipped
                        + " registration rows failed to apply (REBUILD_CRASH_THRESHOLD="
                        + REBUILD_CRASH_THRESHOLD + "); the last at type="
                        + envelope.eventType() + " position=" + envelope.globalPosition()
                        + " cause=" + failure, failure);
            }
        }
    }

    /**
     * One type's read cursor over {@link EventStore#readByType}: at most one page in
     * hand; the cut at {@code head} is this cursor's (the read has no upper bound),
     * and {@code hasMore == false} ends it too.
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
                EventPage next = store.readByType(type, after, REBUILD_PAGE);
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
                throw new IllegalStateException("registry rebuild: no row to take for " + type);
            }
            index++;
            return envelope;
        }
    }

    @Override
    public void onEvent(EventEnvelope event) {
        switch (event.payload()) {
            case DeviceRegisteredEvent registered ->
                    projection.applyDeviceRegistered(registered);
            case EntityRegisteredEvent registered ->
                    projection.applyEntityRegistered(registered);
            case DeviceRemovedEvent removed -> projection.applyDeviceRemoved(
                    new DeviceId(event.subjectRef().id()), removed);
            case CapabilityAdded added -> {
                // IR-67: the fourth apply. The orphan arm (an entity the registry
                // lacks — a wipe or a half-registration repair, both handled
                // downstream) is the projection's false; the WARN is this
                // subscriber's — device-model carries no logging edge.
                if (!projection.applyCapabilityAdded(added.entityId(), added.instance())) {
                    LOG.warn("registry.capability_added_orphan: entity={} capability={} "
                                    + "position={}",
                            added.entityId(), added.capabilityId(), event.globalPosition());
                }
            }
            case DegradedEvent degraded ->
                    // A registration row that failed decode cannot rebuild its
                    // registry entry — loud, never silent (the registry view is
                    // missing a device the log intended to carry).
                    LOG.warn("registry.projection_degraded_event: type={} position={} "
                                    + "reason={}",
                            degraded.eventType(), event.globalPosition(),
                            degraded.failureReason());
            default -> LOG.warn("registry.projection_unexpected_payload: type={} "
                            + "payloadClass={} position={}",
                    event.eventType(), event.payload().getClass().getSimpleName(),
                    event.globalPosition());
        }
        lastAppliedPosition = event.globalPosition();
    }

    @Override
    public void onCaughtUp() {
        LOG.info("registry.projection_live: devices={} entities={} position={}",
                deviceRegistry.listAllDevices().size(),
                entityRegistry.listAllEntities().size(),
                lastAppliedPosition);
    }
}

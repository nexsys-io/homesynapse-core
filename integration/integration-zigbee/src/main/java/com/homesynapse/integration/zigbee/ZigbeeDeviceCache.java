/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Doc 08 §3.14 local device metadata cache: per-IEEE protocol metadata plus
 * the NWK→IEEE index the ingestion resolves senders through, serialized to
 * {@code zigbee-devices.json} (Jackson tree model, interior-only) with a 30 s
 * write debounce and a shutdown flush.
 *
 * <p>The JSON additionally carries {@code lastKnownAvailability} per device —
 * the §8.1 M-1 persisted pre-restart availability the
 * {@link StandardAvailabilityTracker} initializes from (the frozen
 * {@link ZigbeeDeviceRecord} has no availability component; the FILE carries
 * the sidecar, tolerated-additively by this loader) — plus, per device, the
 * WU-AVAIL-SEED DP-4 {@code lastEvidenceAt} (ISO-8601 UTC): the last
 * device-originated evidence instant, persisted so a restart never orphans
 * the availability timeout clocks. Additive and tolerated: an old-format file
 * (absent field) loads with unknown recency; a malformed value is skipped
 * with one WARN, never a load failure. The JSON also carries a TOP-LEVEL
 * {@code learnedZoneTypes} section (LEARN-PERSIST DP-LP-1): wire-learned IAS
 * zone-type ids keyed by IEEE hex, top-level rather than per-record so a learn
 * for a device with no record node still persists and the frozen record shape
 * is provably untouched. Absence and malformed content both degrade to
 * unlearned (skip + one WARN), never a load failure. A second TOP-LEVEL
 * section, {@code learnedMeteringFormatting} (ENERGY-READ R3), carries what
 * each metering endpoint DECLARED about its own scale — IEEE hex → endpoint →
 * the multiplier/divisor pairs and the unit as read at adoption — under the
 * same additive tolerance: an old cache loads clean, malformed content
 * degrades to unread formatting.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11). Writes snapshot the
 * serializable state under the lock and perform the file I/O outside it, so
 * reads never block on a write's I/O; a failed write suppresses further
 * attempts for {@link #WRITE_FAILURE_BACKOFF_MILLIS} (F-14). The file lands
 * temp-then-move (F-3), so a torn write never replaces a complete sidecar.
 */
final class ZigbeeDeviceCache {

    /** Writes debounce to at most one per this window (Doc 08 §3.14). */
    static final Duration WRITE_DEBOUNCE = Duration.ofSeconds(30);

    /**
     * A failed write suppresses further write attempts for this long.
     * {@code maybeFlush()} runs every adapter cycle: without the backoff a
     * broken target (full disk, revoked mount) turns each cycle into a fresh
     * failed syscall plus a WARN — a log storm on a box that is already
     * degraded (F-14, M9.4b §6.7).
     */
    static final long WRITE_FAILURE_BACKOFF_MILLIS = 60_000;

    private static final Logger log = LoggerFactory.getLogger(ZigbeeDeviceCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, ZigbeeDeviceRecord> devices = new LinkedHashMap<>();
    private final Map<Long, Boolean> lastKnownAvailability = new HashMap<>();
    private final Map<Long, Instant> lastEvidenceAt = new HashMap<>();
    private final Map<Long, Long> learnedZoneTypes = new HashMap<>();
    // ENERGY-READ R3: per IEEE → per endpoint → what the endpoint declared.
    private final Map<Long, Map<Integer, MeteringFormatting>>
            learnedMeteringFormatting = new HashMap<>();
    private final Map<Integer, Long> ieeeByNetworkAddress = new HashMap<>();

    private boolean dirty;
    private Instant lastWrite;
    private Instant writeSuppressedUntil;

    /**
     * Creates the cache, loading persisted state when the file exists.
     *
     * @param file the {@code zigbee-devices.json} path (injected — the adapter
     *        data directory binds at M9.4), never {@code null}
     * @param clock the time source, never {@code null}
     */
    ZigbeeDeviceCache(Path file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
        load();
    }

    /**
     * Records a device announce (join/rejoin): creates the initial record or
     * re-indexes the NWK address; interview metadata survives a rejoin.
     *
     * @param ieee the announcing device, never {@code null}
     * @param networkAddress the announced 16-bit network address
     */
    void recordAnnounce(IEEEAddress ieee, int networkAddress) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            ZigbeeDeviceRecord existing = devices.get(ieee.value());
            Instant now = clock.instant();
            ZigbeeDeviceRecord updated = existing == null
                    ? new ZigbeeDeviceRecord(ieee, networkAddress, null, null,
                            null, null, 0, now, InterviewStatus.PENDING, null)
                    : new ZigbeeDeviceRecord(ieee, networkAddress,
                            existing.nodeDescriptor(), existing.endpoints(),
                            existing.manufacturerName(),
                            existing.modelIdentifier(), existing.powerSource(),
                            now, existing.interviewStatus(),
                            existing.matchedProfileId());
            reindex(existing, updated);
            devices.put(ieee.value(), updated);
            dirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a completed/partial interview.
     *
     * @param interview the interview result, never {@code null}
     * @param matchedProfileId the matched profile id; {@code null} when none —
     *        a re-validated cache, never identity (Q10)
     */
    void recordInterview(InterviewResult interview, String matchedProfileId) {
        Objects.requireNonNull(interview, "interview");
        lock.lock();
        try {
            ZigbeeDeviceRecord existing = devices.get(interview.ieeeAddress().value());
            ZigbeeDeviceRecord updated = new ZigbeeDeviceRecord(
                    interview.ieeeAddress(),
                    interview.networkAddress(),
                    interview.nodeDescriptor(),
                    interview.endpoints(),
                    interview.manufacturerName(),
                    interview.modelIdentifier(),
                    interview.powerSource(),
                    clock.instant(),
                    interview.interviewStatus(),
                    matchedProfileId);
            reindex(existing, updated);
            devices.put(interview.ieeeAddress().value(), updated);
            dirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Updates the last-seen timestamp on any frame from a device.
     *
     * @param ieee the device, never {@code null}
     */
    void recordFrame(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            ZigbeeDeviceRecord existing = devices.get(ieee.value());
            if (existing == null) {
                return;
            }
            devices.put(ieee.value(), new ZigbeeDeviceRecord(
                    existing.ieeeAddress(), existing.networkAddress(),
                    existing.nodeDescriptor(), existing.endpoints(),
                    existing.manufacturerName(), existing.modelIdentifier(),
                    existing.powerSource(), clock.instant(),
                    existing.interviewStatus(), existing.matchedProfileId()));
            dirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Persists a device's last-known availability (the M-1 restart-init input).
     *
     * @param ieee the device, never {@code null}
     * @param available the availability to persist
     */
    void setAvailability(IEEEAddress ieee, boolean available) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            lastKnownAvailability.put(ieee.value(), available);
            dirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a device's persisted availability.
     *
     * @param ieee the device, never {@code null}
     * @return the persisted state, or empty when never recorded
     */
    Optional<Boolean> lastKnownAvailability(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            return Optional.ofNullable(lastKnownAvailability.get(ieee.value()));
        } finally {
            lock.unlock();
        }
    }

    /** Returns the persisted availability map snapshot (tracker restart-init). */
    Map<Long, Boolean> availabilitySnapshot() {
        lock.lock();
        try {
            return Map.copyOf(lastKnownAvailability);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records device-originated evidence recency (WU-AVAIL-SEED DP-4): a
     * value update under the lock only — the debounced {@link #maybeFlush()}
     * and the shutdown {@link #flush()} carry the file I/O, exactly as they do
     * for availability. Called per frame and per answered ping; per-frame FILE
     * writes never happen (the 30 s debounce is the write mechanism).
     *
     * @param ieee the evidencing device, never {@code null}
     * @param at the evidence instant, never {@code null}
     */
    void recordEvidence(IEEEAddress ieee, Instant at) {
        Objects.requireNonNull(ieee, "ieee");
        Objects.requireNonNull(at, "at");
        lock.lock();
        try {
            lastEvidenceAt.put(ieee.value(), at);
            dirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a device's persisted evidence recency.
     *
     * @param ieee the device, never {@code null}
     * @return the last recorded evidence instant, or empty when never recorded
     *         (an old-format sidecar reads empty — unknown recency)
     */
    Optional<Instant> lastEvidenceAt(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            return Optional.ofNullable(lastEvidenceAt.get(ieee.value()));
        } finally {
            lock.unlock();
        }
    }

    /** Returns the evidence-recency map snapshot (the DP-1 seed input). */
    Map<Long, Instant> lastEvidenceSnapshot() {
        lock.lock();
        try {
            return Map.copyOf(lastEvidenceAt);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Persists a wire-learned IAS zone type (LEARN-PERSIST DP-LP-3): state
     * mutation under the lock ONLY — no I/O on the calling (ingestion cycle)
     * thread; the debounced {@link #maybeFlush()} and the shutdown
     * {@link #flush()} carry the file I/O exactly as they do for availability.
     *
     * @param ieee the learned device, never {@code null}
     * @param zclId the learned ZCL zone-type id ({@link ZoneType#zclId()})
     */
    void recordLearnedZoneType(IEEEAddress ieee, long zclId) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            learnedZoneTypes.put(ieee.value(), zclId);
            dirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the persisted wire-learned zone-type ids by IEEE value — the
     * LEARN-PERSIST DP-LP-4 rehydration seed. Ids, never enum names, so the
     * ingestion's candidate-scan tolerance stays the one code path (DP-LP-2:
     * an unknown persisted id is the SEED consumer's skip, not a load error).
     */
    Map<Long, Long> learnedZoneTypeIds() {
        lock.lock();
        try {
            return Map.copyOf(learnedZoneTypes);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Persists the metering formatting an endpoint declared (ENERGY-READ R3):
     * state mutation under the lock ONLY — no I/O on the calling (ingestion
     * cycle) thread; the debounced {@link #maybeFlush()} and the shutdown
     * {@link #flush()} carry the file I/O, exactly as they do for learned zone
     * types.
     *
     * @param ieee the read device, never {@code null}
     * @param endpoint the read endpoint
     * @param formatting what the endpoint declared, never {@code null}
     */
    void recordLearnedMeteringFormatting(IEEEAddress ieee, int endpoint,
            MeteringFormatting formatting) {
        Objects.requireNonNull(ieee, "ieee");
        Objects.requireNonNull(formatting, "formatting");
        lock.lock();
        try {
            learnedMeteringFormatting
                    .computeIfAbsent(ieee.value(), key -> new TreeMap<>())
                    .put(endpoint, formatting);
            dirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the persisted metering formatting by IEEE value, then by
     * endpoint — the ingestion's rehydration seed (a deep immutable snapshot;
     * the records themselves are immutable).
     */
    Map<Long, Map<Integer, MeteringFormatting>> learnedMeteringFormatting() {
        lock.lock();
        try {
            return formattingSnapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the formatting persisted for one endpoint — the rejoin arm's
     * cached view.
     *
     * @param ieee the device, never {@code null}
     * @param endpoint the endpoint
     * @return the persisted formatting, or empty when none was recorded
     */
    Optional<MeteringFormatting> learnedMeteringFormatting(IEEEAddress ieee,
            int endpoint) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            Map<Integer, MeteringFormatting> byEndpoint =
                    learnedMeteringFormatting.get(ieee.value());
            return byEndpoint == null ? Optional.empty()
                    : Optional.ofNullable(byEndpoint.get(endpoint));
        } finally {
            lock.unlock();
        }
    }

    private Map<Long, Map<Integer, MeteringFormatting>> formattingSnapshotLocked() {
        Map<Long, Map<Integer, MeteringFormatting>> snapshot = new HashMap<>();
        learnedMeteringFormatting.forEach((ieee, byEndpoint) ->
                snapshot.put(ieee, Map.copyOf(byEndpoint)));
        return Map.copyOf(snapshot);
    }

    /**
     * Returns a device's record.
     *
     * @param ieee the device, never {@code null}
     * @return the record, or empty when unknown
     */
    Optional<ZigbeeDeviceRecord> device(IEEEAddress ieee) {
        Objects.requireNonNull(ieee, "ieee");
        lock.lock();
        try {
            return Optional.ofNullable(devices.get(ieee.value()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolves a sender network address to its device (the ingestion index).
     *
     * @param networkAddress the 16-bit sender address
     * @return the device, or empty when unknown
     */
    Optional<IEEEAddress> deviceForNetworkAddress(int networkAddress) {
        lock.lock();
        try {
            Long ieee = ieeeByNetworkAddress.get(networkAddress);
            return ieee == null ? Optional.empty()
                    : Optional.of(new IEEEAddress(ieee));
        } finally {
            lock.unlock();
        }
    }

    /** Returns all cached records. */
    Collection<ZigbeeDeviceRecord> all() {
        lock.lock();
        try {
            return List.copyOf(devices.values());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes the cache if dirty, the debounce window elapsed, and no write
     * failure backoff is active (called each ingestion cycle).
     */
    void maybeFlush() {
        WriteSnapshot snapshot;
        lock.lock();
        try {
            if (!dirty) {
                return;
            }
            Instant now = clock.instant();
            if (lastWrite != null
                    && Duration.between(lastWrite, now)
                            .compareTo(WRITE_DEBOUNCE) < 0) {
                return;
            }
            if (writeSuppressedUntil != null
                    && now.isBefore(writeSuppressedUntil)) {
                // F-14: a recent write failed — skip without touching the
                // disk; the cycle calls this every pass and the backoff is
                // what keeps a broken target from becoming a WARN storm.
                return;
            }
            snapshot = snapshotLocked(now);
        } finally {
            lock.unlock();
        }
        write(snapshot);
    }

    /** Writes the cache immediately (adapter shutdown). */
    void flush() {
        WriteSnapshot snapshot;
        lock.lock();
        try {
            snapshot = snapshotLocked(clock.instant());
        } finally {
            lock.unlock();
        }
        // F-14: flush() ignores the failure backoff — shutdown is the last
        // chance to persist, and a single attempt cannot storm.
        write(snapshot);
    }

    private void reindex(ZigbeeDeviceRecord previous, ZigbeeDeviceRecord updated) {
        if (previous != null) {
            ieeeByNetworkAddress.remove(previous.networkAddress());
        }
        Long victim = ieeeByNetworkAddress.put(updated.networkAddress(),
                updated.ieeeAddress().value());
        if (victim != null && victim != updated.ieeeAddress().value()) {
            // F-6: the 16-bit address was REASSIGNED — the victim record's cached
            // networkAddress is stale truth. Invalidate it to the unknown sentinel
            // so the dispatch identity join (§3.3) treats the hint as broken and
            // re-resolves via the coordinator, never actuating the wrong device.
            invalidateAddressLocked(victim, updated.networkAddress());
        }
    }

    /**
     * The protocol's unknown-address sentinel (EmberNodeId 0xFFFF — the same value
     * {@code lookupNodeIdByEui64} treats as not-in-table): a record carrying it has
     * NO usable network address and must be re-resolved before dispatch (F-6).
     */
    static final int NETWORK_ADDRESS_UNKNOWN = 0xFFFF;

    private void invalidateAddressLocked(long victimIeee, int reassignedAddress) {
        ZigbeeDeviceRecord record = devices.get(victimIeee);
        if (record == null) {
            return;
        }
        devices.put(victimIeee, new ZigbeeDeviceRecord(record.ieeeAddress(),
                NETWORK_ADDRESS_UNKNOWN, record.nodeDescriptor(), record.endpoints(),
                record.manufacturerName(), record.modelIdentifier(),
                record.powerSource(), record.lastSeen(), record.interviewStatus(),
                record.matchedProfileId()));
        dirty = true;
        log.warn("zigbee.network_address_collision: nwk=0x{} reassigned away from "
                        + "device {}; the victim's cached address is invalidated "
                        + "pending re-resolution (F-6)",
                Integer.toHexString(reassignedAddress),
                record.ieeeAddress().toHexString());
    }

    /**
     * The consistent view a write serializes outside the lock (F-14): the
     * records and boxed values are immutable, so the copied collections stay
     * valid however long the file I/O takes.
     */
    private record WriteSnapshot(List<ZigbeeDeviceRecord> devices,
            Map<Long, Boolean> availability,
            Map<Long, Instant> evidence,
            Map<Long, Long> learnedZoneTypes,
            Map<Long, Map<Integer, MeteringFormatting>> learnedMeteringFormatting) { }

    private WriteSnapshot snapshotLocked(Instant now) {
        // F-14: dirty clears at snapshot time — a mutation racing the file
        // I/O re-dirties, and a failed write re-dirties, so no change is lost.
        dirty = false;
        lastWrite = now;
        return new WriteSnapshot(List.copyOf(devices.values()),
                Map.copyOf(lastKnownAvailability),
                Map.copyOf(lastEvidenceAt),
                Map.copyOf(learnedZoneTypes),
                formattingSnapshotLocked());
    }

    private void write(WriteSnapshot snapshot) {
        // F-14: serialization and file I/O run outside the lock — reads never
        // block on a write's I/O. F-3 (S-5c): the bytes land at a .tmp
        // sibling and the final path is only ever replaced by a completed
        // move (the PersistentNetworkParameterStore idiom), so a reader or
        // the loader can never observe a partial file at the final path — a
        // crash or power cut mid-write leaves the previous complete file
        // (plus at worst a stale .tmp the next write truncates and
        // consumes), never a torn file the loader would discard along with
        // the availability seed. Two racing writers remain last-writer-wins
        // on the content (an older snapshot landing last is corrected by
        // the next dirty write).
        try {
            String json = toJson(snapshot);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            lock.lock();
            try {
                // The target proved writable — a still-armed backoff is stale.
                writeSuppressedUntil = null;
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            // The cache is a warm-start optimization: a failed write degrades
            // restart behavior, never live operation.
            lock.lock();
            try {
                dirty = true;
                writeSuppressedUntil = clock.instant()
                        .plusMillis(WRITE_FAILURE_BACKOFF_MILLIS);
            } finally {
                lock.unlock();
            }
            log.warn("zigbee.device_cache_write_failed: {}: {}; writes "
                            + "suppressed for {} ms", file, e.getMessage(),
                    WRITE_FAILURE_BACKOFF_MILLIS);
        }
    }

    private String toJson(WriteSnapshot snapshot) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        ArrayNode deviceArray = root.putArray("devices");
        for (ZigbeeDeviceRecord record : snapshot.devices()) {
            ObjectNode node = deviceArray.addObject();
            node.put("ieee", record.ieeeAddress().toHexString());
            node.put("networkAddress", record.networkAddress());
            node.put("powerSource", record.powerSource());
            node.put("lastSeen", record.lastSeen().toString());
            node.put("interviewStatus", record.interviewStatus().name());
            if (record.manufacturerName() != null) {
                node.put("manufacturerName", record.manufacturerName());
            }
            if (record.modelIdentifier() != null) {
                node.put("modelIdentifier", record.modelIdentifier());
            }
            if (record.matchedProfileId() != null) {
                node.put("matchedProfileId", record.matchedProfileId());
            }
            Boolean availability =
                    snapshot.availability().get(record.ieeeAddress().value());
            if (availability != null) {
                node.put("lastKnownAvailability", availability);
            }
            Instant evidence =
                    snapshot.evidence().get(record.ieeeAddress().value());
            if (evidence != null) {
                // DP-4: additive, ISO-8601 UTC — absent when never recorded.
                node.put("lastEvidenceAt", evidence.toString());
            }
            if (record.nodeDescriptor() != null) {
                ObjectNode descriptor = node.putObject("nodeDescriptor");
                descriptor.put("deviceType", record.nodeDescriptor().deviceType());
                descriptor.put("manufacturerCode",
                        record.nodeDescriptor().manufacturerCode());
                descriptor.put("maxBufferSize",
                        record.nodeDescriptor().maxBufferSize());
                descriptor.put("macCapabilityFlags",
                        record.nodeDescriptor().macCapabilityFlags());
            }
            if (record.endpoints() != null) {
                ArrayNode endpoints = node.putArray("endpoints");
                for (EndpointDescriptor endpoint : record.endpoints()) {
                    ObjectNode endpointNode = endpoints.addObject();
                    endpointNode.put("endpointId", endpoint.endpointId());
                    endpointNode.put("profileId", endpoint.profileId());
                    endpointNode.put("deviceTypeId", endpoint.deviceTypeId());
                    ArrayNode in = endpointNode.putArray("inputClusters");
                    endpoint.inputClusters().forEach(in::add);
                    ArrayNode out = endpointNode.putArray("outputClusters");
                    endpoint.outputClusters().forEach(out::add);
                }
            }
        }
        if (!snapshot.learnedZoneTypes().isEmpty()) {
            // DP-LP-1: a TOP-LEVEL section, not a per-record field — a learn
            // for a device with no record node still persists. Keys sorted
            // unsigned so identical state serializes to identical bytes.
            ObjectNode learnedNode = root.putObject("learnedZoneTypes");
            List<Long> ieees =
                    new ArrayList<>(snapshot.learnedZoneTypes().keySet());
            ieees.sort(Long::compareUnsigned);
            for (Long ieee : ieees) {
                learnedNode.put(new IEEEAddress(ieee).toHexString(),
                        snapshot.learnedZoneTypes().get(ieee).longValue());
            }
        }
        if (!snapshot.learnedMeteringFormatting().isEmpty()) {
            // ENERGY-READ R3: TOP-LEVEL like the zone types (a read for a
            // device with no record node still persists; the frozen record
            // shape is untouched) — IEEE hex → endpoint → the declared
            // formatting. IEEE keys sorted unsigned, endpoints ascending, so
            // identical state serializes to identical bytes. An absent pair
            // writes nothing: absence in the file IS absence in the record.
            ObjectNode formattingNode =
                    root.putObject("learnedMeteringFormatting");
            List<Long> ieees = new ArrayList<>(
                    snapshot.learnedMeteringFormatting().keySet());
            ieees.sort(Long::compareUnsigned);
            for (Long ieee : ieees) {
                ObjectNode deviceNode = formattingNode.putObject(
                        new IEEEAddress(ieee).toHexString());
                new TreeMap<>(snapshot.learnedMeteringFormatting().get(ieee))
                        .forEach((endpoint, formatting) -> writeFormatting(
                                deviceNode.putObject(String.valueOf(endpoint)),
                                formatting));
            }
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static void writeFormatting(ObjectNode node,
            MeteringFormatting formatting) {
        if (formatting.hasElectrical()) {
            node.put("powerMultiplier", formatting.powerMultiplier());
            node.put("powerDivisor", formatting.powerDivisor());
        }
        if (formatting.hasVoltage()) {
            node.put("voltageMultiplier", formatting.voltageMultiplier());
            node.put("voltageDivisor", formatting.voltageDivisor());
        }
        if (formatting.hasCurrent()) {
            node.put("currentMultiplier", formatting.currentMultiplier());
            node.put("currentDivisor", formatting.currentDivisor());
        }
        if (formatting.hasMetering()) {
            node.put("summationMultiplier", formatting.summationMultiplier());
            node.put("summationDivisor", formatting.summationDivisor());
        }
        if (formatting.unitOfMeasure() != null) {
            node.put("unitOfMeasure", formatting.unitOfMeasure());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        int malformedEvidence = 0;
        try {
            JsonNode root = MAPPER.readTree(
                    Files.readString(file, StandardCharsets.UTF_8));
            for (JsonNode node : root.path("devices")) {
                IEEEAddress ieee =
                        IEEEAddress.fromHexString(node.get("ieee").asText());
                NodeDescriptor descriptor = null;
                JsonNode descriptorNode = node.get("nodeDescriptor");
                if (descriptorNode != null && descriptorNode.isObject()) {
                    descriptor = new NodeDescriptor(
                            descriptorNode.path("deviceType").asInt(),
                            descriptorNode.path("manufacturerCode").asInt(),
                            descriptorNode.path("maxBufferSize").asInt(),
                            descriptorNode.path("macCapabilityFlags").asInt());
                }
                List<EndpointDescriptor> endpoints = null;
                JsonNode endpointsNode = node.get("endpoints");
                if (endpointsNode != null && endpointsNode.isArray()) {
                    endpoints = new ArrayList<>();
                    for (JsonNode endpointNode : endpointsNode) {
                        List<Integer> in = new ArrayList<>();
                        endpointNode.path("inputClusters")
                                .forEach(c -> in.add(c.asInt()));
                        List<Integer> out = new ArrayList<>();
                        endpointNode.path("outputClusters")
                                .forEach(c -> out.add(c.asInt()));
                        endpoints.add(new EndpointDescriptor(
                                endpointNode.path("endpointId").asInt(),
                                endpointNode.path("profileId").asInt(),
                                endpointNode.path("deviceTypeId").asInt(),
                                in, out));
                    }
                }
                ZigbeeDeviceRecord record = new ZigbeeDeviceRecord(
                        ieee,
                        node.path("networkAddress").asInt(),
                        descriptor,
                        endpoints,
                        node.hasNonNull("manufacturerName")
                                ? node.get("manufacturerName").asText() : null,
                        node.hasNonNull("modelIdentifier")
                                ? node.get("modelIdentifier").asText() : null,
                        node.path("powerSource").asInt(),
                        Instant.parse(node.get("lastSeen").asText()),
                        InterviewStatus.valueOf(
                                node.get("interviewStatus").asText()),
                        node.hasNonNull("matchedProfileId")
                                ? node.get("matchedProfileId").asText() : null);
                devices.put(ieee.value(), record);
                ieeeByNetworkAddress.put(record.networkAddress(), ieee.value());
                if (node.hasNonNull("lastKnownAvailability")) {
                    lastKnownAvailability.put(ieee.value(),
                            node.get("lastKnownAvailability").asBoolean());
                }
                if (node.hasNonNull("lastEvidenceAt")) {
                    // DP-4 tolerance (the learnedZoneTypes posture): a
                    // malformed VALUE degrades that device to unknown recency
                    // — the DP-1 seed's lawful worst case — never a load
                    // failure. Absence is the old-format path, silent.
                    try {
                        lastEvidenceAt.put(ieee.value(),
                                Instant.parse(node.get("lastEvidenceAt").asText()));
                    } catch (RuntimeException e) {
                        malformedEvidence++;
                    }
                }
            }
            loadLearnedZoneTypes(root);
            loadLearnedMeteringFormatting(root);
            if (malformedEvidence > 0) {
                log.warn("zigbee.evidence_recency_malformed: {} unparseable "
                        + "lastEvidenceAt values in {} skipped; those devices "
                        + "seed with unknown recency", malformedEvidence, file);
            }
            log.info("zigbee.device_cache_loaded: {} devices from {}",
                    devices.size(), file);
        } catch (IOException | RuntimeException e) {
            // A corrupt cache is discarded: the coordinator device table +
            // fresh interviews rebuild it (Doc 08 §3.14 startup reconcile).
            log.warn("zigbee.device_cache_corrupt: {} unreadable ({}); starting "
                    + "with an empty cache", file, e.getMessage());
            devices.clear();
            ieeeByNetworkAddress.clear();
            lastKnownAvailability.clear();
            lastEvidenceAt.clear();
            learnedZoneTypes.clear();
            learnedMeteringFormatting.clear();
        }
    }

    /**
     * Loads the top-level {@code learnedMeteringFormatting} section
     * (ENERGY-READ R3) under the LEARN-PERSIST tolerance: absent ⇒ empty,
     * silently (an old cache loads clean — the upgrade path); malformed
     * content — a non-object section, an unparseable IEEE or endpoint key, a
     * non-object device or record node — is skipped with ONE WARN and the
     * parseable siblings still apply. Fail-safe is always UNREAD formatting,
     * which configures nothing and scales nothing until the next rejoin reads
     * again. A missing or non-integral field reads as absent through the
     * record's own per-pair normalization — one tolerance code path.
     */
    private void loadLearnedMeteringFormatting(JsonNode root) {
        JsonNode section = root.path("learnedMeteringFormatting");
        if (section.isMissingNode()) {
            return;
        }
        if (!section.isObject()) {
            log.warn("zigbee.learned_metering_formatting_malformed: section in "
                    + "{} is not an object; ignored", file);
            return;
        }
        int skipped = 0;
        Iterator<Map.Entry<String, JsonNode>> devices = section.fields();
        while (devices.hasNext()) {
            Map.Entry<String, JsonNode> device = devices.next();
            long ieee;
            try {
                ieee = IEEEAddress.fromHexString(device.getKey()).value();
            } catch (RuntimeException e) {
                skipped++;
                continue;
            }
            if (!device.getValue().isObject()) {
                skipped++;
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> endpoints =
                    device.getValue().fields();
            while (endpoints.hasNext()) {
                Map.Entry<String, JsonNode> endpoint = endpoints.next();
                int endpointId;
                try {
                    endpointId = Integer.parseInt(endpoint.getKey());
                } catch (NumberFormatException e) {
                    skipped++;
                    continue;
                }
                JsonNode node = endpoint.getValue();
                if (!node.isObject()) {
                    skipped++;
                    continue;
                }
                learnedMeteringFormatting
                        .computeIfAbsent(ieee, key -> new TreeMap<>())
                        .put(endpointId, new MeteringFormatting(
                                node.path("powerMultiplier").asInt(0),
                                node.path("powerDivisor").asInt(0),
                                node.path("voltageMultiplier").asInt(0),
                                node.path("voltageDivisor").asInt(0),
                                node.path("currentMultiplier").asInt(0),
                                node.path("currentDivisor").asInt(0),
                                node.path("summationMultiplier").asInt(0),
                                node.path("summationDivisor").asInt(0),
                                node.hasNonNull("unitOfMeasure")
                                        && node.get("unitOfMeasure")
                                                .isIntegralNumber()
                                        ? node.get("unitOfMeasure").asInt()
                                        : null));
            }
        }
        if (skipped > 0) {
            log.warn("zigbee.learned_metering_formatting_malformed: {} "
                    + "unparseable entries in {} skipped; the parseable "
                    + "entries apply", skipped, file);
        }
    }

    /**
     * Loads the top-level {@code learnedZoneTypes} section (LEARN-PERSIST):
     * absent ⇒ empty, silently (the first-run/upgrade path); malformed content
     * — a non-object section, an unparseable IEEE key, a non-integral value —
     * is skipped with ONE WARN and the parseable entries still apply.
     * Fail-safe is always unlearned, which is exactly the pre-LEARN-PERSIST
     * behavior (the classifier's MOTION default). Zone-type VALIDITY is not
     * judged here — DP-LP-2 keeps that in the ingestion's candidate scan, the
     * one tolerance code path.
     */
    private void loadLearnedZoneTypes(JsonNode root) {
        JsonNode learnedNode = root.path("learnedZoneTypes");
        if (learnedNode.isMissingNode()) {
            return;
        }
        if (!learnedNode.isObject()) {
            log.warn("zigbee.learned_zonetypes_malformed: section in {} is not "
                    + "an object; ignored", file);
            return;
        }
        int skipped = 0;
        Iterator<Map.Entry<String, JsonNode>> entries = learnedNode.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!entry.getValue().isIntegralNumber()) {
                skipped++;
                continue;
            }
            try {
                learnedZoneTypes.put(
                        IEEEAddress.fromHexString(entry.getKey()).value(),
                        entry.getValue().asLong());
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("zigbee.learned_zonetypes_malformed: {} unparseable "
                    + "entries in {} skipped; the parseable entries apply",
                    skipped, file);
        }
    }
}

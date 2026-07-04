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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * the sidecar, tolerated-additively by this loader).
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 */
final class ZigbeeDeviceCache {

    /** Writes debounce to at most one per this window (Doc 08 §3.14). */
    static final Duration WRITE_DEBOUNCE = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(ZigbeeDeviceCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, ZigbeeDeviceRecord> devices = new LinkedHashMap<>();
    private final Map<Long, Boolean> lastKnownAvailability = new HashMap<>();
    private final Map<Integer, Long> ieeeByNetworkAddress = new HashMap<>();

    private boolean dirty;
    private Instant lastWrite;

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
     * Writes the cache if dirty and the debounce window elapsed (called each
     * ingestion cycle).
     */
    void maybeFlush() {
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
            writeLocked(now);
        } finally {
            lock.unlock();
        }
    }

    /** Writes the cache immediately (adapter shutdown). */
    void flush() {
        lock.lock();
        try {
            writeLocked(clock.instant());
        } finally {
            lock.unlock();
        }
    }

    private void reindex(ZigbeeDeviceRecord previous, ZigbeeDeviceRecord updated) {
        if (previous != null) {
            ieeeByNetworkAddress.remove(previous.networkAddress());
        }
        ieeeByNetworkAddress.put(updated.networkAddress(),
                updated.ieeeAddress().value());
    }

    private void writeLocked(Instant now) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        ArrayNode deviceArray = root.putArray("devices");
        for (ZigbeeDeviceRecord record : devices.values()) {
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
                    lastKnownAvailability.get(record.ieeeAddress().value());
            if (availability != null) {
                node.put("lastKnownAvailability", availability);
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
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root), StandardCharsets.UTF_8);
            dirty = false;
            lastWrite = now;
        } catch (IOException e) {
            // The cache is a warm-start optimization: a failed write degrades
            // restart behavior, never live operation.
            log.warn("zigbee.device_cache_write_failed: {}: {}", file,
                    e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
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
        }
    }
}

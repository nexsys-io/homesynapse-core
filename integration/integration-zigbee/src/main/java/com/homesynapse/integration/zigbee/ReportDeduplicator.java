/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The measured ingestion dedup (Doc 08 §3.6 engine caveat 4 / the SNZB
 * walk-test): every event arrives ×2 with CONSECUTIVE TSNs and identical
 * payloads, 8–21 ms apart — distinct ZCL transactions, not MAC retries.
 *
 * <p><strong>The contract:</strong> a frame is a duplicate iff its payload
 * equals the scope's last payload AND its TSN is the same or the successor
 * (mod 256) of the last TSN — scoped per (device, endpoint, cluster), CLEARED
 * on device-announce/rejoin. Never TSN comparison alone: the TSN is 8-bit
 * wrapping AND resets on power-cycle, and distinct edges legitimately repeat
 * payloads at non-consecutive TSNs.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 */
final class ReportDeduplicator {

    private record ScopeKey(long device, int endpoint, int cluster) {
    }

    private record LastFrame(int tsn, byte[] payload) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<ScopeKey, LastFrame> lastByScope = new HashMap<>();

    /** Creates an empty deduplicator. */
    ReportDeduplicator() {
    }

    /**
     * Tests-and-records one frame observation.
     *
     * @param device the reporting device, never {@code null}
     * @param endpoint the source endpoint
     * @param cluster the ZCL cluster id
     * @param tsn the ZCL transaction sequence number (0–255)
     * @param payload the ZCL frame bytes, never {@code null}
     * @return {@code true} if the frame duplicates the scope's previous frame
     */
    boolean isDuplicate(IEEEAddress device, int endpoint, int cluster, int tsn,
            byte[] payload) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(payload, "payload");
        ScopeKey key = new ScopeKey(device.value(), endpoint, cluster);
        lock.lock();
        try {
            LastFrame last = lastByScope.get(key);
            boolean duplicate = last != null
                    && Arrays.equals(last.payload(), payload)
                    && (tsn == last.tsn() || tsn == ((last.tsn() + 1) & 0xFF));
            lastByScope.put(key, new LastFrame(tsn, payload.clone()));
            return duplicate;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears every scope for a device — called on device-announce/rejoin
     * (post-power-cycle TSN state is stale truth).
     *
     * @param device the announcing device, never {@code null}
     */
    void clearDevice(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            lastByScope.keySet().removeIf(key -> key.device() == device.value());
        } finally {
            lock.unlock();
        }
    }
}

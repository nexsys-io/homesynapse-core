/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.time.Instant;
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
 * (mod 256) of the last TSN AND the scope's last frame is at most
 * {@link #DEDUP_WINDOW_MS} old — scoped per (device, endpoint, cluster),
 * CLEARED on device-announce/rejoin. Never TSN comparison alone: the TSN is
 * 8-bit wrapping AND resets on power-cycle, and distinct edges legitimately
 * repeat payloads at non-consecutive TSNs. Never untimed (F-4): a periodic
 * reporter emitting an unchanged value on consecutive TSNs minutes apart is a
 * genuine report, not a twin — the false-drop class the window closes.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 */
final class ReportDeduplicator {

    /**
     * The twin window (F-4, M9.4b §6.1). Derivation: the measured corpus twins
     * arrive 8–21 ms apart — always sub-second — while the slowest legitimate
     * same-payload repeat (an unchanged periodic report on consecutive TSNs)
     * arrives on the reporting min/max-interval scale, minutes apart. 10 s
     * sits orders of magnitude above the twins and well below the periodic
     * floor.
     */
    static final long DEDUP_WINDOW_MS = 10_000;

    private record ScopeKey(long device, int endpoint, int cluster) {
    }

    private record LastFrame(int tsn, byte[] payload, Instant seenAt) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<ScopeKey, LastFrame> lastByScope = new HashMap<>();
    private final Clock clock;

    /**
     * Creates an empty deduplicator.
     *
     * @param clock the time source for the twin window, never {@code null}
     */
    ReportDeduplicator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
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
        Instant now = clock.instant();
        lock.lock();
        try {
            LastFrame last = lastByScope.get(key);
            boolean duplicate = last != null
                    && Arrays.equals(last.payload(), payload)
                    && (tsn == last.tsn() || tsn == ((last.tsn() + 1) & 0xFF))
                    && now.toEpochMilli() - last.seenAt().toEpochMilli()
                            <= DEDUP_WINDOW_MS;
            lastByScope.put(key, new LastFrame(tsn, payload.clone(), now));
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

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.Ulid;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Per-subscriber filter that suppresses re-entrant delivery of events the
 * {@link StateProjection} itself published (AMD-41 §3.2.2, INV-SUB-ISO-06).
 *
 * <p>When the projection publishes a derived {@code state_changed} event in LIVE
 * mode, the bus eventually delivers that same event back to the projection
 * because it matches the projection's subscription filter. The
 * {@code SelfProducedFilter} records each published event ID with the current
 * clock instant and rejects re-delivery within the 60-second TTL window, breaking
 * the would-be infinite re-derivation loop.</p>
 *
 * <h2>Mode bypass (AMD-41 §3.2.2)</h2>
 *
 * <p>{@link #isSelfProduced(Ulid, SubscriberMode)} returns {@code false}
 * unconditionally when the projection is in {@link SubscriberMode#REPLAY} or
 * {@link SubscriberMode#TRANSITION}. During replay from the persisted log, every
 * derived event the projection published in a previous LIVE session is replayed
 * back to the projection — but the projection's derivation rule produces drafts
 * only from {@code state_reported} (never from {@code state_changed}), so no
 * re-derivation loop forms. Bypassing the filter during REPLAY/TRANSITION ensures
 * the projection still advances {@code stateVersion} for replayed derived events
 * (INV-PROJ-01 determinism).</p>
 *
 * <h2>Lazy eviction</h2>
 *
 * <p>Expired entries are evicted on every {@link #isSelfProduced} call by sweeping
 * the entry set against the cutoff {@code clock.instant().minus(ttl)}. The sweep
 * is O(N) but N is bounded by the publish rate × TTL — at the AMD-43 default rate
 * of 200 derived publishes/sec × 60-second TTL = 12,000 entries maximum. The
 * sweep is fast in practice and avoids the complexity of a separate eviction
 * scheduler.</p>
 *
 * <h2>Thread confinement (INV-SUB-ISO-06)</h2>
 *
 * <p>One {@code SelfProducedFilter} instance is associated with exactly one
 * {@link StateProjection} instance, and all calls — {@link #record} and
 * {@link #isSelfProduced} — execute on the subscriber's single virtual thread.
 * The internal {@link HashMap} is single-threaded; no synchronization is needed.
 * Using {@link java.util.concurrent.ConcurrentHashMap ConcurrentHashMap} would
 * waste cycles on lock acquisition for a single-threaded access pattern.</p>
 *
 * <p>Package-private: only code within {@code com.homesynapse.state} can
 * construct a filter, which transitively means only code in this package can
 * construct a {@link StateProjection} with a custom filter. The public
 * {@link StateProjection#create} factory creates a filter with the default
 * 60-second TTL.</p>
 */
final class SelfProducedFilter {

    /** Default TTL for self-produced entries (AMD-41 §3.2.2). */
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final Clock clock;
    private final Duration ttl;
    private final Map<Ulid, Instant> entries = new HashMap<>();

    /**
     * Constructs a filter with the given clock and TTL.
     *
     * @param clock injected clock for time-stamping recorded entries; never
     *              {@code null}
     * @param ttl   how long a recorded entry remains active before lazy eviction;
     *              must be positive
     * @throws NullPointerException     if {@code clock} or {@code ttl} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code ttl} is zero or negative
     */
    SelfProducedFilter(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        this.ttl = ttl;
    }

    /**
     * Records that the projection has just published an event with the given ID.
     * Subsequent {@link #isSelfProduced} calls within {@code ttl} will return
     * {@code true} for this ID (outside of REPLAY/TRANSITION).
     *
     * @param eventId the published event's ULID; never {@code null}
     */
    void record(Ulid eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        entries.put(eventId, clock.instant());
    }

    /**
     * Returns whether the given event was published by this projection within the
     * TTL window.
     *
     * <p>Returns {@code false} unconditionally when {@code mode} is
     * {@link SubscriberMode#REPLAY} or {@link SubscriberMode#TRANSITION} — replay
     * paths must replay every event including projection-derived ones to maintain
     * deterministic state.</p>
     *
     * <p>Runs lazy eviction inline: expired entries (recorded earlier than
     * {@code clock.instant().minus(ttl)}) are removed during this call.</p>
     *
     * @param eventId the event identifier to check; never {@code null}
     * @param mode    the projection's current subscriber mode; never {@code null}
     * @return {@code true} if {@code mode} is LIVE/COLD/SUSPENDED and the event
     *         was recorded by this filter within the TTL window
     */
    boolean isSelfProduced(Ulid eventId, SubscriberMode mode) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        if (mode == SubscriberMode.REPLAY || mode == SubscriberMode.TRANSITION) {
            return false;
        }
        evictExpired();
        return entries.containsKey(eventId);
    }

    /**
     * Returns the current entry count. Test-only accessor.
     *
     * @return number of currently-stored (non-evicted) entries
     */
    int size() {
        return entries.size();
    }

    /**
     * Sweeps and removes entries older than {@code clock.instant().minus(ttl)}.
     * Called inline from {@link #isSelfProduced}.
     */
    private void evictExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        Iterator<Map.Entry<Ulid, Instant>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Ulid, Instant> next = it.next();
            if (next.getValue().isBefore(cutoff)) {
                it.remove();
            }
        }
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Instant;

/**
 * Point-in-time introspection snapshot of a subscriber's state (PLAN §4.3).
 *
 * <p>Returned by {@link EventBus#subscriberInfo(String)} and
 * {@link EventBus#subscribers()}. This is a read-only projection of the
 * subscriber's runtime state, separate from the registration descriptor
 * {@link SubscriberInfo}.</p>
 *
 * <p>M3.7 extended the record from 5 to 6 fields by adding
 * {@link #oldestParkedAt()} — the {@code parkedAt} timestamp of the oldest
 * entry currently in the subscriber's in-memory DLQ ring. The value is
 * {@code null} when the DLQ is empty (the field is intentionally nullable
 * to keep the record-component contract simple — operators that prefer
 * {@link java.util.Optional Optional} handling can wrap with
 * {@code Optional.ofNullable(snapshot.oldestParkedAt())}).</p>
 *
 * <p>FIX-2b-i (2026-09-11) extended the record from 6 to 7 fields by adding
 * {@link #pendingDepth()} — the size of the subscriber's LIVE pending-position
 * queue at the snapshot instant: positions {@code notifyEvent} offered and the
 * subscriber's virtual thread has not yet consumed. A read-only observation
 * (no behaviour of the bus changes); with it a stalled subscriber's snapshot
 * reads either "offered and not consumed" ({@code pendingDepth ≥ 1} with the
 * checkpoint below the head) or "never offered" ({@code pendingDepth = 0}).</p>
 *
 * @param subscriberId    the subscriber's stable identifier
 * @param mode            the current lifecycle mode
 * @param checkpoint      the last delivered global position; 0 if never delivered
 * @param dlqDepth        current DLQ size (in-memory entries)
 * @param pendingDepth    positions offered to this subscriber's LIVE queue and
 *                        not yet consumed at the snapshot instant; 0 in
 *                        REPLAY/TRANSITION (those modes queue elsewhere — the
 *                        replay window, which this count does not include —
 *                        unless a LIVE queue survived a SUSPENDED → {@code resume()},
 *                        which clears the DLQ but not this queue) (FIX-2b-i)
 * @param crashCount      crashes within the current rolling 10-minute window
 * @param oldestParkedAt  the {@code parkedAt} timestamp of the oldest DLQ
 *                        entry, or {@code null} when the DLQ is empty (M3.7).
 *                        The ring is insertion-ordered (oldest = head), so
 *                        this is the head entry's stamp; eviction does NOT
 *                        preserve the all-time-oldest value.
 */
public record SubscriberSnapshot(
    String subscriberId,
    SubscriberMode mode,
    long checkpoint,
    int dlqDepth,
    int pendingDepth,
    int crashCount,
    Instant oldestParkedAt
) {

    /**
     * Validates {@code subscriberId} and {@code mode}. The
     * {@code oldestParkedAt} field is intentionally NOT validated for
     * non-null — {@code null} is the documented "DLQ empty" sentinel.
     *
     * @throws NullPointerException if {@code subscriberId} or {@code mode} is {@code null}
     */
    public SubscriberSnapshot {
        java.util.Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        java.util.Objects.requireNonNull(mode, "mode must not be null");
    }
}

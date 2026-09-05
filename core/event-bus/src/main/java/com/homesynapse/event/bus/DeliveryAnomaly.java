/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Instant;
import java.util.Objects;

/**
 * A delivery drop made visible (FAILCHAN-FIX-1a, 2026-09-05).
 *
 * <p>The bus's LIVE and TRANSITION delivery paths never page forward from a
 * checkpoint: a position is delivered only if it was offered by
 * {@link EventBus#notifyEvent(long)} AND the subscriber's own single-position
 * read returned the envelope. Before FIX-1a, each read that came back empty or
 * threw was skipped silently, and the next successful delivery wrote a
 * checkpoint past it — a failure through a silent channel in the module the
 * deterministic floor stands on. Every such point now emits one
 * {@code DeliveryAnomaly} through the {@code Consumer<DeliveryAnomaly>}
 * injected at {@link InProcessEventBus} construction, BEFORE the path returns
 * or continues.</p>
 *
 * <p>Carried exactly as {@link HealthSignal} is carried: a typed record over a
 * {@code java.util.function.Consumer}, components from {@code java.base} only.
 * The bus module stays SLF4J-free and JFR-metric-neutral (AMD-43 §3.6.2 — the
 * seven canonical names are locked; this is not an eighth); the composition
 * root owns the transport (one structured WARN, {@code bus.delivery_anomaly}).
 * It is an in-process signal, never an event: it does not enter the store (the
 * bus does not publish about itself).</p>
 *
 * <p><strong>Emitter contract.</strong> The emitter is invoked on the thread
 * that observed the drop — the publisher's thread for
 * {@link Kind#NOTIFY_NOT_VISIBLE}, the subscriber's virtual thread for the
 * rest. The bus swallows any {@link RuntimeException} the emitter throws: an
 * instrument must never become a failure channel.</p>
 *
 * @param subscriberId   the subscriber whose delivery dropped, or {@code "*"}
 *                       when the drop happened before fan-out
 *                       ({@link Kind#NOTIFY_NOT_VISIBLE} — no subscriber has
 *                       been resolved yet)
 * @param globalPosition the event position that was not delivered
 * @param kind           which drop point emitted
 * @param detail         one line of mechanism (the exception's simple class
 *                       name and message for a failed read; a fixed phrase
 *                       naming the arm otherwise)
 * @param timestamp      when the drop was observed, from the bus's injected
 *                       {@code Clock} (LTD-09 / NO_DIRECT_TIME_ACCESS)
 */
public record DeliveryAnomaly(
        String subscriberId,
        long globalPosition,
        Kind kind,
        String detail,
        Instant timestamp
) {

    /**
     * Compact constructor validating the reference components.
     *
     * @throws NullPointerException if {@code subscriberId}, {@code kind},
     *                              {@code detail} or {@code timestamp} is {@code null}
     */
    public DeliveryAnomaly {
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /** The drop point that emitted the anomaly. */
    public enum Kind {

        /**
         * {@code notifyEvent(P)} read an empty page for {@code P} — the
         * notification could not be filtered and was offered to no subscriber
         * (FIX-1a); the subscriber id is {@code "*"}.
         */
        NOTIFY_NOT_VISIBLE,

        /** The LIVE loop's single-position read for an offered position returned an empty page. */
        LIVE_READ_EMPTY,

        /** The LIVE loop's single-position read for an offered position threw. */
        LIVE_READ_FAILED,

        /** The TRANSITION drain's single-position read for a queued position returned an empty page. */
        TRANSITION_READ_EMPTY,

        /**
         * FIX-1b (DP-2): the LIVE loop exhausted its bounded read attempts on
         * one offered position and suspended the subscriber honestly — the
         * position was never delivered and the checkpoint never passed it.
         */
        LIVE_READ_EXHAUSTED,

        /**
         * FIX-1b (DP-2): the TRANSITION drain exhausted its bounded read
         * attempts on one queued position and suspended the subscriber
         * honestly ({@code drainAndPromote} returns {@code false}).
         */
        TRANSITION_READ_EXHAUSTED
    }
}

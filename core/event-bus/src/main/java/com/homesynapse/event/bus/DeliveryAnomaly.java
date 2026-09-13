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
 * <p>Before FIX-1a, a position the bus could not read at the moment it was
 * offered was skipped silently, and the next successful delivery wrote a
 * checkpoint past it — a failure through a silent channel in the module the
 * deterministic floor stands on. Every such point emits one
 * {@code DeliveryAnomaly} through the {@code Consumer<DeliveryAnomaly>}
 * injected at {@link InProcessEventBus} construction, BEFORE the path returns
 * or continues.</p>
 *
 * <p><strong>BUS-ORDER-1 (2026-09-12, AMD-101 §2).</strong> The LIVE loop and
 * the TRANSITION drain now deliver by reading the store forward from the
 * subscriber's in-memory cursor, in pages; an empty page means "caught up",
 * not a drop, and the next wake or idle tick reads again. The FIX-1b
 * per-position retry-and-suspend is retired on both paths, so four kinds
 * ({@link Kind#LIVE_READ_EMPTY}, {@link Kind#LIVE_READ_EXHAUSTED},
 * {@link Kind#TRANSITION_READ_EMPTY}, {@link Kind#TRANSITION_READ_EXHAUSTED})
 * and the notify-guard tripwire ({@link Kind#NOTIFY_SKIPPED_LIVE}) are no
 * longer emitted by any path. They stay defined: identifiers are permanent
 * (INV-GA-02), and a line carrying one of them in an older log still means
 * what it meant. What is still emitted: {@link Kind#NOTIFY_NOT_VISIBLE} and
 * {@link Kind#LIVE_READ_FAILED}.</p>
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
 * {@link Kind#NOTIFY_NOT_VISIBLE}, the subscriber's virtual thread for
 * {@link Kind#LIVE_READ_FAILED}. The bus swallows any
 * {@link RuntimeException} the emitter throws: an instrument must never
 * become a failure channel.</p>
 *
 * @param subscriberId   the subscriber whose delivery dropped, or {@code "*"}
 *                       when the drop happened before fan-out
 *                       ({@link Kind#NOTIFY_NOT_VISIBLE} — no subscriber has
 *                       been resolved yet)
 * @param globalPosition the event position that was not delivered — for a
 *                       failed page read, the first position the page would
 *                       have shown ({@code cursor + 1})
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

    /**
     * The drop point that emitted the anomaly. The constants are permanent
     * (INV-GA-02); the ones marked "retained" are no longer emitted by any
     * path since BUS-ORDER-1 (2026-09-12) — see the class javadoc.
     */
    public enum Kind {

        /**
         * {@code notifyEvent(P)} read an empty page for {@code P} — the
         * notification could not be filtered and was offered to no subscriber
         * (FIX-1a); the subscriber id is {@code "*"}. Still emitted: the bus then
         * wakes every active subscriber unfiltered (FIX-1b).
         */
        NOTIFY_NOT_VISIBLE,

        /**
         * Retained by INV-GA-02; not emitted by the LIVE path since BUS-ORDER-1.
         * FIX-1a: the LIVE loop's single-position read for an offered position
         * returned an empty page. Under the read-forward an empty page is
         * "caught up", never a drop.
         */
        LIVE_READ_EMPTY,

        /**
         * The LIVE loop's page read from the cursor threw. Still emitted, once
         * per failed page (BUS-ORDER-1), at {@code cursor + 1}; the next idle
         * tick retries the read.
         */
        LIVE_READ_FAILED,

        /**
         * Retained by INV-GA-02; not emitted since BUS-ORDER-1 — the TRANSITION
         * drain reads forward from the cursor in pages, and an empty page is
         * "caught up". FIX-1a: the drain's single-position read for a queued
         * position returned an empty page.
         */
        TRANSITION_READ_EMPTY,

        /**
         * Retained by INV-GA-02; not emitted by the LIVE path since BUS-ORDER-1
         * (the per-position retry-and-suspend is retired). FIX-1b (DP-2): the
         * LIVE loop exhausted its bounded read attempts on one offered position
         * and suspended the subscriber honestly.
         */
        LIVE_READ_EXHAUSTED,

        /**
         * Retained by INV-GA-02; not emitted since BUS-ORDER-1 (the drain's
         * per-position retry is retired; a page read that throws SUSPENDs the
         * subscriber without an anomaly, as REPLAY does). FIX-1b (DP-2): the
         * TRANSITION drain exhausted its bounded read attempts on one queued
         * position and suspended the subscriber honestly.
         */
        TRANSITION_READ_EXHAUSTED,

        /**
         * Retained by INV-GA-02; unreachable since BUS-ORDER-1 — the LIVE path
         * has no persisted-checkpoint guard, so a wake hint is never skipped.
         * FIX-2b-ii (i): {@code notifyEvent(P)} skipped a LIVE subscriber because
         * its persisted checkpoint was at or past {@code P} — for the
         * notification-ordered delivery of 5f918c7 that skip was a drop of
         * {@code P} (a LIVE subscriber learned a position only from its notify);
         * its first line in the wild was ci sample #15 (AMD-101 §6). The name
         * stays as the tripwire's name.
         */
        NOTIFY_SKIPPED_LIVE
    }
}

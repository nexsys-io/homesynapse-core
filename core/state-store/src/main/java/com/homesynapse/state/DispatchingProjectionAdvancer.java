/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Dispatching {@link ProjectionAdvancer} (Research 8 REC-28, PM mods A/B/C) —
 * replaces the M3.7 {@code MinimalProjectionAdvancer} placeholder at the
 * composition root (closes OR-M3-18).
 *
 * <p>Each {@link #advance} opens a single short-lived bounded read through
 * {@link EventStore#readFrom(long, int)}, then dispatches every envelope in the
 * returned page <b>by event type</b> to a constructor-injected
 * {@link EnvelopeHandler} (REC-28 mod C — map-lookup-by-event-type), falling
 * back to a default handler for unmapped types. No read transaction is held
 * across {@code advance} calls (AMD-38 bounded-window discipline).</p>
 *
 * <h2>Dispatch is the read/forward concern, not derivation (plan §4.2)</h2>
 *
 * <p>There is a genuine tension between REC-28's assessment language ("the
 * dispatch table IS the derivation mechanism; no new events") and plan §4.2,
 * which overrides it: the production {@link DerivationRule} <em>publishes</em>
 * real {@code state_changed} events so automation triggers wake. In current
 * source these are two separate collaborators — {@link StateProjection} calls
 * {@code rule.evaluate(...)} directly in the LIVE {@code onEvent} path (which
 * does not touch the advancer) and uses the advancer only in
 * {@code processBatch}. Therefore the handlers here dispatch <b>which event
 * types the projection forwards to its processor</b>, never derivation —
 * advancer-side derivation would silently never run on the LIVE path.</p>
 *
 * <h2>Amendment-free boundary: forward all, exact cursor parity</h2>
 *
 * <p>{@link AdvanceResult} has no {@code skipped()} factory (verified against
 * source), so this advancer does not invent a skip signal. Every dispatched
 * envelope is forwarded and counted toward {@code eventsProcessed}, and
 * {@code lastProcessedPosition} advances on every envelope — byte-for-byte the
 * same cursor accounting the M3.7 {@code MinimalProjectionAdvancer} guaranteed.
 * The default handler set ({@link #withDefaultHandlers}) maps the three event
 * types the projection materializes ({@code state_reported}, {@code
 * state_changed}, {@code availability_changed}) to a forwarding handler and uses
 * the same handler as the default, so unknown types forward identically. The
 * map exists as the REC-28 dispatch structure and the seam for future
 * type-specific read/forward handlers (M4.0b-2 / Workstream-B).</p>
 *
 * <h2>Gateway (DEC-M3-16, REC-28 mod A + B)</h2>
 *
 * <p>This class and {@link EnvelopeHandler} are package-private in
 * {@code com.homesynapse.state} (mod B — no {@code .handlers} subpackage). The
 * composition root cannot reference them directly, so the handler set is
 * assembled by {@link #withDefaultHandlers} inside this package and exposed
 * through the public static factory {@link ProjectionAdvancer#dispatching} (mod
 * A — explicit constructor injection, no {@code ServiceLoader} per DECIDE-04).
 * This mirrors {@link StateQueryService#materialized} and
 * {@link StateCheckpointSource#stub()}.</p>
 *
 * @see ProjectionAdvancer#dispatching(EventStore)
 * @see EnvelopeHandler
 * @see DerivationRule#production()
 */
final class DispatchingProjectionAdvancer implements ProjectionAdvancer {

    private final EventStore eventStore;
    private final Map<String, EnvelopeHandler> handlers;
    private final EnvelopeHandler defaultHandler;

    /**
     * Constructs an advancer with an explicitly-injected handler set (REC-28
     * mod A — no {@code ServiceLoader}).
     *
     * @param eventStore     the backing event store; never {@code null}
     * @param handlers       per-event-type handlers, keyed by event type string;
     *                       never {@code null} (defensively copied)
     * @param defaultHandler handler for event types absent from {@code handlers};
     *                       never {@code null}
     */
    DispatchingProjectionAdvancer(EventStore eventStore,
                                  Map<String, EnvelopeHandler> handlers,
                                  EnvelopeHandler defaultHandler) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.handlers = Map.copyOf(Objects.requireNonNull(handlers, "handlers"));
        this.defaultHandler = Objects.requireNonNull(defaultHandler, "defaultHandler");
    }

    /**
     * Assembles the standard handler set for the materialized state projection
     * and constructs the advancer. The three event types the projection
     * materializes are mapped to a single forwarding handler; the same handler
     * backs the default, so every event type forwards (preserving the M3.7
     * cursor semantics). This is the REC-28 mod A "explicit assembly" step,
     * performed inside the package that can see the package-private handlers.
     *
     * @param eventStore the backing event store; never {@code null}
     * @return a new dispatching advancer over {@code eventStore}
     */
    static DispatchingProjectionAdvancer withDefaultHandlers(EventStore eventStore) {
        EnvelopeHandler forward = new ForwardingHandler();
        Map<String, EnvelopeHandler> handlers = Map.of(
                EventTypes.STATE_REPORTED, forward,
                EventTypes.STATE_CHANGED, forward,
                EventTypes.AVAILABILITY_CHANGED, forward);
        return new DispatchingProjectionAdvancer(eventStore, handlers, forward);
    }

    @Override
    public AdvanceResult advance(long fromPosition, int maxRows,
                                 Consumer<EventEnvelope> processor) {
        Objects.requireNonNull(processor, "processor must not be null");
        if (fromPosition < 0) {
            throw new IllegalArgumentException(
                    "fromPosition must be >= 0, got " + fromPosition);
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException(
                    "maxRows must be >= 1, got " + maxRows);
        }

        int boundedMaxRows = Math.min(maxRows, DEFAULT_MAX_ROWS);
        EventPage page = eventStore.readFrom(fromPosition, boundedMaxRows);

        int processed = 0;
        long lastPosition = fromPosition;
        for (EventEnvelope envelope : page.events()) {
            EnvelopeHandler handler =
                    handlers.getOrDefault(envelope.eventType(), defaultHandler);
            handler.handle(envelope, processor);
            processed++;
            lastPosition = envelope.globalPosition();
        }

        return new AdvanceResult(lastPosition, processed, page.hasMore());
    }

    /**
     * The amendment-free read/forward handler: forwards the envelope unchanged
     * to the projection's processor. Derivation stays in the
     * {@link DerivationRule} (plan §4.2); this handler does not publish or
     * mutate state. M4.0b-2 may replace individual map entries with
     * type-specific handlers without changing the advancer's loop.
     */
    static final class ForwardingHandler implements EnvelopeHandler {

        /** Stateless; reached only from {@link #withDefaultHandlers}. */
        ForwardingHandler() {
            // No initialization required.
        }

        @Override
        public void handle(EventEnvelope envelope, Consumer<EventEnvelope> processor) {
            processor.accept(envelope);
        }
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.integration.CommandEnvelope;
import com.homesynapse.integration.CommandHandler;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The {@code command_dispatched} → {@link CommandHandler#handle(CommandEnvelope)}
 * routing subscriber (Doc 08 §3.10 steps 1–2) — the M9.1 spine's consumer half.
 *
 * <h2>The DP-2 causation join</h2>
 *
 * <p>{@code command_dispatched} carries no command name or parameters (frozen
 * shape, G3) — the router joins it back to its originating
 * {@code command_issued} via {@code causalContext().causationId()}. LIVE
 * {@code command_issued} envelopes (and REPLAY/TRANSITION ones — DP-4: a
 * {@code command_issued} delivered during TRANSITION may legitimately join a
 * LIVE {@code command_dispatched} across the flip) populate a bounded
 * insertion-ordered join cache keyed by {@code eventId().value()}. A join miss
 * is a structured WARN + skip — no dispatch, no result event (the pending-command
 * ledger's timeout owns that outcome). A successful join EVICTS the entry, so a
 * bus at-least-once redelivery is a miss and {@code handle(...)} runs exactly
 * once per dispatched command (AMD-90-INV-01 composes: no router retry).</p>
 *
 * <h2>INV-ES-09 — pure-function replay</h2>
 *
 * <p><strong>Dispatch fires only for LIVE {@code command_dispatched}
 * events.</strong> The {@code replayMode} guard is shape-identical to
 * {@code StandardCommandDispatchService}'s: the bus drives
 * {@link #setMode(SubscriberMode)} through COLD→REPLAY→LIVE, and
 * {@link #onEvent(EventEnvelope)} returns before any adapter I/O when not LIVE.
 * The composition-root gate ({@code RunPipelineReplaySafetyTest}) pins this
 * with a registered, routable recording fake.</p>
 *
 * <h2>The DP-5 invocation + failure boundary</h2>
 *
 * <p>{@code handle(...)} runs on the owning adapter's single-threaded command
 * executor (virtual thread {@code integration-cmd-<type>}, FIFO per adapter) —
 * never on the bus subscriber thread (W5: the {@code onEvent} path does
 * cache/join/submit only). Post-join failures each publish a
 * {@code command_result} (CRITICAL, origin SYSTEM, causation chained from the
 * {@code command_dispatched} envelope): unknown/not-running integration →
 * {@code "integration_unavailable"}; null {@code commandHandler()} (a legal
 * read-only adapter) → {@code "unsupported"}; a {@code handle(...)} throw →
 * {@code "handler_error"} (also fed to the health error window). On a normal
 * return the router publishes NOTHING — the adapter owns the eventual
 * {@code command_result} (Doc 08 §3.10 step 7); the router never fabricates
 * success.</p>
 *
 * <p>Never throws from {@code onEvent} — a throw is a subscriber crash; the
 * bus's isolation is the backstop, not the plan.</p>
 */
final class CommandRoutingSubscriber implements Subscriber {

    private static final Logger LOG = LoggerFactory.getLogger(CommandRoutingSubscriber.class);

    /** DP-4: join-cache capacity — the cap owns memory; the ledger owns timeouts. */
    static final int COMMAND_CACHE_CAPACITY = 1024;

    private static final int SCHEMA_VERSION = 1;

    private final StandardIntegrationSupervisor supervisor;
    private final Function<String, Map<String, Object>> parameterDecoder;
    private final EventPublisher publisher;
    private final Clock clock;

    private final ReentrantLock cacheLock = new ReentrantLock();
    /** Insertion-ordered drop-oldest join cache, keyed by the command_issued eventId Ulid. */
    private final LinkedHashMap<Ulid, CachedCommand> commandCache = new LinkedHashMap<>();

    /**
     * {@code false} only in {@code LIVE} mode. Defaults to {@code false} (the
     * dispatch-service/ledger precedent); the bus drives {@link #setMode} through
     * COLD→REPLAY→LIVE, setting this {@code true} before any replay delivery so
     * {@link #onEvent} never dispatches on replay.
     */
    private volatile boolean replayMode;

    CommandRoutingSubscriber(StandardIntegrationSupervisor supervisor,
                             Function<String, Map<String, Object>> parameterDecoder,
                             EventPublisher publisher,
                             Clock clock) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.parameterDecoder = Objects.requireNonNull(parameterDecoder, "parameterDecoder");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ── Subscriber ───────────────────────────────────────────────────────────

    @Override
    public void setMode(SubscriberMode mode) {
        this.replayMode = mode != SubscriberMode.LIVE;
    }

    @Override
    public void onEvent(EventEnvelope event) {
        try {
            if (event.payload() instanceof CommandIssuedEvent issued) {
                cacheIssued(event, issued);            // ALL modes (DP-4)
            } else if (event.payload() instanceof CommandDispatchedEvent dispatched) {
                if (replayMode) {
                    return;                            // INV-ES-09 — never dispatch on replay
                }
                routeDispatched(event, dispatched);
            }
            // Any other payload: the subscription filter should preclude it — ignore.
        } catch (RuntimeException unexpected) {
            LOG.error("integration.route_failure: unexpected error routing event_id={} "
                            + "event_type={} correlation_id={}",
                    event.eventId(), event.eventType(),
                    event.causalContext().correlationId(), unexpected);
        }
    }

    // ── Join cache (DP-4) ────────────────────────────────────────────────────

    private void cacheIssued(EventEnvelope event, CommandIssuedEvent issued) {
        Instant cachedAt = clock.instant();
        Ulid evictedKey = null;
        CachedCommand evicted = null;
        cacheLock.lock();
        try {
            if (commandCache.size() >= COMMAND_CACHE_CAPACITY) {
                Iterator<Map.Entry<Ulid, CachedCommand>> oldest =
                        commandCache.entrySet().iterator();
                Map.Entry<Ulid, CachedCommand> entry = oldest.next();
                evictedKey = entry.getKey();
                evicted = entry.getValue();
                oldest.remove();
            }
            commandCache.put(event.eventId().value(),
                    new CachedCommand(issued.commandType(), issued.parameters(), cachedAt));
        } finally {
            cacheLock.unlock();
        }
        if (evictedKey != null) {
            LOG.warn("integration.command_cache_evicted: command_event_id={} command_type={} "
                            + "cached_at={} — join cache at capacity {}; oldest dropped",
                    evictedKey, evicted.commandType(), evicted.cachedAt(),
                    COMMAND_CACHE_CAPACITY);
        }
    }

    // ── Routing (DP-2 / DP-5) ────────────────────────────────────────────────

    private void routeDispatched(EventEnvelope event, CommandDispatchedEvent dispatched) {
        Ulid causationId = event.causalContext().causationId();
        CachedCommand issued = null;
        if (causationId != null) {
            cacheLock.lock();
            try {
                issued = commandCache.remove(causationId);     // evict-on-join (T14)
            } finally {
                cacheLock.unlock();
            }
        }
        if (issued == null) {
            // No dispatch, no result event — the ledger's timeout owns the outcome.
            LOG.warn("integration.route_join_miss: event_id={} causation_id={} "
                            + "integration_id={} correlation_id={} — no cached command_issued; "
                            + "skipping dispatch",
                    event.eventId(), causationId, dispatched.integrationId(),
                    event.causalContext().correlationId());
            return;
        }

        IntegrationId integrationId = IntegrationId.of(dispatched.integrationId());
        CausalContext resultCause = CausalContext.chain(
                event.causalContext().correlationId(), event.eventId().value());

        Optional<StandardIntegrationSupervisor.RouteTarget> target =
                supervisor.routeTarget(integrationId);
        if (target.isEmpty()) {
            publishResult(dispatched.targetEntityRef(), issued.commandType(),
                    "integration_unavailable",
                    "Integration '" + integrationId + "' is not registered or not running",
                    resultCause, integrationId, causationId);
            return;
        }
        CommandHandler handler = target.get().adapter().commandHandler();
        if (handler == null) {
            // Legal per the integration-api gotcha: a read-only adapter.
            publishResult(dispatched.targetEntityRef(), issued.commandType(), "unsupported",
                    "Integration '" + target.get().integrationType()
                            + "' does not handle commands",
                    resultCause, integrationId, causationId);
            return;
        }

        CommandEnvelope command = new CommandEnvelope(
                EntityId.of(dispatched.targetEntityRef()),
                issued.commandType(),
                decodeParameters(issued.parameters(), causationId),
                causationId,
                event.causalContext().correlationId(),
                integrationId);
        try {
            // W5: never block the bus thread — handle(...) runs on the owning
            // adapter's single-threaded command executor (FIFO per adapter).
            target.get().commandExecutor().execute(
                    () -> invokeHandler(handler, command, resultCause));
        } catch (RejectedExecutionException stopped) {
            publishResult(dispatched.targetEntityRef(), issued.commandType(),
                    "integration_unavailable",
                    "Integration '" + target.get().integrationType()
                            + "' command executor is stopped",
                    resultCause, integrationId, causationId);
        }
    }

    private void invokeHandler(CommandHandler handler, CommandEnvelope command,
                               CausalContext resultCause) {
        try {
            handler.handle(command);
            // Normal return: publish NOTHING — the adapter owns the eventual
            // command_result (Doc 08 §3.10 step 7); never fabricate success.
        } catch (InterruptedException shutdown) {
            Thread.currentThread().interrupt();       // shutdown — no result event
        } catch (Exception failure) {
            supervisor.recordHandlerError(command.integrationId(), failure);
            LOG.warn("integration.handler_error: integration_id={} command_event_id={} "
                            + "correlation_id={} command={}",
                    command.integrationId(), command.commandEventId(),
                    command.correlationId(), command.commandName(), failure);
            publishResult(command.entityRef().value(), command.commandName(), "handler_error",
                    "Command handler failed: " + failure.getClass().getSimpleName()
                            + (failure.getMessage() != null ? ": " + failure.getMessage() : ""),
                    resultCause, command.integrationId(), command.commandEventId());
        }
    }

    private Map<String, Object> decodeParameters(String parameters, Ulid commandEventId) {
        try {
            Map<String, Object> decoded = parameterDecoder.apply(parameters);
            return decoded != null ? decoded : Map.of();
        } catch (RuntimeException decodeFailure) {
            // DP-3: a decode failure never blocks the dispatch — the adapter still
            // receives the command with empty parameters.
            LOG.warn("integration.parameter_decode_failed: command_event_id={} — dispatching "
                    + "with empty parameters", commandEventId, decodeFailure);
            return Map.of();
        }
    }

    /**
     * DP-5 failure results: CRITICAL priority (Doc 01 §4.3 dual default on
     * failure), origin SYSTEM, causation chained from the {@code command_dispatched}
     * envelope (INV-ES-06).
     */
    private void publishResult(Ulid targetEntityRef, String commandType, String outcome,
                               String failureReason, CausalContext cause,
                               IntegrationId integrationId, Ulid commandEventId) {
        EventDraft draft = new EventDraft(EventTypes.COMMAND_RESULT, SCHEMA_VERSION, null,
                SubjectRef.entity(EntityId.of(targetEntityRef)), EventPriority.CRITICAL,
                EventOrigin.SYSTEM,
                new CommandResultEvent(targetEntityRef, commandType, outcome, failureReason),
                null, null);
        LOG.warn("integration.command_result: outcome={} integration_id={} command_event_id={} "
                        + "correlation_id={} reason={}",
                outcome, integrationId, commandEventId, cause.correlationId(), failureReason);
        try {
            publisher.publish(draft, cause);
        } catch (SequenceConflictException conflict) {
            LOG.error("Failed to publish command_result({}) for entity {}: sequence conflict",
                    outcome, targetEntityRef, conflict);
        }
    }

    /** One cached {@code command_issued}: the join payload the dispatched event lacks. */
    private record CachedCommand(String commandType, String parameters, Instant cachedAt) {
    }
}

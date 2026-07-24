/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Javalin handler for {@code GET /api/v1/commands/{commandId}} — the
 * four-phase command lifecycle status (CMD-API, Doc 09 §4.5).
 *
 * <p>Assembles the status purely from ONE {@code readByCorrelation} chain
 * (a root command's correlation IS its own event id). Phase events link to
 * the command the way the live producers thread them (the M7.5a
 * ExplanationService precedent): {@code command_dispatched} /
 * {@code command_result} by causation id, {@code state_confirmed} /
 * {@code command_confirmation_timed_out} by their payload
 * {@code commandEventId} (the ledger's causation is the report event).</p>
 *
 * <p>The honesty contract (DP-5 / AMD-97-INV-01): {@code CONFIRMED} derives
 * ONLY from a {@code state_confirmed} event — no other path may render it
 * (never-false-CONFIRMED). An acknowledged {@code command_result} is
 * NON-terminal; any other outcome value (rejection or a ledger disposition
 * such as {@code superseded}) is terminal at ACKNOWLEDGED.</p>
 *
 * <p>Thread safety: stateless — all state reached through the injected
 * {@link EventStore} and {@link EntityRegistry}.</p>
 *
 * @see IssueCommandEndpoint
 * @see RestFilters#installCommandEndpoints
 */
final class GetCommandStatusEndpoint implements Handler {

    /** The single non-terminal {@code command_result} outcome (DP-5). */
    private static final String OUTCOME_ACKNOWLEDGED = "acknowledged";

    private final EventStore eventStore;
    private final EntityRegistry entityRegistry;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    /**
     * Constructs the handler over the given collaborators.
     *
     * @param eventStore           the correlation-chain read seam; never
     *                             {@code null}
     * @param entityRegistry       best-effort capability derivation for the
     *                             response's {@code capability} field (the
     *                             event payload carries only the command
     *                             name); never {@code null}
     * @param viewPositionSupplier projection cursor for {@code meta}; never
     *                             {@code null}
     * @param clock                injected clock; never {@code null}
     */
    GetCommandStatusEndpoint(EventStore eventStore,
                             EntityRegistry entityRegistry,
                             LongSupplier viewPositionSupplier,
                             Clock clock) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.viewPositionSupplier =
                Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void handle(Context ctx) {
        apply(new JavalinEndpointContext(ctx));
    }

    /**
     * Pure handler logic — package-private so tests can drive the endpoint
     * with a recording {@link EndpointContext} stub.
     *
     * @param ctx the request/response SPI; never {@code null}
     */
    void apply(EndpointContext ctx) {
        String raw = ctx.pathParam("commandId");
        Ulid commandUlid = parseUlid(raw);
        if (commandUlid == null) {
            notFound(ctx, raw);
            return;
        }
        List<EventEnvelope> chain = eventStore.readByCorrelation(commandUlid);
        if (chain.isEmpty()) {
            notFound(ctx, raw);
            return;
        }
        EventEnvelope issuedEnvelope = null;
        CommandIssuedEvent issuedPayload = null;
        for (EventEnvelope envelope : chain) {
            if (EventTypes.COMMAND_ISSUED.equals(envelope.eventType())
                    && envelope.eventId().value().equals(commandUlid)
                    && envelope.payload() instanceof CommandIssuedEvent issued) {
                issuedEnvelope = envelope;
                issuedPayload = issued;
                break;
            }
        }
        if (issuedEnvelope == null) {
            notFound(ctx, raw);
            return;
        }

        EntityId entityId = EntityId.of(issuedPayload.targetEntityRef());
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        CommandLifecyclePhase currentPhase = null;
        boolean terminal = false;

        for (EventEnvelope envelope : chain) {
            CommandLifecyclePhase phase = null;
            Map<String, Object> details = null;
            switch (envelope.eventType()) {
                case EventTypes.COMMAND_ISSUED -> {
                    if (envelope.eventId().value().equals(commandUlid)) {
                        phase = CommandLifecyclePhase.ACCEPTED;
                    }
                }
                case EventTypes.COMMAND_DISPATCHED -> {
                    if (causedByCommand(envelope, commandUlid)
                            && envelope.payload() instanceof CommandDispatchedEvent dispatched) {
                        phase = CommandLifecyclePhase.DISPATCHED;
                        details = detail("integration_id", dispatched.integrationId().toString());
                    }
                }
                case EventTypes.COMMAND_RESULT -> {
                    if (causedByCommand(envelope, commandUlid)
                            && envelope.payload() instanceof CommandResultEvent result) {
                        phase = CommandLifecyclePhase.ACKNOWLEDGED;
                        details = detail("result", result.outcome());
                        if (!OUTCOME_ACKNOWLEDGED.equals(result.outcome())) {
                            terminal = true;
                        }
                    }
                }
                case EventTypes.STATE_CONFIRMED -> {
                    // Never-false-CONFIRMED (AMD-97-INV-01): only a state_confirmed
                    // whose payload names THIS command may render CONFIRMED.
                    if (envelope.payload() instanceof StateConfirmedEvent confirmedEvent
                            && confirmedEvent.commandEventId().value().equals(commandUlid)) {
                        phase = CommandLifecyclePhase.CONFIRMED;
                        details = detail("match_type", confirmedEvent.matchType());
                        terminal = true;
                    }
                }
                case EventTypes.COMMAND_CONFIRMATION_TIMED_OUT -> {
                    if (envelope.payload() instanceof CommandConfirmationTimedOutEvent timedOut
                            && timedOut.commandEventId().value().equals(commandUlid)) {
                        phase = CommandLifecyclePhase.CONFIRMATION_TIMED_OUT;
                        terminal = true;
                    }
                }
                default -> {
                    // Not a lifecycle event for this command (e.g. state_reported).
                }
            }
            if (phase != null) {
                lifecycle.put(phase.name(), phaseDetail(envelope, details));
                currentPhase = phase;
            }
        }

        long cursor = viewPositionSupplier.getAsLong();

        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("commandId", issuedEnvelope.eventId().toString());
        data.put("correlationId",
                issuedEnvelope.causalContext().correlationId().toString());
        data.put("entityId", entityId.toString());
        data.put("capability", deriveCapability(entityId, issuedPayload.commandType()));
        data.put("command", issuedPayload.commandType());
        data.put("lifecycle", lifecycle);
        data.put("currentPhase", currentPhase.name());
        data.put("terminal", terminal);

        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", cursor);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", data);
        body.put("meta", meta);

        ctx.status(200);
        ctx.header("Cache-Control", "no-store");
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER, Long.toString(cursor));
        ctx.json(body);
    }

    /** Dispatch/result events thread {@code causation = command_issued.eventId} (M7.4a). */
    private static boolean causedByCommand(EventEnvelope envelope, Ulid commandUlid) {
        return commandUlid.equals(envelope.causalContext().causationId());
    }

    /**
     * Best-effort capability derivation: the {@code command_issued} payload
     * carries only the command name, so the capability is resolved from the
     * entity's CURRENT registry declaration ({@code null} when the entity or
     * a declaring capability no longer exists).
     */
    private String deriveCapability(EntityId entityId, String commandType) {
        Optional<Entity> entity = entityRegistry.findEntity(entityId);
        if (entity.isEmpty()) {
            return null;
        }
        for (CapabilityInstance instance : entity.get().capabilities()) {
            if (instance.commands().containsKey(commandType)) {
                return instance.capabilityId();
            }
        }
        return null;
    }

    /** Renders one {@code {at, eventId, details}} phase object (DP-2). */
    private static Map<String, Object> phaseDetail(EventEnvelope envelope,
                                                   Map<String, Object> details) {
        Map<String, Object> phase = new LinkedHashMap<>(3);
        phase.put("at", envelope.ingestTime().toString());
        phase.put("eventId", envelope.eventId().toString());
        phase.put("details", details);
        return phase;
    }

    private static Map<String, Object> detail(String key, Object value) {
        Map<String, Object> details = new LinkedHashMap<>(1);
        details.put(key, value);
        return details;
    }

    private static Ulid parseUlid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Ulid.parse(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void notFound(EndpointContext ctx, String raw) {
        EndpointResponses.problem(ctx, ProblemType.COMMAND_NOT_FOUND,
                "Command not found: " + raw);
    }
}

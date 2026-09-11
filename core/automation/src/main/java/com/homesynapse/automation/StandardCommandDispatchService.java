/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.homesynapse.device.Device;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link CommandDispatchService}: the router that resolves a command's target entity to
 * its owning integration, validates the command against the entity's capabilities, and either
 * hands the command off (by publishing {@code command_dispatched}, which the integration
 * supervisor routes to the adapter) or rejects it (Doc 07 §3.11.1).
 *
 * <h2>Event-driven, co-located dispatch (M7.4a — §1 D1 / AMD-95)</h2>
 * <p>This is the co-located {@code command_dispatch_service} bus {@link Subscriber} — one object,
 * two views (the {@link CommandDispatchService} surface and the bus {@code Subscriber}), the
 * M7.3 ledger precedent. {@link #onEvent(EventEnvelope)} consumes {@code command_issued} and runs
 * the resolve&rarr;validate&rarr;handoff&rarr;emit path, so the log is the single source of truth
 * for dispatch (not merely its record). The executor no longer dispatches in-process; it emits
 * {@code command_issued} and this subscriber acts on it.</p>
 *
 * <p><strong>D2 pure-function-replay (the load-bearing constraint).</strong> The subscriber
 * performs no external side-effect on REPLAY: {@link #onEvent} dispatches only when the subscriber
 * is in {@code LIVE} (tracked via {@link #setMode(SubscriberMode)}). The bus FSM already withholds
 * runtime delivery until LIVE; this guard pins it so a recovery replay can never re-fire a real
 * command.</p>
 *
 * <p><strong>Resolution.</strong> The device model exposes no single
 * {@code getIntegrationForEntity}; routing is the two-hop {@link EntityRegistry#findEntity}
 * &rarr; {@link Entity#deviceId()} &rarr; {@link DeviceRegistry#findDevice} &rarr;
 * {@link Device#integrationId()}. A missing entity, a helper entity with no device, or an
 * unknown device yields {@code unroutable}.</p>
 *
 * <p><strong>Events + causality.</strong> On success: {@code command_dispatched} (DIAGNOSTIC). On
 * failure: {@code command_result} with {@code outcome} {@code "invalid"} (capability mismatch) or
 * {@code "unroutable"} (no integration). Both are existing event records (DP-E) — this service
 * never mints a command event type. From the bus path the command events thread the full causal
 * chain (Doc 07 §3.11.2): correlation = the Run's (the {@code command_issued} correlation),
 * causation = the {@code command_issued} event id. The in-process {@link #dispatch} primitive,
 * which knows only the originating command event id, retains the thin-router causality
 * ({@code chain(commandEventId, commandEventId)}).</p>
 *
 * <p><strong>Provenance (HONESTY-1 ORIGIN-1; Doc 01 §3.9).</strong> {@code origin} is
 * evidence-based and the system never guesses it. On the bus path the {@code command_issued}
 * envelope is the evidence, so {@code command_dispatched} and the router's own
 * {@code command_result} INHERIT its {@code origin} and {@code actorRef} (a REST-issued command
 * dispatches as {@code USER_COMMAND} with its actor; an automation's as {@code AUTOMATION}). The
 * in-process {@link #dispatch} primitive holds no envelope, so it stamps
 * {@link EventOrigin#UNKNOWN} with no actor — the enum's honest default, not a guessed
 * {@code AUTOMATION}.</p>
 *
 * <p><strong>Validation scope.</strong> The Tier-1 dispatch validation is command/capability
 * existence + feature gating only (deep parameter-schema validation is out of scope —
 * {@link CommandValidator}); the serialized parameters travel durably on {@code command_issued}
 * for the integration adapter (M9), so the bus path does not deserialize them
 * ({@code com.homesynapse.automation} carries no JSON library).</p>
 *
 * <p>Thread-safe — stateless apart from its injected collaborators (and the volatile mode flag);
 * publishes outside any lock (LTD-11).</p>
 */
public final class StandardCommandDispatchService implements CommandDispatchService, Subscriber {

    private static final Logger LOG =
            LoggerFactory.getLogger(StandardCommandDispatchService.class);

    private static final int SCHEMA_VERSION = 1;

    /** {@code command_dispatched} carries no protocol metadata yet (filled by the adapter). */
    private static final String NO_PROTOCOL_METADATA = "{}";

    /**
     * The {@code command_result.outcome} for a Tier-1 validation rejection — a report
     * about a command that never entered the dispatch pipeline. A DISPOSITION: the
     * ledger's {@code onCommandResult} guard skips it (SD-4 / M9.4b §4 — the F-2
     * loop-back class found live at grounding); package-private so the membership
     * test cross-pins it against the ledger's constant.
     */
    static final String OUTCOME_INVALID = "invalid";

    private final EntityRegistry entityRegistry;
    private final DeviceRegistry deviceRegistry;
    private final CommandValidator commandValidator;
    private final EventPublisher publisher;

    /**
     * {@code false} only in {@code LIVE} mode. Defaults to {@code false} (the ledger precedent);
     * the bus drives {@link #setMode(SubscriberMode)} through COLD&rarr;REPLAY&rarr;LIVE, setting
     * this {@code true} before any replay delivery so {@link #onEvent} never dispatches on replay.
     */
    private volatile boolean replayMode;

    /**
     * Constructs the dispatch service against its injected collaborators.
     *
     * @param entityRegistry   resolves an entity to its owning device, never {@code null}
     * @param deviceRegistry   resolves a device to its integration, never {@code null}
     * @param commandValidator validates a command against the target's capabilities, never
     *                         {@code null}
     * @param publisher        the durable event publish surface, never {@code null}
     */
    public StandardCommandDispatchService(EntityRegistry entityRegistry,
                                          DeviceRegistry deviceRegistry,
                                          CommandValidator commandValidator,
                                          EventPublisher publisher) {
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
        this.commandValidator = Objects.requireNonNull(commandValidator, "commandValidator");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    // ── CommandDispatchService (in-process primitive) ───────────────────────

    /**
     * The in-process primitive. Provenance: no issuing envelope is in hand — only the
     * originating command event id — so the command events it publishes carry
     * {@link EventOrigin#UNKNOWN} and no {@code actorRef} (Doc 01 §3.9: origin is never
     * guessed; a store read to recover the issuer's origin is out of scope). The bus path
     * ({@link #onEvent}) inherits the real provenance.
     */
    @Override
    public void dispatch(EventId commandEventId, EntityId targetRef, String commandName,
                         Map<String, Object> parameters) {
        Objects.requireNonNull(commandEventId, "commandEventId must not be null");
        Objects.requireNonNull(targetRef, "targetRef must not be null");
        Objects.requireNonNull(commandName, "commandName must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        // Thin-router causality: only the originating command event id is known here, so the
        // command events publish on chain(commandEventId, commandEventId). The bus path
        // (onEvent) threads the full Run correlation per Doc 07 §3.11.2.
        route(targetRef, commandName, parameters,
                CausalContext.chain(commandEventId.value(), commandEventId.value()),
                EventOrigin.UNKNOWN, null);
    }

    @Override
    public void close() {
        // Stateless today — the dispatch service holds no SQLite read connection or other
        // resource. The composition root MUST still call this (the reverted-M7.3 lesson: a
        // runtime subscriber registered with no paired teardown leaks its held resources). This
        // establishes the teardown pattern M7.4b mirrors for the pending_command_ledger.
        // Idempotent.
    }

    // ── Subscriber (the co-located command_dispatch_service bus view) ────────

    @Override
    public void setMode(SubscriberMode mode) {
        this.replayMode = mode != SubscriberMode.LIVE;
    }

    /**
     * The bus path: consumes {@code command_issued} in {@code LIVE} only. The issued envelope is
     * the evidence for provenance (Doc 01 §3.9), so its {@code origin} and {@code actorRef} are
     * inherited by the {@code command_dispatched} / {@code command_result} this call publishes.
     */
    @Override
    public void onEvent(EventEnvelope event) {
        if (replayMode) {
            return;                             // D2 pure-function-replay — never dispatch on replay
        }
        if (!(event.payload() instanceof CommandIssuedEvent issued)) {
            return;                             // not command_issued — the filter should preclude this
        }
        EntityId target = EntityId.of(issued.targetEntityRef());
        // Tier-1 validation is command/capability existence only; the serialized parameters stay
        // on the durable command_issued for the adapter (M9), so an empty map suffices here.
        // Causality (Doc 07 §3.11.2): correlation = the Run's, causation = the command_issued id.
        // Provenance (Doc 01 §3.9): the issued envelope's origin + actorRef, inherited as-is.
        route(target, issued.commandType(), Map.of(),
                CausalContext.chain(event.causalContext().correlationId(), event.eventId().value()),
                event.origin(), event.actorRef());
    }

    // ── Shared resolve → validate → emit ─────────────────────────────────────

    /**
     * The dispatch core shared by the in-process primitive and the bus path. Resolves the target
     * to an integration, validates the command, and publishes {@code command_dispatched} on
     * success or {@code command_result} on failure — all threaded on {@code cause} and stamped
     * with the caller's provenance pair ({@code origin}, {@code actorRef}; the actor nullable).
     */
    private void route(EntityId targetRef, String commandName, Map<String, Object> parameters,
                       CausalContext cause, EventOrigin origin, Ulid actorRef) {
        Optional<IntegrationId> integration = resolveIntegration(targetRef);
        if (integration.isEmpty()) {
            publishResult(targetRef, commandName, "unroutable",
                    "Entity '" + targetRef + "' is not routable to an integration",
                    cause, origin, actorRef);
            return;
        }
        CommandValidator.ValidationResult validation =
                commandValidator.validate(targetRef, commandName, parameters);
        if (!validation.valid()) {
            publishResult(targetRef, commandName, OUTCOME_INVALID, validation.reason(),
                    cause, origin, actorRef);
            return;
        }
        publishDispatched(targetRef, integration.get(), cause, origin, actorRef);
    }

    /** Two-hop entity &rarr; device &rarr; integration resolution; empty when unroutable. */
    private Optional<IntegrationId> resolveIntegration(EntityId targetRef) {
        Optional<Entity> entity = entityRegistry.findEntity(targetRef);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        DeviceId deviceId = entity.get().deviceId();
        if (deviceId == null) {
            return Optional.empty();
        }
        return deviceRegistry.findDevice(deviceId).map(Device::integrationId);
    }

    private void publishDispatched(EntityId targetRef, IntegrationId integrationId,
                                   CausalContext cause, EventOrigin origin, Ulid actorRef) {
        CommandDispatchedEvent payload = new CommandDispatchedEvent(
                targetRef.value(), integrationId.value(), NO_PROTOCOL_METADATA);
        publish(EventTypes.COMMAND_DISPATCHED, payload, targetRef, EventPriority.DIAGNOSTIC,
                cause, origin, actorRef);
    }

    private void publishResult(EntityId targetRef, String commandName, String outcome,
                               String failureReason, CausalContext cause,
                               EventOrigin origin, Ulid actorRef) {
        CommandResultEvent payload = new CommandResultEvent(
                targetRef.value(), commandName, outcome, failureReason);
        publish(EventTypes.COMMAND_RESULT, payload, targetRef, EventPriority.NORMAL,
                cause, origin, actorRef);
    }

    /**
     * Publishes one command event on {@code cause}, stamped with the provenance pair the caller
     * established: the issued envelope's {@code origin}/{@code actorRef} on the bus path,
     * {@link EventOrigin#UNKNOWN}/{@code null} from the primitive. The draft's
     * {@code idempotencyKey} stays {@code null} (unchanged).
     */
    private void publish(String eventType, DomainEvent payload, EntityId targetRef,
                         EventPriority priority, CausalContext cause,
                         EventOrigin origin, Ulid actorRef) {
        EventDraft draft = new EventDraft(eventType, SCHEMA_VERSION, null,
                SubjectRef.entity(targetRef), priority, origin, payload,
                actorRef, null);
        try {
            publisher.publish(draft, cause);
        } catch (SequenceConflictException ex) {
            LOG.error("Failed to publish {} for entity {}: sequence conflict",
                    eventType, targetRef, ex);
        }
    }
}

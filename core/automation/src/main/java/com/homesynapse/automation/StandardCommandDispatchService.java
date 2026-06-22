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
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link CommandDispatchService}: the thin router that resolves a command's target
 * entity to its owning integration, validates the command against the entity's capabilities,
 * and either hands the command off (by publishing {@code command_dispatched}, which the
 * integration supervisor routes to the adapter) or rejects it (Doc 07 §3.11.1).
 *
 * <p><strong>Resolution.</strong> The device model exposes no single
 * {@code getIntegrationForEntity}; routing is the two-hop {@link EntityRegistry#findEntity}
 * &rarr; {@link Entity#deviceId()} &rarr; {@link DeviceRegistry#findDevice} &rarr;
 * {@link Device#integrationId()}. A missing entity, a helper entity with no device, or an
 * unknown device yields {@code unroutable}.</p>
 *
 * <p><strong>Events.</strong> On success: {@code command_dispatched} (DIAGNOSTIC). On
 * failure: {@code command_result} with {@code outcome} {@code "invalid"} (capability
 * mismatch) or {@code "unroutable"} (no integration). Both are existing event records (DP-E)
 * — this service never mints a command event type.</p>
 *
 * <p><strong>Causality (thin-router limitation).</strong> The fixed {@code dispatch}
 * signature carries only the originating {@code commandEventId}, not the chain's correlation
 * id or actor — so the command events publish on
 * {@code CausalContext.chain(commandEventId, commandEventId)} with a {@code null} actor.
 * Full command-causality threading is M7.3 ({@code PendingCommandLedger}) scope.</p>
 *
 * <p>Thread-safe — stateless apart from its injected collaborators; publishes outside any
 * lock (LTD-11).</p>
 */
public final class StandardCommandDispatchService implements CommandDispatchService {

    private static final Logger LOG =
            LoggerFactory.getLogger(StandardCommandDispatchService.class);

    private static final int SCHEMA_VERSION = 1;

    /** {@code command_dispatched} carries no protocol metadata yet (filled by the adapter). */
    private static final String NO_PROTOCOL_METADATA = "{}";

    private final EntityRegistry entityRegistry;
    private final DeviceRegistry deviceRegistry;
    private final CommandValidator commandValidator;
    private final EventPublisher publisher;

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

    @Override
    public void dispatch(EventId commandEventId, EntityId targetRef, String commandName,
                         Map<String, Object> parameters) {
        Objects.requireNonNull(commandEventId, "commandEventId must not be null");
        Objects.requireNonNull(targetRef, "targetRef must not be null");
        Objects.requireNonNull(commandName, "commandName must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");

        Optional<IntegrationId> integration = resolveIntegration(targetRef);
        if (integration.isEmpty()) {
            publishResult(commandEventId, targetRef, commandName, "unroutable",
                    "Entity '" + targetRef + "' is not routable to an integration");
            return;
        }
        CommandValidator.ValidationResult validation =
                commandValidator.validate(targetRef, commandName, parameters);
        if (!validation.valid()) {
            publishResult(commandEventId, targetRef, commandName, "invalid",
                    validation.reason());
            return;
        }
        publishDispatched(commandEventId, targetRef, integration.get());
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

    private void publishDispatched(EventId commandEventId, EntityId targetRef,
                                   IntegrationId integrationId) {
        CommandDispatchedEvent payload = new CommandDispatchedEvent(
                targetRef.value(), integrationId.value(), NO_PROTOCOL_METADATA);
        publish(EventTypes.COMMAND_DISPATCHED, payload, targetRef, EventPriority.DIAGNOSTIC,
                commandEventId);
    }

    private void publishResult(EventId commandEventId, EntityId targetRef, String commandName,
                               String outcome, String failureReason) {
        CommandResultEvent payload = new CommandResultEvent(
                targetRef.value(), commandName, outcome, failureReason);
        publish(EventTypes.COMMAND_RESULT, payload, targetRef, EventPriority.NORMAL,
                commandEventId);
    }

    private void publish(String eventType, DomainEvent payload, EntityId targetRef,
                         EventPriority priority, EventId commandEventId) {
        EventDraft draft = new EventDraft(eventType, SCHEMA_VERSION, null,
                SubjectRef.entity(targetRef), priority, EventOrigin.AUTOMATION, payload,
                null, null);
        try {
            publisher.publish(draft,
                    CausalContext.chain(commandEventId.value(), commandEventId.value()));
        } catch (SequenceConflictException ex) {
            LOG.error("Failed to publish {} for entity {}: sequence conflict",
                    eventType, targetRef, ex);
        }
    }
}

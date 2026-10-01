/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.CapabilityAdded;
import com.homesynapse.integration.CapabilityPublisher;
import com.homesynapse.integration.CapabilityRemovalReason;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;

/**
 * The DISCOVERY family's publisher (AMD-59): ONE {@code capability.added} root
 * draft per call on the ENTITY subject (a capability event is about the entity),
 * origin INTEGRATION, priority NORMAL, event time from the injected clock, the
 * payload a {@link CapabilityAdded} whose {@code DeviceId} is resolved from the
 * shared registry. Provisioned by {@code StandardIntegrationSupervisor#buildContext}
 * into {@code IntegrationContext#discovery()} when the descriptor declares
 * {@code RequiredService.DISCOVERY} (IR-67); {@link #publishRemoved} throws until
 * the removal unit — the door stays shut mechanically, not by convention.
 *
 * <p>An entity the registry lacks, or a helper entity with no device
 * ({@code Entity.deviceId() == null}), is an {@link IllegalArgumentException} —
 * never a silent publish, never the record constructor's NPE. A
 * {@link SequenceConflictException} from the bus is ONE WARN
 * ({@code integration.capability_event_conflict}): the record's gap is the
 * finding; the registry's write-ahead view stands (the PJ-2 form).</p>
 *
 * <p>Thread-safe: stateless over the bus's publisher, which the supervisor's own
 * lifecycle publishes already share from other threads.</p>
 */
final class SupervisorCapabilityPublisher implements CapabilityPublisher {

    private static final Logger LOG =
            LoggerFactory.getLogger(SupervisorCapabilityPublisher.class);

    private static final int SCHEMA_VERSION = 1;

    private final IntegrationId integrationId;
    private final EventPublisher publisher;
    private final EntityRegistry entityRegistry;
    private final Clock clock;

    SupervisorCapabilityPublisher(IntegrationId integrationId, EventPublisher publisher,
            EntityRegistry entityRegistry, Clock clock) {
        this.integrationId = Objects.requireNonNull(integrationId, "integrationId");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void publishAdded(EntityId entityId, CapabilityInstance instance) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(instance, "instance");
        Entity entity = entityRegistry.findEntity(entityId).orElseThrow(
                () -> new IllegalArgumentException(
                        "capability.added: unknown entity " + entityId));
        if (entity.deviceId() == null) {
            throw new IllegalArgumentException(
                    "capability.added: entity has no device: " + entityId);
        }
        try {
            publisher.publishRoot(new EventDraft(
                    EventTypes.CAPABILITY_ADDED, SCHEMA_VERSION, clock.instant(),
                    SubjectRef.entity(entityId),
                    EventPriority.NORMAL, EventOrigin.INTEGRATION,
                    new CapabilityAdded(integrationId, entity.deviceId(), entityId, instance),
                    null, null));
        } catch (SequenceConflictException conflict) {
            LOG.warn("integration.capability_event_conflict: entity={} capability={}: {}",
                    entityId, instance.capabilityId(), conflict.getMessage());
        }
    }

    @Override
    public void publishAdded(EntityId entityId, Class<? extends Capability> capability) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(capability, "capability");
        // AMD-59-INV-03: the permit class is the identity; the published instance
        // is the standard one in the classifier's seven-arg form (featureMap 0).
        Capability standard = StandardCapabilities.all().stream()
                .filter(capability::isInstance)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "capability.added: no standard instance for " + capability.getName()));
        publishAdded(entityId, new CapabilityInstance(standard.capabilityId(),
                standard.version(), standard.namespace(), 0, standard.attributeSchemas(),
                standard.commandDefinitions(), standard.confirmationPolicy()));
    }

    @Override
    public void publishRemoved(EntityId entityId, String capabilityId,
            CapabilityRemovalReason reason) {
        throw new UnsupportedOperationException(
                "capability.removed is not published before its own unit "
                        + "(IR-67: additive only — removal=none)");
    }
}

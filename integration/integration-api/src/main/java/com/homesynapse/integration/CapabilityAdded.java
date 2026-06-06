/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.event.EventType;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;

import java.util.Objects;

/**
 * Payload for {@code capability.added} events, produced when an entity gains a
 * capability after adoption (AMD-59 §2.2).
 *
 * <p>The event carries the complete {@link CapabilityInstance} so that replay
 * reconstructs {@code Entity.capabilities} from the log alone — replay
 * self-sufficiency (AMD-59-INV-02). The entity-registry projection appends the
 * instance to the target entity's capability list, replacing any same-{@code
 * capabilityId} instance (a re-add is an upgrade).</p>
 *
 * <p>{@link #capabilityId()} is derived from {@code instance.capabilityId()} — it
 * satisfies the {@link CapabilityEvent} contract without being a record
 * component, so it is not serialized as a separate field (the same pattern as
 * {@code IntegrationStarted.previousState()}).</p>
 *
 * @param integrationId the integration that owns the entity; never {@code null}
 * @param deviceId      the device behind the entity; never {@code null}
 * @param entityId      the entity that gained the capability; never {@code null}
 * @param instance      the complete capability instance bound to the entity;
 *                      never {@code null}
 *
 * @see CapabilityEvent
 * @see CapabilityRemoved
 * @see CapabilityPublisher#publishAdded(EntityId, CapabilityInstance)
 */
@EventType(EventTypes.CAPABILITY_ADDED)
public record CapabilityAdded(
        IntegrationId integrationId,
        DeviceId deviceId,
        EntityId entityId,
        CapabilityInstance instance
) implements CapabilityEvent {

    public CapabilityAdded {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(instance, "instance must not be null");
    }

    /**
     * Returns the persisted capability identity, derived from the carried
     * instance.
     *
     * @return {@code instance.capabilityId()}, never {@code null}
     */
    @Override
    public String capabilityId() {
        return instance.capabilityId();
    }
}

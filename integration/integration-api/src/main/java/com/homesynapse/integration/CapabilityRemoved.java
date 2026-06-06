/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.EventType;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;

import java.util.Objects;

/**
 * Payload for {@code capability.removed} events, produced when an entity loses a
 * capability after adoption (AMD-59 §2.2).
 *
 * <p>The entity-registry projection removes the matching {@code capabilityId}
 * from the target entity's capability list. {@link #entityId()} is stable across
 * capability add/remove — there is no identity churn (AMD-59-INV-04).</p>
 *
 * <p>The {@link #reason()} is descriptive diagnostics only and never branches
 * core behaviour (AMD-59-INV-06); consumers (M8 automations, the UI) may branch
 * on it — see {@link CapabilityRemovalReason}.</p>
 *
 * @param integrationId the integration that owns the entity; never {@code null}
 * @param deviceId      the device behind the entity; never {@code null}
 * @param entityId      the entity that lost the capability; never {@code null}
 * @param capabilityId  the persisted identity of the removed capability;
 *                      never {@code null}
 * @param reason        why the capability was removed; never {@code null}
 *
 * @see CapabilityEvent
 * @see CapabilityAdded
 * @see CapabilityRemovalReason
 * @see CapabilityPublisher#publishRemoved(EntityId, String, CapabilityRemovalReason)
 */
@EventType(EventTypes.CAPABILITY_REMOVED)
public record CapabilityRemoved(
        IntegrationId integrationId,
        DeviceId deviceId,
        EntityId entityId,
        String capabilityId,
        CapabilityRemovalReason reason
) implements CapabilityEvent {

    public CapabilityRemoved {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}

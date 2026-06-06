/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;

/**
 * Sealed root of the capability-change event hierarchy (AMD-59 §2.2).
 *
 * <p>Devices gain and lose capabilities after adoption (firmware updates,
 * endpoint reconfiguration, quirk-profile corrections). These events are the only
 * post-adoption mutation path for {@code Entity.capabilities} (AMD-59-INV-01):
 * the entity-registry projection applies {@link CapabilityAdded} by appending the
 * instance to the target entity's capability list and {@link CapabilityRemoved}
 * by removing the matching {@code capabilityId}.</p>
 *
 * <p>This is a separate hierarchy from {@link IntegrationLifecycleEvent} — the
 * lifecycle parent's five-accessor state-transition contract does not fit
 * capability changes. Like the lifecycle events, the permits live in
 * {@code com.homesynapse.integration} rather than {@code com.homesynapse.event}
 * (AMD-33: {@code DomainEvent} is permanently non-sealed precisely so subsystem
 * event hierarchies can live in their own module).</p>
 *
 * <p>Capability type identity is the permit class of {@code Capability} (in-JVM,
 * for pattern matching) and its {@code String capabilityId} (the persisted form);
 * no {@code CapabilityId} wrapper type exists (AMD-59-INV-03).</p>
 *
 * @see CapabilityAdded
 * @see CapabilityRemoved
 * @see CapabilityPublisher
 */
public sealed interface CapabilityEvent extends DomainEvent
        permits CapabilityAdded, CapabilityRemoved {

    /**
     * Returns the identity of the integration that owns the affected entity.
     *
     * @return the integration ID, never {@code null}
     */
    IntegrationId integrationId();

    /**
     * Returns the identity of the device behind the affected entity.
     *
     * @return the device ID, never {@code null}
     */
    DeviceId deviceId();

    /**
     * Returns the identity of the entity whose capabilities changed.
     *
     * <p>Capabilities live on the {@code Entity}; the projection cannot
     * deterministically target an entity from {@code DeviceId} alone, because a
     * multi-endpoint device maps to many entities.</p>
     *
     * @return the entity ID, never {@code null}
     */
    EntityId entityId();

    /**
     * Returns the persisted string identity of the affected capability (e.g.,
     * {@code "on_off"}, {@code "occupancy"}).
     *
     * @return the capability identifier, never {@code null}
     */
    String capabilityId();
}

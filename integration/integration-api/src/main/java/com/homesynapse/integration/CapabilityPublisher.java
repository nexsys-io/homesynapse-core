/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.platform.identity.EntityId;

/**
 * Sanctioned path for an integration to publish post-adoption capability changes
 * for the entities it owns (AMD-59 §2.3). Reached through
 * {@link DiscoveryServices} on {@link IntegrationContext}, gated by
 * {@link RequiredService#DISCOVERY}.
 *
 * <p><strong>Integration-scoped (LTD-17, AMD-59-INV-05).</strong> An adapter may
 * publish capability changes only for entities owned by its own integration — the
 * M9 implementation injects the calling adapter's {@code IntegrationId}/{@code
 * DeviceId} scoping, mirroring the filtered {@code EntityRegistry}, and routes
 * through the standard {@code EventPublisher}.</p>
 *
 * @see DiscoveryServices
 * @see IntegrationContext#discovery()
 * @see RequiredService#DISCOVERY
 * @see CapabilityEvent
 */
public interface CapabilityPublisher {

    /**
     * Publishes a {@code capability.added} event for an entity owned by this
     * integration, carrying the complete capability instance.
     *
     * @param entityId the entity that gained the capability; never {@code null}
     * @param instance the complete capability instance bound to the entity;
     *                 never {@code null}
     */
    void publishAdded(EntityId entityId, CapabilityInstance instance);

    /**
     * Typed-identity convenience that resolves the standard
     * {@link CapabilityInstance} for the given permit class and publishes it
     * (honouring the permit-class identity model — AMD-59-INV-03).
     *
     * @param entityId   the entity that gained the capability; never {@code null}
     * @param capability the standard capability permit class; never {@code null}
     */
    void publishAdded(EntityId entityId, Class<? extends Capability> capability);

    /**
     * Publishes a {@code capability.removed} event for an entity owned by this
     * integration.
     *
     * @param entityId     the entity that lost the capability; never {@code null}
     * @param capabilityId the persisted identity of the removed capability;
     *                     never {@code null}
     * @param reason       why the capability was removed; never {@code null}
     */
    void publishRemoved(EntityId entityId, String capabilityId, CapabilityRemovalReason reason);
}

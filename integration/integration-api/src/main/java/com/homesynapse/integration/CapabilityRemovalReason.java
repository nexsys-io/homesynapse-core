/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Why a capability was removed from an entity, carried by {@link CapabilityRemoved}
 * (AMD-59 §2.2, ratification edit E8).
 *
 * <p><strong>Descriptive metadata only.</strong> This reason is for diagnostics,
 * audit, and consumer policy — it never routes supervisor, projection, or registry
 * behavior; orphan detection is unchanged (AMD-59-INV-06). The core never branches
 * on it. Consumers (M8 automations, the UI) may branch on it — in particular,
 * {@link #TRANSIENT_LOSS} exists so a transient mesh drop can be treated differently
 * from a deliberate unregistration: stripping user automations on a transient drop
 * is the documented Home Assistant failure mode this distinction prevents (Research
 * 12's Aqara field evidence — devices dropping off mesh without a leave request).</p>
 *
 * @see CapabilityRemoved
 * @see CapabilityPublisher#publishRemoved(com.homesynapse.platform.identity.EntityId, String, CapabilityRemovalReason)
 */
public enum CapabilityRemovalReason {

    /**
     * The device's firmware was downgraded and no longer reports the capability.
     */
    FIRMWARE_DOWNGRADE,

    /**
     * The physical device behind the entity was replaced with one lacking the
     * capability.
     */
    DEVICE_REPLACED,

    /**
     * The capability dropped off transiently (e.g., a mesh device fell off the
     * network without sending a leave request). Consumers should treat this as
     * potentially recoverable rather than a deliberate removal.
     */
    TRANSIENT_LOSS,

    /**
     * The capability was deliberately unregistered (e.g., the device left the
     * network or was administratively removed).
     */
    UNREGISTERED
}

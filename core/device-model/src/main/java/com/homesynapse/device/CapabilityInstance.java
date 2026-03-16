/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * A specific instantiation of a capability on a device entity.
 *
 * <p>While a {@link Capability} defines the abstract schema for what a device
 * <em>can</em> do, a {@code CapabilityInstance} represents the concrete binding
 * of that capability to a specific entity with a specific feature map. The
 * {@code featureMap} is a bitmap indicating which optional features this device
 * supports for this capability (per Zigbee ZCL semantics).</p>
 *
 * <p>All maps are unmodifiable.</p>
 *
 * <p>Defined in Doc 02 §4.3.</p>
 *
 * @param capabilityId the identifier of the instantiated capability, never {@code null}
 * @param version the schema version of the capability
 * @param namespace the namespace of the capability, never {@code null}
 * @param featureMap a bitmap of optional feature flags supported by this instance
 * @param attributes the attribute schemas for this instance, keyed by attribute key; unmodifiable
 * @param commands the command definitions for this instance, keyed by command type; unmodifiable
 * @param confirmation the confirmation policy for this instance, never {@code null}
 * @see Capability
 * @see Entity
 * @since 1.0
 */
public record CapabilityInstance(
        String capabilityId,
        int version,
        String namespace,
        int featureMap,
        Map<String, AttributeSchema> attributes,
        Map<String, CommandDefinition> commands,
        ConfirmationPolicy confirmation
) { }

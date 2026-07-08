/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Map;
import java.util.Objects;

/**
 * Event-local mirror of the device-model {@code CapabilityInstance} (AMD-99 §3,
 * full-fidelity — same seven components; maps keyed {@code String} to mirror
 * values). The installed DP-a confirmation tuning rides {@link #confirmation()}
 * — this is the fact the trust product proves later, independent of what
 * profile files say today.
 *
 * <p>NOT an event: no {@code EventType} annotation, does not implement
 * {@link DomainEvent}.</p>
 *
 * @param capabilityId the instantiated capability id, never {@code null}
 * @param version the capability schema version
 * @param namespace the capability namespace, never {@code null}
 * @param featureMap the optional-feature bitmap supported by this instance
 * @param attributes the attribute-schema mirrors keyed by attribute key,
 *        never {@code null}; key-sorted unmodifiable copy
 * @param commands the command-definition mirrors keyed by command type,
 *        never {@code null}; key-sorted unmodifiable copy
 * @param confirmation the confirmation-policy mirror (the installed DP-a
 *        tuning, captured as adopted), never {@code null}
 * @see EntityRegisteredEvent
 */
public record CapabilityInstanceRef(
        String capabilityId,
        int version,
        String namespace,
        int featureMap,
        Map<String, AttributeSchemaRef> attributes,
        Map<String, CommandDefinitionRef> commands,
        ConfirmationPolicyRef confirmation
) {

    /**
     * Validates required components and copies the map components into
     * key-sorted unmodifiable maps (deterministic serialization, AMD-99 §3).
     *
     * @throws NullPointerException if any required component is {@code null}
     */
    public CapabilityInstanceRef {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        Objects.requireNonNull(commands, "commands must not be null");
        Objects.requireNonNull(confirmation, "confirmation must not be null");
        attributes = PayloadMirrors.sortedMapCopy(attributes);
        commands = PayloadMirrors.sortedMapCopy(commands);
    }
}

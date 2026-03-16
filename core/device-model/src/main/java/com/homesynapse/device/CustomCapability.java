/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;
import java.util.Objects;

/**
 * A runtime-registered capability for devices with non-standard features.
 *
 * <p>Unlike the 14 standard capability records, {@code CustomCapability} is a
 * {@code final class} because custom capabilities are constructed at runtime from
 * JSON schema definitions provided by integration adapters. The namespace must NOT
 * be {@code "core"} (reserved for standard capabilities).</p>
 *
 * <p>Custom capabilities are registered via
 * {@link CapabilityRegistry#registerCustomCapability(CustomCapability)} and
 * participate in the same type system as standard capabilities, enabling
 * consistent attribute validation, command dispatch, and state management.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * <p>Defined in Doc 02 §3.9.</p>
 *
 * @see Capability
 * @see CapabilityRegistry
 * @since 1.0
 */
public final class CustomCapability implements Capability {

    private final String capabilityId;
    private final int version;
    private final String namespace;
    private final Map<String, AttributeSchema> attributeSchemas;
    private final Map<String, CommandDefinition> commandDefinitions;
    private final ConfirmationPolicy confirmationPolicy;

    /**
     * Constructs a new custom capability with the given schema definition.
     *
     * @param capabilityId the unique identifier for this custom capability, never {@code null}
     * @param version the schema version
     * @param namespace the owning namespace, never {@code null}; must not be {@code "core"}
     * @param attributeSchemas the attribute schemas keyed by attribute key, never {@code null}; stored as unmodifiable
     * @param commandDefinitions the command definitions keyed by command type, never {@code null}; stored as unmodifiable
     * @param confirmationPolicy the confirmation policy, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CustomCapability(
            String capabilityId,
            int version,
            String namespace,
            Map<String, AttributeSchema> attributeSchemas,
            Map<String, CommandDefinition> commandDefinitions,
            ConfirmationPolicy confirmationPolicy) {
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        this.version = version;
        this.namespace = Objects.requireNonNull(namespace, "namespace must not be null");
        this.attributeSchemas = Map.copyOf(Objects.requireNonNull(attributeSchemas, "attributeSchemas must not be null"));
        this.commandDefinitions = Map.copyOf(Objects.requireNonNull(commandDefinitions, "commandDefinitions must not be null"));
        this.confirmationPolicy = Objects.requireNonNull(confirmationPolicy, "confirmationPolicy must not be null");
    }

    @Override
    public String capabilityId() {
        return capabilityId;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public Map<String, AttributeSchema> attributeSchemas() {
        return attributeSchemas;
    }

    @Override
    public Map<String, CommandDefinition> commandDefinitions() {
        return commandDefinitions;
    }

    @Override
    public ConfirmationPolicy confirmationPolicy() {
        return confirmationPolicy;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CustomCapability other)) return false;
        return version == other.version
                && Objects.equals(capabilityId, other.capabilityId)
                && Objects.equals(namespace, other.namespace)
                && Objects.equals(attributeSchemas, other.attributeSchemas)
                && Objects.equals(commandDefinitions, other.commandDefinitions)
                && Objects.equals(confirmationPolicy, other.confirmationPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(capabilityId, version, namespace, attributeSchemas,
                commandDefinitions, confirmationPolicy);
    }

    @Override
    public String toString() {
        return "CustomCapability[capabilityId=" + capabilityId
                + ", version=" + version
                + ", namespace=" + namespace + "]";
    }
}

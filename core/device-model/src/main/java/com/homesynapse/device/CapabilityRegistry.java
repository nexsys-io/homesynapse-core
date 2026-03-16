/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.List;
import java.util.Optional;

/**
 * Registry for looking up capability definitions and registering custom capabilities.
 *
 * <p>Provides access to the 15 standard sealed capabilities and supports
 * runtime registration of {@link CustomCapability} instances from integration
 * adapters. Also provides schema and command definition lookups by capability
 * and attribute/command identifiers.</p>
 *
 * <p>Implementations must be safe for concurrent read access. Custom capability
 * registration is serialized.</p>
 *
 * <p>Defined in Doc 02 §8.1.</p>
 *
 * @see Capability
 * @see CustomCapability
 * @since 1.0
 */
public interface CapabilityRegistry {

    /**
     * Retrieves a capability by its identifier. Returns both standard and custom capabilities.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @return the capability definition, never {@code null}
     * @throws IllegalArgumentException if no capability exists with the given identifier
     */
    Capability getCapability(String capabilityId);

    /**
     * Returns all 14 standard sealed capabilities.
     *
     * @return an unmodifiable list of standard capabilities, never {@code null}
     */
    List<Capability> getAllStandardCapabilities();

    /**
     * Registers a custom capability for runtime use.
     *
     * @param capability the custom capability to register, never {@code null}
     * @throws IllegalArgumentException if the capability's namespace is {@code "core"} or
     *                                  if a capability with the same ID already exists
     */
    void registerCustomCapability(CustomCapability capability);

    /**
     * Finds a custom capability by its identifier.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @return an {@link Optional} containing the custom capability if found, or empty
     */
    Optional<CustomCapability> getCustomCapability(String capabilityId);

    /**
     * Retrieves the attribute schema for a specific attribute within a capability.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @param attributeKey the attribute key, never {@code null}
     * @return the attribute schema, never {@code null}
     * @throws IllegalArgumentException if the capability or attribute key does not exist
     */
    AttributeSchema getAttributeSchema(String capabilityId, String attributeKey);

    /**
     * Retrieves the command definition for a specific command within a capability.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @param commandType the command type, never {@code null}
     * @return the command definition, never {@code null}
     * @throws IllegalArgumentException if the capability or command type does not exist
     */
    CommandDefinition getCommandDefinition(String capabilityId, String commandType);
}

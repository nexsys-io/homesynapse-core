/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.platform.identity.EntityId;

/**
 * Production {@link CommandValidator}: resolves the target entity via the
 * {@link EntityRegistry} and checks that one of its capabilities declares the command, with
 * the required feature bits present (Doc 07 §3.11.1).
 *
 * <p>Tier-1 floor — command/capability existence and feature gating only; deep
 * parameter-schema validation is out of scope (the device model's
 * {@code com.homesynapse.device.CommandValidator} owns that). Stateless and thread-safe.</p>
 */
public final class StandardCommandValidator implements CommandValidator {

    private final EntityRegistry entityRegistry;

    /**
     * Constructs a validator over the given entity registry.
     *
     * @param entityRegistry the registry that resolves an entity to its capabilities, never
     *                       {@code null}
     */
    public StandardCommandValidator(EntityRegistry entityRegistry) {
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
    }

    @Override
    public ValidationResult validate(EntityId targetRef, String commandName,
                                     Map<String, Object> parameters) {
        Objects.requireNonNull(targetRef, "targetRef must not be null");
        Objects.requireNonNull(commandName, "commandName must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");

        Optional<Entity> entity = entityRegistry.findEntity(targetRef);
        if (entity.isEmpty()) {
            return ValidationResult.invalid("Entity '" + targetRef + "' does not exist");
        }
        for (CapabilityInstance capability : entity.get().capabilities()) {
            CommandDefinition definition = capability.commands().get(commandName);
            if (definition != null && featuresSatisfied(capability, definition)) {
                return ValidationResult.accepted();
            }
        }
        return ValidationResult.invalid(
                "Entity '" + targetRef + "' does not support command '" + commandName + "'");
    }

    /** A command is available when the instance's feature map carries all required bits. */
    private static boolean featuresSatisfied(CapabilityInstance capability,
                                             CommandDefinition definition) {
        int required = definition.requiredFeatures();
        return (capability.featureMap() & required) == required;
    }
}

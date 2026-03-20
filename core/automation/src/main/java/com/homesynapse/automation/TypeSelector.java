/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * Selector that resolves to all entities of the given entity type.
 *
 * <p>Entity types are capability-based classifications (e.g., {@code "light"},
 * {@code "thermostat"}). This selector resolves to zero or more entities
 * that match the specified type.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.2.</p>
 *
 * @param entityType the entity type to match, never {@code null}
 * @see Selector
 * @see SelectorResolver
 */
public record TypeSelector(String entityType) implements Selector {

    /**
     * Validates that the entity type is non-null.
     *
     * @throws NullPointerException if {@code entityType} is {@code null}
     */
    public TypeSelector {
        Objects.requireNonNull(entityType, "entityType must not be null");
    }
}

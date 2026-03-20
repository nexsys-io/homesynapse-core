/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

import com.homesynapse.platform.identity.EntityId;

/**
 * Selector that directly references a single entity by its ULID.
 *
 * <p>Resolves to exactly one entity. If the entity ID is not found in the registry,
 * resolves to an empty set.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.2.</p>
 *
 * @param entityId the entity to select, never {@code null}
 * @see Selector
 * @see SelectorResolver
 */
public record DirectRefSelector(EntityId entityId) implements Selector {

    /**
     * Validates that the entity ID is non-null.
     *
     * @throws NullPointerException if {@code entityId} is {@code null}
     */
    public DirectRefSelector {
        Objects.requireNonNull(entityId, "entityId must not be null");
    }
}

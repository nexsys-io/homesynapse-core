/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Set;

import com.homesynapse.platform.identity.EntityId;

/**
 * Resolves selector expressions to sets of entity IDs using current registry state.
 *
 * <p>Resolution uses Identity Model primitives. {@link DirectRefSelector} and
 * {@link SlugSelector} resolve to exactly one entity (empty set if not found).
 * {@link AreaSelector}, {@link LabelSelector}, and {@link TypeSelector} may resolve
 * to zero or more entities. {@link CompoundSelector} uses intersection semantics
 * (§7.3 deduplication ensures each entity appears at most once).</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.1.</p>
 *
 * @see Selector
 */
public interface SelectorResolver {

    /**
     * Resolves a selector expression to a set of entity IDs.
     *
     * @param selector the selector to resolve, never {@code null}
     * @return the resolved entity IDs, never {@code null} (may be empty)
     */
    Set<EntityId> resolve(Selector selector);
}

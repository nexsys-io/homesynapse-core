/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Objects;

/**
 * Selector that combines multiple selectors using intersection semantics ({@code all_of}).
 *
 * <p>Each sub-selector is resolved independently, then the resulting entity sets are
 * intersected. An entity must match ALL sub-selectors to be included in the final
 * resolved set. Identity Model §7.3 deduplication ensures each entity appears at
 * most once in the result.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.2.</p>
 *
 * @param selectors the sub-selectors to intersect, unmodifiable, never {@code null}
 * @see Selector
 * @see SelectorResolver
 */
public record CompoundSelector(List<Selector> selectors) implements Selector {

    /**
     * Validates non-null and makes the list unmodifiable.
     *
     * @throws NullPointerException if {@code selectors} is {@code null}
     */
    public CompoundSelector {
        Objects.requireNonNull(selectors, "selectors must not be null");
        selectors = List.copyOf(selectors);
    }
}

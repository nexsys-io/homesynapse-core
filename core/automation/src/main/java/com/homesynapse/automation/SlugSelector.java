/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * Selector that references a single entity by its human-readable slug.
 *
 * <p>Resolves to exactly one entity via slug lookup (e.g., {@code "kitchen.overhead_light"}).
 * If the slug is not found in the registry, resolves to an empty set.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.2.</p>
 *
 * @param slug the human-readable slug, never {@code null}
 * @see Selector
 * @see SelectorResolver
 */
public record SlugSelector(String slug) implements Selector {

    /**
     * Validates that the slug is non-null.
     *
     * @throws NullPointerException if {@code slug} is {@code null}
     */
    public SlugSelector {
        Objects.requireNonNull(slug, "slug must not be null");
    }
}

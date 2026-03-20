/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * Selector that resolves to all entities in the named area.
 *
 * <p>Areas are organizational groupings defined in the home configuration.
 * This selector resolves to zero or more entities that belong to the
 * specified area.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.2.</p>
 *
 * @param areaSlug the slug identifying the area, never {@code null}
 * @see Selector
 * @see SelectorResolver
 */
public record AreaSelector(String areaSlug) implements Selector {

    /**
     * Validates that the area slug is non-null.
     *
     * @throws NullPointerException if {@code areaSlug} is {@code null}
     */
    public AreaSelector {
        Objects.requireNonNull(areaSlug, "areaSlug must not be null");
    }
}

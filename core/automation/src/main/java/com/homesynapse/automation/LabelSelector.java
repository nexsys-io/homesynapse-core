/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * Selector that resolves to all entities with the specified label.
 *
 * <p>Labels are user-assigned tags on entities. This selector resolves to
 * zero or more entities that carry the given label.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.2.</p>
 *
 * @param label the label to match, never {@code null}
 * @see Selector
 * @see SelectorResolver
 */
public record LabelSelector(String label) implements Selector {

    /**
     * Validates that the label is non-null.
     *
     * @throws NullPointerException if {@code label} is {@code null}
     */
    public LabelSelector {
        Objects.requireNonNull(label, "label must not be null");
    }
}

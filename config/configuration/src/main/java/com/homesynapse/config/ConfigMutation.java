/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Objects;

/**
 * A single key-level mutation for the UI/API write path (Doc 06 §3.5, §4.7).
 *
 * <p>Mutations are submitted in a list to
 * {@link ConfigurationService#write(java.util.List, java.time.Instant)}.
 * Each mutation targets a specific key within a configuration section. A
 * {@code null} value for {@code newValue} means the key should be removed,
 * reverting to its JSON Schema default.</p>
 *
 * @param sectionPath the dotted path identifying the configuration section
 *                    (e.g., {@code "persistence.retention"}); never {@code null}
 * @param key         the configuration key within the section
 *                    (e.g., {@code "maxDays"}); never {@code null}
 * @param newValue    the new value to set, or {@code null} to remove the key
 *                    and revert to the schema default
 *
 * @see ConfigurationService#write(java.util.List, java.time.Instant)
 */
public record ConfigMutation(
        String sectionPath,
        String key,
        Object newValue
) {

    /**
     * Validates that required fields are non-null.
     */
    public ConfigMutation {
        Objects.requireNonNull(sectionPath, "sectionPath must not be null");
        Objects.requireNonNull(key, "key must not be null");
    }
}

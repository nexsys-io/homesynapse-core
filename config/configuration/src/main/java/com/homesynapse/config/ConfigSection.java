/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Map;
import java.util.Objects;

/**
 * A subtree of the configuration identified by a dotted path
 * (Doc 06 §4.2).
 *
 * <p>Each {@code ConfigSection} represents a named slice of the validated
 * configuration (e.g., {@code "persistence.retention"}) with its current
 * values and the JSON Schema defaults for those keys. The {@link #values()}
 * map contains the effective runtime values after merging user configuration
 * with defaults. The {@link #defaults()} map contains the original schema
 * defaults for comparison and revert operations.</p>
 *
 * <p>Both maps are unmodifiable. Attempts to modify them throw
 * {@link UnsupportedOperationException}.</p>
 *
 * @param path     the dotted path identifying this section
 *                 (e.g., {@code "persistence.retention"}); never {@code null}
 * @param values   the effective runtime values for this section, unmodifiable;
 *                 never {@code null}
 * @param defaults the JSON Schema default values for this section, unmodifiable;
 *                 never {@code null}
 *
 * @see ConfigModel
 * @see ConfigurationAccess
 */
public record ConfigSection(
        String path,
        Map<String, Object> values,
        Map<String, Object> defaults
) {

    /**
     * Validates that all fields are non-null and makes both maps unmodifiable.
     */
    public ConfigSection {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(values, "values must not be null");
        Objects.requireNonNull(defaults, "defaults must not be null");
        values = Map.copyOf(values);
        defaults = Map.copyOf(defaults);
    }
}

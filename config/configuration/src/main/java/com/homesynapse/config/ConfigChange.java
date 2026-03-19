/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Objects;

/**
 * A single key-level change detected during the configuration reload diff
 * (Doc 06 §3.3, §4.3).
 *
 * <p>When the reload pipeline compares the candidate {@link ConfigModel}
 * against the active model, it produces a list of {@code ConfigChange}
 * records collected into a {@link ConfigChangeSet}. Each change identifies
 * the affected key, the old and new values, and the
 * {@link ReloadClassification} that determines how the change should be
 * applied at runtime.</p>
 *
 * @param sectionPath the dotted path of the configuration section containing
 *                    the changed key (e.g., {@code "persistence.retention"});
 *                    never {@code null}
 * @param key         the configuration key that changed; never {@code null}
 * @param oldValue    the previous value, or {@code null} for newly added keys
 * @param newValue    the new value, or {@code null} for removed keys
 * @param reload      the reload classification derived from the key's
 *                    {@code x-reload} JSON Schema annotation; never {@code null}
 *
 * @see ConfigChangeSet
 * @see ReloadClassification
 */
public record ConfigChange(
        String sectionPath,
        String key,
        Object oldValue,
        Object newValue,
        ReloadClassification reload
) {

    /**
     * Validates that required fields are non-null.
     */
    public ConfigChange {
        Objects.requireNonNull(sectionPath, "sectionPath must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(reload, "reload must not be null");
    }
}

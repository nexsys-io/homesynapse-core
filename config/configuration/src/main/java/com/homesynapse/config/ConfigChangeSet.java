/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The complete diff between two {@link ConfigModel} instances, produced by
 * the reload pipeline (Doc 06 §3.3, §4.3).
 *
 * <p>A {@code ConfigChangeSet} captures every key-level change detected when
 * comparing the candidate configuration against the active configuration. It
 * is included in the {@link ReloadResult} returned by
 * {@link ConfigurationService#reload()} and consumed by the REST API for
 * change notification and by subscribers for targeted reconfiguration.</p>
 *
 * <p>The {@link #changes()} list is unmodifiable. The convenience filter
 * methods ({@link #hot()}, {@link #integrationRestart()},
 * {@link #processRestart()}) slice it by the per-property {@code x-reload}
 * classification each {@link ConfigChange} carries (Doc 06 §4.3) — note
 * this is the schema-derived per-key classification, independent of any
 * AMD-66 listener's section-level override.</p>
 *
 * @param timestamp the instant the reload diff was computed; never {@code null}
 * @param changes   the list of individual key-level changes, unmodifiable;
 *                  never {@code null}
 *
 * @see ConfigChange
 * @see ReloadResult
 * @see ConfigurationService#reload()
 */
public record ConfigChangeSet(
        Instant timestamp,
        List<ConfigChange> changes
) {

    /**
     * Validates that all fields are non-null and makes the changes list
     * unmodifiable.
     */
    public ConfigChangeSet {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(changes, "changes must not be null");
        changes = List.copyOf(changes);
    }

    /**
     * Returns the changes whose properties are {@code x-reload: hot} —
     * applied by the swap itself, no restart required.
     *
     * @return the {@link ReloadClassification#HOT} changes, unmodifiable;
     *         never {@code null}
     */
    public List<ConfigChange> hot() {
        return filterBy(ReloadClassification.HOT);
    }

    /**
     * Returns the changes whose properties require an integration restart
     * to take effect.
     *
     * @return the {@link ReloadClassification#INTEGRATION_RESTART} changes,
     *         unmodifiable; never {@code null}
     */
    public List<ConfigChange> integrationRestart() {
        return filterBy(ReloadClassification.INTEGRATION_RESTART);
    }

    /**
     * Returns the changes whose properties require a full process restart
     * to take effect — the default for unannotated properties.
     *
     * @return the {@link ReloadClassification#PROCESS_RESTART} changes,
     *         unmodifiable; never {@code null}
     */
    public List<ConfigChange> processRestart() {
        return filterBy(ReloadClassification.PROCESS_RESTART);
    }

    private List<ConfigChange> filterBy(ReloadClassification classification) {
        return changes.stream()
                .filter(change -> change.reload() == classification)
                .toList();
    }
}

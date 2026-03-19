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
 * <p>The {@link #changes()} list is unmodifiable. Convenience filter methods
 * ({@code hot()}, {@code integrationRestart()}, {@code processRestart()})
 * are Phase 3 implementation.</p>
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
}

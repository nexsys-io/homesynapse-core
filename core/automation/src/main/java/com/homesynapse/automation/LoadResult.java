/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of loading {@code automations.yaml}: the valid-subset of definitions plus
 * the rejected ones (Doc 07 §6.1 valid-subset semantics; SD-9). A fail-closed load never
 * silently drops a definition into an inert state — every rejection is surfaced as a
 * {@link LoadFailure}.
 *
 * @param loaded   the successfully loaded definitions, unmodifiable; never {@code null}
 * @param failures the rejected definitions with their reasons, unmodifiable; never {@code null}
 */
public record LoadResult(List<AutomationDefinition> loaded, List<LoadFailure> failures) {

    /** Validates non-null fields and makes the lists unmodifiable. */
    public LoadResult {
        Objects.requireNonNull(loaded, "loaded must not be null");
        Objects.requireNonNull(failures, "failures must not be null");
        loaded = List.copyOf(loaded);
        failures = List.copyOf(failures);
    }

    /** Whether any definition was rejected (engine health → DEGRADED when true). */
    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}

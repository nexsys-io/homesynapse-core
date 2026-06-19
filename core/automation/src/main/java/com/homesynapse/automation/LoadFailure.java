/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * A single automation definition that was rejected at load (Doc 07 §6.1; SD-9
 * fail-closed-at-load). The caller surfaces each failure as a {@code config_error}
 * event (CRITICAL) and sets the engine health to DEGRADED; valid sibling definitions
 * load normally.
 *
 * @param automationName the offending automation's name (or {@code "<file>"} for a
 *                       whole-file failure such as a too-new schema major), never {@code null}
 * @param detail         a user-legible failure description (the unresolved reference,
 *                       the bad field, etc.), never {@code null}
 */
public record LoadFailure(String automationName, String detail) {

    /** Validates non-null fields. */
    public LoadFailure {
        Objects.requireNonNull(automationName, "automationName must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }
}

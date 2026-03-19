/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

/**
 * Validation issue severity for the three-tier configuration error model
 * (Doc 06 §3.6, §4.6).
 *
 * <p>The Configuration System classifies every validation finding into one of
 * three severities, each with distinct pipeline behaviour:</p>
 *
 * <ul>
 *   <li>{@link #FATAL} — the configuration is unusable; startup is aborted or
 *       the reload candidate is rejected entirely.</li>
 *   <li>{@link #ERROR} — the specific key is invalid; the system reverts it to
 *       the JSON Schema default and continues. On reload, ERROR issues cause
 *       the entire candidate to be rejected (stricter than startup).</li>
 *   <li>{@link #WARNING} — informational; the value is accepted but may cause
 *       suboptimal behaviour (e.g., a retention period below the recommended
 *       minimum).</li>
 * </ul>
 *
 * @see ConfigIssue
 * @see ConfigurationService
 */
public enum Severity {

    /**
     * The configuration is structurally invalid or missing a required section.
     * Prevents startup; rejects reload candidates.
     */
    FATAL,

    /**
     * A specific key fails schema validation. On startup, the key reverts to
     * its schema default. On reload, the entire candidate is rejected.
     */
    ERROR,

    /**
     * Informational finding. The value is accepted but may cause suboptimal
     * behaviour.
     */
    WARNING
}

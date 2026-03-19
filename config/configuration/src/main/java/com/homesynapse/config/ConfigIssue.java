/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Objects;

/**
 * A single validation finding from the configuration loading or reload
 * pipeline (Doc 06 §3.6, §4.6).
 *
 * <p>Each {@code ConfigIssue} represents one problem detected during JSON
 * Schema validation of the parsed YAML configuration. Issues are collected
 * into a list and returned alongside the validated {@link ConfigModel}
 * (on startup) or inside a {@link ReloadResult} (on reload).</p>
 *
 * <h2>Severity-Specific Behaviour</h2>
 *
 * <ul>
 *   <li>{@link Severity#FATAL} — the configuration is structurally invalid.
 *       {@code appliedDefault} is always {@code null} because no default can
 *       compensate for a structural error.</li>
 *   <li>{@link Severity#ERROR} — the key failed validation. On startup the
 *       system reverts the key to the schema default ({@code appliedDefault}
 *       is non-null). On reload the entire candidate is rejected.</li>
 *   <li>{@link Severity#WARNING} — informational. {@code appliedDefault} is
 *       always {@code null} because the value is accepted as-is.</li>
 * </ul>
 *
 * @param severity       the severity of this validation finding; never {@code null}
 * @param path           the JSON Schema path to the offending key (e.g.,
 *                       {@code "persistence.retention.maxDays"}); never {@code null}
 * @param message        a human-readable description of the validation failure;
 *                       never {@code null}
 * @param invalidValue   the value that failed validation, or {@code null} for
 *                       missing-key issues
 * @param appliedDefault the schema default applied in place of the invalid value,
 *                       or {@code null} for {@link Severity#FATAL} and
 *                       {@link Severity#WARNING} issues
 * @param yamlLine       the line number in the YAML file where the issue was
 *                       detected, or {@code null} when the parser cannot determine
 *                       the line number
 *
 * @see Severity
 * @see ConfigValidator
 * @see ReloadResult
 */
public record ConfigIssue(
        Severity severity,
        String path,
        String message,
        Object invalidValue,
        Object appliedDefault,
        Integer yamlLine
) {

    /**
     * Validates that all required fields are non-null.
     */
    public ConfigIssue {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.List;
import java.util.Objects;

/**
 * The result of a configuration reload, containing the new model, the diff
 * from the previous model, and any validation warnings encountered
 * (Doc 06 §8.3).
 *
 * <p>A {@code ReloadResult} is returned by
 * {@link ConfigurationService#reload()} on success. If the reload fails
 * (FATAL or ERROR issues in the candidate), a
 * {@link ConfigurationReloadException} is thrown instead and no
 * {@code ReloadResult} is produced.</p>
 *
 * @param newModel  the newly loaded and validated configuration model;
 *                  never {@code null}
 * @param changeSet the complete diff between the previous and new models;
 *                  never {@code null}
 * @param issues    validation warnings encountered during reload (only
 *                  {@link Severity#WARNING} issues, since FATAL and ERROR
 *                  cause rejection), unmodifiable; never {@code null}
 *
 * @see ConfigurationService#reload()
 * @see ConfigModel
 * @see ConfigChangeSet
 * @see ConfigIssue
 */
public record ReloadResult(
        ConfigModel newModel,
        ConfigChangeSet changeSet,
        List<ConfigIssue> issues
) {

    /**
     * Validates that all fields are non-null and makes the issues list
     * unmodifiable.
     */
    public ReloadResult {
        Objects.requireNonNull(newModel, "newModel must not be null");
        Objects.requireNonNull(changeSet, "changeSet must not be null");
        Objects.requireNonNull(issues, "issues must not be null");
        issues = List.copyOf(issues);
    }
}

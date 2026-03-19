/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

/**
 * Classifies the runtime impact of a configuration change detected during
 * reload (Doc 06 §3.3, §4.3).
 *
 * <p>Each configuration property has an {@code x-reload} annotation in its
 * JSON Schema definition. The reload pipeline reads this annotation to
 * determine how the change should be applied. Properties without an
 * {@code x-reload} annotation default to {@link #PROCESS_RESTART}.</p>
 *
 * @see ConfigChange
 * @see ConfigChangeSet
 */
public enum ReloadClassification {

    /**
     * The change takes effect on the next access to {@link ConfigModel}.
     * No restart of any component is required.
     */
    HOT,

    /**
     * The change requires restarting the affected integration adapter.
     * The Integration Supervisor handles the restart automatically.
     */
    INTEGRATION_RESTART,

    /**
     * The change requires a full process restart. The operator must
     * restart HomeSynapse manually. This is the default for unannotated
     * properties.
     */
    PROCESS_RESTART
}

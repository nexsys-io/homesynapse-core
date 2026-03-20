/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.observability;

import java.time.Instant;
import java.util.Objects;

/**
 * Active dynamic log level override, representing a runtime adjustment to a logger's effective level.
 *
 * <p>Dynamic log level changes are applied via {@link LogLevelController#setLevel(String, String)}
 * and persist only until the next restart — they are not written to configuration files
 * (Doc 11 §3.6, §8.2). This record represents a single active override; multiple
 * overrides can exist simultaneously for different loggers.</p>
 *
 * @see LogLevelController
 */
public record LogLevelOverride(
    /**
     * The fully-qualified logger name.
     *
     * <p>Non-null. Examples: "com.homesynapse.integration.zigbee",
     * "com.homesynapse.automation.engine". Must match a configured allowed
     * prefix (default: "com.homesynapse.*", configurable via
     * {@code observability.logging.dynamic_level_allowed_prefixes}).</p>
     */
    String loggerName,

    /**
     * The logger's configured default level before the override.
     *
     * <p>Non-null. Uses SLF4J level names: "TRACE", "DEBUG", "INFO", "WARN",
     * "ERROR". This is the level that will be restored if the override is
     * reset via {@link LogLevelController#resetLevel(String)}.</p>
     */
    String originalLevel,

    /**
     * The currently active override level.
     *
     * <p>Non-null. Uses SLF4J level names: "TRACE", "DEBUG", "INFO", "WARN",
     * "ERROR". This is the level currently in effect for logging by the named
     * logger.</p>
     */
    String overrideLevel,

    /**
     * Timestamp when the override was applied.
     *
     * <p>Non-null. Used for audit trails and determining how long an override
     * has been active. Produced by {@link LogLevelController#setLevel(String, String)}.</p>
     */
    Instant appliedAt
) {
    /**
     * Compact constructor validating all fields.
     *
     * @throws NullPointerException if any field is null
     */
    public LogLevelOverride {
        Objects.requireNonNull(loggerName, "loggerName cannot be null");
        Objects.requireNonNull(originalLevel, "originalLevel cannot be null");
        Objects.requireNonNull(overrideLevel, "overrideLevel cannot be null");
        Objects.requireNonNull(appliedAt, "appliedAt cannot be null");
    }
}

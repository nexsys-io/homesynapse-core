/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.observability;

import java.util.List;

/**
 * Runtime log level adjustment interface for per-package SLF4J/Logback log levels.
 *
 * <p>Changes take effect immediately via Logback's {@code LoggerContext} API —
 * no restart, no config file reload. Dynamic adjustments persist only until the
 * next restart (they are not written to configuration files) (Doc 11 §3.6,
 * §8.1–§8.2, decision D-11). Exposed through the REST API (Doc 09) as an
 * authenticated endpoint. Inspired by openHAB's Karaf console {@code log:set}
 * capability, but GUI-accessible.</p>
 *
 * @see LogLevelOverride
 */
public interface LogLevelController {
    /**
     * Return the current effective log level for a logger.
     *
     * <p>Thread-safe.</p>
     *
     * @param loggerName the fully-qualified logger name (e.g.,
     *        "com.homesynapse.integration.zigbee"). Non-null.
     * @return the effective level as an SLF4J level string: "TRACE", "DEBUG",
     *         "INFO", "WARN", "ERROR". Non-null. Returns the effective level
     *         (override if active, or configured default).
     *
     * @throws NullPointerException if loggerName is null
     *
     * @see LogLevelOverride
     */
    String getLevel(String loggerName);

    /**
     * Set the effective log level for a logger.
     *
     * <p>Changes take effect immediately. Produces a structured log entry and a
     * {@code LogLevelChangeEvent} JFR event (Doc 11 §11.2). The change persists
     * only until the next restart — it is not written to the configuration file.</p>
     *
     * <p>Thread-safe.</p>
     *
     * @param loggerName the fully-qualified logger name (e.g.,
     *        "com.homesynapse.integration.zigbee"). Must match a configured
     *        allowed prefix (default: "com.homesynapse.*", configurable via
     *        {@code observability.logging.dynamic_level_allowed_prefixes}).
     *        Non-null.
     * @param level the SLF4J level name: "TRACE", "DEBUG", "INFO", "WARN",
     *        "ERROR". Non-null.
     *
     * @throws NullPointerException if loggerName or level is null
     * @throws IllegalArgumentException if loggerName does not match any allowed
     *         prefix or if level is not a valid SLF4J level name
     *
     * @see LogLevelOverride
     */
    void setLevel(String loggerName, String level);

    /**
     * Restore a logger to its configured default level.
     *
     * <p>Removes any active override. If no override is active for this logger,
     * this is a no-op.</p>
     *
     * <p>Thread-safe.</p>
     *
     * @param loggerName the fully-qualified logger name. Non-null.
     *
     * @throws NullPointerException if loggerName is null
     *
     * @see LogLevelOverride
     */
    void resetLevel(String loggerName);

    /**
     * Return all currently active dynamic log level overrides.
     *
     * <p>Returns an unmodifiable list of {@link LogLevelOverride} records
     * representing all active overrides. The list may be empty if no overrides
     * are currently in effect.</p>
     *
     * <p>Thread-safe. Returns a snapshot of the current override state.</p>
     *
     * @return a non-null, unmodifiable list of active overrides, ordered by
     *         application timestamp ascending (oldest first).
     *
     * @see LogLevelOverride
     */
    List<LogLevelOverride> listOverrides();
}

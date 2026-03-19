/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Map;
import java.util.Optional;

/**
 * Read-only, integration-scoped configuration access interface
 * (Doc 05 §3.8, Doc 06 §8.4).
 *
 * <p>{@code ConfigurationAccess} provides an integration adapter with access
 * to its own configuration section only. The adapter cannot see global
 * configuration or other integrations' sections. The returned map contains
 * only keys under {@code integrations.{type}:} — scoped at construction
 * time by the Configuration System.</p>
 *
 * <h2>Value Resolution</h2>
 *
 * <p>All {@code !secret} and {@code !env} tag values are already resolved
 * before the adapter receives them. The map values are typed according to
 * the integration's registered JSON Schema (strings, numbers, booleans,
 * lists, maps).</p>
 *
 * <h2>Always Provided</h2>
 *
 * <p>Unlike optional services ({@code TelemetryWriter},
 * {@code SchedulerService}, {@code ManagedHttpClient}) which are gated by
 * {@code RequiredService} declarations, {@code ConfigurationAccess} is
 * always provided to every integration adapter. An adapter always has
 * configuration access for its own section — even if the section is empty
 * (INV-CE-02 — zero-config is valid).</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Instances are immutable after construction and safe for concurrent
 * access from multiple threads.</p>
 *
 * @see ConfigurationService
 * @see ConfigModel
 */
public interface ConfigurationAccess {

    /**
     * Returns the validated configuration subtree for this integration type
     * as an unmodifiable map.
     *
     * <p>The map contains only keys under {@code integrations.{type}:}.
     * All {@code !secret} and {@code !env} values are resolved. The map
     * may be empty if the integration has no configuration (zero-config
     * default).</p>
     *
     * @return the integration's configuration map, unmodifiable;
     *         never {@code null}
     */
    Map<String, Object> getConfig();

    /**
     * Convenience accessor that returns the value for the given key as a
     * {@link String}.
     *
     * @param key the configuration key; never {@code null}
     * @return the string value, or empty if the key does not exist or is
     *         not a string
     */
    Optional<String> getString(String key);

    /**
     * Convenience accessor that returns the value for the given key as an
     * {@link Integer}.
     *
     * @param key the configuration key; never {@code null}
     * @return the integer value, or empty if the key does not exist or is
     *         not an integer
     */
    Optional<Integer> getInt(String key);

    /**
     * Convenience accessor that returns the value for the given key as a
     * {@link Boolean}.
     *
     * @param key the configuration key; never {@code null}
     * @return the boolean value, or empty if the key does not exist or is
     *         not a boolean
     */
    Optional<Boolean> getBoolean(String key);
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.bus.EventBusConfig;
import com.homesynapse.persistence.PersistenceConfig;

import java.util.Objects;

/**
 * Consolidated configuration for the HomeSynapse Core runtime.
 *
 * <p>Bundles per-subsystem configuration records into a single carrier passed
 * to the composition root ({@code HomeSynapseCore}, landing in M3.6d-b). Each
 * subsystem owns its own config record in its own module; this record is the
 * lifecycle-module aggregation surface.</p>
 *
 * <h2>Programmatic construction (M3.6d-a)</h2>
 *
 * <p>YAML loading is deferred to a later work unit (Doc 06 §14). M3.6d uses
 * programmatic construction via the constructor or the
 * {@link #HOME_DEFAULT} constant.</p>
 *
 * <h2>Extensibility</h2>
 *
 * <p>Future subsystem configs (e.g., {@code StateProjectionConfig},
 * {@code AutomationConfig}, {@code IntegrationRuntimeConfig}) will be added
 * as new record components. Adding a component is a source-incompatible
 * change to direct constructor callers but stays binary-compatible for
 * callers that go through {@link #HOME_DEFAULT}. Per AMD-38's profile model,
 * adding new components alongside existing {@code *_DEFAULT} constants is
 * the established pattern.</p>
 *
 * @param persistence persistence-layer configuration (deployment profile,
 *                    retention policy); never {@code null}
 * @param eventBus    event-bus configuration (replay queue capacity,
 *                    publisher-blocked depth threshold); never {@code null}
 * @see PersistenceConfig
 * @see EventBusConfig
 */
public record HomeSynapseConfig(
        PersistenceConfig persistence,
        EventBusConfig eventBus) {

    /**
     * Default configuration for the HOME deployment profile — pairs
     * {@link PersistenceConfig#HOME_DEFAULT} with
     * {@link EventBusConfig#HOME_DEFAULT}. Reproduces the configuration
     * already in use across all test harnesses (PersistenceTestHarness,
     * IntegrationTestHarness) exactly.
     */
    public static final HomeSynapseConfig HOME_DEFAULT = new HomeSynapseConfig(
            PersistenceConfig.HOME_DEFAULT,
            EventBusConfig.HOME_DEFAULT);

    /**
     * Compact constructor validating non-null components.
     *
     * @throws NullPointerException if {@code persistence} or {@code eventBus}
     *                              is {@code null}
     */
    public HomeSynapseConfig {
        Objects.requireNonNull(persistence, "persistence config must not be null");
        Objects.requireNonNull(eventBus, "eventBus config must not be null");
    }
}

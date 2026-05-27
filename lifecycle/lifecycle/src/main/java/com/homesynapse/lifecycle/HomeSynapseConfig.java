/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.bus.EventBusConfig;
import com.homesynapse.persistence.DeploymentProfile;
import com.homesynapse.persistence.PersistenceConfig;
import com.homesynapse.persistence.RetentionPolicy;

import java.util.Objects;

/**
 * Consolidated configuration for the HomeSynapse Core runtime.
 *
 * <p>Bundles per-subsystem configuration records into a single carrier passed
 * to the composition root ({@code HomeSynapseCore}). Each subsystem owns its
 * own config record in its own module; this record is the lifecycle-module
 * aggregation surface.</p>
 *
 * <h2>Programmatic construction</h2>
 *
 * <p>YAML loading is deferred (Doc 06 §14). M3.6d/M3.7 use programmatic
 * construction via the constructor, the {@link #HOME_DEFAULT} constant, or the
 * {@link #testing()} factory.</p>
 *
 * <h2>Extensibility</h2>
 *
 * <p>Future subsystem configs (e.g., {@code StateProjectionConfig},
 * {@code AutomationConfig}, {@code IntegrationRuntimeConfig}) will be added
 * as new record components. Adding a component is a source-incompatible
 * change to direct constructor callers but stays binary-compatible for
 * callers that go through {@link #HOME_DEFAULT}.</p>
 *
 * @param persistence persistence-layer configuration (deployment profile,
 *                    retention policy); never {@code null}
 * @param eventBus    event-bus configuration (replay queue capacity,
 *                    publisher-blocked depth threshold); never {@code null}
 * @param httpPort    embedded Javalin HTTP server port; {@code 0} requests an
 *                    ephemeral port (used by M3.7 E2E tests for parallel
 *                    execution); must be {@code >= 0}
 * @see PersistenceConfig
 * @see EventBusConfig
 */
public record HomeSynapseConfig(
        PersistenceConfig persistence,
        EventBusConfig eventBus,
        int httpPort) {

    /**
     * Default configuration for the HOME deployment profile — pairs
     * {@link PersistenceConfig#HOME_DEFAULT} with
     * {@link EventBusConfig#HOME_DEFAULT} and the production HTTP port
     * {@code 7070} (PLAN-M3 §10).
     */
    public static final HomeSynapseConfig HOME_DEFAULT = new HomeSynapseConfig(
            PersistenceConfig.HOME_DEFAULT,
            EventBusConfig.HOME_DEFAULT,
            7070);

    /**
     * Compact constructor validating non-null components and non-negative
     * {@code httpPort}.
     *
     * @throws NullPointerException     if {@code persistence} or {@code eventBus}
     *                                  is {@code null}
     * @throws IllegalArgumentException if {@code httpPort} is negative
     */
    public HomeSynapseConfig {
        Objects.requireNonNull(persistence, "persistence config must not be null");
        Objects.requireNonNull(eventBus, "eventBus config must not be null");
        if (httpPort < 0) {
            throw new IllegalArgumentException(
                    "httpPort must be >= 0 (0 = ephemeral), got " + httpPort);
        }
    }

    /**
     * M3.7 E2E test factory — pairs the {@link DeploymentProfile#TESTING}
     * profile with {@link RetentionPolicy#SOURCE_DEFAULT},
     * {@link EventBusConfig#HOME_DEFAULT}, and an ephemeral HTTP port
     * ({@code 0}). Javalin's {@code start(0)} binds any free port; the bound
     * port is then readable via {@code HomeSynapseCore.boundHttpPort()}.
     *
     * <p>Use exclusively from tests — production deployments call
     * {@link #HOME_DEFAULT}.</p>
     *
     * @return a configuration suited for parallel, isolated E2E test execution
     */
    public static HomeSynapseConfig testing() {
        return new HomeSynapseConfig(
                new PersistenceConfig(
                        DeploymentProfile.TESTING,
                        RetentionPolicy.SOURCE_DEFAULT),
                EventBusConfig.HOME_DEFAULT,
                0);
    }
}

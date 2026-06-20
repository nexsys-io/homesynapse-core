/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.bus.EventBusConfig;
import com.homesynapse.persistence.DeploymentProfile;
import com.homesynapse.persistence.PersistenceConfig;
import com.homesynapse.persistence.RetentionPolicy;
import com.homesynapse.state.CheckpointPolicy;
import com.homesynapse.state.FixedCheckpointPolicy;

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
 * callers that go through {@link #HOME_DEFAULT}. As of AB-1 the record has
 * five components: {@code persistence}, {@code eventBus}, {@code httpPort},
 * {@code checkpointPolicy}, and {@code bindHost}.</p>
 *
 * <h2>Bind posture (AB-1, A1)</h2>
 *
 * <p>{@code bindHost} is the interface the embedded HTTP/WS surface binds to.
 * It defaults to loopback ({@code 127.0.0.1}) so the surface answers only the
 * local host out of the box; LAN exposure is an explicit, authenticated opt-in
 * that sets {@code bindHost} to a non-loopback address (e.g. a specific LAN IP
 * or {@code 0.0.0.0}). The default is <strong>never</strong> all-interfaces —
 * Javalin/Jetty bind {@code 0.0.0.0} when the host is unset, which is exactly
 * the hole this component closes (no interface is treated as "internal";
 * authentication is mandatory on every interface regardless — INV-SE-02).</p>
 *
 * @param persistence      persistence-layer configuration (deployment profile,
 *                         retention policy); never {@code null}
 * @param eventBus         event-bus configuration (replay queue capacity,
 *                         publisher-blocked depth threshold); never {@code null}
 * @param httpPort         embedded Javalin HTTP server port; {@code 0} requests
 *                         an ephemeral port (used by M3.7 E2E tests for
 *                         parallel execution); must be {@code >= 0}
 * @param checkpointPolicy checkpoint policy for the state projection;
 *                         never {@code null}. Use
 *                         {@link FixedCheckpointPolicy#HOME_DEFAULT} for
 *                         production and {@link FixedCheckpointPolicy#TESTING}
 *                         for tests.
 * @param bindHost         the network interface the HTTP/WS surface binds to;
 *                         never {@code null} or blank. {@link #LOOPBACK_HOST}
 *                         (the default) restricts it to the local host; a
 *                         non-loopback value is the explicit LAN opt-in.
 * @see PersistenceConfig
 * @see EventBusConfig
 */
public record HomeSynapseConfig(
        PersistenceConfig persistence,
        EventBusConfig eventBus,
        int httpPort,
        CheckpointPolicy checkpointPolicy,
        String bindHost) {

    /** Loopback bind host — the secure default (the surface answers only localhost). */
    public static final String LOOPBACK_HOST = "127.0.0.1";

    /** All-interfaces bind host — the explicit LAN opt-in (authenticated). */
    public static final String ALL_INTERFACES_HOST = "0.0.0.0";

    /**
     * Default configuration for the HOME deployment profile — pairs
     * {@link PersistenceConfig#HOME_DEFAULT} with
     * {@link EventBusConfig#HOME_DEFAULT} and the production HTTP port
     * {@code 7070} (PLAN-M3 §10).
     */
    public static final HomeSynapseConfig HOME_DEFAULT = new HomeSynapseConfig(
            PersistenceConfig.HOME_DEFAULT,
            EventBusConfig.HOME_DEFAULT,
            7070,
            FixedCheckpointPolicy.HOME_DEFAULT,
            LOOPBACK_HOST);

    /**
     * Compact constructor validating non-null components, non-negative
     * {@code httpPort}, and a non-blank {@code bindHost}.
     *
     * @throws NullPointerException     if {@code persistence}, {@code eventBus},
     *                                  {@code checkpointPolicy}, or
     *                                  {@code bindHost} is {@code null}
     * @throws IllegalArgumentException if {@code httpPort} is negative or
     *                                  {@code bindHost} is blank
     */
    public HomeSynapseConfig {
        Objects.requireNonNull(persistence, "persistence config must not be null");
        Objects.requireNonNull(eventBus, "eventBus config must not be null");
        Objects.requireNonNull(checkpointPolicy, "checkpointPolicy must not be null");
        Objects.requireNonNull(bindHost, "bindHost must not be null");
        if (httpPort < 0) {
            throw new IllegalArgumentException(
                    "httpPort must be >= 0 (0 = ephemeral), got " + httpPort);
        }
        if (bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost must not be blank");
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
                0,
                FixedCheckpointPolicy.TESTING,
                LOOPBACK_HOST);
    }
}

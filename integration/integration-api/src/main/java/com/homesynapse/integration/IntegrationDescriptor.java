/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The integration's contract with the supervisor, declared by the
 * {@link IntegrationFactory} at discovery time (Doc 05 §4.1).
 *
 * <p>The descriptor carries the static declaration of what an integration is and
 * what it needs — not its runtime identity. The key distinction:
 * {@code integrationType} identifies the <em>software</em> (e.g., {@code "zigbee"}),
 * while {@link com.homesynapse.platform.identity.IntegrationId} identifies the
 * installed <em>instance</em> (a ULID assigned by the supervisor at first load and
 * stable across restarts). Multiple installations of the same integration type each
 * get a different {@code IntegrationId}.</p>
 *
 * <p>The supervisor reads the descriptor to determine thread allocation
 * ({@link #ioType()}), service provisioning ({@link #requiredServices()}),
 * health monitoring configuration ({@link #healthParameters()}), startup
 * ordering ({@link #dependsOn()}/{@link #softDependencies()}), transient-retry
 * backoff ({@link #backoffParameters()}), isolation ({@link #isolationLevel()}),
 * and the planned-restart grace period ({@link #plannedRestartTimeout()}).</p>
 *
 * <h2>Versioning (AMD-54)</h2>
 *
 * <p>{@link #descriptorSchemaVersion()} versions the <em>descriptor contract</em>
 * itself (the supervisor's parsing contract). It is distinct from the
 * config-document schema pair
 * ({@link #configSchemaMajor()}/{@link #configSchemaMinor()}), which versions the
 * adapter's own <em>configuration</em> layout and drives
 * {@link IntegrationAdapter#migrate(int, int)}. No code path derives one from the
 * other (AMD-54-INV-01). This pair is unrelated to the configuration module's
 * system-wide {@code ConfigMigrator}, which migrates the whole system config
 * document, not a single adapter's section.</p>
 *
 * <p><strong>Example — Zigbee adapter descriptor (8-arg convenience constructor):</strong></p>
 * <pre>{@code
 * new IntegrationDescriptor(
 *     "zigbee",
 *     "Zigbee Adapter",
 *     IoType.SERIAL,
 *     Set.of(RequiredService.SCHEDULER, RequiredService.TELEMETRY_WRITER),
 *     Set.of(DataPath.DOMAIN, DataPath.TELEMETRY),
 *     HealthParameters.defaults(),
 *     Set.of(),
 *     1
 * )
 * }</pre>
 *
 * <p>The 8-arg convenience constructor preserves the pre-AMD-54 signature and
 * defaults the six newer components ({@code configSchemaMajor = 1},
 * {@code configSchemaMinor = 0}, {@code softDependencies = Set.of()},
 * {@code backoffParameters = BackoffParameters.defaults()},
 * {@code isolationLevel = IN_JVM}, {@code plannedRestartTimeout = null}).</p>
 *
 * <p>All collection fields are defensively copied to unmodifiable sets at
 * construction time. This record is immutable and thread-safe.</p>
 *
 * @param integrationType        the software identity of this integration (e.g.,
 *                               {@code "zigbee"}, {@code "hue"}, {@code "mqtt"});
 *                               never {@code null} or blank
 * @param displayName            a human-readable name for dashboard and log display;
 *                               never {@code null} or blank
 * @param ioType                 the I/O model determining thread allocation;
 *                               never {@code null}
 * @param requiredServices       optional services the adapter requires in its
 *                               {@link IntegrationContext}; never {@code null},
 *                               may be empty; returned as an unmodifiable set
 * @param dataPaths              data routing paths this adapter uses;
 *                               never {@code null}, must contain at least
 *                               {@link DataPath#DOMAIN}; returned as an
 *                               unmodifiable set
 * @param healthParameters       health monitoring thresholds and restart limits;
 *                               never {@code null}
 * @param dependsOn              integration types this adapter hard-depends on for
 *                               startup ordering (per AMD-14); never {@code null},
 *                               may be empty; returned as an unmodifiable set
 * @param descriptorSchemaVersion the descriptor contract version for forward
 *                               compatibility (NOT the config schema); must be
 *                               {@code >= 1}
 * @param configSchemaMajor      the config-document schema major version; bumped on
 *                               breaking config-layout changes; must be {@code >= 1}
 * @param configSchemaMinor      the config-document schema minor version; bumped on
 *                               additive changes, reset to 0 on a major bump; must
 *                               be {@code >= 0}
 * @param softDependencies       integration types this adapter prefers to start
 *                               after <em>if present</em>, but never blocks on
 *                               (AMD-61); never {@code null}, may be empty; must be
 *                               disjoint from {@code dependsOn}; returned as an
 *                               unmodifiable set
 * @param backoffParameters      the transient-failure retry backoff schedule
 *                               (AMD-62); never {@code null}
 * @param isolationLevel         the isolation level at which the adapter runs
 *                               (AMD-63); never {@code null}
 * @param plannedRestartTimeout  the per-adapter planned-restart grace period
 *                               (AMD-64); {@code null} means use the global Doc 05
 *                               §3.14 default (60s); when present, must be positive
 *
 * @see IntegrationFactory
 * @see IntegrationContext
 * @see com.homesynapse.platform.identity.IntegrationId
 */
public record IntegrationDescriptor(
        String integrationType,
        String displayName,
        IoType ioType,
        Set<RequiredService> requiredServices,
        Set<DataPath> dataPaths,
        HealthParameters healthParameters,
        Set<String> dependsOn,
        int descriptorSchemaVersion,
        int configSchemaMajor,
        int configSchemaMinor,
        Set<String> softDependencies,
        BackoffParameters backoffParameters,
        IsolationLevel isolationLevel,
        Duration plannedRestartTimeout
) {

    /**
     * Validates all fields and defensively copies collection fields to unmodifiable sets.
     *
     * @throws NullPointerException     if any non-null field is {@code null}
     * @throws IllegalArgumentException if a string field is blank, a version is out
     *         of range, {@code plannedRestartTimeout} is non-positive, or
     *         {@code dependsOn} and {@code softDependencies} overlap
     */
    public IntegrationDescriptor {
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        if (integrationType.isBlank()) {
            throw new IllegalArgumentException("integrationType must not be blank");
        }
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        Objects.requireNonNull(ioType, "ioType must not be null");
        Objects.requireNonNull(requiredServices, "requiredServices must not be null");
        Objects.requireNonNull(dataPaths, "dataPaths must not be null");
        Objects.requireNonNull(healthParameters, "healthParameters must not be null");
        Objects.requireNonNull(dependsOn, "dependsOn must not be null");
        Objects.requireNonNull(softDependencies, "softDependencies must not be null");
        Objects.requireNonNull(backoffParameters, "backoffParameters must not be null");
        Objects.requireNonNull(isolationLevel, "isolationLevel must not be null");

        if (descriptorSchemaVersion < 1) {
            throw new IllegalArgumentException(
                    "descriptorSchemaVersion must be >= 1: " + descriptorSchemaVersion);
        }
        if (configSchemaMajor < 1) {
            throw new IllegalArgumentException(
                    "configSchemaMajor must be >= 1: " + configSchemaMajor);
        }
        if (configSchemaMinor < 0) {
            throw new IllegalArgumentException(
                    "configSchemaMinor must be >= 0: " + configSchemaMinor);
        }
        if (plannedRestartTimeout != null
                && (plannedRestartTimeout.isZero() || plannedRestartTimeout.isNegative())) {
            throw new IllegalArgumentException(
                    "plannedRestartTimeout must be positive: " + plannedRestartTimeout);
        }

        // AMD-61-INV-02: a type may not be both a hard and a soft dependency.
        for (String type : softDependencies) {
            if (dependsOn.contains(type)) {
                throw new IllegalArgumentException(
                        "integration type '" + type
                                + "' appears in both dependsOn and softDependencies");
            }
        }

        // Defensive copy to unmodifiable sets
        requiredServices = Collections.unmodifiableSet(new LinkedHashSet<>(requiredServices));
        dataPaths = Collections.unmodifiableSet(new LinkedHashSet<>(dataPaths));
        dependsOn = Collections.unmodifiableSet(new LinkedHashSet<>(dependsOn));
        softDependencies = Collections.unmodifiableSet(new LinkedHashSet<>(softDependencies));
    }

    /**
     * Convenience constructor preserving the pre-AMD-54 8-argument signature.
     *
     * <p>Defaults the six components introduced by AMD-54/61/62/63/64:
     * {@code configSchemaMajor = 1}, {@code configSchemaMinor = 0},
     * {@code softDependencies = Set.of()},
     * {@code backoffParameters = BackoffParameters.defaults()},
     * {@code isolationLevel = IsolationLevel.IN_JVM}, and
     * {@code plannedRestartTimeout = null} (use the global default).</p>
     *
     * @param integrationType         the software identity; never {@code null} or blank
     * @param displayName             the human-readable name; never {@code null} or blank
     * @param ioType                  the I/O model; never {@code null}
     * @param requiredServices        optional services required; never {@code null}
     * @param dataPaths               data routing paths; never {@code null}
     * @param healthParameters        health thresholds; never {@code null}
     * @param dependsOn               hard startup dependencies; never {@code null}
     * @param descriptorSchemaVersion the descriptor contract version; must be {@code >= 1}
     */
    public IntegrationDescriptor(
            String integrationType,
            String displayName,
            IoType ioType,
            Set<RequiredService> requiredServices,
            Set<DataPath> dataPaths,
            HealthParameters healthParameters,
            Set<String> dependsOn,
            int descriptorSchemaVersion) {
        this(integrationType, displayName, ioType, requiredServices, dataPaths,
                healthParameters, dependsOn, descriptorSchemaVersion,
                1, 0, Set.of(), BackoffParameters.defaults(), IsolationLevel.IN_JVM, null);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

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
 * health monitoring configuration ({@link #healthParameters()}), and startup
 * ordering ({@link #dependsOn()}).</p>
 *
 * <p><strong>Example — Zigbee adapter descriptor:</strong></p>
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
 * <p>All collection fields are defensively copied to unmodifiable sets at
 * construction time. This record is immutable and thread-safe.</p>
 *
 * @param integrationType   the software identity of this integration (e.g.,
 *                          {@code "zigbee"}, {@code "hue"}, {@code "mqtt"});
 *                          never {@code null} or blank
 * @param displayName       a human-readable name for dashboard and log display;
 *                          never {@code null} or blank
 * @param ioType            the I/O model determining thread allocation;
 *                          never {@code null}
 * @param requiredServices  optional services the adapter requires in its
 *                          {@link IntegrationContext}; never {@code null},
 *                          may be empty; returned as an unmodifiable set
 * @param dataPaths         data routing paths this adapter uses;
 *                          never {@code null}, must contain at least
 *                          {@link DataPath#DOMAIN}; returned as an
 *                          unmodifiable set
 * @param healthParameters  health monitoring thresholds and restart limits;
 *                          never {@code null}
 * @param dependsOn         integration types this adapter depends on for startup
 *                          ordering (per AMD-14); never {@code null}, may be
 *                          empty; returned as an unmodifiable set
 * @param schemaVersion     the descriptor schema version for forward compatibility;
 *                          must be positive
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
        int schemaVersion
) {

    /**
     * Validates all fields and defensively copies collection fields to unmodifiable sets.
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
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive: " + schemaVersion);
        }

        // Defensive copy to unmodifiable sets
        requiredServices = Collections.unmodifiableSet(new LinkedHashSet<>(requiredServices));
        dataPaths = Collections.unmodifiableSet(new LinkedHashSet<>(dataPaths));
        dependsOn = Collections.unmodifiableSet(new LinkedHashSet<>(dependsOn));
    }
}

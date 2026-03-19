/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.persistence.TelemetryWriter;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.state.StateQueryService;

import java.util.Objects;

/**
 * The composed API surface injected into each integration adapter at
 * construction (Doc 05 §3.8, §8.1).
 *
 * <p>{@code IntegrationContext} is the complete API surface available to the
 * adapter — no other entry point into the HomeSynapse core exists (P4 — no
 * god object). Each field provides a narrow, typed interface scoped to the
 * minimum access the adapter needs (LTD-17).</p>
 *
 * <h2>Scoping Model</h2>
 *
 * <p>The {@link #entityRegistry()} and {@link #stateQueryService()} are
 * <em>integration-scoped</em>: they filter by {@code integration_id} at the
 * query level and return only entities owned by this integration. The adapter
 * never receives a reference to the full registry or state store. Build-time
 * enforcement (Gradle module dependencies) and runtime enforcement (JPMS
 * module boundaries) prevent access to other integrations' data.</p>
 *
 * <p>The {@link #eventPublisher()} is write-only: the adapter may only
 * produce event types permitted by Doc 01 §3.1 ({@code state_reported},
 * {@code command_result}, {@code availability_changed},
 * {@code device_discovered}, {@code presence_signal}).</p>
 *
 * <h2>Configuration Access</h2>
 *
 * <p>The {@link #configAccess()} field is always provided (not gated by
 * {@link RequiredService}). Every adapter receives read-only access to its
 * own configuration section, even if that section is empty (INV-CE-02 —
 * zero-config is valid).</p>
 *
 * <h2>Optional Services</h2>
 *
 * <p>The {@link #schedulerService()}, {@link #httpClient()}, and
 * {@link #telemetryWriter()} fields are {@code null} unless the adapter
 * declared the corresponding {@link RequiredService} in its
 * {@link IntegrationDescriptor#requiredServices()}. The supervisor only
 * provisions services the adapter declares — undeclared services are not
 * available.</p>
 *
 * @param integrationId     the instance identity assigned by the supervisor
 *                          (a ULID stable across restarts); never {@code null}
 * @param integrationType   the software identity from the descriptor (e.g.,
 *                          {@code "zigbee"}); never {@code null}
 * @param eventPublisher    write-only event production interface — the adapter
 *                          may only produce permitted event types;
 *                          never {@code null}
 * @param entityRegistry    read-only, integration-scoped entity registry —
 *                          returns only entities owned by this integration;
 *                          never {@code null}
 * @param stateQueryService read-only, integration-scoped state query service —
 *                          returns only state for entities owned by this
 *                          integration; never {@code null}
 * @param healthReporter    adapter-to-supervisor health signal channel;
 *                          never {@code null}
 * @param configAccess      read-only, integration-scoped configuration access —
 *                          returns only configuration for this integration type;
 *                          always provided (not gated by {@link RequiredService});
 *                          never {@code null}
 * @param schedulerService  integration-scoped task scheduler, or {@code null}
 *                          if {@link RequiredService#SCHEDULER} was not
 *                          declared
 * @param telemetryWriter   telemetry sample writer from the Persistence Layer
 *                          (Doc 04 §8.3) for high-frequency numeric data;
 *                          {@code null} if
 *                          {@link RequiredService#TELEMETRY_WRITER} was not
 *                          declared or {@link com.homesynapse.integration.IntegrationDescriptor#dataPaths()
 *                          DataPath.TELEMETRY} was not included
 * @param httpClient        integration-scoped managed HTTP client with
 *                          concurrency limits and rate limiting, or
 *                          {@code null} if {@link RequiredService#HTTP_CLIENT}
 *                          was not declared
 *
 * @see IntegrationFactory#create(IntegrationContext)
 * @see IntegrationAdapter
 * @see IntegrationDescriptor#requiredServices()
 * @see ConfigurationAccess
 */
public record IntegrationContext(
        IntegrationId integrationId,
        String integrationType,
        EventPublisher eventPublisher,
        EntityRegistry entityRegistry,
        StateQueryService stateQueryService,
        HealthReporter healthReporter,
        ConfigurationAccess configAccess,
        SchedulerService schedulerService,
        TelemetryWriter telemetryWriter,
        ManagedHttpClient httpClient
) {

    /**
     * Validates that all required fields are non-null. Optional fields
     * ({@code schedulerService}, {@code telemetryWriter}, {@code httpClient})
     * may be {@code null} based on the adapter's declared requirements.
     * {@code configAccess} is always required (not gated by RequiredService).
     */
    public IntegrationContext {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        Objects.requireNonNull(entityRegistry, "entityRegistry must not be null");
        Objects.requireNonNull(stateQueryService, "stateQueryService must not be null");
        Objects.requireNonNull(healthReporter, "healthReporter must not be null");
        Objects.requireNonNull(configAccess, "configAccess must not be null");
        // schedulerService may be null if RequiredService.SCHEDULER not declared
        // telemetryWriter may be null if RequiredService.TELEMETRY_WRITER not declared
        // httpClient may be null if RequiredService.HTTP_CLIENT not declared
    }
}

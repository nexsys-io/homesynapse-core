/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Declares the data routing paths an integration adapter uses to communicate
 * information to the HomeSynapse core (Doc 05 §4.1).
 *
 * <p>An adapter declares its data paths in {@link IntegrationDescriptor#dataPaths()}
 * at discovery time. The supervisor uses this declaration to provision the
 * appropriate write interfaces in the adapter's {@link IntegrationContext}.</p>
 *
 * <p>Most adapters declare only {@link #DOMAIN}. Adapters that also produce
 * high-frequency numeric samples (e.g., energy meters reporting watt-seconds
 * every second) additionally declare {@link #TELEMETRY} to gain access to the
 * telemetry ring store via {@code TelemetryWriter}.</p>
 *
 * @see IntegrationDescriptor
 * @see IntegrationContext
 */
public enum DataPath {

    /**
     * The adapter publishes domain events ({@code state_reported},
     * {@code command_result}, {@code availability_changed},
     * {@code device_discovered}, {@code presence_signal}) via
     * {@link com.homesynapse.event.EventPublisher}.
     *
     * <p>All adapters declare this path. Domain events flow through the
     * event-sourced pipeline: persisted to SQLite WAL, then dispatched
     * to subscribers (State Store projection, Automation Engine, WebSocket
     * broadcast).</p>
     */
    DOMAIN,

    /**
     * The adapter also produces high-frequency numeric samples routed to
     * {@code TelemetryWriter} in the Persistence Layer (Doc 04 §3.6).
     *
     * <p>Telemetry samples bypass the domain event store to avoid overwhelming
     * it with high-volume, low-semantic-weight data. Samples are stored in a
     * separate ring buffer with configurable retention. Only adapters that
     * declare this path receive a {@code TelemetryWriter} reference in their
     * {@link IntegrationContext}.</p>
     */
    TELEMETRY
}

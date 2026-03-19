/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Declares optional services an integration adapter requires in its
 * {@link IntegrationContext} (Doc 05 §4.1).
 *
 * <p>The supervisor provisions only the services the adapter declares in
 * {@link IntegrationDescriptor#requiredServices()}. Undeclared services are
 * not available — the corresponding field in {@link IntegrationContext} will
 * be {@code null}. This ensures resource allocation is explicit and auditable:
 * an adapter that doesn't need an HTTP client doesn't receive a connection pool.</p>
 *
 * @see IntegrationDescriptor
 * @see IntegrationContext
 */
public enum RequiredService {

    /**
     * A managed HTTP client with concurrency limits and rate limiting.
     *
     * <p>When declared, the supervisor provisions a {@link ManagedHttpClient}
     * instance with an isolated connection pool and lifecycle tied to the
     * adapter. Used by cloud-connected adapters (e.g., Philips Hue, cloud
     * MQTT brokers) that need outbound HTTP access (Doc 05 §3.9).</p>
     */
    HTTP_CLIENT,

    /**
     * A timer and periodic task scheduling service.
     *
     * <p>When declared, the supervisor provisions a {@link SchedulerService}
     * instance that executes callbacks on the integration's virtual thread
     * group. Used by adapters that need polling loops, retry timers, or
     * periodic keepalive checks (Doc 05 §3.8).</p>
     */
    SCHEDULER,

    /**
     * A telemetry sample writer for high-frequency numeric data.
     *
     * <p>When declared, the supervisor provisions a {@code TelemetryWriter}
     * reference for routing samples to the telemetry ring store
     * (Persistence Layer, Doc 04 §3.6). Used by adapters that produce
     * high-frequency measurements (e.g., energy meters, power monitors)
     * alongside domain events.</p>
     */
    TELEMETRY_WRITER
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

/**
 * Machine-readable cause accompanying an integration's {@code HealthState} on
 * {@link IntegrationHealthRecord#detail()} (AMD-57).
 *
 * <p>{@code HealthState} reports <em>which</em> state an integration is in;
 * {@code HealthDetail} reports <em>why</em>. Every value maps 1:1 to a supervisor
 * transition trigger on the
 * {@link com.homesynapse.integration.HealthParameters} surface — a
 * metrics-driven FSM can emit this vocabulary truthfully, unlike an
 * operator-cause taxonomy that would require adapters to self-report
 * (AMD-57 §2.1, Nick arbitration A1).</p>
 *
 * <p>The enum is append-only once ratified; values map 1:1 to supervisor
 * transition triggers (AMD-57-INV-02). The supervisor populates it on every
 * record it produces; {@link #NONE} is the explicit no-cause value. Adapters
 * never set it — they have no write path to the record (AMD-57-INV-01).</p>
 *
 * @see IntegrationHealthRecord#detail()
 * @see com.homesynapse.integration.HealthState
 */
public enum HealthDetail {

    /** No detail applies — the integration is HEALTHY with no active cause. */
    NONE,

    /** The adapter's heartbeat timeout was exceeded. */
    HEARTBEAT_TIMEOUT,

    /** A protocol-level keepalive is overdue. */
    KEEPALIVE_TIMEOUT,

    /** The error-rate sliding window is over threshold. */
    ERROR_RATE_EXCEEDED,

    /** The timeout-rate sliding window is over threshold. */
    TIMEOUT_RATE_EXCEEDED,

    /** The slow-call-rate sliding window is over threshold. */
    SLOW_CALL_RATE_EXCEEDED,

    /** A recovery probe cycle failed. */
    PROBE_FAILED,

    /** The maximum restarts within the restart window were exhausted. */
    RESTART_LIMIT_EXCEEDED,

    /** The maximum suspension cycles were exhausted. */
    SUSPENSION_LIMIT_EXCEEDED,

    /** A resource quota was breached (pairs with {@code IntegrationResourceExceeded}). */
    RESOURCE_QUOTA_EXCEEDED,

    /**
     * An authentication failure is active
     * ({@link ExceptionClassification#AUTH_FAILED}, AMD-56).
     */
    AUTH_FAILURE,

    /**
     * A {@code PermanentIntegrationException} drove the adapter to FAILED with no
     * retry.
     */
    PERMANENT_FAILURE
}

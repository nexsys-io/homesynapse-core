/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

/**
 * Deterministic classification of exceptions escaping the adapter–supervisor
 * boundary (Doc 05 §3.7).
 *
 * <p>When an integration adapter throws an exception that propagates to the
 * supervisor, the supervisor classifies it before deciding whether to restart
 * the adapter or transition it to {@link com.homesynapse.integration.HealthState#FAILED}.
 * This enum defines the three possible classification outcomes.</p>
 *
 * <h2>Exception classification table</h2>
 *
 * <p><strong>{@link #TRANSIENT}:</strong> {@code IOException},
 * {@code SocketException}, {@code SocketTimeoutException}, unknown
 * {@code RuntimeException}. These indicate potentially recoverable conditions.
 * Unknown {@code RuntimeException} defaults to TRANSIENT to prevent the
 * Home Assistant anti-pattern of permanent failure on unexpected exception
 * types.</p>
 *
 * <p><strong>{@link #PERMANENT}:</strong>
 * {@link com.homesynapse.integration.PermanentIntegrationException},
 * {@code ConfigurationException}, {@code AuthenticationException},
 * {@code UnsupportedOperationException}, {@code OutOfMemoryError}, other
 * {@code Error} subclasses. These indicate conditions that will not resolve
 * without manual intervention.</p>
 *
 * <p><strong>{@link #SHUTDOWN_SIGNAL}:</strong> {@code InterruptedException},
 * {@code ClosedByInterruptException}. These are caused by the supervisor's
 * own shutdown sequence, not by genuine adapter failures.</p>
 *
 * <h2>Shutdown-aware reclassification</h2>
 *
 * <p>When the supervisor sets the per-adapter {@code shuttingDown} flag during
 * graceful shutdown, normally-TRANSIENT exceptions ({@code SocketException},
 * {@code IOException}) are reclassified to {@code SHUTDOWN_SIGNAL}. This
 * prevents the supervisor from attempting to restart an adapter that was
 * interrupted by its own shutdown sequence.</p>
 *
 * <p>This enum is thread-safe (enum values are inherently immutable).</p>
 *
 * @see com.homesynapse.integration.HealthState
 * @see com.homesynapse.integration.PermanentIntegrationException
 */
public enum ExceptionClassification {

    /**
     * The exception indicates a potentially recoverable condition. The
     * supervisor restarts the adapter with exponential backoff, subject to
     * the restart intensity limit configured in
     * {@link com.homesynapse.integration.HealthParameters#maxRestarts()} and
     * {@link com.homesynapse.integration.HealthParameters#restartWindow()}.
     *
     * <p>Classified exceptions: {@code IOException}, {@code SocketException},
     * {@code SocketTimeoutException}, unknown {@code RuntimeException}.</p>
     */
    TRANSIENT,

    /**
     * The exception indicates an unrecoverable condition. The supervisor
     * transitions the adapter directly to
     * {@link com.homesynapse.integration.HealthState#FAILED} with no restart
     * attempt. Manual intervention (REST API or configuration reload) is
     * required to restart the adapter.
     *
     * <p>Classified exceptions:
     * {@link com.homesynapse.integration.PermanentIntegrationException},
     * {@code ConfigurationException}, {@code AuthenticationException},
     * {@code UnsupportedOperationException}, {@code OutOfMemoryError},
     * other {@code Error} subclasses.</p>
     */
    PERMANENT,

    /**
     * The exception was caused by the supervisor's own shutdown sequence,
     * not by a genuine adapter failure. The supervisor does not restart the
     * adapter and does not record the exception as a failure.
     *
     * <p>Classified exceptions: {@code InterruptedException},
     * {@code ClosedByInterruptException}. Additionally, {@code SocketException}
     * and {@code IOException} are reclassified from {@link #TRANSIENT} to
     * {@code SHUTDOWN_SIGNAL} when the per-adapter {@code shuttingDown} flag
     * is set.</p>
     */
    SHUTDOWN_SIGNAL
}

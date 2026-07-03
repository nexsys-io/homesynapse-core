/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.integration.PermanentIntegrationException;

import java.util.Objects;

/**
 * The Doc 05 §3.7 exception-classification table — the M9.1 slice.
 *
 * <p>The deliberate default is the load-bearing row: an <em>unknown</em>
 * {@link RuntimeException} classifies {@link ExceptionClassification#TRANSIENT},
 * never PERMANENT. This guards against the Home Assistant anti-pattern where an
 * unexpected exception type permanently kills an integration — the safe default
 * is restart-with-backoff, and only known-unrecoverable failures
 * ({@link PermanentIntegrationException}, {@link OutOfMemoryError},
 * {@link LinkageError}) transition an integration to FAILED without retry.</p>
 *
 * <p>Shutdown-aware reclassification (Doc 05 §3.7 — IOException/SocketException
 * become SHUTDOWN_SIGNAL while the per-adapter {@code shuttingDown} flag is set)
 * lives in {@link StandardIntegrationSupervisor}'s run loop, not here: the
 * classifier is a pure function of the throwable. {@code AUTH_FAILED} routing
 * (AMD-56) is deferred supervisor breadth — nothing maps to it in M9.1.</p>
 */
final class ExceptionClassifier {

    private ExceptionClassifier() {
        // Static classification table — no instantiation.
    }

    /**
     * Classifies a throwable that escaped the adapter boundary.
     *
     * @param failure the escaped throwable; never {@code null}
     * @return the supervisor response classification; never {@code null}
     */
    static ExceptionClassification classify(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof PermanentIntegrationException) {
            return ExceptionClassification.PERMANENT;
        }
        if (failure instanceof InterruptedException) {
            return ExceptionClassification.SHUTDOWN_SIGNAL;
        }
        if (failure instanceof OutOfMemoryError || failure instanceof LinkageError) {
            return ExceptionClassification.PERMANENT;
        }
        // Everything else — IOException, unknown RuntimeException, wrapped causes —
        // is TRANSIENT (Doc 05 §3.7, the HA anti-pattern guard).
        return ExceptionClassification.TRANSIENT;
    }
}

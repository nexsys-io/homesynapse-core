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
        if (failure instanceof UnsupportedOperationException) {
            // Doc 05 §3.7 taxonomy currency (M9.4, F-5): an UnsupportedOperation is
            // definitionally non-transient — retry cannot make an unimplemented
            // operation succeed. Bare instanceof ONLY: a wrapped UOE stays TRANSIENT
            // by design; deliberate permanence travels as PIE (which gets the walk).
            return ExceptionClassification.PERMANENT;
        }
        if (hasPermanentIntentInCauseChain(failure)) {
            // The PIE-only cause-walk (M9.4 ruling): finding a PIE anywhere in a cause
            // chain means someone deliberately threw permanent intent — suppressing
            // that intent is the bug, not the walk. Defense-in-depth: the checked
            // seams should deliver PIE bare; the walk is the net, not the wire.
            return ExceptionClassification.PERMANENT;
        }
        // Everything else — IOException, unknown RuntimeException, wrapped causes —
        // is TRANSIENT (Doc 05 §3.7, the HA anti-pattern guard).
        return ExceptionClassification.TRANSIENT;
    }

    /** Bounded {@code getCause()} walk (identity-hop cap 8 — cycle-safe), PIE only. */
    private static boolean hasPermanentIntentInCauseChain(Throwable failure) {
        Throwable cause = failure.getCause();
        for (int hop = 0; cause != null && hop < 8; hop++) {
            if (cause instanceof PermanentIntegrationException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.integration.PermanentIntegrationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * T17 — the Doc 05 §3.7 classification table for the M9.1 slice. The
 * load-bearing row is the LAST one: an unknown {@code RuntimeException}
 * classifies TRANSIENT, never PERMANENT (the Home Assistant anti-pattern
 * guard — an unexpected exception type must not permanently kill an
 * integration; the safe default is restart-with-backoff).
 */
@DisplayName("ExceptionClassifier -- Doc 05 §3.7 classification table (M9.1 slice)")
final class ExceptionClassifierTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ExceptionClassifierTest() {
    }

    @Test
    void permanentIntegrationException_classifiesPermanent() {
        assertThat(ExceptionClassifier.classify(new PermanentIntegrationException("bad firmware")))
                .isEqualTo(ExceptionClassification.PERMANENT);
    }

    @Test
    void interruptedException_classifiesShutdownSignal() {
        assertThat(ExceptionClassifier.classify(new InterruptedException()))
                .isEqualTo(ExceptionClassification.SHUTDOWN_SIGNAL);
    }

    @Test
    void outOfMemoryError_classifiesPermanent() {
        assertThat(ExceptionClassifier.classify(new OutOfMemoryError("heap")))
                .isEqualTo(ExceptionClassification.PERMANENT);
    }

    @Test
    void linkageError_classifiesPermanent() {
        assertThat(ExceptionClassifier.classify(new NoClassDefFoundError("gone")))
                .isEqualTo(ExceptionClassification.PERMANENT);
    }

    @Test
    void illegalStateException_classifiesTransient() {
        assertThat(ExceptionClassifier.classify(new IllegalStateException("socket reset")))
                .isEqualTo(ExceptionClassification.TRANSIENT);
    }

    @Test
    void ioExceptionWrappedInRuntimeException_classifiesTransient() {
        assertThat(ExceptionClassifier.classify(
                new RuntimeException(new IOException("read timed out"))))
                .isEqualTo(ExceptionClassification.TRANSIENT);
    }

    @Test
    void checkedIoException_classifiesTransient() {
        assertThat(ExceptionClassifier.classify(new IOException("port gone")))
                .isEqualTo(ExceptionClassification.TRANSIENT);
    }

    @Test
    void unknownRuntimeException_classifiesTransient_theHomeAssistantAntiPatternGuard() {
        assertThat(ExceptionClassifier.classify(new RuntimeException("never seen before")))
                .isEqualTo(ExceptionClassification.TRANSIENT);
    }

    @Test
    void nullThrowable_isRejected() {
        assertThatThrownBy(() -> ExceptionClassifier.classify(null))
                .isInstanceOf(NullPointerException.class);
    }
}

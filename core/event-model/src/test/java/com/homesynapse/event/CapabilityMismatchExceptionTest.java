/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CapabilityMismatchException")
class CapabilityMismatchExceptionTest {

    @Test
    @DisplayName("extends HomeSynapseException")
    void extendsBase() {
        assertThat(HomeSynapseException.class).isAssignableFrom(CapabilityMismatchException.class);
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {
        @Test
        @DisplayName("message-only constructor")
        void messageOnly() {
            var ex = new CapabilityMismatchException("detail");
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("message and cause constructor")
        void messageAndCause() {
            var cause = new RuntimeException("root");
            var ex = new CapabilityMismatchException("detail", cause);
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Test
    @DisplayName("errorCode returns 'capability.mismatch'")
    void errorCode() {
        assertThat(new CapabilityMismatchException("msg").errorCode()).isEqualTo("capability.mismatch");
    }

    @Test
    @DisplayName("suggestedHttpStatus returns 409")
    void suggestedHttpStatus() {
        assertThat(new CapabilityMismatchException("msg").suggestedHttpStatus()).isEqualTo(409);
    }
}

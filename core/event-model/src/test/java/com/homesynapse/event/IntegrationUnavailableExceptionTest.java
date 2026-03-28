/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("IntegrationUnavailableException")
class IntegrationUnavailableExceptionTest {

    @Test
    @DisplayName("extends HomeSynapseException")
    void extendsBase() {
        assertThat(HomeSynapseException.class).isAssignableFrom(IntegrationUnavailableException.class);
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {
        @Test
        @DisplayName("message-only constructor")
        void messageOnly() {
            var ex = new IntegrationUnavailableException("detail");
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("message and cause constructor")
        void messageAndCause() {
            var cause = new RuntimeException("root");
            var ex = new IntegrationUnavailableException("detail", cause);
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Test
    @DisplayName("errorCode returns 'integration.unavailable'")
    void errorCode() {
        assertThat(new IntegrationUnavailableException("msg").errorCode()).isEqualTo("integration.unavailable");
    }

    @Test
    @DisplayName("suggestedHttpStatus returns 503")
    void suggestedHttpStatus() {
        assertThat(new IntegrationUnavailableException("msg").suggestedHttpStatus()).isEqualTo(503);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConfigurationValidationException")
class ConfigurationValidationExceptionTest {

    @Test
    @DisplayName("extends HomeSynapseException")
    void extendsBase() {
        assertThat(HomeSynapseException.class).isAssignableFrom(ConfigurationValidationException.class);
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {
        @Test
        @DisplayName("message-only constructor")
        void messageOnly() {
            var ex = new ConfigurationValidationException("detail");
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("message and cause constructor")
        void messageAndCause() {
            var cause = new RuntimeException("root");
            var ex = new ConfigurationValidationException("detail", cause);
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Test
    @DisplayName("errorCode returns 'config.validation_failed'")
    void errorCode() {
        assertThat(new ConfigurationValidationException("msg").errorCode())
                .isEqualTo("config.validation_failed");
    }

    @Test
    @DisplayName("suggestedHttpStatus returns 422")
    void suggestedHttpStatus() {
        assertThat(new ConfigurationValidationException("msg").suggestedHttpStatus()).isEqualTo(422);
    }
}

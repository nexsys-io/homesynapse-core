/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DeviceNotFoundException")
class DeviceNotFoundExceptionTest {

    @Test
    @DisplayName("extends HomeSynapseException")
    void extendsBase() {
        assertThat(HomeSynapseException.class).isAssignableFrom(DeviceNotFoundException.class);
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {
        @Test
        @DisplayName("message-only constructor")
        void messageOnly() {
            var ex = new DeviceNotFoundException("detail");
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("message and cause constructor")
        void messageAndCause() {
            var cause = new RuntimeException("root");
            var ex = new DeviceNotFoundException("detail", cause);
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Test
    @DisplayName("errorCode returns 'device.not_found'")
    void errorCode() {
        assertThat(new DeviceNotFoundException("msg").errorCode()).isEqualTo("device.not_found");
    }

    @Test
    @DisplayName("suggestedHttpStatus returns 404")
    void suggestedHttpStatus() {
        assertThat(new DeviceNotFoundException("msg").suggestedHttpStatus()).isEqualTo(404);
    }
}

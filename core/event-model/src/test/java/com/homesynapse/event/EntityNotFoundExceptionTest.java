/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EntityNotFoundException")
class EntityNotFoundExceptionTest {

    @Test
    @DisplayName("extends HomeSynapseException")
    void extendsBase() {
        assertThat(HomeSynapseException.class).isAssignableFrom(EntityNotFoundException.class);
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {
        @Test
        @DisplayName("message-only constructor")
        void messageOnly() {
            var ex = new EntityNotFoundException("detail");
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("message and cause constructor")
        void messageAndCause() {
            var cause = new RuntimeException("root");
            var ex = new EntityNotFoundException("detail", cause);
            assertThat(ex.getMessage()).isEqualTo("detail");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Test
    @DisplayName("errorCode returns 'entity.not_found'")
    void errorCode() {
        assertThat(new EntityNotFoundException("msg").errorCode()).isEqualTo("entity.not_found");
    }

    @Test
    @DisplayName("suggestedHttpStatus returns 404")
    void suggestedHttpStatus() {
        assertThat(new EntityNotFoundException("msg").suggestedHttpStatus()).isEqualTo(404);
    }
}

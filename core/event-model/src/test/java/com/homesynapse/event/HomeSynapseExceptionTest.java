/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HomeSynapseException")
class HomeSynapseExceptionTest {

    @Test
    @DisplayName("is abstract")
    void isAbstract() {
        assertThat(Modifier.isAbstract(HomeSynapseException.class.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("extends Exception")
    void extendsException() {
        assertThat(Exception.class).isAssignableFrom(HomeSynapseException.class);
    }

    @Test
    @DisplayName("declares abstract errorCode method")
    void declaresErrorCode() throws NoSuchMethodException {
        Method m = HomeSynapseException.class.getDeclaredMethod("errorCode");
        assertThat(Modifier.isAbstract(m.getModifiers())).isTrue();
        assertThat(m.getReturnType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("declares abstract suggestedHttpStatus method")
    void declaresSuggestedHttpStatus() throws NoSuchMethodException {
        Method m = HomeSynapseException.class.getDeclaredMethod("suggestedHttpStatus");
        assertThat(Modifier.isAbstract(m.getModifiers())).isTrue();
        assertThat(m.getReturnType()).isEqualTo(int.class);
    }

    @Nested
    @DisplayName("Construction via concrete subclass")
    class ConstructionTests {
        @Test
        @DisplayName("message-only constructor")
        void messageOnly() {
            var ex = new EntityNotFoundException("test msg");
            assertThat(ex.getMessage()).isEqualTo("test msg");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("message and cause constructor")
        void messageAndCause() {
            var cause = new RuntimeException("root cause");
            var ex = new EntityNotFoundException("test msg", cause);
            assertThat(ex.getMessage()).isEqualTo("test msg");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }
}

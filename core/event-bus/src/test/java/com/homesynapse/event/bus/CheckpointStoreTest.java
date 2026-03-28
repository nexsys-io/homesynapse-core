/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Shape verification tests for the {@link CheckpointStore} interface.
 *
 * <p>Verifies that CheckpointStore is an interface with the expected method signatures
 * for subscriber position persistence. Behavioral testing requires a SQLite
 * implementation (future session).</p>
 */
@DisplayName("CheckpointStore")
class CheckpointStoreTest {

    // ── Interface shape verification ─────────────────────────────────────

    @Nested
    @DisplayName("Interface shape")
    class InterfaceShapeTests {

        @Test
        @DisplayName("CheckpointStore is an interface")
        void isInterface() {
            assertThat(CheckpointStore.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("exactly 2 declared methods")
        void exactlyTwoMethods() {
            assertThat(CheckpointStore.class.getDeclaredMethods()).hasSize(2);
        }

        @Test
        @DisplayName("all methods are public and abstract")
        void allMethodsPublicAbstract() {
            for (Method method : CheckpointStore.class.getDeclaredMethods()) {
                assertThat(Modifier.isPublic(method.getModifiers()))
                        .as("method %s should be public", method.getName())
                        .isTrue();
                assertThat(Modifier.isAbstract(method.getModifiers()))
                        .as("method %s should be abstract", method.getName())
                        .isTrue();
            }
        }
    }

    // ── Method signature verification ────────────────────────────────────

    @Nested
    @DisplayName("Method signatures")
    class MethodSignatureTests {

        private final Map<String, Method> methods = Arrays.stream(
                        CheckpointStore.class.getDeclaredMethods())
                .collect(Collectors.toMap(Method::getName, m -> m));

        @Test
        @DisplayName("readCheckpoint(String) returns long")
        void readCheckpointSignature() {
            var method = methods.get("readCheckpoint");

            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(long.class);
            assertThat(method.getParameterTypes()).containsExactly(String.class);
        }

        @Test
        @DisplayName("writeCheckpoint(String, long) returns void")
        void writeCheckpointSignature() {
            var method = methods.get("writeCheckpoint");

            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterTypes()).containsExactly(String.class, long.class);
        }
    }
}

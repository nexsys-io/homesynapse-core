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
 * Shape verification tests for the {@link EventBus} interface.
 *
 * <p>Verifies that EventBus is an interface with the expected method signatures.
 * Behavioral testing requires a full implementation (future session).</p>
 */
@DisplayName("EventBus")
class EventBusTest {

    // ── Interface shape verification ─────────────────────────────────────

    @Nested
    @DisplayName("Interface shape")
    class InterfaceShapeTests {

        @Test
        @DisplayName("EventBus is an interface")
        void isInterface() {
            assertThat(EventBus.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("exactly 8 declared methods (4 abstract + 4 default)")
        void exactlyEightMethods() {
            assertThat(EventBus.class.getDeclaredMethods()).hasSize(8);
        }

        @Test
        @DisplayName("all methods are public")
        void allMethodsPublic() {
            for (Method method : EventBus.class.getDeclaredMethods()) {
                assertThat(Modifier.isPublic(method.getModifiers()))
                        .as("method %s should be public", method.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("original 4 methods are abstract")
        void originalFourMethodsAbstract() {
            long abstractCount = Arrays.stream(EventBus.class.getDeclaredMethods())
                    .filter(m -> Modifier.isAbstract(m.getModifiers()))
                    .count();
            assertThat(abstractCount).isEqualTo(4);
        }
    }

    // ── Method signature verification ────────────────────────────────────

    @Nested
    @DisplayName("Method signatures")
    class MethodSignatureTests {

        private final Map<String, Method> methods = Arrays.stream(
                        EventBus.class.getDeclaredMethods())
                .collect(Collectors.toMap(Method::getName, m -> m));

        @Test
        @DisplayName("subscribe(SubscriberInfo) returns void")
        void subscribeSignature() {
            var method = methods.get("subscribe");

            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterTypes()).containsExactly(SubscriberInfo.class);
        }

        @Test
        @DisplayName("unsubscribe(String) returns void")
        void unsubscribeSignature() {
            var method = methods.get("unsubscribe");

            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterTypes()).containsExactly(String.class);
        }

        @Test
        @DisplayName("notifyEvent(long) returns void")
        void notifyEventSignature() {
            var method = methods.get("notifyEvent");

            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterTypes()).containsExactly(long.class);
        }

        @Test
        @DisplayName("subscriberPosition(String) returns long")
        void subscriberPositionSignature() {
            var method = methods.get("subscriberPosition");

            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(long.class);
            assertThat(method.getParameterTypes()).containsExactly(String.class);
        }
    }
}

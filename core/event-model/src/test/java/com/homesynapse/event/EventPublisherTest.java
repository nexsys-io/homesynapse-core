/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventPublisher")
class EventPublisherTest {

    @Test
    @DisplayName("is an interface")
    void isInterface() {
        assertThat(EventPublisher.class.isInterface()).isTrue();
    }

    @Test
    @DisplayName("has exactly 2 declared methods")
    void methodCount() {
        assertThat(EventPublisher.class.getDeclaredMethods()).hasSize(2);
    }

    @Test
    @DisplayName("publish method signature")
    void publishSignature() throws NoSuchMethodException {
        Method m = EventPublisher.class.getDeclaredMethod("publish", EventDraft.class, CausalContext.class);
        assertThat(m.getReturnType()).isEqualTo(EventEnvelope.class);
        assertThat(m.getExceptionTypes()).containsExactly(SequenceConflictException.class);
    }

    @Test
    @DisplayName("publishRoot method signature")
    void publishRootSignature() throws NoSuchMethodException {
        Method m = EventPublisher.class.getDeclaredMethod("publishRoot", EventDraft.class);
        assertThat(m.getReturnType()).isEqualTo(EventEnvelope.class);
        assertThat(m.getExceptionTypes()).containsExactly(SequenceConflictException.class);
    }
}

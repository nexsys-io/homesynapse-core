/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AMD-92 row 17 — {@code cascade_depth_exceeded}: flattened int depths + correlationId. */
@DisplayName("CascadeDepthExceededEvent (AMD-92 row 17)")
class CascadeDepthExceededEventTest {

    private static final AutomationId AUTO =
            AutomationId.of(Ulid.parse("00000000000000000000000000"));
    private static final EventId TRIGGER =
            EventId.of(Ulid.parse("00000000000000000000000001"));
    private static final Ulid CORRELATION = Ulid.parse("00000000000000000000000002");

    @Test
    @DisplayName("all fields accessible")
    void construction() {
        var event = new CascadeDepthExceededEvent(AUTO, TRIGGER, 8, 8, CORRELATION);

        assertThat(event.automationId()).isEqualTo(AUTO);
        assertThat(event.triggeringEventId()).isEqualTo(TRIGGER);
        assertThat(event.cascadeDepth()).isEqualTo(8);
        assertThat(event.maxCascadeDepth()).isEqualTo(8);
        assertThat(event.correlationId()).isEqualTo(CORRELATION);
        assertThat(event).isInstanceOf(DomainEvent.class);
    }

    @Test
    @DisplayName("required fields reject null")
    void nullValidation() {
        assertThatNullPointerException().isThrownBy(() ->
                new CascadeDepthExceededEvent(null, TRIGGER, 8, 8, CORRELATION))
                .withMessageContaining("automationId");
        assertThatNullPointerException().isThrownBy(() ->
                new CascadeDepthExceededEvent(AUTO, null, 8, 8, CORRELATION))
                .withMessageContaining("triggeringEventId");
        assertThatNullPointerException().isThrownBy(() ->
                new CascadeDepthExceededEvent(AUTO, TRIGGER, 8, 8, null))
                .withMessageContaining("correlationId");
    }

    @Test
    @DisplayName("negative depth and a max below 1 are rejected")
    void rangeValidation() {
        assertThatThrownBy(() -> new CascadeDepthExceededEvent(AUTO, TRIGGER, -1, 8, CORRELATION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cascadeDepth");
        assertThatThrownBy(() -> new CascadeDepthExceededEvent(AUTO, TRIGGER, 8, 0, CORRELATION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxCascadeDepth");
    }

    @Test
    @DisplayName("record has 5 components and carries the CASCADE_DEPTH_EXCEEDED @EventType")
    void shapeAndAnnotation() {
        assertThat(CascadeDepthExceededEvent.class.getRecordComponents()).hasSize(5);
        assertThat(CascadeDepthExceededEvent.class.getAnnotation(EventType.class).value())
                .isEqualTo(EventTypes.CASCADE_DEPTH_EXCEEDED);
    }
}

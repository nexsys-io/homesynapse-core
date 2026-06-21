/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AMD-92 row 18 — {@code cascade_loop_detected}: flattened ids + the AutomationId cycle path. */
@DisplayName("CascadeLoopDetectedEvent (AMD-92 row 18)")
class CascadeLoopDetectedEventTest {

    private static final AutomationId AUTO_A =
            AutomationId.of(Ulid.parse("00000000000000000000000000"));
    private static final AutomationId AUTO_B =
            AutomationId.of(Ulid.parse("00000000000000000000000001"));
    private static final EventId TRIGGER =
            EventId.of(Ulid.parse("00000000000000000000000002"));
    private static final Ulid CORRELATION = Ulid.parse("00000000000000000000000003");
    private static final Ulid ORIGINAL_RUN = Ulid.parse("00000000000000000000000004");

    @Test
    @DisplayName("all fields accessible; chain carries the cycle path")
    void construction() {
        var event = new CascadeLoopDetectedEvent(
                AUTO_A, TRIGGER, CORRELATION, ORIGINAL_RUN, List.of(AUTO_A, AUTO_B, AUTO_A));

        assertThat(event.automationId()).isEqualTo(AUTO_A);
        assertThat(event.triggeringEventId()).isEqualTo(TRIGGER);
        assertThat(event.correlationId()).isEqualTo(CORRELATION);
        assertThat(event.originalRunId()).isEqualTo(ORIGINAL_RUN);
        assertThat(event.chain()).containsExactly(AUTO_A, AUTO_B, AUTO_A);
        assertThat(event).isInstanceOf(DomainEvent.class);
    }

    @Test
    @DisplayName("chain is defensively copied and unmodifiable")
    void defensiveCopy() {
        var chain = new java.util.ArrayList<>(List.of(AUTO_A, AUTO_B, AUTO_A));
        var event = new CascadeLoopDetectedEvent(AUTO_A, TRIGGER, CORRELATION, ORIGINAL_RUN, chain);

        chain.clear();
        assertThat(event.chain()).containsExactly(AUTO_A, AUTO_B, AUTO_A);
        assertThatThrownBy(() -> event.chain().add(AUTO_B))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("every field rejects null")
    void nullValidation() {
        assertThatNullPointerException().isThrownBy(() -> new CascadeLoopDetectedEvent(
                null, TRIGGER, CORRELATION, ORIGINAL_RUN, List.of(AUTO_A)))
                .withMessageContaining("automationId");
        assertThatNullPointerException().isThrownBy(() -> new CascadeLoopDetectedEvent(
                AUTO_A, null, CORRELATION, ORIGINAL_RUN, List.of(AUTO_A)))
                .withMessageContaining("triggeringEventId");
        assertThatNullPointerException().isThrownBy(() -> new CascadeLoopDetectedEvent(
                AUTO_A, TRIGGER, null, ORIGINAL_RUN, List.of(AUTO_A)))
                .withMessageContaining("correlationId");
        assertThatNullPointerException().isThrownBy(() -> new CascadeLoopDetectedEvent(
                AUTO_A, TRIGGER, CORRELATION, null, List.of(AUTO_A)))
                .withMessageContaining("originalRunId");
        assertThatNullPointerException().isThrownBy(() -> new CascadeLoopDetectedEvent(
                AUTO_A, TRIGGER, CORRELATION, ORIGINAL_RUN, null))
                .withMessageContaining("chain");
    }

    @Test
    @DisplayName("record has 5 components and carries the CASCADE_LOOP_DETECTED @EventType")
    void shapeAndAnnotation() {
        assertThat(CascadeLoopDetectedEvent.class.getRecordComponents()).hasSize(5);
        assertThat(CascadeLoopDetectedEvent.class.getAnnotation(EventType.class).value())
                .isEqualTo(EventTypes.CASCADE_LOOP_DETECTED);
    }
}

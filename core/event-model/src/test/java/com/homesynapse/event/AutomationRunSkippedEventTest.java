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

/** AMD-92 row 7 — {@code automation_run_skipped}: flattened shape, nullable activeRunId. */
@DisplayName("AutomationRunSkippedEvent (AMD-92 row 7)")
class AutomationRunSkippedEventTest {

    private static final AutomationId AUTO =
            AutomationId.of(Ulid.parse("00000000000000000000000000"));
    private static final EventId TRIGGER =
            EventId.of(Ulid.parse("00000000000000000000000001"));
    private static final Ulid ACTIVE_RUN = Ulid.parse("00000000000000000000000002");

    @Test
    @DisplayName("all fields accessible; activeRunId may be null")
    void construction() {
        var event = new AutomationRunSkippedEvent(
                AUTO, TRIGGER, "mode_busy", "SINGLE", ACTIVE_RUN, "INFO");

        assertThat(event.automationId()).isEqualTo(AUTO);
        assertThat(event.triggeringEventId()).isEqualTo(TRIGGER);
        assertThat(event.reason()).isEqualTo("mode_busy");
        assertThat(event.mode()).isEqualTo("SINGLE");
        assertThat(event.activeRunId()).isEqualTo(ACTIVE_RUN);
        assertThat(event.maxExceededSeverity()).isEqualTo("INFO");
        assertThat(event).isInstanceOf(DomainEvent.class);

        var queueFull = new AutomationRunSkippedEvent(
                AUTO, TRIGGER, "queue_full", "QUEUED", null, "WARNING");
        assertThat(queueFull.activeRunId()).isNull();
    }

    @Test
    @DisplayName("required fields reject null")
    void nullValidation() {
        assertThatNullPointerException().isThrownBy(() -> new AutomationRunSkippedEvent(
                null, TRIGGER, "mode_busy", "SINGLE", ACTIVE_RUN, "INFO"))
                .withMessageContaining("automationId");
        assertThatNullPointerException().isThrownBy(() -> new AutomationRunSkippedEvent(
                AUTO, null, "mode_busy", "SINGLE", ACTIVE_RUN, "INFO"))
                .withMessageContaining("triggeringEventId");
        assertThatNullPointerException().isThrownBy(() -> new AutomationRunSkippedEvent(
                AUTO, TRIGGER, null, "SINGLE", ACTIVE_RUN, "INFO"))
                .withMessageContaining("reason");
        assertThatNullPointerException().isThrownBy(() -> new AutomationRunSkippedEvent(
                AUTO, TRIGGER, "mode_busy", null, ACTIVE_RUN, "INFO"))
                .withMessageContaining("mode");
        assertThatNullPointerException().isThrownBy(() -> new AutomationRunSkippedEvent(
                AUTO, TRIGGER, "mode_busy", "SINGLE", ACTIVE_RUN, null))
                .withMessageContaining("maxExceededSeverity");
    }

    @Test
    @DisplayName("blank reason, mode, and severity are rejected")
    void blankValidation() {
        assertThatThrownBy(() -> new AutomationRunSkippedEvent(
                AUTO, TRIGGER, "  ", "SINGLE", ACTIVE_RUN, "INFO"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> new AutomationRunSkippedEvent(
                AUTO, TRIGGER, "mode_busy", " ", ACTIVE_RUN, "INFO"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mode");
        assertThatThrownBy(() -> new AutomationRunSkippedEvent(
                AUTO, TRIGGER, "mode_busy", "SINGLE", ACTIVE_RUN, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxExceededSeverity");
    }

    @Test
    @DisplayName("record has 6 components and carries the AUTOMATION_RUN_SKIPPED @EventType")
    void shapeAndAnnotation() {
        assertThat(AutomationRunSkippedEvent.class.getRecordComponents()).hasSize(6);
        assertThat(AutomationRunSkippedEvent.class.getAnnotation(EventType.class).value())
                .isEqualTo(EventTypes.AUTOMATION_RUN_SKIPPED);
    }
}

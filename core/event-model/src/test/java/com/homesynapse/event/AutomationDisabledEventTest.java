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

/** AMD-92 row 10 — {@code automation_disabled}: NORMAL priority, nullable lastError/lastRunId. */
@DisplayName("AutomationDisabledEvent (AMD-92 row 10)")
class AutomationDisabledEventTest {

    private static final AutomationId AUTO =
            AutomationId.of(Ulid.parse("00000000000000000000000000"));
    private static final Ulid LAST_RUN = Ulid.parse("00000000000000000000000001");

    @Test
    @DisplayName("all fields accessible; lastError and lastRunId may be null")
    void construction() {
        var event = new AutomationDisabledEvent(
                AUTO, "repeated_failure", 5, 10, "device unreachable", LAST_RUN);

        assertThat(event.automationId()).isEqualTo(AUTO);
        assertThat(event.reason()).isEqualTo("repeated_failure");
        assertThat(event.failureCount()).isEqualTo(5);
        assertThat(event.windowMinutes()).isEqualTo(10);
        assertThat(event.lastError()).isEqualTo("device unreachable");
        assertThat(event.lastRunId()).isEqualTo(LAST_RUN);
        assertThat(event).isInstanceOf(DomainEvent.class);

        var noDetail = new AutomationDisabledEvent(AUTO, "repeated_failure", 5, 10, null, null);
        assertThat(noDetail.lastError()).isNull();
        assertThat(noDetail.lastRunId()).isNull();
    }

    @Test
    @DisplayName("required fields reject null and blank")
    void nullAndBlankValidation() {
        assertThatNullPointerException().isThrownBy(() ->
                new AutomationDisabledEvent(null, "repeated_failure", 5, 10, null, null))
                .withMessageContaining("automationId");
        assertThatNullPointerException().isThrownBy(() ->
                new AutomationDisabledEvent(AUTO, null, 5, 10, null, null))
                .withMessageContaining("reason");
        assertThatThrownBy(() -> new AutomationDisabledEvent(AUTO, "  ", 5, 10, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
    }

    @Test
    @DisplayName("negative counts are rejected")
    void negativeCounts() {
        assertThatThrownBy(() ->
                new AutomationDisabledEvent(AUTO, "repeated_failure", -1, 10, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failureCount");
        assertThatThrownBy(() ->
                new AutomationDisabledEvent(AUTO, "repeated_failure", 5, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windowMinutes");
    }

    @Test
    @DisplayName("record has 6 components and carries the AUTOMATION_DISABLED @EventType")
    void shapeAndAnnotation() {
        assertThat(AutomationDisabledEvent.class.getRecordComponents()).hasSize(6);
        assertThat(AutomationDisabledEvent.class.getAnnotation(EventType.class).value())
                .isEqualTo(EventTypes.AUTOMATION_DISABLED);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AMD-92 row 8 — {@code automation_run_cancelled}: flattened cancelledRunId, two event ids. */
@DisplayName("AutomationRunCancelledEvent (AMD-92 row 8)")
class AutomationRunCancelledEventTest {

    private static final AutomationId AUTO =
            AutomationId.of(Ulid.parse("00000000000000000000000000"));
    private static final Ulid CANCELLED_RUN = Ulid.parse("00000000000000000000000001");
    private static final EventId REPLACING =
            EventId.of(Ulid.parse("00000000000000000000000002"));
    private static final EventId TRIGGERING =
            EventId.of(Ulid.parse("00000000000000000000000003"));

    @Test
    @DisplayName("all fields accessible")
    void construction() {
        var event = new AutomationRunCancelledEvent(AUTO, CANCELLED_RUN, REPLACING, TRIGGERING);

        assertThat(event.automationId()).isEqualTo(AUTO);
        assertThat(event.cancelledRunId()).isEqualTo(CANCELLED_RUN);
        assertThat(event.replacingEventId()).isEqualTo(REPLACING);
        assertThat(event.triggeringEventId()).isEqualTo(TRIGGERING);
        assertThat(event).isInstanceOf(DomainEvent.class);
    }

    @Test
    @DisplayName("every field rejects null")
    void nullValidation() {
        assertThatNullPointerException().isThrownBy(() ->
                new AutomationRunCancelledEvent(null, CANCELLED_RUN, REPLACING, TRIGGERING))
                .withMessageContaining("automationId");
        assertThatNullPointerException().isThrownBy(() ->
                new AutomationRunCancelledEvent(AUTO, null, REPLACING, TRIGGERING))
                .withMessageContaining("cancelledRunId");
        assertThatNullPointerException().isThrownBy(() ->
                new AutomationRunCancelledEvent(AUTO, CANCELLED_RUN, null, TRIGGERING))
                .withMessageContaining("replacingEventId");
        assertThatNullPointerException().isThrownBy(() ->
                new AutomationRunCancelledEvent(AUTO, CANCELLED_RUN, REPLACING, null))
                .withMessageContaining("triggeringEventId");
    }

    @Test
    @DisplayName("record has 4 components and carries the AUTOMATION_RUN_CANCELLED @EventType")
    void shapeAndAnnotation() {
        assertThat(AutomationRunCancelledEvent.class.getRecordComponents()).hasSize(4);
        assertThat(AutomationRunCancelledEvent.class.getAnnotation(EventType.class).value())
                .isEqualTo(EventTypes.AUTOMATION_RUN_CANCELLED);
    }
}

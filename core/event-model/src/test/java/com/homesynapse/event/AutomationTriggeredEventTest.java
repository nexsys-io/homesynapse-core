/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The AMD-92 row-1 reshape: flattened shape, residency, and defensive copies. */
@DisplayName("AutomationTriggeredEvent (AMD-92 reshape)")
class AutomationTriggeredEventTest {

    private static final Ulid RUN = Ulid.parse("00000000000000000000000000");
    private static final EntityId ENTITY = EntityId.of(Ulid.parse("00000000000000000000000001"));
    private static final EventId TRIGGERING = EventId.of(Ulid.parse("00000000000000000000000002"));

    @Test
    @DisplayName("matched_triggers carries trigger IDs (strings), not raw indices")
    void carriesTriggerIds() {
        var event = new AutomationTriggeredEvent(RUN, TRIGGERING, List.of("t-morning", "t-dusk"),
                Map.of("target", Set.of(ENTITY)), "abc123", 0);

        assertThat(event.matchedTriggers()).containsExactly("t-morning", "t-dusk");
        assertThat(event.resolvedTargets()).containsEntry("target", Set.of(ENTITY));
        assertThat(event.cascadeDepth()).isZero();
        assertThat(event).isInstanceOf(DomainEvent.class);
    }

    @Test
    @DisplayName("collection components are deeply unmodifiable")
    void defensiveCopies() {
        var event = new AutomationTriggeredEvent(RUN, TRIGGERING, List.of("t1"),
                Map.of("target", Set.of(ENTITY)), "hash", 1);

        assertThatThrownBy(() -> event.matchedTriggers().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> event.resolvedTargets().get("target").add(ENTITY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("validation rejects a blank hash, a negative cascade depth, and nulls")
    void validation() {
        assertThatThrownBy(() -> new AutomationTriggeredEvent(RUN, TRIGGERING, List.of(),
                Map.of(), "  ", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomationTriggeredEvent(RUN, TRIGGERING, List.of(),
                Map.of(), "hash", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomationTriggeredEvent(null, TRIGGERING, List.of(),
                Map.of(), "hash", 0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("type residency: every component is event-resident-or-below (no automation type)")
    void typeResidency() {
        assertThat(AutomationTriggeredEvent.class.getRecordComponents()).hasSize(6);
        for (RecordComponent component : AutomationTriggeredEvent.class.getRecordComponents()) {
            String pkg = component.getType().getPackageName();
            assertThat(pkg)
                    .as("component %s type %s", component.getName(), component.getType())
                    .matches("java\\..*|com\\.homesynapse\\.platform.*"
                            + "|com\\.homesynapse\\.event|com\\.homesynapse\\.value");
        }
    }

    @Test
    @DisplayName("carries the AUTOMATION_TRIGGERED @EventType")
    void eventTypeAnnotation() {
        assertThat(AutomationTriggeredEvent.class.getAnnotation(EventType.class).value())
                .isEqualTo(EventTypes.AUTOMATION_TRIGGERED);
    }
}

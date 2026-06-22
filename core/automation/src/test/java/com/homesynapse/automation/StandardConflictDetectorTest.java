/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.homesynapse.event.AutomationConflictDetectedEvent;
import com.homesynapse.event.AutomationConflictDetectedEvent.ConflictEntry;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardConflictDetector} — post-execution scan for contradictory commands to the
 * same entity across Runs triggered by one event, emitting {@code automation_conflict_detected}
 * (AMD-92 row 9), report-only (DP-F).
 */
@DisplayName("StandardConflictDetector (M7.2a-2)")
class StandardConflictDetectorTest {

    private final EntityId entity = AutomationTestSupport.entityId();
    private final Selector selector = new DirectRefSelector(entity);

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private AutomationTestSupport.MapAutomationRegistry registry;
    private AutomationTestSupport.FakeSelectorResolver resolver;
    private StandardConflictDetector detector;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        registry = new AutomationTestSupport.MapAutomationRegistry();
        resolver = new AutomationTestSupport.FakeSelectorResolver().bind(selector, Set.of(entity));
        detector = new StandardConflictDetector(registry, resolver, publisher);
    }

    @Test
    @DisplayName("two Runs issuing contradictory commands to the same entity emit one conflict")
    void contradictoryCommands_emitOneConflict() {
        AutomationId a1 = AutomationTestSupport.automationId();
        AutomationId a2 = AutomationTestSupport.automationId();
        registry.add(automationWithCommand(a1, "turn_on")).add(automationWithCommand(a2, "turn_off"));
        EventId triggeringEventId = AutomationTestSupport.eventId();

        detector.scanForConflicts(triggeringEventId,
                List.of(runContext(a1, triggeringEventId), runContext(a2, triggeringEventId)));

        List<EventEnvelope> conflicts = publisher.ofType(EventTypes.AUTOMATION_CONFLICT_DETECTED);
        assertThat(conflicts).hasSize(1);
        AutomationConflictDetectedEvent payload =
                (AutomationConflictDetectedEvent) conflicts.get(0).payload();
        assertThat(payload.entityRef()).isEqualTo(entity);
        assertThat(payload.triggeringEventId()).isEqualTo(triggeringEventId);
        assertThat(payload.contradictory()).isTrue();
        assertThat(payload.conflicts()).extracting(ConflictEntry::commandName)
                .containsExactlyInAnyOrder("turn_on", "turn_off");
    }

    @Test
    @DisplayName("two Runs issuing the same command to the same entity emit no conflict")
    void identicalCommands_emitNothing() {
        AutomationId a1 = AutomationTestSupport.automationId();
        AutomationId a2 = AutomationTestSupport.automationId();
        registry.add(automationWithCommand(a1, "turn_on")).add(automationWithCommand(a2, "turn_on"));
        EventId triggeringEventId = AutomationTestSupport.eventId();

        detector.scanForConflicts(triggeringEventId,
                List.of(runContext(a1, triggeringEventId), runContext(a2, triggeringEventId)));

        assertThat(publisher.ofType(EventTypes.AUTOMATION_CONFLICT_DETECTED)).isEmpty();
    }

    private AutomationDefinition automationWithCommand(AutomationId id, String commandName) {
        return new AutomationDefinition(id, "auto-" + id, "auto", null, true,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(selector, "on_off", "on", null, "t1")),
                List.of(),
                List.of(new CommandAction(selector, commandName, Map.of(), UnavailablePolicy.SKIP)));
    }

    private RunContext runContext(AutomationId automationId, EventId triggeringEventId) {
        return new RunContext(new RunId(AutomationTestSupport.ulid()), automationId,
                triggeringEventId, List.of(0), Map.of(), "hash", RunCausalChain.root(), 1L);
    }
}

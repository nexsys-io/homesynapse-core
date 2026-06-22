/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
import com.homesynapse.event.AutomationCapabilityMismatchEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent.EvaluatedEntityState;
import com.homesynapse.event.AutomationConflictDetectedEvent;
import com.homesynapse.event.AutomationConflictDetectedEvent.ConflictEntry;
import com.homesynapse.event.AutomationDisabledEvent;
import com.homesynapse.event.AutomationInvokedEvent;
import com.homesynapse.event.AutomationRunCancelledEvent;
import com.homesynapse.event.AutomationRunSkippedEvent;
import com.homesynapse.event.AutomationSlugRedirectEvent;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.CascadeDepthExceededEvent;
import com.homesynapse.event.CascadeLoopDetectedEvent;
import com.homesynapse.event.EventId;
import com.homesynapse.event.TriggerDurationCancelledEvent;
import com.homesynapse.event.TriggerDurationExpiredEvent;
import com.homesynapse.event.TriggerDurationLimitExceededEvent;
import com.homesynapse.event.TriggerDurationStartedEvent;
import com.homesynapse.event.TriggerDurationStateValidatedEvent;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Persistence-codec round-trip tests for the AMD-92 automation event slices — the M7.1
 * run-initiation rows (1, 3, 11–16, 19) and the M7.2 run-lifecycle rows (2 reshape, 7, 8,
 * 10, 17, 18) — the FLATTEN residency precedent (AMD-52). Each record round-trips through
 * the {@link PersistenceObjectMapper} (typed ULID wrappers serialize as Crockford strings)
 * including nullable fields.
 */
@DisplayName("Automation event serde (AMD-92 M7.1 + M7.2 slices)")
class AutomationEventSerdeTest {

    private static final Ulid U1 = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final Ulid U2 = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ");
    private static final AutomationId AUTO = AutomationId.of(U1);
    private static final AutomationId AUTO_2 = AutomationId.of(U2);
    private static final EntityId ENTITY = EntityId.of(U2);
    private static final EventId EVENT = EventId.of(U2);
    private static final EventId EVENT_2 = EventId.of(U1);

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = PersistenceObjectMapper.create();
    }

    private <T> void roundTrip(T value, Class<T> type) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(value);
        T parsed = mapper.readValue(bytes, type);
        assertThat(parsed).isEqualTo(value);
    }

    @Test
    @DisplayName("automation_triggered (row 1) round-trips with nested map/set components")
    void triggered() throws Exception {
        roundTrip(new AutomationTriggeredEvent(U1, EVENT, List.of("t-a", "t-b"),
                Map.of("target", Set.of(ENTITY)), "hash", 3), AutomationTriggeredEvent.class);
    }

    @Test
    @DisplayName("automation_invoked (row 3) round-trips, including a null context")
    void invoked() throws Exception {
        roundTrip(new AutomationInvokedEvent("ui-button"), AutomationInvokedEvent.class);
        roundTrip(new AutomationInvokedEvent(null), AutomationInvokedEvent.class);
    }

    @Test
    @DisplayName("automation_slug_redirect (row 11) round-trips")
    void slugRedirect() throws Exception {
        roundTrip(new AutomationSlugRedirectEvent("old.slug", "new.slug", ENTITY),
                AutomationSlugRedirectEvent.class);
    }

    @Test
    @DisplayName("trigger_duration_* (rows 12–16) round-trip")
    void durationEvents() throws Exception {
        roundTrip(new TriggerDurationStartedEvent(AUTO, 0, "t1", EVENT, ENTITY, 1_800_000L),
                TriggerDurationStartedEvent.class);
        roundTrip(new TriggerDurationCancelledEvent(AUTO, 0, "t1", EVENT, "predicate_false"),
                TriggerDurationCancelledEvent.class);
        roundTrip(new TriggerDurationExpiredEvent(AUTO, 0, "t1", EVENT),
                TriggerDurationExpiredEvent.class);
        roundTrip(new TriggerDurationStateValidatedEvent(AUTO, 0, "t1", EVENT, false),
                TriggerDurationStateValidatedEvent.class);
        roundTrip(new TriggerDurationLimitExceededEvent(AUTO, 0, "t1", 256, 256),
                TriggerDurationLimitExceededEvent.class);
    }

    @Test
    @DisplayName("automation_capability_mismatch (row 19) round-trips with list components")
    void capabilityMismatch() throws Exception {
        roundTrip(new AutomationCapabilityMismatchEvent(AUTO, List.of(ENTITY),
                List.of("cap.dimming", "cap.color")), AutomationCapabilityMismatchEvent.class);
    }

    // ===== M7.2 run-lifecycle slice (AMD-92 rows 2 reshape + 7, 8, 10, 17, 18) =====

    @Test
    @DisplayName("automation_completed (row 2 reshape) round-trips, including null reasons")
    void completed() throws Exception {
        roundTrip(new com.homesynapse.event.AutomationCompletedEvent(
                U1, "COMPLETED", 1234L, 2, 3, null, null),
                com.homesynapse.event.AutomationCompletedEvent.class);
        roundTrip(new com.homesynapse.event.AutomationCompletedEvent(
                U2, "FAILED", 500L, 1, 0, "device unreachable", null),
                com.homesynapse.event.AutomationCompletedEvent.class);
    }

    @Test
    @DisplayName("automation_run_skipped (row 7) round-trips, including a null activeRunId")
    void runSkipped() throws Exception {
        roundTrip(new AutomationRunSkippedEvent(AUTO, EVENT, "mode_busy", "SINGLE", U2, "INFO"),
                AutomationRunSkippedEvent.class);
        roundTrip(new AutomationRunSkippedEvent(AUTO, EVENT, "queue_full", "QUEUED", null, "WARNING"),
                AutomationRunSkippedEvent.class);
    }

    @Test
    @DisplayName("automation_run_cancelled (row 8) round-trips with two distinct event ids")
    void runCancelled() throws Exception {
        roundTrip(new AutomationRunCancelledEvent(AUTO, U2, EVENT_2, EVENT),
                AutomationRunCancelledEvent.class);
    }

    @Test
    @DisplayName("automation_disabled (row 10) round-trips, including null lastError/lastRunId")
    void disabled() throws Exception {
        roundTrip(new AutomationDisabledEvent(AUTO, "repeated_failure", 5, 10, "timeout", U2),
                AutomationDisabledEvent.class);
        roundTrip(new AutomationDisabledEvent(AUTO, "repeated_failure", 5, 10, null, null),
                AutomationDisabledEvent.class);
    }

    @Test
    @DisplayName("cascade_depth_exceeded (row 17) round-trips")
    void cascadeDepthExceeded() throws Exception {
        roundTrip(new CascadeDepthExceededEvent(AUTO, EVENT, 8, 8, U1),
                CascadeDepthExceededEvent.class);
    }

    @Test
    @DisplayName("cascade_loop_detected (row 18) round-trips with the AutomationId cycle path")
    void cascadeLoopDetected() throws Exception {
        roundTrip(new CascadeLoopDetectedEvent(AUTO, EVENT, U1, U2, List.of(AUTO, AUTO_2, AUTO)),
                CascadeLoopDetectedEvent.class);
    }

    // ===== M7.2a-2 execution/dispatch slice (AMD-92 rows 4, 5, 6, 9 + nested records) =====

    @Test
    @DisplayName("automation_condition_evaluated (row 4) round-trips with the nested EvaluatedEntityState")
    void conditionEvaluated() throws Exception {
        Instant lastChanged = Instant.parse("2026-01-01T00:00:00Z");
        roundTrip(new AutomationConditionEvaluatedEvent(U1, 0, "StateCondition", true,
                        List.of(new EvaluatedEntityState(ENTITY, "on_off", "on", lastChanged, EVENT))),
                AutomationConditionEvaluatedEvent.class);
        // Null-safe: a nullable value (unreported attribute) and a nullable lastChangedByEventId.
        roundTrip(new AutomationConditionEvaluatedEvent(U2, 1, "NumericCondition", false,
                        List.of(new EvaluatedEntityState(ENTITY, "temperature", null, lastChanged, null))),
                AutomationConditionEvaluatedEvent.class);
        // Empty evaluated-state list (e.g. a time condition).
        roundTrip(new AutomationConditionEvaluatedEvent(U1, 0, "TimeCondition", true, List.of()),
                AutomationConditionEvaluatedEvent.class);
    }

    @Test
    @DisplayName("automation_action_started (row 5) round-trips with the target list")
    void actionStarted() throws Exception {
        roundTrip(new AutomationActionStartedEvent(U1, 0, "CommandAction", List.of(ENTITY)),
                AutomationActionStartedEvent.class);
        roundTrip(new AutomationActionStartedEvent(U1, 1, "DelayAction", List.of()),
                AutomationActionStartedEvent.class);
    }

    @Test
    @DisplayName("automation_action_completed (row 6) round-trips, including a null errorDetail")
    void actionCompleted() throws Exception {
        roundTrip(new AutomationActionCompletedEvent(U1, 0, "success", null),
                AutomationActionCompletedEvent.class);
        roundTrip(new AutomationActionCompletedEvent(U1, 1, "error", "device unreachable"),
                AutomationActionCompletedEvent.class);
    }

    @Test
    @DisplayName("automation_conflict_detected (row 9) round-trips with the nested ConflictEntry list")
    void conflictDetected() throws Exception {
        roundTrip(new AutomationConflictDetectedEvent(EVENT, ENTITY,
                        List.of(new ConflictEntry(AUTO, EVENT, "turn_on", "{}"),
                                new ConflictEntry(AUTO_2, EVENT_2, "turn_off", "{}")), true),
                AutomationConflictDetectedEvent.class);
    }
}

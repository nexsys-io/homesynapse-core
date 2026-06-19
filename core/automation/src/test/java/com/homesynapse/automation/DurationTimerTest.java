/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.automationId;
import static com.homesynapse.automation.AutomationTestSupport.entity;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.envelope;
import static com.homesynapse.automation.AutomationTestSupport.eventId;
import static com.homesynapse.automation.AutomationTestSupport.mutableClock;
import static com.homesynapse.automation.AutomationTestSupport.snapshot;
import static com.homesynapse.automation.AutomationTestSupport.state;
import static com.homesynapse.automation.AutomationTestSupport.stateChanged;
import static com.homesynapse.automation.AutomationTestSupport.str;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.TriggerDurationStartedEvent;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.Availability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AMD-25 {@code for_duration} timer lifecycle and the R14-B robustness pins
 * (REC-156 monotonic seam, REC-167 DST, REC-165 hash-on-reload, W2 kill-mid-sustain).
 *
 * <blockquote><strong>Tests must inject {@code Clock}.</strong> Timer expiry is driven by
 * a steppable injected {@code MutableClock} via {@code pollExpirations()} — never by
 * wall-clock sleeping — so boundaries are deterministic and NO_DIRECT_TIME_ACCESS-safe.
 * </blockquote>
 */
@DisplayName("Duration timers (AMD-25 / R14-B)")
class DurationTimerTest {

    private AutomationTestSupport.MutableClock clock;
    private EntityId light;
    private AutomationId autoId;
    private StandardAutomationRegistry registry;
    private AutomationTestSupport.RecordingEventPublisher publisher;
    private StandardTriggerEvaluator evaluator;

    @BeforeEach
    void setUp() {
        clock = mutableClock();
        light = entityId();
        autoId = automationId();
        registry = new StandardAutomationRegistry();
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        evaluator = newEvaluator(StandardTriggerEvaluator.DEFAULT_MAX_DURATION_TIMERS, clock);
    }

    @AfterEach
    void tearDown() {
        evaluator.close();
    }

    private StandardTriggerEvaluator newEvaluator(int maxTimers, AutomationTestSupport.MutableClock c) {
        StandardSelectorResolver resolver = new StandardSelectorResolver(
                new AutomationTestSupport.StubEntityRegistry(List.of(
                        entity(light, "kitchen.light", EntityType.LIGHT, null,
                                EntityRole.PRIMARY, List.of()))),
                new AutomationTestSupport.StubAreaRegistry(List.of()),
                new AutomationTestSupport.StubDeviceRegistry());
        var stateQuery = new AutomationTestSupport.StubStateQueryService(
                snapshot(Map.of(light, state(light, Availability.AVAILABLE,
                        Map.of("on_off", str("on"))))));
        return new StandardTriggerEvaluator(registry, resolver, stateQuery, publisher, c, maxTimers);
    }

    private void loadDurationAutomation(Duration forDuration) {
        registry.load(List.of(new AutomationDefinition(autoId, "a", "a", null, true,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateChangeTrigger(new DirectRefSelector(light), "on_off",
                        null, "on", forDuration, "t1")),
                List.of(), List.of())));
    }

    @Test
    @DisplayName("REC-156: a timer fires exactly at the sustained boundary across a clock step")
    void firesAtBoundary() {
        loadDurationAutomation(Duration.ofMinutes(30));
        evaluator.evaluate(stateChanged(light, "on_off", str("off"), str("on")));

        assertThat(evaluator.activeDurationTimerCount()).isEqualTo(1);
        assertThat(publisher.countOfType(EventTypes.TRIGGER_DURATION_STARTED)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(29));
        evaluator.pollExpirations();
        assertThat(publisher.countOfType(EventTypes.TRIGGER_DURATION_EXPIRED)).isZero();

        clock.advance(Duration.ofMinutes(1)); // total 30m — the boundary
        evaluator.pollExpirations();
        assertThat(publisher.countOfType(EventTypes.TRIGGER_DURATION_EXPIRED)).isEqualTo(1);
        assertThat(evaluator.activeDurationTimerCount()).isZero();
    }

    @Test
    @DisplayName("REC-167: a sustain window crossing a DST boundary fires after exact elapsed time")
    void firesAcrossDstBoundary() {
        // US spring-forward: 2026-03-08 02:00 local jumps to 03:00 (EST→EDT).
        Instant beforeJump = Instant.parse("2026-03-08T06:30:00Z");
        AutomationTestSupport.MutableClock zoned =
                mutableClock(beforeJump, ZoneId.of("America/New_York"));
        StandardTriggerEvaluator zonedEvaluator = newEvaluator(
                StandardTriggerEvaluator.DEFAULT_MAX_DURATION_TIMERS, zoned);
        try {
            loadDurationAutomation(Duration.ofHours(1));
            zonedEvaluator.evaluate(stateChanged(light, "on_off", str("off"), str("on")));

            zoned.advance(Duration.ofMinutes(59));
            zonedEvaluator.pollExpirations();
            assertThat(publisher.countOfType(EventTypes.TRIGGER_DURATION_EXPIRED)).isZero();

            zoned.advance(Duration.ofMinutes(1)); // exactly one Instant-hour, DST notwithstanding
            zonedEvaluator.pollExpirations();
            assertThat(publisher.countOfType(EventTypes.TRIGGER_DURATION_EXPIRED)).isEqualTo(1);
        } finally {
            zonedEvaluator.close();
        }
    }

    @Test
    @DisplayName("REC-165: a definition edit is detectable by hash and cancels the timer on reload")
    void definitionHashAndReloadCancel() {
        var original = new StateChangeTrigger(new DirectRefSelector(light), "on_off", null, "on",
                Duration.ofMinutes(30), "t1");
        var edited = new StateChangeTrigger(new DirectRefSelector(light), "on_off", null, "off",
                Duration.ofMinutes(30), "t1");
        assertThat(DefinitionHashes.forTrigger(original))
                .isNotEqualTo(DefinitionHashes.forTrigger(edited));

        loadDurationAutomation(Duration.ofMinutes(30));
        evaluator.evaluate(stateChanged(light, "on_off", str("off"), str("on")));
        assertThat(evaluator.activeDurationTimerCount()).isEqualTo(1);

        evaluator.cancelDurationTimer(autoId, 0); // hot-reload reconciliation entry point
        assertThat(evaluator.activeDurationTimerCount()).isZero();
        assertThat(publisher.ofType(EventTypes.TRIGGER_DURATION_CANCELLED)).hasSize(1);
        assertThat(((com.homesynapse.event.TriggerDurationCancelledEvent)
                publisher.ofType(EventTypes.TRIGGER_DURATION_CANCELLED).get(0).payload()).reason())
                .isEqualTo("definition_changed");
    }

    @Test
    @DisplayName("a predicate-false event for the monitored entity cancels the timer")
    void predicateFalseCancels() {
        loadDurationAutomation(Duration.ofMinutes(30));
        evaluator.evaluate(stateChanged(light, "on_off", str("off"), str("on")));
        assertThat(evaluator.activeDurationTimerCount()).isEqualTo(1);

        evaluator.evaluate(stateChanged(light, "on_off", str("on"), str("off")));
        assertThat(evaluator.activeDurationTimerCount()).isZero();
        assertThat(((com.homesynapse.event.TriggerDurationCancelledEvent)
                publisher.ofType(EventTypes.TRIGGER_DURATION_CANCELLED).get(0).payload()).reason())
                .isEqualTo("predicate_false");
    }

    @Test
    @DisplayName("exceeding the concurrent-timer ceiling publishes limit_exceeded and rejects the timer")
    void limitExceeded() {
        StandardTriggerEvaluator capped = newEvaluator(1, clock);
        try {
            AutomationId second = automationId();
            registry.load(List.of(
                    new AutomationDefinition(autoId, "a", "a", null, true, ConcurrencyMode.SINGLE, 1,
                            MaxExceededSeverity.INFO, 0,
                            List.of(new StateChangeTrigger(new DirectRefSelector(light), "on_off",
                                    null, "on", Duration.ofMinutes(30), "t1")),
                            List.of(), List.of()),
                    new AutomationDefinition(second, "b", "b", null, true, ConcurrencyMode.SINGLE, 1,
                            MaxExceededSeverity.INFO, 0,
                            List.of(new StateChangeTrigger(new DirectRefSelector(light), "on_off",
                                    null, "on", Duration.ofMinutes(30), "t2")),
                            List.of(), List.of())));

            capped.evaluate(stateChanged(light, "on_off", str("off"), str("on")));

            assertThat(capped.activeDurationTimerCount()).isEqualTo(1);
            assertThat(publisher.countOfType(EventTypes.TRIGGER_DURATION_LIMIT_EXCEEDED)).isEqualTo(1);
        } finally {
            capped.close();
        }
    }

    @Test
    @DisplayName("W2: a timer active at REPLAY→LIVE is re-derived from events, not re-executed")
    void replayRebuild() {
        loadDurationAutomation(Duration.ofMinutes(30));
        AutomationEngineSubscriber subscriber = new AutomationEngineSubscriber(evaluator);
        subscriber.setMode(com.homesynapse.event.bus.SubscriberMode.REPLAY);

        // The historical trigger_duration_started event from before the crash.
        var started = new TriggerDurationStartedEvent(autoId, 0, "t1", eventId(),
                EntityId.of(light.value()), Duration.ofMinutes(30).toMillis());
        subscriber.onEvent(envelope("trigger_duration_started", SubjectRef.automation(autoId), started));

        // No timer is started during REPLAY (§3.10).
        assertThat(evaluator.activeDurationTimerCount()).isZero();

        subscriber.onCaughtUp(); // REPLAY→LIVE: re-derive the still-active timer
        assertThat(evaluator.activeDurationTimerCount()).isEqualTo(1);
        assertThat(evaluator.isReplayMode()).isFalse();

        clock.advance(Duration.ofMinutes(30));
        evaluator.pollExpirations();
        assertThat(publisher.countOfType(EventTypes.TRIGGER_DURATION_EXPIRED)).isEqualTo(1);
    }
}

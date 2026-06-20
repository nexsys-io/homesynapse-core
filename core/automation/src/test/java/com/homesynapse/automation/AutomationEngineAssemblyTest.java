/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_CLOCK;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.snapshot;
import static com.homesynapse.automation.AutomationTestSupport.stateChanged;
import static com.homesynapse.automation.AutomationTestSupport.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link AutomationEngineAssembly} public seam — it must produce a
 * working {@code automation_engine} {@link Subscriber} that drives the evaluator
 * and translates the bus REPLAY/LIVE lifecycle onto the evaluator's replay
 * suppression, while keeping the concrete subscriber package-private.
 *
 * <p>Time is injected via {@link AutomationTestSupport#FIXED_CLOCK} (§4c).</p>
 */
@DisplayName("AutomationEngineAssembly — automation_engine subscriber seam")
final class AutomationEngineAssemblyTest {

    private StandardTriggerEvaluator evaluator;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    AutomationEngineAssemblyTest() {
    }

    private StandardTriggerEvaluator newEvaluator() {
        StandardAutomationRegistry registry = new StandardAutomationRegistry();
        StandardSelectorResolver resolver = new StandardSelectorResolver(
                new AutomationTestSupport.StubEntityRegistry(List.of()),
                new AutomationTestSupport.StubAreaRegistry(List.of()),
                new AutomationTestSupport.StubDeviceRegistry());
        return new StandardTriggerEvaluator(
                registry,
                resolver,
                new AutomationTestSupport.StubStateQueryService(snapshot(Map.of())),
                new AutomationTestSupport.RecordingEventPublisher(),
                FIXED_CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (evaluator != null) {
            evaluator.close();
        }
    }

    @Test
    @DisplayName("seam returns a non-null Subscriber")
    void returnsSubscriber() {
        evaluator = newEvaluator();
        Subscriber subscriber = AutomationEngineAssembly.automationEngineSubscriber(evaluator);
        assertThat(subscriber).isNotNull();
    }

    @Test
    @DisplayName("seam rejects a null evaluator")
    void rejectsNullEvaluator() {
        assertThatThrownBy(() -> AutomationEngineAssembly.automationEngineSubscriber(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("setMode translates the bus lifecycle onto evaluator replay suppression")
    void setModeTogglesReplay() {
        evaluator = newEvaluator();
        Subscriber subscriber = AutomationEngineAssembly.automationEngineSubscriber(evaluator);

        subscriber.setMode(SubscriberMode.REPLAY);
        assertThat(evaluator.isReplayMode()).isTrue();

        subscriber.setMode(SubscriberMode.TRANSITION);
        assertThat(evaluator.isReplayMode()).isTrue();

        subscriber.setMode(SubscriberMode.LIVE);
        assertThat(evaluator.isReplayMode()).isFalse();
    }

    @Test
    @DisplayName("onCaughtUp clears replay mode (REPLAY -> LIVE transition)")
    void onCaughtUpClearsReplay() {
        evaluator = newEvaluator();
        Subscriber subscriber = AutomationEngineAssembly.automationEngineSubscriber(evaluator);

        subscriber.setMode(SubscriberMode.REPLAY);
        assertThat(evaluator.isReplayMode()).isTrue();

        subscriber.onCaughtUp();
        assertThat(evaluator.isReplayMode()).isFalse();
    }

    @Test
    @DisplayName("onEvent delegates to the evaluator without error (LIVE and REPLAY)")
    void onEventDelegates() {
        evaluator = newEvaluator();
        Subscriber subscriber = AutomationEngineAssembly.automationEngineSubscriber(evaluator);
        EntityId entity = entityId();
        EventEnvelope event = stateChanged(entity, "power", str("off"), str("on"));

        subscriber.setMode(SubscriberMode.LIVE);
        assertThatCode(() -> subscriber.onEvent(event)).doesNotThrowAnyException();

        subscriber.setMode(SubscriberMode.REPLAY);
        assertThatCode(() -> subscriber.onEvent(event)).doesNotThrowAnyException();
    }
}

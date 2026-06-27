/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_CLOCK;
import static com.homesynapse.automation.AutomationTestSupport.automationId;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.snapshot;
import static com.homesynapse.automation.AutomationTestSupport.stateChanged;
import static com.homesynapse.automation.AutomationTestSupport.str;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.homesynapse.automation.AutomationTestSupport.RecordingRunManager;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The M7.4b trigger&rarr;run handoff contract suite — drives the real {@code automation_engine}
 * {@link Subscriber} (built via the augmented {@link AutomationEngineAssembly} seam) over a real
 * {@link StandardTriggerEvaluator} + {@link StandardAutomationRegistry} and a recording
 * {@link RecordingRunManager}, asserting that a LIVE matched trigger initiates one correct root Run
 * per matched automation, that REPLAY initiates none (D2), and that the derived
 * {@code matchedTriggers}/{@code resolvedTargets} are correct.
 *
 * <p>Time is injected via {@link AutomationTestSupport#FIXED_CLOCK} (§4c).</p>
 */
@DisplayName("Trigger->run handoff (M7.4b run initiation)")
final class RunInitiationTest {

    private final EntityId target = entityId();
    private final RecordingRunManager runManager = new RecordingRunManager();
    private final AutomationTestSupport.FakeSelectorResolver resolver =
            new AutomationTestSupport.FakeSelectorResolver();
    private final StandardAutomationRegistry registry = new StandardAutomationRegistry();
    private final DirectRefSelector targetSelector = new DirectRefSelector(target);

    private StandardTriggerEvaluator evaluator;
    private Subscriber subscriber;

    RunInitiationTest() {
        resolver.bind(targetSelector, Set.of(target));
    }

    @AfterEach
    void tearDown() {
        if (evaluator != null) {
            evaluator.close();
        }
    }

    /** Loads the registry, builds the evaluator chain, and wires the M7.4b subscriber seam. */
    private void wire(List<AutomationDefinition> automations) {
        registry.load(automations);
        evaluator = new StandardTriggerEvaluator(
                registry, resolver,
                new AutomationTestSupport.StubStateQueryService(snapshot(Map.of())),
                new AutomationTestSupport.RecordingEventPublisher(), FIXED_CLOCK);
        subscriber = AutomationEngineAssembly.automationEngineSubscriber(
                evaluator, runManager, registry, resolver);
    }

    /** A single-command automation triggered by {@code triggers}, commanding {@link #targetSelector}. */
    private AutomationDefinition automation(AutomationId id, String slug, int priority,
                                            List<TriggerDefinition> triggers) {
        return new AutomationDefinition(
                id, slug, slug, null, true, ConcurrencyMode.SINGLE, 1,
                MaxExceededSeverity.INFO, priority, triggers, List.of(),
                List.of(new CommandAction(targetSelector, "turn_on", Map.of(),
                        UnavailablePolicy.SKIP)));
    }

    @Test
    @DisplayName("a LIVE event matching N automations initiates N root Runs, in C3 order, with "
            + "correct definition/targets/root chain")
    void liveTrigger_initiatesRootRunPerMatchedAutomation() {
        AutomationId highId = automationId();
        AutomationId lowId = automationId();
        AutomationDefinition high = automation(highId, "high", 10,
                List.of(new EventTrigger("state_changed", Map.of(), "t-high")));
        AutomationDefinition low = automation(lowId, "low", 0,
                List.of(new EventTrigger("state_changed", Map.of(), "t-low")));
        // Load in arbitrary order; the initiator must order by C3 (priority desc).
        wire(List.of(low, high));
        subscriber.setMode(SubscriberMode.LIVE);

        subscriber.onEvent(stateChanged(target, "power", str("off"), str("on")));

        List<RecordingRunManager.InitiateCall> calls = runManager.calls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).automation().automationId()).isEqualTo(highId);
        assertThat(calls.get(1).automation().automationId()).isEqualTo(lowId);
        for (RecordingRunManager.InitiateCall call : calls) {
            assertThat(call.parentChain()).isEqualTo(RunCausalChain.root());
            assertThat(call.matchedTriggers()).containsExactly(0);
            assertThat(call.resolvedTargets()).containsEntry("action:0", Set.of(target));
            assertThat(call.triggeringEvent().eventType()).isEqualTo("state_changed");
        }
    }

    @Test
    @DisplayName("an event delivered in REPLAY initiates zero Runs (D2)")
    void replayTrigger_initiatesNoRun() {
        AutomationId id = automationId();
        wire(List.of(automation(id, "a", 0,
                List.of(new EventTrigger("state_changed", Map.of(), "t0")))));
        subscriber.setMode(SubscriberMode.REPLAY);

        subscriber.onEvent(stateChanged(target, "power", str("off"), str("on")));

        assertThat(runManager.calls()).isEmpty();
    }

    @Test
    @DisplayName("matchedTriggers carries only the matching trigger index and resolvedTargets the "
            + "resolved command-action set")
    void matchedTriggersAndResolvedTargets_areCorrect() {
        AutomationId id = automationId();
        // Trigger 0 watches a different event type (no match); trigger 1 watches state_changed.
        AutomationDefinition auto = automation(id, "a", 0, List.of(
                new EventTrigger("other_event", Map.of(), "t0"),
                new EventTrigger("state_changed", Map.of(), "t1")));
        wire(List.of(auto));
        subscriber.setMode(SubscriberMode.LIVE);

        subscriber.onEvent(stateChanged(target, "power", str("off"), str("on")));

        List<RecordingRunManager.InitiateCall> calls = runManager.calls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).matchedTriggers()).containsExactly(1);
        assertThat(calls.get(0).resolvedTargets())
                .hasSize(1)
                .containsEntry("action:0", Set.of(target));
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_CLOCK;
import static com.homesynapse.automation.AutomationTestSupport.automationId;
import static com.homesynapse.automation.AutomationTestSupport.automationInvoked;
import static com.homesynapse.automation.AutomationTestSupport.deviceAvailabilityChanged;
import static com.homesynapse.automation.AutomationTestSupport.entity;
import static com.homesynapse.automation.AutomationTestSupport.entityAvailabilityChanged;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.snapshot;
import static com.homesynapse.automation.AutomationTestSupport.stateChanged;
import static com.homesynapse.automation.AutomationTestSupport.str;
import static com.homesynapse.automation.AutomationTestSupport.ulid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.Availability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardTriggerEvaluator} immediate-match path: trigger index lookup, the
 * Reachability device-vs-entity routing, Manual invocation, and reload index rebuild.
 *
 * <blockquote><strong>Tests must inject {@code Clock}.</strong> Time is supplied via
 * {@code Clock.fixed(...)}; no wall-clock access appears here.</blockquote>
 */
@DisplayName("StandardTriggerEvaluator — immediate matches")
class TriggerEvaluatorTest {

    private EntityId light;
    private StandardAutomationRegistry registry;
    private StandardTriggerEvaluator evaluator;

    @BeforeEach
    void setUp() {
        light = entityId();
        registry = new StandardAutomationRegistry();
        StandardSelectorResolver resolver = new StandardSelectorResolver(
                new AutomationTestSupport.StubEntityRegistry(List.of(
                        entity(light, "kitchen.light", EntityType.LIGHT, null,
                                EntityRole.PRIMARY, List.of()))),
                new AutomationTestSupport.StubAreaRegistry(List.of()),
                new AutomationTestSupport.StubDeviceRegistry());
        evaluator = new StandardTriggerEvaluator(registry, resolver,
                new AutomationTestSupport.StubStateQueryService(snapshot(Map.of())),
                new AutomationTestSupport.RecordingEventPublisher(), FIXED_CLOCK);
    }

    @AfterEach
    void tearDown() {
        evaluator.close();
    }

    private AutomationDefinition automation(AutomationId id, String slug, TriggerDefinition trigger) {
        return new AutomationDefinition(id, slug, slug, null, true, ConcurrencyMode.SINGLE, 1,
                MaxExceededSeverity.INFO, 0, List.of(trigger), List.of(), List.of());
    }

    @Test
    @DisplayName("a level-triggered state predicate matches the triggering event")
    void stateTriggerMatches() {
        AutomationId id = automationId();
        registry.load(List.of(automation(id, "a", new StateTrigger(new DirectRefSelector(light),
                "on_off", "on", null, "t1"))));

        assertThat(evaluator.evaluate(stateChanged(light, "on_off", str("off"), str("on"))))
                .containsExactly(id);
        assertThat(evaluator.evaluate(stateChanged(light, "on_off", str("on"), str("off"))))
                .isEmpty();
        assertThat(evaluator.evaluate(stateChanged(entityId(), "on_off", str("off"), str("on"))))
                .isEmpty(); // a different entity is not in the selector
    }

    @Test
    @DisplayName("ReachabilityTrigger matches device-subject availability, never entity-subject")
    void reachabilityDeviceVsEntity() {
        AutomationId id = automationId();
        DeviceId device = DeviceId.of(ulid());
        registry.load(List.of(automation(id, "r",
                new ReachabilityTrigger(device, Availability.UNAVAILABLE, null, "tr"))));

        assertThat(evaluator.evaluate(deviceAvailabilityChanged(device, "online", "offline")))
                .containsExactly(id);
        // An entity-subject availability_changed (even with the device's ULID) must not match.
        assertThat(evaluator.evaluate(entityAvailabilityChanged(
                EntityId.of(device.value()), "online", "offline"))).isEmpty();
    }

    @Test
    @DisplayName("ManualTrigger initiates on automation_invoked for its own automation only")
    void manualTrigger() {
        AutomationId id = automationId();
        registry.load(List.of(automation(id, "m", new ManualTrigger("ui", "tm"))));

        assertThat(evaluator.evaluate(automationInvoked(id, "ui"))).containsExactly(id);
        assertThat(evaluator.evaluate(automationInvoked(automationId(), "ui"))).isEmpty();
    }

    @Test
    @DisplayName("the trigger index rebuilds on reload")
    void reloadRebuildsIndex() {
        AutomationId id = automationId();
        registry.load(List.of(automation(id, "a", new StateTrigger(new DirectRefSelector(light),
                "on_off", "on", null, "t1"))));
        assertThat(evaluator.evaluate(stateChanged(light, "on_off", str("off"), str("on"))))
                .containsExactly(id);

        registry.reload(List.of());
        assertThat(evaluator.evaluate(stateChanged(light, "on_off", str("off"), str("on"))))
                .isEmpty();
    }

    @Test
    @DisplayName("a disabled automation does not match")
    void disabledAutomation() {
        AutomationId id = automationId();
        AutomationDefinition disabled = new AutomationDefinition(id, "a", "a", null, false,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(new DirectRefSelector(light), "on_off", "on", null, "t1")),
                List.of(), List.of());
        registry.load(List.of(disabled));

        assertThat(evaluator.evaluate(stateChanged(light, "on_off", str("off"), str("on")))).isEmpty();
    }
}

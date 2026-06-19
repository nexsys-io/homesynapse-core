/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_CLOCK;
import static com.homesynapse.automation.AutomationTestSupport.entity;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.snapshot;
import static com.homesynapse.automation.AutomationTestSupport.state;
import static com.homesynapse.automation.AutomationTestSupport.str;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.Availability;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.IntValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardConditionEvaluator} — Tier-1 conditions, combinator short-circuiting,
 * and AMD-03 single-snapshot evaluation.
 *
 * <blockquote><strong>Tests must inject {@code Clock}.</strong> This module is
 * non-whitelisted; no {@code Clock.systemUTC()}, {@code Instant.now()},
 * {@code System.nanoTime()}, or {@code System.currentTimeMillis()} appears here. Time is
 * supplied via {@code Clock.fixed(...)}, and condition time is read from the snapshot
 * (AMD-03).</blockquote>
 */
@DisplayName("StandardConditionEvaluator (AMD-03)")
class ConditionEvaluatorTest {

    private EntityId light;
    private Selector lightRef;
    private StateSnapshot snapshot;
    private StandardConditionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        light = entityId();
        lightRef = new DirectRefSelector(light);
        Map<String, AttributeValue> attributes = Map.of("on_off", str("on"),
                "brightness", new IntValue(80));
        snapshot = snapshot(Map.of(light, state(light, Availability.AVAILABLE, attributes)));

        StandardSelectorResolver resolver = new StandardSelectorResolver(
                new AutomationTestSupport.StubEntityRegistry(List.of(
                        entity(light, "kitchen.light", EntityType.LIGHT, null,
                                EntityRole.PRIMARY, List.of()))),
                new AutomationTestSupport.StubAreaRegistry(List.of()),
                new AutomationTestSupport.StubDeviceRegistry());
        evaluator = new StandardConditionEvaluator(resolver, FIXED_CLOCK);
    }

    @Test
    @DisplayName("StateCondition matches attribute equality against the snapshot")
    void stateCondition() {
        assertThat(evaluator.evaluate(new StateCondition(lightRef, "on_off", "on"), snapshot)).isTrue();
        assertThat(evaluator.evaluate(new StateCondition(lightRef, "on_off", "off"), snapshot)).isFalse();
    }

    @Test
    @DisplayName("NumericCondition checks above/below bounds")
    void numericCondition() {
        assertThat(evaluator.evaluate(new NumericCondition(lightRef, "brightness", 50.0, null), snapshot))
                .isTrue();
        assertThat(evaluator.evaluate(new NumericCondition(lightRef, "brightness", 90.0, null), snapshot))
                .isFalse();
        assertThat(evaluator.evaluate(new NumericCondition(lightRef, "brightness", null, 100.0), snapshot))
                .isTrue();
    }

    @Test
    @DisplayName("TimeCondition evaluates the snapshot instant in the clock's zone, with wraparound")
    void timeCondition() {
        // Snapshot instant is 2026-01-01T00:00:00Z → 00:00 local (UTC clock).
        assertThat(evaluator.evaluate(new TimeCondition("22:00", "06:00"), snapshot)).isTrue();
        assertThat(evaluator.evaluate(new TimeCondition("01:00", "02:00"), snapshot)).isFalse();
        assertThat(evaluator.evaluate(new TimeCondition(null, "06:00"), snapshot)).isTrue();
    }

    @Test
    @DisplayName("and/or/not combinators short-circuit left-to-right")
    void combinators() {
        var on = new StateCondition(lightRef, "on_off", "on");
        var off = new StateCondition(lightRef, "on_off", "off");

        assertThat(evaluator.evaluate(new AndCondition(List.of(on,
                new NumericCondition(lightRef, "brightness", 50.0, null))), snapshot)).isTrue();
        assertThat(evaluator.evaluate(new AndCondition(List.of(off, on)), snapshot)).isFalse();
        assertThat(evaluator.evaluate(new OrCondition(List.of(off, on)), snapshot)).isTrue();
        assertThat(evaluator.evaluate(new OrCondition(List.of(off)), snapshot)).isFalse();
        assertThat(evaluator.evaluate(new NotCondition(off), snapshot)).isTrue();
    }

    @Test
    @DisplayName("a selector resolving to no entity evaluates to false (Doc 07 §3.12)")
    void nonexistentEntityIsFalse() {
        Selector missing = new TypeSelector("thermostat", java.util.Set.of(EntityRole.PRIMARY));
        assertThat(evaluator.evaluate(new StateCondition(missing, "on_off", "on"), snapshot)).isFalse();
    }
}

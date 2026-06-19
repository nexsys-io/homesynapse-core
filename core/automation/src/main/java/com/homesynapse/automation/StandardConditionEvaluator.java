/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.value.AttributeValue;

/**
 * Production {@link ConditionEvaluator}: evaluates boolean guards against a single
 * {@link StateSnapshot} captured at trigger time (AMD-03; Doc 07 §3.8).
 *
 * <p>Logical combinators short-circuit left-to-right depth-first (critical-review 2.3);
 * the order is stable across restarts and replays. All leaf conditions read from the
 * supplied snapshot — never from a fresh {@code getState()} — so every condition in a
 * Run sees a consistent view (AMD-03). Thread-safe and stateless.</p>
 *
 * <p><strong>Multi-entity semantics.</strong> When a leaf condition's selector resolves
 * to multiple entities, the condition holds iff the resolved set is non-empty and
 * <em>every</em> resolved entity satisfies the predicate (all-of). A selector that
 * resolves to zero entities evaluates to {@code false} (Doc 07 §3.12: a condition
 * referencing a nonexistent entity is false). This all-of choice is a documented M7.1
 * decision where Doc 07 §3.8 is silent on the multi-entity case.</p>
 */
public final class StandardConditionEvaluator implements ConditionEvaluator {

    private final SelectorResolver selectorResolver;
    private final Clock clock;

    /**
     * Constructs a condition evaluator.
     *
     * @param selectorResolver resolves a condition's selector to entities, never {@code null}
     * @param clock            supplies the zone for {@code TimeCondition} interpretation
     *                         (the instant is read from the snapshot, AMD-03); never {@code null}
     */
    public StandardConditionEvaluator(SelectorResolver selectorResolver, Clock clock) {
        this.selectorResolver = Objects.requireNonNull(selectorResolver, "selectorResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean evaluate(ConditionDefinition condition, StateSnapshot snapshot) {
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return switch (condition) {
            case StateCondition state -> evaluateState(state, snapshot);
            case NumericCondition numeric -> evaluateNumeric(numeric, snapshot);
            case TimeCondition time -> evaluateTime(time, snapshot);
            case AndCondition and -> evaluateAnd(and, snapshot);
            case OrCondition or -> evaluateOr(or, snapshot);
            case NotCondition not -> !evaluate(not.condition(), snapshot);
            case ZoneCondition ignored -> throw new UnsupportedOperationException(
                    "ZoneCondition is Tier 2 (presence/zone infrastructure) and must be "
                            + "rejected at definition load, not reached at evaluation time");
        };
    }

    private boolean evaluateState(StateCondition condition, StateSnapshot snapshot) {
        Set<EntityId> targets = selectorResolver.resolve(condition.selector());
        if (targets.isEmpty()) {
            return false;
        }
        for (EntityId target : targets) {
            AttributeValue value = attributeOf(snapshot, target, condition.attribute());
            if (!AttributeValues.stringEquals(value, condition.value())) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateNumeric(NumericCondition condition, StateSnapshot snapshot) {
        Set<EntityId> targets = selectorResolver.resolve(condition.selector());
        if (targets.isEmpty()) {
            return false;
        }
        for (EntityId target : targets) {
            AttributeValue value = attributeOf(snapshot, target, condition.attribute());
            OptionalDouble numeric = AttributeValues.asDouble(value);
            if (numeric.isEmpty() || !withinBounds(numeric.getAsDouble(),
                    condition.above(), condition.below())) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateTime(TimeCondition condition, StateSnapshot snapshot) {
        LocalTime now = LocalTime.ofInstant(snapshot.snapshotTime(), clock.getZone());
        LocalTime after = condition.after() == null ? null : LocalTime.parse(condition.after());
        LocalTime before = condition.before() == null ? null : LocalTime.parse(condition.before());
        if (after == null && before == null) {
            return true; // load validation guarantees at least one bound; defensive true
        }
        if (after == null) {
            return !now.isAfter(before);
        }
        if (before == null) {
            return !now.isBefore(after);
        }
        if (!after.isAfter(before)) {
            // non-wrapping window [after, before]
            return !now.isBefore(after) && !now.isAfter(before);
        }
        // wrapping window, e.g. 22:00–06:00
        return !now.isBefore(after) || !now.isAfter(before);
    }

    private boolean evaluateAnd(AndCondition condition, StateSnapshot snapshot) {
        for (ConditionDefinition child : condition.conditions()) {
            if (!evaluate(child, snapshot)) {
                return false; // short-circuit on first false
            }
        }
        return true;
    }

    private boolean evaluateOr(OrCondition condition, StateSnapshot snapshot) {
        for (ConditionDefinition child : condition.conditions()) {
            if (evaluate(child, snapshot)) {
                return true; // short-circuit on first true
            }
        }
        return false;
    }

    private static boolean withinBounds(double value, Double above, Double below) {
        if (above != null && value <= above) {
            return false;
        }
        return below == null || value < below;
    }

    private static AttributeValue attributeOf(StateSnapshot snapshot, EntityId entityId,
                                              String attribute) {
        EntityState state = snapshot.states().get(entityId);
        return state == null ? null : state.attributes().get(attribute);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;

/**
 * A {@link ComputedValue} that resolves to a bounded fold ({@link AggregateOp}) over the
 * numeric values of one attribute across a resolved entity set, read from the trigger-time
 * snapshot (Doc 16 §3.2).
 *
 * <h2>Why a resolved entity set, not a live {@code Selector}</h2>
 *
 * <p>The operand is a finite, already-resolved {@code Set<EntityId>} — the same posture as
 * {@link RunContext#resolvedTargets()} (selectors resolve once at trigger time, C4). This
 * keeps resolution a pure fold over the single snapshot with <strong>no I/O capability on
 * the type</strong> (C-SA-2) and total over a typed finite input set (INV-TO-02); a live
 * {@code Selector} would require a resolver collaborator (an I/O capability) to resolve. The
 * fold is bounded by {@code entities.size()}; there is no recursion (the operands are
 * {@link AttributeValue}s, not {@link ComputedValue}s).</p>
 *
 * <h2>Skip rule (total, never throws — C-SA-2)</h2>
 *
 * <p>A member absent from the snapshot, or whose attribute is absent or non-numeric, is
 * <em>skipped</em> (it does not contribute and is not counted). Over an empty effective
 * selection, {@link AggregateOp#SUM} yields {@code 0.0} and {@link AggregateOp#COUNT} yields
 * {@code 0} (identity elements); {@link AggregateOp#AVG}/{@link AggregateOp#MIN}/{@link
 * AggregateOp#MAX} are undefined and resolve to the typed-absent sentinel
 * ({@link ComputedValues#absent}). Each rule is pinned in {@code ComputedValueTest}.</p>
 *
 * @param entities  the resolved entity set to fold over (defensively copied), never {@code null}
 * @param attribute the attribute key whose numeric values are folded, never {@code null}
 * @param op        the fold operator, never {@code null}
 * @see ComputedValue
 * @see AggregateOp
 */
record AggregateValue(Set<EntityId> entities, String attribute, AggregateOp op)
        implements ComputedValue {

    /**
     * Validates fields and makes {@code entities} unmodifiable.
     *
     * @throws NullPointerException if any field (or any member of {@code entities}) is
     *                              {@code null}
     */
    AggregateValue {
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
        Objects.requireNonNull(op, "op must not be null");
        entities = Set.copyOf(entities);
    }

    @Override
    public AttributeValue resolve(ComputedValueContext ctx) {
        var states = ctx.snapshot().states();
        long count = 0;
        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (EntityId entityId : entities) {
            var state = states.get(entityId);
            if (state == null) {
                continue;   // member entity absent from the snapshot — skip
            }
            OptionalDouble numeric = AttributeValues.asDouble(state.attributes().get(attribute));
            if (numeric.isEmpty()) {
                continue;   // attribute absent or non-numeric — skip
            }
            double value = numeric.getAsDouble();
            count++;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return switch (op) {
            case COUNT -> new IntValue(count);
            case SUM -> new FloatValue(sum);
            case AVG -> count == 0
                    ? ComputedValues.absent("avg over empty selection: " + attribute)
                    : new FloatValue(sum / count);
            case MIN -> count == 0
                    ? ComputedValues.absent("min over empty selection: " + attribute)
                    : new FloatValue(min);
            case MAX -> count == 0
                    ? ComputedValues.absent("max over empty selection: " + attribute)
                    : new FloatValue(max);
        };
    }
}

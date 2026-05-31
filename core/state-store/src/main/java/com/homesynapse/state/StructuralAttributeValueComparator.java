/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.value.ArrayValue;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.DegradedAttributeValue;
import com.homesynapse.value.EnumValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.QuantityValue;
import com.homesynapse.value.StringValue;

import java.util.List;

/**
 * Structural {@link AttributeValueComparator} — the AMD-51 §2.2 per-variant change-detection
 * semantics (AMD-51-INV-01/02/03).
 *
 * <p>The per-variant dispatch is a single exhaustive {@code switch} over the eight-variant
 * sealed {@link AttributeValue} hierarchy with <strong>no {@code default} arm</strong>: a
 * future ninth permit MUST break compilation rather than slip through a catch-all
 * (AMD-51-INV-01). The switch is the single total decision point — the inbound-Degraded
 * suppression and the prior null/Degraded preamble are folded into it via the per-arm
 * {@link #emit(AttributeValue, boolean)} helper.</p>
 *
 * <p>Stateless, pure (no clock, I/O, randomness, or registry — AMD-50-INV-03), and
 * thread-safe.</p>
 *
 * @see AttributeValueComparator#structural()
 * @since 1.0
 */
final class StructuralAttributeValueComparator implements AttributeValueComparator {

    /**
     * Package-private constructor. Reached only through
     * {@link AttributeValueComparator#structural()}; the comparator is stateless and may be
     * shared freely.
     */
    StructuralAttributeValueComparator() {
        // Stateless; no initialization required.
    }

    @Override
    public boolean changed(AttributeValue prior, AttributeValue inbound,
                           ComparisonPolicy policy) {
        // Exhaustive over the eight AttributeValue variants — NO default (AMD-51-INV-01).
        // The first arm encodes the inbound-side of the asymmetric Degraded rule (§2.4b):
        // an inbound Degraded value is never emitted. Every other arm computes whether the
        // (non-degraded) prior equals the inbound under its variant and defers the prior
        // null/Degraded preamble to emit(...).
        return switch (inbound) {
            case DegradedAttributeValue ignored -> false;
            case BooleanValue iv ->
                    emit(prior, prior instanceof BooleanValue pv && pv.value() == iv.value());
            case IntValue iv ->
                    emit(prior, prior instanceof IntValue pv && pv.value() == iv.value());
            case StringValue iv ->
                    emit(prior, prior instanceof StringValue pv && pv.value().equals(iv.value()));
            case EnumValue iv ->
                    emit(prior, prior instanceof EnumValue pv && pv.value().equals(iv.value()));
            case FloatValue iv ->
                    emit(prior, prior instanceof FloatValue pv
                            && !floatChanged(pv.value(), iv.value(), policy));
            case QuantityValue iv ->
                    emit(prior, prior instanceof QuantityValue pv
                            && quantityUnchanged(pv, iv, policy));
            case ArrayValue iv ->
                    emit(prior, prior instanceof ArrayValue pv
                            && arrayUnchanged(pv, iv, policy));
        };
    }

    /**
     * Folds the prior-side rules for a NON-degraded inbound (§2.4b recovery + first-report):
     * a {@code null} prior (first report) or a {@link DegradedAttributeValue} prior
     * (recovery from a degraded state) ⇒ emit; otherwise emit iff the prior is not equal to
     * the inbound. {@code priorEqualsInbound} is computed by the caller's variant arm and
     * already encodes the type-match check — a variant mismatch yields {@code false} there,
     * so a mismatched non-degraded prior emits.
     *
     * @param prior             the prior canonical value, or {@code null}
     * @param priorEqualsInbound whether the (already type-matched) prior equals the inbound
     * @return {@code true} if a {@code state_changed} should be emitted
     */
    private static boolean emit(AttributeValue prior, boolean priorEqualsInbound) {
        if (prior == null || prior instanceof DegradedAttributeValue) {
            return true;
        }
        return !priorEqualsInbound;
    }

    /**
     * Returns {@code true} if the two {@code QuantityValue}s differ enough to emit.
     * Different canonical unit symbols ⇒ different dimension ⇒ changed (no conversion).
     * Same dimension ⇒ the {@link #floatChanged} epsilon on the canonical magnitudes.
     */
    private static boolean quantityUnchanged(QuantityValue prior, QuantityValue inbound,
                                             ComparisonPolicy policy) {
        if (!prior.unit().equals(inbound.unit())) {
            return false; // different dimension ⇒ changed ⇒ not "unchanged"
        }
        // QuantityValue cannot carry NaN/Inf (its constructor rejects non-finite magnitudes),
        // but floatChanged keeps the IEEE-754 totality checks as defence-in-depth
        // (AMD-51 §2.2 / §10).
        return !floatChanged(prior.value(), inbound.value(), policy);
    }

    /**
     * Returns {@code true} if the two arrays differ enough to emit: size-then-order-sensitive
     * deep compare, recursing the comparator element-by-element under the same {@code policy}
     * (AMD-51-INV-01, full-replacement semantics). Any differing element ⇒ changed.
     */
    private boolean arrayUnchanged(ArrayValue prior, ArrayValue inbound,
                                   ComparisonPolicy policy) {
        List<AttributeValue> priorElements = prior.elements();
        List<AttributeValue> inboundElements = inbound.elements();
        if (priorElements.size() != inboundElements.size()) {
            return false; // size differs ⇒ changed
        }
        for (int i = 0; i < priorElements.size(); i++) {
            if (changed(priorElements.get(i), inboundElements.get(i), policy)) {
                return false; // an element changed ⇒ the array changed
            }
        }
        return true; // same size, no element changed ⇒ unchanged
    }

    /**
     * The pinned total-form floating-point change predicate with explicit IEEE-754 totality
     * (AMD-51 §2.3 / AMD-51-INV-02). Returns {@code true} iff {@code a} and {@code b} differ
     * enough to count as a change.
     *
     * <ul>
     *   <li>{@code -0.0} is canonicalized to {@code +0.0} before compare, so {@code +0.0} and
     *       {@code -0.0} are unchanged.</li>
     *   <li>{@code NaN}↔number ⇒ changed; {@code NaN}↔{@code NaN} ⇒ unchanged.</li>
     *   <li>Same-sign {@code Inf} ⇒ unchanged; opposite-sign {@code Inf} or {@code Inf}↔finite
     *       ⇒ changed. Infinity is handled BEFORE the {@code |a - b|} arithmetic so the
     *       formula never sees {@code Inf - Inf = NaN}.</li>
     *   <li>Both finite ⇒ {@code |a - b| > max(absEps, relEps * max(|a|, |b|))}.</li>
     * </ul>
     */
    private static boolean floatChanged(double a, double b, ComparisonPolicy policy) {
        // Canonicalize signed zero: -0.0 == 0.0 is true, so this maps both to +0.0.
        a = (a == 0.0) ? 0.0 : a;
        b = (b == 0.0) ? 0.0 : b;

        boolean aNaN = Double.isNaN(a);
        boolean bNaN = Double.isNaN(b);
        if (aNaN || bNaN) {
            // NaN vs NaN ⇒ unchanged (false); NaN vs number ⇒ changed (true).
            return aNaN != bNaN;
        }

        boolean aInf = Double.isInfinite(a);
        boolean bInf = Double.isInfinite(b);
        if (aInf || bInf) {
            // Handle infinity before the subtraction: Inf - Inf = NaN would corrupt the
            // formula. Same-sign Inf satisfies a == b ⇒ unchanged; any other Inf pairing
            // (opposite sign, or Inf vs finite) has a != b ⇒ changed.
            return a != b;
        }

        double diff = Math.abs(a - b);
        double tolerance = Math.max(policy.absEps(),
                policy.relEps() * Math.max(Math.abs(a), Math.abs(b)));
        return diff > tolerance;
    }
}

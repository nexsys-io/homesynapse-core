/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StructuralAttributeValueComparator} — the AMD-51 §2.2 per-variant
 * change-detection semantics (AMD-51 §5 tests #1–#4).
 *
 * <p>Pure tests — the comparator has no clock, I/O, or randomness, so no {@code Clock} is
 * injected (and none must be: {@code NO_DIRECT_TIME_ACCESS} scans this non-whitelisted
 * package's test classes).</p>
 */
@DisplayName("StructuralAttributeValueComparator (AMD-51 §2.2)")
class StructuralAttributeValueComparatorTest {

    private final AttributeValueComparator comparator = AttributeValueComparator.structural();
    private final ComparisonPolicy policy = ComparisonPolicy.FP_NOISE_DEFAULT;

    private boolean changed(AttributeValue prior, AttributeValue inbound) {
        return comparator.changed(prior, inbound, policy);
    }

    // ──────────────────────────────────────────────────────────────────
    // #1 — exhaustiveness (runtime proxy for the no-default compile guarantee)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the comparator handles all eight inbound variants (no-default switch is exhaustive)")
    void allEightInboundVariants_handled() {
        // A first report (prior == null) of each non-Degraded variant emits; an inbound
        // Degraded never emits. Exercising all eight arms proves the switch covers the full
        // sealed hierarchy. The no-default property (a ninth permit must break compilation)
        // is a compile-time guarantee verified by the build, not assertable at runtime.
        assertThat(changed(null, new BooleanValue(true))).isTrue();
        assertThat(changed(null, new IntValue(1))).isTrue();
        assertThat(changed(null, new FloatValue(1.0))).isTrue();
        assertThat(changed(null, new StringValue("x"))).isTrue();
        assertThat(changed(null, new EnumValue("ON"))).isTrue();
        assertThat(changed(null, new QuantityValue(1.0, "W"))).isTrue();
        assertThat(changed(null, new ArrayValue(List.of(new IntValue(1))))).isTrue();
        assertThat(changed(null, new DegradedAttributeValue("FloatValue", "x", "parse failure")))
                .as("inbound Degraded ⇒ never emit, even on a first report")
                .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // #2 — exact equality for Boolean/Int/Enum/String
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("boolean: equal ⇒ unchanged, distinct ⇒ changed")
    void boolean_exact() {
        assertThat(changed(new BooleanValue(true), new BooleanValue(true))).isFalse();
        assertThat(changed(new BooleanValue(false), new BooleanValue(true))).isTrue();
    }

    @Test
    @DisplayName("int: equal ⇒ unchanged, distinct ⇒ changed")
    void int_exact() {
        assertThat(changed(new IntValue(42), new IntValue(42))).isFalse();
        assertThat(changed(new IntValue(42), new IntValue(43))).isTrue();
    }

    @Test
    @DisplayName("enum: equal ⇒ unchanged, distinct ⇒ changed")
    void enum_exact() {
        assertThat(changed(new EnumValue("IMPORT"), new EnumValue("IMPORT"))).isFalse();
        assertThat(changed(new EnumValue("IMPORT"), new EnumValue("EXPORT"))).isTrue();
    }

    @Test
    @DisplayName("string: equal ⇒ unchanged, distinct ⇒ changed")
    void string_exact() {
        assertThat(changed(new StringValue("21.0"), new StringValue("21.0"))).isFalse();
        // String compare is exact — "21.0" and "21.00" are distinct strings (the very
        // conflation the typed FLOAT path fixes when a schema is present).
        assertThat(changed(new StringValue("21.0"), new StringValue("21.00"))).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    // #3 — Float / Quantity epsilon + IEEE-754 totality
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("float epsilon: within ⇒ unchanged, above ⇒ changed (1e-9 default)")
    void float_epsilon_withinAndAbove() {
        // |20.0 - 20.0000000001| = 1e-10 < max(1e-9, 1e-9*20) = 2e-8 ⇒ unchanged.
        assertThat(changed(new FloatValue(20.0), new FloatValue(20.0000000001))).isFalse();
        // |20.0 - 20.0000001| = 1e-7 > 2e-8 ⇒ changed.
        assertThat(changed(new FloatValue(20.0), new FloatValue(20.0000001))).isTrue();
    }

    @Test
    @DisplayName("float IEEE-754 totality table (AMD-51 §2.3)")
    void float_ieee_table() {
        double nan = Double.NaN;
        double pInf = Double.POSITIVE_INFINITY;
        double nInf = Double.NEGATIVE_INFINITY;

        // NaN ↔ number ⇒ changed (both directions).
        assertThat(changed(new FloatValue(nan), new FloatValue(1.0))).isTrue();
        assertThat(changed(new FloatValue(1.0), new FloatValue(nan))).isTrue();
        // NaN ↔ NaN ⇒ unchanged.
        assertThat(changed(new FloatValue(nan), new FloatValue(nan))).isFalse();
        // +0.0 ↔ -0.0 ⇒ unchanged (signed-zero canonicalisation).
        assertThat(changed(new FloatValue(0.0), new FloatValue(-0.0))).isFalse();
        // same-sign Inf ⇒ unchanged (Inf - Inf = NaN handled BEFORE the arithmetic).
        assertThat(changed(new FloatValue(pInf), new FloatValue(pInf))).isFalse();
        assertThat(changed(new FloatValue(nInf), new FloatValue(nInf))).isFalse();
        // opposite-sign Inf ⇒ changed.
        assertThat(changed(new FloatValue(pInf), new FloatValue(nInf))).isTrue();
        // Inf ↔ finite ⇒ changed.
        assertThat(changed(new FloatValue(pInf), new FloatValue(1.0))).isTrue();
        assertThat(changed(new FloatValue(1.0), new FloatValue(nInf))).isTrue();
    }

    @Test
    @DisplayName("quantity: same canonical unit ⇒ epsilon on magnitude; differing unit ⇒ changed (no conversion)")
    void quantity_sameAndDifferentDimension() {
        // 21.0 °C vs 294.15 K: the K operand canonicalises to (21.0, "°C") AT CONSTRUCTION,
        // so the comparator sees two °C operands of equal magnitude ⇒ unchanged. The
        // comparator itself performs NO unit conversion.
        QuantityValue celsius = new QuantityValue(21.0, "°C");
        QuantityValue fromKelvin = new QuantityValue(294.15, "K");
        assertThat(fromKelvin.unit()).as("canonicalised at construction").isEqualTo("°C");
        assertThat(changed(celsius, fromKelvin)).isFalse();

        // Same dimension, magnitude beyond epsilon ⇒ changed.
        assertThat(changed(new QuantityValue(21.0, "°C"), new QuantityValue(22.0, "°C"))).isTrue();

        // Different canonical unit symbols ⇒ different dimension ⇒ changed (no conversion).
        assertThat(changed(new QuantityValue(21.0, "°C"), new QuantityValue(21.0, "W"))).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    // #2 — Array size/element/reorder/equal
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("array: size / element / reorder ⇒ changed; identical ⇒ unchanged")
    void array_sizeElementReorderEqual() {
        ArrayValue base = new ArrayValue(List.of(new IntValue(1), new IntValue(2)));

        // identical ⇒ unchanged
        assertThat(changed(base, new ArrayValue(List.of(new IntValue(1), new IntValue(2)))))
                .isFalse();
        // size differs ⇒ changed
        assertThat(changed(base, new ArrayValue(List.of(new IntValue(1))))).isTrue();
        // element differs ⇒ changed
        assertThat(changed(base, new ArrayValue(List.of(new IntValue(1), new IntValue(3)))))
                .isTrue();
        // reorder ⇒ changed (order-sensitive)
        assertThat(changed(base, new ArrayValue(List.of(new IntValue(2), new IntValue(1)))))
                .isTrue();
    }

    @Test
    @DisplayName("array: element-wise epsilon — within-epsilon float elements ⇒ unchanged")
    void array_elementEpsilon() {
        ArrayValue prior = new ArrayValue(List.of(new FloatValue(20.0), new FloatValue(30.0)));
        ArrayValue inbound = new ArrayValue(
                List.of(new FloatValue(20.0000000001), new FloatValue(30.0)));
        assertThat(changed(prior, inbound))
                .as("recursion reuses the policy ⇒ within-epsilon elements are unchanged")
                .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // #4 — Degraded semantics (AMD-51-INV-03)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("degraded: inbound ⇒ never emit; prior-degraded + valid ⇒ recovery emit; two degraded ⇒ unchanged")
    void degraded_inboundRecoveryAndTwoDegraded() {
        DegradedAttributeValue degraded =
                new DegradedAttributeValue("FloatValue", "NaNsnt", "parse failure");

        // inbound Degraded ⇒ never emit (do not overwrite a good value).
        assertThat(changed(new FloatValue(22.5), degraded)).isFalse();
        // prior Degraded + valid inbound ⇒ emit (recovery).
        assertThat(changed(degraded, new FloatValue(22.5))).isTrue();
        // two Degraded ⇒ unchanged (inbound Degraded short-circuits to no-emit).
        assertThat(changed(degraded, degraded)).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // #2 — prior == null + type mismatch
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("prior == null (first report) ⇒ changed for a non-degraded inbound")
    void priorNull_emitsForNonDegraded() {
        assertThat(changed(null, new IntValue(7))).isTrue();
        assertThat(changed(null, new DegradedAttributeValue("IntValue", "?", "bad")))
                .as("first report of a Degraded value still suppresses")
                .isFalse();
    }

    @Test
    @DisplayName("type mismatch between non-degraded prior and inbound ⇒ changed")
    void typeMismatch_changed() {
        assertThat(changed(new IntValue(21), new StringValue("21"))).isTrue();
        assertThat(changed(new StringValue("true"), new BooleanValue(true))).isTrue();
        assertThat(changed(new FloatValue(21.0), new IntValue(21))).isTrue();
    }
}

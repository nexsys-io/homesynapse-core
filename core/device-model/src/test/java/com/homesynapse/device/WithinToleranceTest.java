/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.DegradedAttributeValue;
import com.homesynapse.value.EnumValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.QuantityValue;
import com.homesynapse.value.StringValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WithinTolerance#evaluate} — the ±tolerance confirmation
 * semantics (Doc 02 §3.8: reported numeric value within ±band of target;
 * {@code set_brightness(75)} → {@code brightness ∈ [73, 77]}), realized at M9.3
 * because color-temperature confirmation is impossible without it (the measured
 * Hue ±1-mired re-derivation makes EXACT_MATCH false-fail — AMD-97's TOLERANCE
 * ±50K validation).
 */
@DisplayName("WithinTolerance evaluation")
class WithinToleranceTest {

    @Test
    @DisplayName("confirms a float value inside the ±band, inclusive at both edges")
    void confirmsInsideBandInclusive() {
        WithinTolerance expectation = new WithinTolerance(75.0, 2.0);

        assertThat(expectation.evaluate(new FloatValue(75.0)))
                .isEqualTo(ConfirmationResult.CONFIRMED);
        assertThat(expectation.evaluate(new FloatValue(73.0)))
                .isEqualTo(ConfirmationResult.CONFIRMED);
        assertThat(expectation.evaluate(new FloatValue(77.0)))
                .isEqualTo(ConfirmationResult.CONFIRMED);
    }

    @Test
    @DisplayName("a value outside the band is NOT_YET, never FAILED")
    void outsideBandIsNotYet() {
        WithinTolerance expectation = new WithinTolerance(75.0, 2.0);

        assertThat(expectation.evaluate(new FloatValue(72.9)))
                .isEqualTo(ConfirmationResult.NOT_YET);
        assertThat(expectation.evaluate(new FloatValue(77.1)))
                .isEqualTo(ConfirmationResult.NOT_YET);
    }

    @Test
    @DisplayName("the ±50K color-temperature band (Doc 02 §3.6 default, measured-validated)")
    void colorTemperatureFiftyKelvinBand() {
        // Commanded 153 mireds = 6536 K (canonical); the bulb re-derives ±1 mired
        // (reports 154 mireds = 6494 K). The ±50K band must confirm the drift.
        WithinTolerance expectation = new WithinTolerance(6536.0, 50.0);

        assertThat(expectation.evaluate(new IntValue(6494L)))
                .as("±1-mired drift (6494 K vs commanded 6536 K) is inside ±50K")
                .isEqualTo(ConfirmationResult.CONFIRMED);
        assertThat(expectation.evaluate(new IntValue(6586L)))
                .isEqualTo(ConfirmationResult.CONFIRMED);
        assertThat(expectation.evaluate(new IntValue(6587L)))
                .isEqualTo(ConfirmationResult.NOT_YET);
        assertThat(expectation.evaluate(new IntValue(6485L)))
                .isEqualTo(ConfirmationResult.NOT_YET);
    }

    @Test
    @DisplayName("accepts all three numeric AttributeValue variants")
    void acceptsNumericVariants() {
        WithinTolerance expectation = new WithinTolerance(128.0, 2.0);

        assertThat(expectation.evaluate(new IntValue(128L)))
                .isEqualTo(ConfirmationResult.CONFIRMED);
        assertThat(expectation.evaluate(new FloatValue(129.5)))
                .isEqualTo(ConfirmationResult.CONFIRMED);
        // QuantityValue normalizes to canonical units in its compact constructor;
        // "%" is identity, so the magnitude reaches evaluate() unchanged.
        assertThat(expectation.evaluate(new QuantityValue(126.0, "%")))
                .isEqualTo(ConfirmationResult.CONFIRMED);
    }

    @Test
    @DisplayName("zero tolerance degenerates to exact numeric equality")
    void zeroToleranceIsExact() {
        WithinTolerance expectation = new WithinTolerance(100.0, 0.0);

        assertThat(expectation.evaluate(new IntValue(100L)))
                .isEqualTo(ConfirmationResult.CONFIRMED);
        assertThat(expectation.evaluate(new FloatValue(100.001)))
                .isEqualTo(ConfirmationResult.NOT_YET);
    }

    @Test
    @DisplayName("non-numeric reported values are NOT_YET (the EnumTransition precedent)")
    void nonNumericIsNotYet() {
        WithinTolerance expectation = new WithinTolerance(75.0, 2.0);

        assertThat(expectation.evaluate(new BooleanValue(true)))
                .isEqualTo(ConfirmationResult.NOT_YET);
        assertThat(expectation.evaluate(new StringValue("75")))
                .isEqualTo(ConfirmationResult.NOT_YET);
        assertThat(expectation.evaluate(new EnumValue("BRIGHT")))
                .isEqualTo(ConfirmationResult.NOT_YET);
        assertThat(expectation.evaluate(
                new DegradedAttributeValue("FLOAT", "75", "not coercible")))
                .isEqualTo(ConfirmationResult.NOT_YET);
    }
}

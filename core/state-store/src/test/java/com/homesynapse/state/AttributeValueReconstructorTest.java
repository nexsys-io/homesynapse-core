/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.AttributeType;
import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.BooleanValue;
import com.homesynapse.device.DegradedAttributeValue;
import com.homesynapse.device.EnumValue;
import com.homesynapse.device.FloatValue;
import com.homesynapse.device.IntValue;
import com.homesynapse.device.Permission;
import com.homesynapse.device.QuantityValue;
import com.homesynapse.device.StringValue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AttributeValueReconstructor} — the schema-driven, both-sides parse
 * (AMD-51 §2.6 / AMD-51-INV-05, §5 tests #5 and #5b).
 *
 * <p>Pure tests — the reconstructor has no clock (it logs only), so no {@code Clock} is
 * injected ({@code NO_DIRECT_TIME_ACCESS} scans this non-whitelisted package's tests).</p>
 */
@DisplayName("AttributeValueReconstructor (AMD-51 §2.6)")
class AttributeValueReconstructorTest {

    private final AttributeValueReconstructor reconstructor = new AttributeValueReconstructor();
    private final AttributeValueComparator comparator = AttributeValueComparator.structural();
    private final ComparisonPolicy policy = ComparisonPolicy.FP_NOISE_DEFAULT;

    // ──────────────────────────────────────────────────────────────────
    // Per-type reconstruction
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("primitive types reconstruct into their declared variant")
    void primitiveTypesReconstruct() {
        assertThat(reconstructor.reconstruct("true", null, schema("on", AttributeType.BOOLEAN),
                "on", null)).isEqualTo(new BooleanValue(true));
        assertThat(reconstructor.reconstruct("100", null, schema("brightness", AttributeType.INT),
                "brightness", null)).isEqualTo(new IntValue(100L));
        assertThat(reconstructor.reconstruct("21.5", null, schema("temperature_c", AttributeType.FLOAT),
                "temperature_c", null)).isEqualTo(new FloatValue(21.5));
        assertThat(reconstructor.reconstruct("hello", null, schema("msg", AttributeType.STRING),
                "msg", null)).isEqualTo(new StringValue("hello"));
        assertThat(reconstructor.reconstruct("IMPORT", null, schema("direction", AttributeType.ENUM),
                "direction", null)).isEqualTo(new EnumValue("IMPORT"));
    }

    @Test
    @DisplayName("an un-reconstructable value degrades (no emit), never halts or writes canonical state")
    void unreconstructableValueDegrades() {
        AttributeValue result = reconstructor.reconstruct(
                "not-a-number", null, schema("temperature_c", AttributeType.FLOAT),
                "temperature_c", null);
        assertThat(result).isInstanceOf(DegradedAttributeValue.class);
        // DP-F: a Degraded inbound is suppressed by the comparator (no emit).
        assertThat(comparator.changed(new FloatValue(20.0), result, policy))
                .as("a Degraded inbound never overwrites a good canonical value")
                .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // #5 — both sides reconstruct by the IDENTICAL step (no spurious type mismatch)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a prior StringValue(\"21.5\") + FLOAT schema compares EQUAL to inbound \"21.5\"")
    void bothSidesReconstructEqual_noSpuriousTypeMismatch() {
        AttributeSchema floatSchema = schema("temperature_c", AttributeType.FLOAT);
        // Inbound: the reported String value. Prior: the materialized StringValue's value,
        // reconstructed by the IDENTICAL parse (the prior is always a StringValue, §1.2).
        AttributeValue inbound =
                reconstructor.reconstruct("21.5", null, floatSchema, "temperature_c", null);
        AttributeValue prior = reconstructor.reconstruct(
                "21.5", floatSchema.canonicalUnitSymbol(), floatSchema, "temperature_c", null);

        assertThat(inbound).isEqualTo(new FloatValue(21.5));
        assertThat(prior).isEqualTo(new FloatValue(21.5));
        assertThat(comparator.changed(prior, inbound, policy))
                .as("symmetric reconstruction ⇒ no spurious type-mismatch 'changed' (AMD-51-INV-05)")
                .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // QUANTITY reconstruction — event unit + canonicalUnitSymbol fallback
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("QUANTITY reconstruction uses the event unit and canonicalises at construction")
    void quantityUsesEventUnit() {
        AttributeSchema qSchema = quantitySchema("temp_q", "°C");
        AttributeValue result =
                reconstructor.reconstruct("294.15", "K", qSchema, "temp_q", null);
        // K -> °C is affine, so the magnitude is asserted within tolerance (NOT bit-exact —
        // the M4.B3 affine-conversion lesson); the canonical unit is exact.
        assertThat(result).isInstanceOf(QuantityValue.class);
        QuantityValue quantity = (QuantityValue) result;
        assertThat(quantity.unit()).as("canonicalised to °C").isEqualTo("°C");
        assertThat(quantity.value())
                .as("294.15 K canonicalises to ~21.0 °C")
                .isCloseTo(21.0, within(1e-9));
    }

    @Test
    @DisplayName("QUANTITY reconstruction falls back to canonicalUnitSymbol when the event unit is absent")
    void quantityFallsBackToCanonicalUnit() {
        // The reported value carries no unit; reconstruction falls back to the schema
        // canonical unit (and logs a WARN — the silent-corruption guard, verified by
        // inspection; a captured-appender assertion is intentionally not added here).
        AttributeSchema qSchema = quantitySchema("temp_q", "°C");
        AttributeValue result =
                reconstructor.reconstruct("21.0", null, qSchema, "temp_q", null);
        assertThat(result).isEqualTo(new QuantityValue(21.0, "°C"));
    }

    @Test
    @DisplayName("QUANTITY with no recognised unit at all degrades (no emit)")
    void quantityWithNoRecognisedUnitDegrades() {
        // Neither the reported unit ("furlong") nor the canonical unit ("furlong") is in the
        // QuantityValue catalogue ⇒ reconstruction degrades.
        AttributeSchema qSchema = quantitySchema("odd_q", "furlong");
        AttributeValue result =
                reconstructor.reconstruct("3.0", "furlong", qSchema, "odd_q", null);
        assertThat(result).isInstanceOf(DegradedAttributeValue.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // No schema ⇒ string fallback (preserves the pre-typed behaviour)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no schema ⇒ StringValue fallback (string-compare semantics preserved)")
    void noSchemaStringFallback() {
        AttributeValue inbound = reconstructor.reconstruct("blue", null, null, "color", null);
        AttributeValue prior = reconstructor.reconstruct("blue", null, null, "color", null);
        assertThat(inbound).isEqualTo(new StringValue("blue"));
        assertThat(comparator.changed(prior, inbound, policy))
                .as("identical string values with no schema ⇒ unchanged")
                .isFalse();
        assertThat(comparator.changed(new StringValue("blue"),
                reconstructor.reconstruct("red", null, null, "color", null), policy))
                .as("differing string values with no schema ⇒ changed")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    // #5b — conversion-noise floor (epsilon lock verification)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("QuantityValue conversion re-constructs bit-identically (determinism, AMD-47-INV-03)")
    void quantityConversionIsBitIdentical() {
        long first = Double.doubleToLongBits(new QuantityValue(70.0, "°F").value());
        long second = Double.doubleToLongBits(new QuantityValue(70.0, "°F").value());
        assertThat(second)
                .as("the same (value, inputUnit) yields a bit-identical canonical magnitude")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("°F→°C conversion noise sits below the locked epsilon")
    void fahrenheitConversionNoiseBelowEpsilon() {
        QuantityValue fromFahrenheit = new QuantityValue(70.0, "°F");
        QuantityValue fromCelsius = new QuantityValue((70.0 - 32.0) * 5.0 / 9.0, "°C");
        double noise = Math.abs(fromFahrenheit.value() - fromCelsius.value());
        assertThat(noise)
                .as("worst-case cross-path conversion noise is below absEps (1e-9)")
                .isLessThan(ComparisonPolicy.FP_NOISE_DEFAULT.absEps());
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static AttributeSchema schema(String key, AttributeType type) {
        return new AttributeSchema(
                key, type, null, null, null, null, null, null,
                Set.of(Permission.READ), false, true);
    }

    private static AttributeSchema quantitySchema(String key, String canonicalUnit) {
        return new AttributeSchema(
                key, AttributeType.QUANTITY, null, null, null, null,
                canonicalUnit, canonicalUnit,
                Set.of(Permission.READ), false, true);
    }
}

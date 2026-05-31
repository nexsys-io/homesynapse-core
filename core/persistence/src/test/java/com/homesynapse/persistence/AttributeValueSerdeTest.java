/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the AMD-52 {@link AttributeValueSerializer}/{@link AttributeValueDeserializer}
 * tagged-union codec (AMD-52 §5 tests #1–#4 / AMD-52-INV-02/03/04).
 *
 * <p>Exercises the codec directly through the canonical persistence {@link ObjectMapper}
 * (which registers the pair via {@code PersistenceJacksonModule}) so the same configuration
 * the event-payload and checkpoint surfaces use is under test.</p>
 */
@DisplayName("AttributeValue codec (AMD-52)")
final class AttributeValueSerdeTest {

    /** The production persistence mapper — registers the AttributeValue serde pair. */
    private final ObjectMapper mapper = PersistenceObjectMapper.create();

    AttributeValueSerdeTest() {
        // Explicit constructor for -Xlint:all -Werror.
    }

    private AttributeValue roundTrip(AttributeValue value) throws JsonProcessingException {
        String json = mapper.writeValueAsString(value);
        return mapper.readValue(json, AttributeValue.class);
    }

    private String json(AttributeValue value) throws JsonProcessingException {
        return mapper.writeValueAsString(value);
    }

    // ──────────────────────────────────────────────────────────────────
    // #1 — round-trip, all eight variants + ArrayValue recursion/order
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("#1 round-trip across all eight variants (INV-02)")
    class RoundTrip {

        @Test
        void booleanValue() throws Exception {
            assertThat(roundTrip(new BooleanValue(true))).isEqualTo(new BooleanValue(true));
            assertThat(roundTrip(new BooleanValue(false))).isEqualTo(new BooleanValue(false));
        }

        @Test
        void intValue() throws Exception {
            assertThat(roundTrip(new IntValue(42L))).isEqualTo(new IntValue(42L));
            assertThat(roundTrip(new IntValue(Long.MIN_VALUE)))
                    .isEqualTo(new IntValue(Long.MIN_VALUE));
            assertThat(roundTrip(new IntValue(Long.MAX_VALUE)))
                    .isEqualTo(new IntValue(Long.MAX_VALUE));
        }

        @Test
        void floatValue() throws Exception {
            assertThat(roundTrip(new FloatValue(21.5))).isEqualTo(new FloatValue(21.5));
        }

        @Test
        void stringValue() throws Exception {
            assertThat(roundTrip(new StringValue("hello world")))
                    .isEqualTo(new StringValue("hello world"));
        }

        @Test
        void enumValue() throws Exception {
            assertThat(roundTrip(new EnumValue("HEATING"))).isEqualTo(new EnumValue("HEATING"));
        }

        @Test
        void quantityValue() throws Exception {
            QuantityValue q = new QuantityValue(21.0, "°C");
            assertThat(roundTrip(q)).isEqualTo(q);
        }

        @Test
        @DisplayName("QuantityValue carries its canonical unit in the 'u' field")
        void quantityValue_envelopeShape() throws Exception {
            String j = json(new QuantityValue(21.0, "°C"));
            assertThat(j).contains("\"t\":\"QUANTITY\"");
            assertThat(j).contains("\"u\":\"°C\"");
        }

        @Test
        @DisplayName("a non-canonical unit canonicalizes at construction and round-trips canonical")
        void quantityValue_nonCanonicalUnit_roundTripsCanonical() throws Exception {
            QuantityValue fahrenheit = new QuantityValue(212.0, "°F"); // -> 100.0 °C
            assertThat(fahrenheit.unit()).isEqualTo("°C");
            assertThat(roundTrip(fahrenheit)).isEqualTo(fahrenheit);
        }

        @Test
        void degradedAttributeValue() throws Exception {
            DegradedAttributeValue d = new DegradedAttributeValue(
                    "FloatValue", "not-a-number", "value 'not-a-number' is not a valid FLOAT");
            assertThat(roundTrip(d)).isEqualTo(d);
        }

        @Test
        @DisplayName("ArrayValue recurses the envelope per element and preserves order")
        void arrayValue_orderedMixedVariants() throws Exception {
            ArrayValue array = new ArrayValue(List.of(
                    new IntValue(1),
                    new StringValue("two"),
                    new FloatValue(3.5),
                    new BooleanValue(true)));
            assertThat(roundTrip(array)).isEqualTo(array);
        }

        @Test
        @DisplayName("nested ArrayValue of ArrayValues round-trips")
        void arrayValue_nested() throws Exception {
            ArrayValue nested = new ArrayValue(List.of(
                    new ArrayValue(List.of(new IntValue(1), new IntValue(2))),
                    new ArrayValue(List.of(new StringValue("a"), new EnumValue("B")))));
            assertThat(roundTrip(nested)).isEqualTo(nested);
        }

        @Test
        @DisplayName("empty ArrayValue round-trips")
        void arrayValue_empty() throws Exception {
            ArrayValue empty = new ArrayValue(List.of());
            assertThat(roundTrip(empty)).isEqualTo(empty);
        }

        @Test
        @DisplayName("the envelope carries the explicit 't' AttributeType discriminator")
        void envelope_carriesTypeDiscriminator() throws Exception {
            assertThat(json(new FloatValue(1.0))).contains("\"t\":\"FLOAT\"");
            assertThat(json(new BooleanValue(true))).contains("\"t\":\"BOOLEAN\"");
            assertThat(json(new IntValue(1L))).contains("\"t\":\"INT\"");
            assertThat(json(new EnumValue("X"))).contains("\"t\":\"ENUM\"");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // #2 — exhaustiveness guard (a 9th permit must force a codec review)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#2 AttributeValue seals exactly 8 variants — a 9th permit forces a codec review (INV-02)")
    void sealedHierarchyHasExactlyEightPermits() {
        // The serializer/deserializer dispatch is an exhaustive switch with NO default, so a
        // 9th permit breaks compilation. This guard makes the count assertion explicit: if it
        // fails, the codec's switch arms must be revisited.
        assertThat(AttributeValue.class.getPermittedSubclasses())
                .as("AttributeValue must seal exactly 8 variants (codec switch is total, no default)")
                .hasSize(8);
    }

    // ──────────────────────────────────────────────────────────────────
    // #3 — float bit-identity over a round-trippable text rendering (INV-03)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("#3 float bit-identity (INV-03)")
    class FloatBitIdentity {

        // A corpus including a value that historically rendered differently across the
        // JDK-18->19 Schubfach change (JDK-8202555), a subnormal, and large/small magnitudes.
        private final double[] corpus = {
                1e23,
                Double.MIN_VALUE,          // smallest subnormal
                Double.MIN_NORMAL,
                0.1,
                1.1,
                4.35,
                9.999999999999999,
                Math.PI,
                -2.5e-300,
                1.0e308,
                Double.MAX_VALUE,
                123456.789,
        };

        @Test
        @DisplayName("identity is over the bits, not the text — every corpus value is bit-identical post round-trip")
        void corpus_isBitIdenticalAfterRoundTrip() throws Exception {
            for (double x : corpus) {
                FloatValue original = new FloatValue(x);
                FloatValue decoded = (FloatValue) roundTrip(original);
                assertThat(Double.doubleToLongBits(decoded.value()))
                        .as("bit-identity for %s", x)
                        .isEqualTo(Double.doubleToLongBits(x));
            }
        }

        @Test
        @DisplayName("the stored text is round-trippable: parseDouble(render(x)) recovers the same bits")
        void storedText_isRoundTrippable() throws Exception {
            for (double x : corpus) {
                String j = json(new FloatValue(x));
                // The envelope is {"t":"FLOAT","v":<number>}; extract the number text after "v":.
                int idx = j.indexOf("\"v\":");
                String numberText = j.substring(idx + 4, j.indexOf('}', idx));
                assertThat(Double.doubleToLongBits(Double.parseDouble(numberText)))
                        .as("round-trippable text for %s -> '%s'", x, numberText)
                        .isEqualTo(Double.doubleToLongBits(x));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // #4 — non-finite sentinels + signed-zero canonicalization (INV-04)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("#4 non-finite sentinels + signed zero (INV-04)")
    class NonFiniteSentinels {

        @Test
        @DisplayName("NaN round-trips via the \"NaN\" sentinel string")
        void nan_roundTripsViaSentinel() throws Exception {
            String j = json(new FloatValue(Double.NaN));
            assertThat(j).contains("\"v\":\"NaN\"");

            FloatValue decoded = (FloatValue) roundTrip(new FloatValue(Double.NaN));
            assertThat(Double.isNaN(decoded.value())).isTrue();
            assertThat(Double.doubleToLongBits(decoded.value()))
                    .as("collapses to the canonical NaN bit pattern")
                    .isEqualTo(Double.doubleToLongBits(Double.NaN));
        }

        @Test
        @DisplayName("+Inf and -Inf round-trip via sentinel strings")
        void infinities_roundTripViaSentinels() throws Exception {
            assertThat(json(new FloatValue(Double.POSITIVE_INFINITY))).contains("\"v\":\"+Inf\"");
            assertThat(json(new FloatValue(Double.NEGATIVE_INFINITY))).contains("\"v\":\"-Inf\"");

            assertThat(((FloatValue) roundTrip(new FloatValue(Double.POSITIVE_INFINITY))).value())
                    .isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(((FloatValue) roundTrip(new FloatValue(Double.NEGATIVE_INFINITY))).value())
                    .isEqualTo(Double.NEGATIVE_INFINITY);
        }

        @Test
        @DisplayName("the serializer never emits a bare NaN/Infinity token")
        void noBareNonNumericTokens() throws Exception {
            assertThat(json(new FloatValue(Double.NaN))).doesNotContain(":NaN").doesNotContain(",NaN");
            assertThat(json(new FloatValue(Double.POSITIVE_INFINITY)))
                    .doesNotContain("Infinity").doesNotContain(":Inf");
        }

        @Test
        @DisplayName("the decoder rejects an unknown sentinel token")
        void unknownSentinel_isRejected() {
            String bad = "{\"t\":\"FLOAT\",\"v\":\"Inf\"}"; // not one of NaN/+Inf/-Inf
            assertThatThrownBy(() -> mapper.readValue(bad, AttributeValue.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("-0.0 serializes as +0.0 (coherent with the AMD-51 comparator)")
        void negativeZero_serializesAsPositiveZero() throws Exception {
            String j = json(new FloatValue(-0.0));
            assertThat(j).doesNotContain("-0.0");

            FloatValue decoded = (FloatValue) roundTrip(new FloatValue(-0.0));
            assertThat(Double.doubleToLongBits(decoded.value()))
                    .as("-0.0 canonicalizes to +0.0 (bits == 0)")
                    .isEqualTo(Double.doubleToLongBits(0.0));
        }

        @Test
        @DisplayName("QuantityValue cannot carry non-finite — construction is rejected (no sentinel path)")
        void quantityValue_rejectsNonFinite() {
            assertThatThrownBy(() -> new QuantityValue(Double.NaN, "°C"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new QuantityValue(Double.POSITIVE_INFINITY, "W"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // strict-decode failure modes
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("strict decode failures")
    class StrictDecode {

        @Test
        @DisplayName("a missing 't' discriminator is rejected")
        void missingType_rejected() {
            assertThatThrownBy(() -> mapper.readValue("{\"v\":1}", AttributeValue.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("an unknown 't' discriminator is rejected")
        void unknownType_rejected() {
            assertThatThrownBy(() ->
                    mapper.readValue("{\"t\":\"WIDGET\",\"v\":1}", AttributeValue.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("a malformed 'v' for an INT (textual) is rejected")
        void malformedIntValue_rejected() {
            assertThatThrownBy(() ->
                    mapper.readValue("{\"t\":\"INT\",\"v\":\"oops\"}", AttributeValue.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("a QUANTITY with an unrecognised unit is rejected")
        void quantityUnknownUnit_rejected() {
            assertThatThrownBy(() ->
                    mapper.readValue("{\"t\":\"QUANTITY\",\"v\":1.0,\"u\":\"furlong\"}",
                            AttributeValue.class))
                    .isInstanceOf(JsonProcessingException.class);
        }
    }
}

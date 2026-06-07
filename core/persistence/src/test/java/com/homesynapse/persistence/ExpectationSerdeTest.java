/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.device.AnyChange;
import com.homesynapse.device.EnumTransition;
import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.Expectation;
import com.homesynapse.device.WithinTolerance;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.EnumValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.QuantityValue;
import com.homesynapse.value.StringValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the AMD-87 {@link ExpectationSerializer}/{@link ExpectationDeserializer}
 * tagged-union codec (AMD-87 §2 / AMD-87-INV-01).
 *
 * <p>Exercises the codec directly through the canonical persistence {@link ObjectMapper}
 * (which registers the pair via {@code PersistenceJacksonModule}) so the same configuration
 * the event-payload surface uses is under test. The acceptance test
 * ({@code EventPayloadCodecTest.capabilityAdded_onOff_roundTrips}) covers the full
 * command-bearing {@code CapabilityAdded} round-trip; this suite isolates the four permits.</p>
 */
@DisplayName("Expectation codec (AMD-87)")
final class ExpectationSerdeTest {

    /** The production persistence mapper — registers the Expectation serde pair. */
    private final ObjectMapper mapper = PersistenceObjectMapper.create();

    ExpectationSerdeTest() {
        // Explicit constructor for -Xlint:all -Werror.
    }

    private Expectation roundTrip(Expectation value) throws JsonProcessingException {
        String json = mapper.writeValueAsString(value);
        return mapper.readValue(json, Expectation.class);
    }

    private String json(Expectation value) throws JsonProcessingException {
        return mapper.writeValueAsString(value);
    }

    // ──────────────────────────────────────────────────────────────────
    // #1 — per-permit round-trip; ExactMatch/AnyChange delegate to the AttributeValue codec
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("#1 per-permit round-trip (INV-01)")
    class RoundTrip {

        @Test
        @DisplayName("ExactMatch round-trips across representative AttributeValue variants")
        void exactMatch_acrossVariants() throws Exception {
            assertThat(roundTrip(new ExactMatch(new BooleanValue(true))))
                    .isEqualTo(new ExactMatch(new BooleanValue(true)));
            assertThat(roundTrip(new ExactMatch(new IntValue(42L))))
                    .isEqualTo(new ExactMatch(new IntValue(42L)));
            assertThat(roundTrip(new ExactMatch(new FloatValue(21.5))))
                    .isEqualTo(new ExactMatch(new FloatValue(21.5)));
            assertThat(roundTrip(new ExactMatch(new StringValue("hello"))))
                    .isEqualTo(new ExactMatch(new StringValue("hello")));
        }

        @Test
        @DisplayName("ExactMatch wrapping a QuantityValue carries the delegated 'u' unit field")
        void exactMatch_quantityValue() throws Exception {
            ExactMatch original = new ExactMatch(new QuantityValue(21.0, "°C"));
            assertThat(roundTrip(original)).isEqualTo(original);
        }

        @Test
        @DisplayName("AnyChange round-trips wrapping an AttributeValue")
        void anyChange() throws Exception {
            assertThat(roundTrip(new AnyChange(new BooleanValue(false))))
                    .isEqualTo(new AnyChange(new BooleanValue(false)));
            assertThat(roundTrip(new AnyChange(new EnumValue("HEATING"))))
                    .isEqualTo(new AnyChange(new EnumValue("HEATING")));
        }

        @Test
        @DisplayName("EnumTransition round-trips a plain string")
        void enumTransition() throws Exception {
            assertThat(roundTrip(new EnumTransition("COOLING")))
                    .isEqualTo(new EnumTransition("COOLING"));
        }

        @Test
        @DisplayName("WithinTolerance round-trips its two doubles")
        void withinTolerance() throws Exception {
            assertThat(roundTrip(new WithinTolerance(0.1, 0.05)))
                    .isEqualTo(new WithinTolerance(0.1, 0.05));
        }

        @Test
        @DisplayName("the envelope carries the permit-simple-name 't' discriminator")
        void envelope_carriesDiscriminator() throws Exception {
            assertThat(json(new ExactMatch(new BooleanValue(true))))
                    .contains("\"t\":\"ExactMatch\"");
            assertThat(json(new AnyChange(new BooleanValue(true))))
                    .contains("\"t\":\"AnyChange\"");
            assertThat(json(new EnumTransition("X"))).contains("\"t\":\"EnumTransition\"");
            assertThat(json(new WithinTolerance(1.0, 0.1)))
                    .contains("\"t\":\"WithinTolerance\"");
        }

        @Test
        @DisplayName("ExactMatch delegates 'v' to the nested AttributeValue envelope, not a re-encode")
        void exactMatch_nestsAttributeValueEnvelope() throws Exception {
            String j = json(new ExactMatch(new BooleanValue(true)));
            assertThat(j).contains("\"v\":{\"t\":\"BOOLEAN\",\"v\":true}");
        }

        @Test
        @DisplayName("EnumTransition's 'v' is a plain JSON string")
        void enumTransition_plainStringValue() throws Exception {
            assertThat(json(new EnumTransition("HEATING"))).contains("\"v\":\"HEATING\"");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // #2 — exhaustiveness guard (a 5th permit must force a codec review)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#2 Expectation seals exactly 4 permits — a 5th forces a codec review (INV-01)")
    void sealedHierarchyHasExactlyFourPermits() {
        // The serializer dispatch is an exhaustive switch with NO default, so a 5th permit
        // breaks compilation. This guard makes the count assertion explicit: if it fails, the
        // codec's switch arms must be revisited.
        assertThat(Expectation.class.getPermittedSubclasses())
                .as("Expectation must seal exactly 4 permits (codec switch is total, no default)")
                .hasSize(4);
    }

    // ──────────────────────────────────────────────────────────────────
    // #3 — WithinTolerance float bit-identity (AMD-52 reuse, AMD-87-INV-01)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("#3 WithinTolerance bit-identity (AMD-52 reuse)")
    class WithinToleranceBitIdentity {

        private WithinTolerance decode(double target, double tolerance) throws Exception {
            return (WithinTolerance) roundTrip(new WithinTolerance(target, tolerance));
        }

        @Test
        @DisplayName("finite doubles (0.1 / 0.05) survive encode→decode bit-identically")
        void finite_isBitIdentical() throws Exception {
            WithinTolerance decoded = decode(0.1, 0.05);
            assertThat(Double.doubleToLongBits(decoded.target()))
                    .isEqualTo(Double.doubleToLongBits(0.1));
            assertThat(Double.doubleToLongBits(decoded.tolerance()))
                    .isEqualTo(Double.doubleToLongBits(0.05));
        }

        @Test
        @DisplayName("NaN round-trips via the \"NaN\" sentinel (canonical NaN bits)")
        void nan_roundTripsViaSentinel() throws Exception {
            assertThat(json(new WithinTolerance(Double.NaN, 1.0))).contains("\"target\":\"NaN\"");

            WithinTolerance decoded = decode(Double.NaN, 1.0);
            assertThat(Double.isNaN(decoded.target())).isTrue();
            assertThat(Double.doubleToLongBits(decoded.target()))
                    .isEqualTo(Double.doubleToLongBits(Double.NaN));
        }

        @Test
        @DisplayName("+Inf and -Inf round-trip via sentinel strings")
        void infinities_roundTripViaSentinels() throws Exception {
            String j = json(new WithinTolerance(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
            assertThat(j).contains("\"target\":\"+Inf\"").contains("\"tolerance\":\"-Inf\"");

            WithinTolerance decoded =
                    decode(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
            assertThat(decoded.target()).isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(decoded.tolerance()).isEqualTo(Double.NEGATIVE_INFINITY);
        }

        @Test
        @DisplayName("-0.0 canonicalizes to +0.0 (bits == 0) on both magnitudes")
        void negativeZero_canonicalizesToPositiveZero() throws Exception {
            assertThat(json(new WithinTolerance(-0.0, -0.0))).doesNotContain("-0.0");

            WithinTolerance decoded = decode(-0.0, -0.0);
            assertThat(Double.doubleToLongBits(decoded.target()))
                    .as("-0.0 target canonicalizes to +0.0")
                    .isEqualTo(Double.doubleToLongBits(0.0));
            assertThat(Double.doubleToLongBits(decoded.tolerance()))
                    .as("-0.0 tolerance canonicalizes to +0.0")
                    .isEqualTo(Double.doubleToLongBits(0.0));
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
            assertThatThrownBy(() ->
                    mapper.readValue("{\"v\":{\"t\":\"BOOLEAN\",\"v\":true}}", Expectation.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("an unknown 't' discriminator is rejected")
        void unknownType_rejected() {
            assertThatThrownBy(() ->
                    mapper.readValue("{\"t\":\"Widget\",\"v\":1}", Expectation.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("an ExactMatch with no 'v' payload is rejected")
        void exactMatchMissingValue_rejected() {
            assertThatThrownBy(() ->
                    mapper.readValue("{\"t\":\"ExactMatch\"}", Expectation.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("an EnumTransition with a non-textual 'v' is rejected")
        void enumTransitionNonTextual_rejected() {
            assertThatThrownBy(() ->
                    mapper.readValue("{\"t\":\"EnumTransition\",\"v\":5}", Expectation.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("a WithinTolerance missing 'target' is rejected")
        void withinToleranceMissingTarget_rejected() {
            assertThatThrownBy(() ->
                    mapper.readValue("{\"t\":\"WithinTolerance\",\"tolerance\":1.0}",
                            Expectation.class))
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("a WithinTolerance with an unknown non-finite sentinel is rejected")
        void withinToleranceBadSentinel_rejected() {
            assertThatThrownBy(() ->
                    mapper.readValue("{\"t\":\"WithinTolerance\",\"target\":\"Inf\",\"tolerance\":1.0}",
                            Expectation.class))
                    .isInstanceOf(JsonProcessingException.class);
        }
    }
}

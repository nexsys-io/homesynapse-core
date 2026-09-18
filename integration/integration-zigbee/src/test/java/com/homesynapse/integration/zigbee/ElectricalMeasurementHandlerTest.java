/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ElectricalMeasurementHandler} (0x0B04, ENERGY-READ R4 / T5):
 * {@code ActivePower} (int16) → {@code power_w}, {@code RMSVoltage} /
 * {@code RMSCurrent} (uint16) → {@code voltage_v} / {@code current_a}, each
 * scaled by the pair the DEVICE declared ({@code raw × multiplier / divisor})
 * with the raw value and the formatting riding beside the derived value. The
 * fixture replays divisors 1, 10 and 100 (DEVICE-SET note 3) — the owned
 * Gen4's own value is UNREAD, and no test here predicts it.
 */
@DisplayName("ElectricalMeasurementHandler — 0x0B04 → power_w / voltage_v / "
        + "current_a, scaled by the READ formatting (ENERGY-READ R4)")
class ElectricalMeasurementHandlerTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x00124B00AA0000E1L);

    private TestClock clock;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ElectricalMeasurementHandlerTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
    }

    /** A formatting with ONLY the ActivePower pair read. */
    private static MeteringFormatting power(int multiplier, int divisor) {
        return new MeteringFormatting(multiplier, divisor, 0, 0, 0, 0,
                null, null, null);
    }

    private List<NormalizedAttribute> normalize(MeteringFormatting formatting,
            Map<Integer, Object> attributes) {
        return new ElectricalMeasurementHandler(DEVICE, clock, formatting)
                .normalize(1, ElectricalMeasurementHandler.CLUSTER_ID, attributes);
    }

    @Test
    @DisplayName("ActivePower 8000 raw scales by the read divisor: ÷1 → 8000 W, "
            + "÷10 → 800 W, ÷100 → 80 W — raw and formatting retained")
    void activePowerScalesByTheReadDivisor() {
        assertThat(normalize(power(1, 1), Map.of(0x050B, 8000L)).get(0).value())
                .isEqualTo(8000.0);
        assertThat(normalize(power(1, 10), Map.of(0x050B, 8000L)).get(0).value())
                .isEqualTo(800.0);

        List<NormalizedAttribute> reports =
                normalize(power(1, 100), Map.of(0x050B, 8000L));

        assertThat(reports).hasSize(1);
        NormalizedAttribute report = reports.get(0);
        assertThat(report.attributeKey()).isEqualTo("power_w");
        assertThat(report.value()).isEqualTo(80.0);
        assertThat(report.unit()).isEqualTo("W");
        assertThat(report.rawProtocolValue())
                .as("the observation rides beside the derived value")
                .isEqualTo("8000");
        assertThat(report.rawProtocolUnit()).isEqualTo("mult=1 div=100");
    }

    @Test
    @DisplayName("the multiplier is applied too: raw × mult / div (8000 × 5 / 100 "
            + "= 400 W)")
    void multiplierApplies() {
        NormalizedAttribute report =
                normalize(power(5, 100), Map.of(0x050B, 8000L)).get(0);

        assertThat(report.value()).isEqualTo(400.0);
        assertThat(report.rawProtocolUnit()).isEqualTo("mult=5 div=100");
    }

    @Test
    @DisplayName("negative ActivePower (the codec sign-extends int16) is a legal "
            + "reading — export/backfeed scales like any other")
    void negativeActivePowerScales() {
        assertThat(normalize(power(1, 10), Map.of(0x050B, -150L)).get(0).value())
                .isEqualTo(-15.0);
    }

    @Test
    @DisplayName("the ActivePower invalid sentinel (wire 0x8000, sign-extended "
            + "−32768) emits NOTHING — honest absence, never a fabricated reading")
    void activePowerInvalidSentinelNeverObserves() {
        assertThat(normalize(power(1, 100), Map.of(0x050B, -32768L))).isEmpty();
    }

    @Test
    @DisplayName("an ActivePower outside the int16 band emits nothing — the "
            + "mis-typed-record guard (F-9)")
    void activePowerOutOfBandSkipped() {
        assertThat(normalize(power(1, 100), Map.of(0x050B, 32768L))).isEmpty();
        assertThat(normalize(power(1, 100), Map.of(0x050B, -40000L))).isEmpty();
    }

    @Test
    @DisplayName("RMSVoltage and RMSCurrent scale by THEIR OWN pairs: 1203 ÷ 10 → "
            + "120.3 V, 667 ÷ 1000 → 0.667 A — one report, three observations, "
            + "power first")
    void voltageAndCurrentScaleByTheirOwnFormatting() {
        MeteringFormatting formatting = new MeteringFormatting(1, 100, 1, 10,
                1, 1000, null, null, null);
        Map<Integer, Object> attributes = new LinkedHashMap<>();
        attributes.put(0x0508, 667L);
        attributes.put(0x0505, 1203L);
        attributes.put(0x050B, 8000L);

        List<NormalizedAttribute> reports = normalize(formatting, attributes);

        assertThat(reports).extracting(NormalizedAttribute::attributeKey)
                .containsExactly("power_w", "voltage_v", "current_a");
        assertThat(reports.get(1).value()).isEqualTo(120.3);
        assertThat(reports.get(1).unit()).isEqualTo("V");
        assertThat(reports.get(1).rawProtocolValue()).isEqualTo("1203");
        assertThat(reports.get(1).rawProtocolUnit()).isEqualTo("mult=1 div=10");
        assertThat(reports.get(2).value()).isEqualTo(0.667);
        assertThat(reports.get(2).unit()).isEqualTo("A");
        assertThat(reports.get(2).rawProtocolUnit()).isEqualTo("mult=1 div=1000");
    }

    @Test
    @DisplayName("a voltage/current whose OWN pair was not read emits nothing — "
            + "the power pair is never borrowed (no guessed scale)")
    void voltageWithoutItsOwnFormattingIsSilent() {
        Map<Integer, Object> attributes = new LinkedHashMap<>();
        attributes.put(0x0505, 1203L);
        attributes.put(0x0508, 667L);

        assertThat(normalize(power(1, 100), attributes)).isEmpty();
    }

    @Test
    @DisplayName("the uint16 invalid sentinel (0xFFFF) on RMSVoltage/RMSCurrent "
            + "emits nothing")
    void voltageAndCurrentInvalidSentinelNeverObserve() {
        MeteringFormatting formatting = new MeteringFormatting(1, 100, 1, 10,
                1, 1000, null, null, null);

        assertThat(normalize(formatting, Map.of(0x0505, 0xFFFFL))).isEmpty();
        assertThat(normalize(formatting, Map.of(0x0508, 0xFFFFL))).isEmpty();
        assertThat(normalize(formatting, Map.of(0x0505, -1L))).isEmpty();
    }

    @Test
    @DisplayName("every emitted key IS a powerMeter schema key — never an invented "
            + "key (the device model declares them; this lane declares none)")
    void emittedKeysMatchTheCapabilitySchema() {
        MeteringFormatting formatting = new MeteringFormatting(1, 100, 1, 10,
                1, 1000, null, null, null);
        Map<Integer, Object> attributes = new LinkedHashMap<>();
        attributes.put(0x050B, 8000L);
        attributes.put(0x0505, 1203L);
        attributes.put(0x0508, 667L);

        List<NormalizedAttribute> reports = normalize(formatting, attributes);

        assertThat(reports).hasSize(3);
        for (NormalizedAttribute report : reports) {
            assertThat(StandardCapabilities.powerMeter().attributeSchemas())
                    .containsKey(report.attributeKey());
        }
    }

    @Test
    @DisplayName("an attribute the handler does not map → empty; a malformed "
            + "payload type → empty, never a throw")
    void unmappedAndMalformedEmitNothing() {
        assertThat(normalize(power(1, 100), Map.of(0x050E, 12L))).isEmpty();
        assertThat(normalize(power(1, 100), Map.of(0x050B, "eighty"))).isEmpty();
        assertThat(normalize(power(1, 100), Map.of(0x050B, Boolean.TRUE)))
                .isEmpty();
    }

    @Test
    @DisplayName("constructed WITH a formatting — never without")
    void constructionRequiresAFormatting() {
        assertThatThrownBy(() -> new ElectricalMeasurementHandler(DEVICE, clock,
                null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the formatting read is ONE frame of six attributes, the power "
            + "pair first (the BENCH-VERIFY ids, bound by code and test together)")
    void formattingAttributesAreTheSixAcPairs() {
        assertThat(ElectricalMeasurementHandler.formattingAttributes())
                .containsExactly(0x0604, 0x0605, 0x0600, 0x0601, 0x0602, 0x0603);
    }
}

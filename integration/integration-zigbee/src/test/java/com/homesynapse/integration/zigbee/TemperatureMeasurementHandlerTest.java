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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TemperatureMeasurementHandler} (0x0402, M9.7-W2 §2): measuredValue
 * (Int16, 0.01 °C) → the canonical {@code temperature_c} float, raw retained.
 * The codec's int16 decode sign-extends, so the handler's inputs — including
 * the 0x8000 invalid sentinel (−32768) and the negative wire vector 0xF830
 * (−2000 = −20.00 °C) — arrive in signed form; the wire-level byte vectors
 * ride {@code ZclIngestionUnitTest}.
 */
@DisplayName("TemperatureMeasurementHandler — 0x0402 → temperature_c (M9.7-W2 §2)")
class TemperatureMeasurementHandlerTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x00124B00AA000002L);

    private TestClock clock;
    private TemperatureMeasurementHandler handler;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    TemperatureMeasurementHandlerTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        handler = new TemperatureMeasurementHandler(DEVICE, clock);
    }

    private List<NormalizedAttribute> normalize(Map<Integer, Object> attributes) {
        return handler.normalize(1, TemperatureMeasurementHandler.CLUSTER_ID,
                attributes);
    }

    @Test
    @DisplayName("measuredValue scales 0.01 °C → °C: 2350 → 23.50 °C, raw retained")
    void mapsScaledCelsius() {
        List<NormalizedAttribute> reports = normalize(Map.of(0x0000, 2350L));

        assertThat(reports).hasSize(1);
        NormalizedAttribute report = reports.get(0);
        assertThat(report.attributeKey()).isEqualTo("temperature_c");
        assertThat(report.value()).isEqualTo(23.5);
        assertThat(report.unit()).isEqualTo("°C");
        assertThat(report.rawProtocolValue()).isEqualTo("2350");
    }

    @Test
    @DisplayName("the emitted key IS the temperatureMeasurement capability's "
            + "canonical schema key (DP-9 — never an invented key)")
    void emittedKeyMatchesTheCapabilitySchema() {
        String key = normalize(Map.of(0x0000, 2350L)).get(0).attributeKey();

        assertThat(StandardCapabilities.temperatureMeasurement().attributeSchemas())
                .containsKey(key);
    }

    @Test
    @DisplayName("negative temperatures arrive sign-extended and scale correctly: "
            + "wire 0xF830 = −2000 → −20.00 °C")
    void negativeSignExtendedVectorScales() {
        List<NormalizedAttribute> reports = normalize(Map.of(0x0000, -2000L));

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).value()).isEqualTo(-20.0);
        assertThat(reports.get(0).rawProtocolValue()).isEqualTo("-2000");
    }

    @Test
    @DisplayName("the invalid sentinel (wire 0x8000, sign-extended −32768) emits "
            + "NOTHING — honest absence, never a fabricated reading")
    void invalidSentinelNeverObserves() {
        assertThat(normalize(Map.of(0x0000, -32768L))).isEmpty();
    }

    @Test
    @DisplayName("below the ZCL band floor (−273.15 °C) emits nothing — an "
            + "impossible reading never observes (F-9)")
    void belowAbsoluteZeroSkipped() {
        assertThat(normalize(Map.of(0x0000, -30_000L))).isEmpty();
    }

    @Test
    @DisplayName("above the int16 ceiling emits nothing — the mis-typed-record "
            + "guard (a uint16-decoded 0x8000 arrives as 32768, never 327.68 °C)")
    void aboveInt16CeilingSkipped() {
        assertThat(normalize(Map.of(0x0000, 32_768L))).isEmpty();
        assertThat(normalize(Map.of(0x0000, 0xFFFFL))).isEmpty();
    }

    @Test
    @DisplayName("the band floor itself (−27315 = −273.15 °C) still reports")
    void bandFloorReports() {
        List<NormalizedAttribute> reports = normalize(Map.of(0x0000, -27_315L));

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).value()).isEqualTo(-273.15);
    }

    @Test
    @DisplayName("a malformed payload type emits nothing — the total-codec "
            + "discipline, never a throw")
    void malformedPayloadEmitsNothing() {
        assertThat(normalize(Map.of(0x0000, "warm"))).isEmpty();
        assertThat(normalize(Map.of(0x0000, Boolean.TRUE))).isEmpty();
    }

    @Test
    @DisplayName("unknown attributes within the cluster are ignored, not errors")
    void unknownAttributesIgnored() {
        assertThat(normalize(Map.of(0x0001, 2350L))).isEmpty();
    }
}

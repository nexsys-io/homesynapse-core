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
 * {@link IlluminanceMeasurementHandler} (0x0400, IR-18): measuredValue (Uint16
 * on the ZCL8 §4.2 log scale, {@code 10,000 × log10(lux) + 1}) → the canonical
 * {@code illuminance_lux} float rounded to one decimal, the raw retained with
 * its dialect. Uint16 arrives unsigned, so the 0xFFFF invalid sentinel is
 * 65535 and emits nothing (F-9); 0 is the device's "too low to be measured"
 * and observes as 0.0 lux (DP-1).
 */
@DisplayName("IlluminanceMeasurementHandler — 0x0400 → illuminance_lux (IR-18)")
class IlluminanceMeasurementHandlerTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x00124B00AA000004L);

    private TestClock clock;
    private IlluminanceMeasurementHandler handler;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    IlluminanceMeasurementHandlerTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        handler = new IlluminanceMeasurementHandler(DEVICE, clock);
    }

    private List<NormalizedAttribute> normalize(Map<Integer, Object> attributes) {
        return handler.normalize(1, IlluminanceMeasurementHandler.CLUSTER_ID,
                attributes);
    }

    /** The one canonical value a raw measuredValue observes as. */
    private Object luxOf(long raw) {
        List<NormalizedAttribute> reports = normalize(Map.of(0x0000, raw));
        assertThat(reports).as("raw %d", raw).hasSize(1);
        return reports.get(0).value();
    }

    @Test
    @DisplayName("T1: the log scale maps to lux — 10001 → 10.0 lux, the raw and "
            + "its dialect retained")
    void mapsLogScaleToLux() {
        List<NormalizedAttribute> reports = normalize(Map.of(0x0000, 10_001L));

        assertThat(reports).hasSize(1);
        NormalizedAttribute report = reports.get(0);
        assertThat(report.attributeKey()).isEqualTo("illuminance_lux");
        assertThat(report.value()).isEqualTo(10.0);
        assertThat(report.unit()).isEqualTo("lux");
        assertThat(report.rawProtocolValue()).isEqualTo("10001");
        assertThat(report.rawProtocolUnit()).isEqualTo("log10x10000+1");
    }

    @Test
    @DisplayName("T2: the ZCL8 §4.2.2.2.1 worked values, rounded to one decimal — "
            + "1 → 1.0 · 20001 → 100.0 · 25001 → 316.2 · 30001 → 1000.0 · "
            + "65534 (the largest valid) → 3575197.2")
    void pinsTheWorkedValues() {
        assertThat(luxOf(1L)).as("raw 1").isEqualTo(1.0);
        assertThat(luxOf(20_001L)).as("raw 20001").isEqualTo(100.0);
        assertThat(luxOf(25_001L)).as("raw 25001").isEqualTo(316.2);
        assertThat(luxOf(30_001L)).as("raw 30001").isEqualTo(1000.0);
        assertThat(luxOf(65_534L)).as("raw 65534").isEqualTo(3_575_197.2);
    }

    @Test
    @DisplayName("T3: the emitted key IS the illuminanceMeasurement capability's "
            + "canonical schema key (DP-9 — never an invented key)")
    void emittedKeyMatchesTheCapabilitySchema() {
        String key = normalize(Map.of(0x0000, 10_001L)).get(0).attributeKey();

        assertThat(StandardCapabilities.illuminanceMeasurement().attributeSchemas())
                .containsKey(key);
    }

    @Test
    @DisplayName("T4: the invalid sentinel 0xFFFF emits NOTHING — honest absence, "
            + "never a fabricated reading (the formula would say 3576020.5 lux)")
    void invalidSentinelEmitsNothing() {
        assertThat(normalize(Map.of(0x0000, 0xFFFFL))).isEmpty();
    }

    @Test
    @DisplayName("T5: 0 — the device's own \"too low to be measured\" — observes as "
            + "0.0 lux, raw \"0\" (DP-1: emitting nothing would leave the last "
            + "daylight value standing after dusk)")
    void tooLowToMeasureEmitsZeroLux() {
        List<NormalizedAttribute> reports = normalize(Map.of(0x0000, 0L));

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).attributeKey()).isEqualTo("illuminance_lux");
        assertThat(reports.get(0).value()).isEqualTo(0.0);
        assertThat(reports.get(0).rawProtocolValue()).isEqualTo("0");
    }

    @Test
    @DisplayName("T6: a value that is not a Long emits nothing — the total-codec "
            + "discipline, never a throw; an absent measuredValue likewise")
    void nonLongEmitsNothing() {
        assertThat(normalize(Map.of(0x0000, "bright"))).isEmpty();
        assertThat(normalize(Map.of(0x0000, 10_001))).as("an Integer").isEmpty();
        assertThat(normalize(Map.of(0x0001, 10_001L))).as("not measuredValue")
                .isEmpty();
    }

    @Test
    @DisplayName("T6b: a value no uint16 carries — negative, or above 0xFFFF "
            + "(reachable only through a mis-typed wire record) — emits nothing "
            + "(the band, F-9)")
    void outOfBandEmitsNothing() {
        assertThat(normalize(Map.of(0x0000, -1L))).as("negative").isEmpty();
        assertThat(normalize(Map.of(0x0000, 0x1_0000L))).as("0x10000").isEmpty();
        assertThat(normalize(Map.of(0x0000, 0xFFFF_FFFFL))).as("a uint32 ceiling")
                .isEmpty();
    }
}

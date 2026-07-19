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
 * {@link RelativeHumidityHandler} (0x0405, M9.7-W2 §2): measuredValue (Uint16,
 * 0.01 %RH) → the canonical {@code humidity_pct} float, raw retained. Uint16
 * arrives unsigned, so the 0xFFFF invalid sentinel is 65535 and values above
 * 10000 (100 %RH) are out of the legal band — both emit nothing (F-9).
 */
@DisplayName("RelativeHumidityHandler — 0x0405 → humidity_pct (M9.7-W2 §2)")
class RelativeHumidityHandlerTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x00124B00AA000003L);

    private TestClock clock;
    private RelativeHumidityHandler handler;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RelativeHumidityHandlerTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        handler = new RelativeHumidityHandler(DEVICE, clock);
    }

    private List<NormalizedAttribute> normalize(Map<Integer, Object> attributes) {
        return handler.normalize(1, RelativeHumidityHandler.CLUSTER_ID, attributes);
    }

    @Test
    @DisplayName("measuredValue scales 0.01 % → %: 4523 → 45.23 %, raw retained")
    void mapsScaledPercent() {
        List<NormalizedAttribute> reports = normalize(Map.of(0x0000, 4523L));

        assertThat(reports).hasSize(1);
        NormalizedAttribute report = reports.get(0);
        assertThat(report.attributeKey()).isEqualTo("humidity_pct");
        assertThat(report.value()).isEqualTo(45.23);
        assertThat(report.unit()).isEqualTo("%");
        assertThat(report.rawProtocolValue()).isEqualTo("4523");
    }

    @Test
    @DisplayName("the emitted key IS the humidityMeasurement capability's "
            + "canonical schema key (DP-9 — never an invented key)")
    void emittedKeyMatchesTheCapabilitySchema() {
        String key = normalize(Map.of(0x0000, 4523L)).get(0).attributeKey();

        assertThat(StandardCapabilities.humidityMeasurement().attributeSchemas())
                .containsKey(key);
    }

    @Test
    @DisplayName("10000 = exactly 100 % — the legal ceiling reports")
    void ceilingReports() {
        List<NormalizedAttribute> reports = normalize(Map.of(0x0000, 10_000L));

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).value()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("the invalid sentinel 0xFFFF emits NOTHING — honest absence, "
            + "never a fabricated reading")
    void invalidSentinelNeverObserves() {
        assertThat(normalize(Map.of(0x0000, 0xFFFFL))).isEmpty();
    }

    @Test
    @DisplayName("out-of-band values above 100 % are skipped (the F-9 battery "
            + "out-of-band precedent)")
    void outOfBandSkipped() {
        assertThat(normalize(Map.of(0x0000, 10_001L))).isEmpty();
    }

    @Test
    @DisplayName("a malformed payload type emits nothing — the total-codec "
            + "discipline, never a throw")
    void malformedPayloadEmitsNothing() {
        assertThat(normalize(Map.of(0x0000, "damp"))).isEmpty();
        assertThat(normalize(Map.of(0x0000, Boolean.TRUE))).isEmpty();
    }

    @Test
    @DisplayName("unknown attributes within the cluster are ignored, not errors")
    void unknownAttributesIgnored() {
        assertThat(normalize(Map.of(0x0001, 4523L))).isEmpty();
    }
}

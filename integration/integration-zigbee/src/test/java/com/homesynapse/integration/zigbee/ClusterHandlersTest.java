/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cluster handler tests (Doc 08 §3.5): per-cluster value normalization to the
 * in-tree canonical attribute keys, the AMD-96 Kelvin-at-ingestion conversion
 * with raw mireds retained, occupancy bit 0, battery raw/2 with the F-9
 * invalid-marker guards, and the IAS Zone tolerate-path. Command building on
 * the actuator trio is {@code BuildCommandTest}'s; the ingestion-only handlers
 * throw (M9.4a).
 */
class ClusterHandlersTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x0017880109AB12CDL);

    private TestClock clock;
    private Map<Integer, ZigbeeClusterHandler> handlers;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        handlers = ClusterHandlers.forDevice(DEVICE, clock, ZoneType.MOTION);
    }

    private List<NormalizedAttribute> normalize(int cluster,
            Map<Integer, Object> attributes) {
        return handlers.get(cluster).normalize(11, cluster, attributes);
    }

    @Nested
    @DisplayName("OnOff 0x0006 → on_off.on")
    class OnOff {

        @Test
        @DisplayName("boolean state maps to the canonical 'on' key")
        void mapsBoolean() {
            List<NormalizedAttribute> reports =
                    normalize(0x0006, Map.of(0x0000, Boolean.TRUE));

            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).attributeKey()).isEqualTo("on");
            assertThat(reports.get(0).value()).isEqualTo(Boolean.TRUE);
        }
    }

    @Nested
    @DisplayName("LevelControl 0x0008 → brightness")
    class LevelControl {

        @Test
        @DisplayName("currentLevel passes through as the canonical 0-254 level")
        void mapsLevel() {
            List<NormalizedAttribute> reports =
                    normalize(0x0008, Map.of(0x0000, 128L));

            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).attributeKey()).isEqualTo("brightness");
            assertThat(reports.get(0).value()).isEqualTo(128L);
        }
    }

    @Nested
    @DisplayName("ColorControl 0x0300 → color_temp_kelvin (AMD-96 Kelvin-at-ingestion)")
    class ColorControl {

        @Test
        @DisplayName("mireds convert at ingestion: K = 1,000,000 / mireds, rounded")
        void convertsMiredsToKelvin() {
            List<NormalizedAttribute> reports =
                    normalize(0x0300, Map.of(0x0007, 447L));

            assertThat(reports).hasSize(1);
            NormalizedAttribute report = reports.get(0);
            assertThat(report.attributeKey()).isEqualTo("color_temp_kelvin");
            assertThat(report.value()).isEqualTo(2237L);
            assertThat(report.unit()).isEqualTo("K");
        }

        @Test
        @DisplayName("raw mireds are RETAINED alongside the canonical value (Doc 02 §3.7)")
        void retainsRawMireds() {
            NormalizedAttribute report =
                    normalize(0x0300, Map.of(0x0007, 447L)).get(0);

            assertThat(report.rawProtocolValue()).isEqualTo("447");
            assertThat(report.rawProtocolUnit()).isEqualTo("mired");
        }

        @Test
        @DisplayName("the ±1-mired drift rows pin the rounding choice (nearest)")
        void driftRowsPinRounding() {
            // Measured: commanded 153 mireds, the bulb re-derives and reports 154.
            // 1,000,000/153 = 6535.9 → 6536; 1,000,000/154 = 6493.5 → 6494.
            assertThat(normalize(0x0300, Map.of(0x0007, 153L)).get(0).value())
                    .isEqualTo(6536L);
            assertThat(normalize(0x0300, Map.of(0x0007, 154L)).get(0).value())
                    .isEqualTo(6494L);
        }

        @Test
        @DisplayName("zero/invalid mireds are skipped, never a division crash")
        void invalidMiredsSkipped() {
            assertThat(normalize(0x0300, Map.of(0x0007, 0L))).isEmpty();
        }
    }

    @Nested
    @DisplayName("OccupancySensing 0x0406 → occupancy.occupied (the hero-trigger cluster)")
    class OccupancySensing {

        @Test
        @DisplayName("bit 0 of the occupancy bitmap is the occupied boolean")
        void mapsBitZero() {
            assertThat(normalize(0x0406, Map.of(0x0000, 1L)).get(0).value())
                    .isEqualTo(Boolean.TRUE);
            assertThat(normalize(0x0406, Map.of(0x0000, 0L)).get(0).value())
                    .isEqualTo(Boolean.FALSE);
            assertThat(normalize(0x0406, Map.of(0x0000, 2L)).get(0).value())
                    .as("only bit 0 carries occupancy")
                    .isEqualTo(Boolean.FALSE);
            assertThat(normalize(0x0406, Map.of(0x0000, 1L)).get(0).attributeKey())
                    .isEqualTo("occupied");
        }
    }

    @Nested
    @DisplayName("PowerConfiguration 0x0001 → battery_pct (raw/2)")
    class PowerConfiguration {

        @Test
        @DisplayName("batteryPercentageRemaining halves from 0.5% units")
        void halvesRawPercent() {
            NormalizedAttribute report =
                    normalize(0x0001, Map.of(0x0021, 200L)).get(0);

            assertThat(report.attributeKey()).isEqualTo("battery_pct");
            assertThat(report.value()).isEqualTo(100L);
            assertThat(report.rawProtocolValue()).isEqualTo("200");
        }
    }

    @Nested
    @DisplayName("IAS Zone 0x0500 — tolerate, never require (§3.12)")
    class IasZone {

        @Test
        @DisplayName("zoneStatus attribute reports ingest regardless of enrollment state")
        void zoneStatusAttributeIngests() {
            List<NormalizedAttribute> reports =
                    normalize(0x0500, Map.of(0x0002, 1L));

            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).attributeKey()).isEqualTo("detected");
            assertThat(reports.get(0).value()).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("ZoneStatusChangeNotification ingests through the dedicated path")
        void zoneStatusNotificationIngests() {
            IasZoneHandler handler =
                    (IasZoneHandler) handlers.get(0x0500);

            List<NormalizedAttribute> reports = handler.normalizeZoneStatus(1, 0x0021);

            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).attributeKey()).isEqualTo("detected");
            assertThat(reports.get(0).value())
                    .as("bit 0 (Alarm1) carries the primary alarm")
                    .isEqualTo(Boolean.TRUE);
        }
    }

    @Test
    @DisplayName("the frozen interface path produces AttributeReports with the device-scoped entityRef")
    void interfacePathCarriesEntityRef() {
        List<AttributeReport> reports = handlers.get(0x0006)
                .handleAttributeReport(11, 0x0006, Map.of(0x0000, Boolean.TRUE));

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).entityRef())
                .isEqualTo("zigbee:" + DEVICE.toHexString() + "/11");
        assertThat(reports.get(0).eventTime()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("the table carries exactly the EIGHT report-path handlers "
            + "(M9.7-W2: + TemperatureMeasurement 0x0402 + RelativeHumidity 0x0405)")
    void tableCarriesExactlyTheEightReportPathHandlers() {
        assertThat(handlers).hasSize(8);
        assertThat(handlers.get(0x0402))
                .isInstanceOf(TemperatureMeasurementHandler.class);
        assertThat(handlers.get(0x0405))
                .isInstanceOf(RelativeHumidityHandler.class);
    }

    @Nested
    @DisplayName("TemperatureMeasurement 0x0402 → temperature_c (M9.7-W2 §2)")
    class TemperatureMeasurement {

        @Test
        @DisplayName("measuredValue dispatches through the table: 2350 → 23.50 °C")
        void dispatchesThroughTheTable() {
            List<NormalizedAttribute> reports =
                    normalize(0x0402, Map.of(0x0000, 2350L));

            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).attributeKey()).isEqualTo("temperature_c");
            assertThat(reports.get(0).value()).isEqualTo(23.5);
        }
    }

    @Nested
    @DisplayName("RelativeHumidity 0x0405 → humidity_pct (M9.7-W2 §2)")
    class RelativeHumidity {

        @Test
        @DisplayName("measuredValue dispatches through the table: 4523 → 45.23 %")
        void dispatchesThroughTheTable() {
            List<NormalizedAttribute> reports =
                    normalize(0x0405, Map.of(0x0000, 4523L));

            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).attributeKey()).isEqualTo("humidity_pct");
            assertThat(reports.get(0).value()).isEqualTo(45.23);
        }
    }

    @Test
    @DisplayName("buildCommand: the ingestion-only handlers throw naming handler + command (M9.4a — the actuator trio is BuildCommandTest's)")
    void buildCommand_ingestionOnlyHandlersThrow() {
        for (int clusterId : new int[] {0x0406, 0x0001, 0x0500, 0x0402, 0x0405}) {
            ZigbeeClusterHandler handler = handlers.get(clusterId);
            assertThatThrownBy(() -> handler.buildCommand("turn_on", Map.of()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining(handler.getClass().getSimpleName())
                    .hasMessageContaining("turn_on");
        }
    }

    @Nested
    @DisplayName("F-9 — invalid-marker guards")
    class InvalidMarkers {

        @Test
        @DisplayName("battery 0xFF (unknown) produces no report")
        void batteryUnknownMarkerSkipped() {
            assertThat(normalize(0x0001, Map.of(0x0021, 255L))).isEmpty();
        }

        @Test
        @DisplayName("battery > 200 half-percent units is out-of-band — skipped with DEBUG")
        void batteryOutOfBandSkipped() {
            assertThat(normalize(0x0001, Map.of(0x0021, 201L))).isEmpty();
        }

        @Test
        @DisplayName("battery 200 = exactly 100 % — the legal ceiling reports")
        void batteryCeilingReports() {
            List<NormalizedAttribute> reports =
                    normalize(0x0001, Map.of(0x0021, 200L));

            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).value()).isEqualTo(100L);
        }

        @Test
        @DisplayName("an invalid bool marker never becomes an on_off observation (and so can never CONFIRM a turn_on)")
        void invalidBoolMarker_neverObserves() {
            // The wire guard lives in ZclCodec (marker != 0x00/0x01 is dropped
            // pre-handler); the handler's own type gate is the second net.
            assertThat(normalize(0x0006, Map.of(0x0000, 255L))).isEmpty();
        }
    }

    @Test
    @DisplayName("unknown attributes within a known cluster are ignored, not errors")
    void unknownAttributesIgnored() {
        assertThat(normalize(0x0006, Map.of(0x4001, 5L))).isEmpty();
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.EntityType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EndpointClassifier} — §3.2 (SD-3): endpoints whose input clusters
 * include Identify (0x0003) gain the {@code identify} capability instance on
 * EVERY classification arm (device-type table AND cluster fallback), making the
 * entity identify-issuable through the real Tier-1 validator. Endpoints without
 * 0x0003 gain nothing.
 *
 * <p>M9.7-W2 §3: the 0x0302 arm widens (humidity + battery when their clusters
 * are present — DP-8), IAS selection is zoneType-aware (a wire-learned CONTACT
 * installs {@code contact}; unlearned/MOTION/others stay {@code motion} —
 * DP-6), and the fallback gains the battery-only remainder ({@code SENSOR} +
 * {@code battery} — DP-7, the R2(B) SNZB-01P ruling). Occupancy still outranks
 * IAS regardless of zone type (the measured 03P dual-path rule).
 */
@DisplayName("EndpointClassifier — identify attachment (M9.4b §3.2, SD-3) "
        + "+ the Wave-2 arms (M9.7-W2 §3)")
class EndpointClassifierTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    EndpointClassifierTest() {
    }

    private static EndpointDescriptor endpoint(int deviceTypeId, List<Integer> inputClusters) {
        return new EndpointDescriptor(1, 0x0104, deviceTypeId, inputClusters, List.of());
    }

    private static List<String> capabilityIds(Optional<EndpointClassifier.Classification> c) {
        return c.orElseThrow().capabilities().stream()
                .map(CapabilityInstance::capabilityId)
                .toList();
    }

    @Test
    @DisplayName("a CT light with 0x0003 classifies LIGHT + identify (the Hue arm)")
    void light_withIdentifyCluster_gainsIdentify() {
        Optional<EndpointClassifier.Classification> classified = EndpointClassifier.classify(
                endpoint(0x010D, List.of(0x0000, 0x0003, 0x0006, 0x0008, 0x0300)));

        assertThat(classified.orElseThrow().entityType()).isEqualTo(EntityType.LIGHT);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "on_off", "brightness", "color_temperature", "identify");
    }

    @Test
    @DisplayName("an occupancy sensor with 0x0003 classifies BINARY_SENSOR + identify (the SNZB arm)")
    void occupancySensor_withIdentifyCluster_gainsIdentify() {
        Optional<EndpointClassifier.Classification> classified = EndpointClassifier.classify(
                endpoint(0x0107, List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500)));

        assertThat(classified.orElseThrow().entityType()).isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "occupancy", "battery", "identify");
    }

    @Test
    @DisplayName("the cluster FALLBACK arm also gains identify (mechanically: every arm)")
    void fallbackArm_gainsIdentify() {
        // Unknown device type, on/off cluster present → the fallback SWITCH arm.
        Optional<EndpointClassifier.Classification> classified = EndpointClassifier.classify(
                endpoint(0x9999, List.of(0x0003, 0x0006)));

        assertThat(classified.orElseThrow().entityType()).isEqualTo(EntityType.SWITCH);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder("on_off", "identify");
    }

    @Test
    @DisplayName("an endpoint WITHOUT 0x0003 gains nothing")
    void withoutIdentifyCluster_gainsNothing() {
        Optional<EndpointClassifier.Classification> classified = EndpointClassifier.classify(
                endpoint(0x0100, List.of(0x0000, 0x0006)));

        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder("on_off");
    }

    @Test
    @DisplayName("an unmapped endpoint stays empty — 0x0003 alone never invents an entity")
    void unmappedEndpoint_staysEmpty() {
        assertThat(EndpointClassifier.classify(
                endpoint(0x9999, List.of(0x0000, 0x0003)))).isEmpty();
    }

    // ── M9.7-W2 §3 — the Wave-2 arms ────────────────────────────────────────

    @Test
    @DisplayName("DP-8: the 02P shape (0x0302 + humidity + battery clusters) "
            + "classifies SENSOR {temperature, humidity, battery, identify}")
    void temperatureSensor02pShape_gainsHumidityAndBattery() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0302,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0402, 0x0405)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.SENSOR);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "temperature_measurement", "humidity_measurement", "battery",
                "identify");
    }

    @Test
    @DisplayName("DP-8 boundary: a bare 0x0302 endpoint stays temperature-only — "
            + "absent clusters never invent capabilities")
    void temperatureSensorBareShape_staysTemperatureOnly() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0302, List.of(0x0402)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.SENSOR);
        assertThat(capabilityIds(classified))
                .containsExactlyInAnyOrder("temperature_measurement");
    }

    @Test
    @DisplayName("DP-6: the 04P shape under a wire-learned CONTACT installs "
            + "contact — BINARY_SENSOR {contact, battery, identify}")
    void iasContactLearned_installsContact() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0402,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)),
                        ZoneType.CONTACT);

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "contact", "battery", "identify");
    }

    @Test
    @DisplayName("DP-6 boundary: the 04P shape UNLEARNED falls back to motion — "
            + "byte-for-byte today's behavior (deleting the CONTACT arm makes "
            + "the learned leg yield exactly this)")
    void iasUnlearned_fallsBackToMotion() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0402,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)), null);

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "motion", "battery", "identify");
    }

    @Test
    @DisplayName("DP-6 limitation pin: a learned WATER_LEAK still installs motion "
            + "this WU — no fleet device; the recorded MODULE_CONTEXT limitation")
    void iasNonContactLearned_staysMotion() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0402,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)),
                        ZoneType.WATER_LEAK);

        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "motion", "battery", "identify");
    }

    @Test
    @DisplayName("DP-6: the 0x0107 arm's IAS-without-occupancy path is ALSO "
            + "zoneType-aware — a learned CONTACT installs contact there too")
    void occupancyArmWithoutOccupancy_contactLearned_installsContact() {
        // 0x0107 deviceType but NO 0x0406 in-cluster: the arm's IAS path (the
        // older SNZB-03 class) — a mutant passing null here instead of the
        // learned type survives every fallback-path test; this leg kills it.
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)),
                        ZoneType.CONTACT);

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "contact", "battery", "identify");
    }

    @Test
    @DisplayName("the 1-arg overload IS the null-zoneType path on IAS shapes — "
            + "motion, never a learned default (the byte-equivalence pin)")
    void oneArgOverload_iasShape_staysMotion() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0402,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)));

        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "motion", "battery", "identify");
    }

    @Test
    @DisplayName("the 03P dual-path pin: occupancy outranks IAS regardless of the "
            + "learned zone type")
    void occupancyOutranksIas_regardlessOfZoneType() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500)),
                        ZoneType.CONTACT);

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "occupancy", "battery", "identify");
    }

    @Test
    @DisplayName("DP-7: the 01P shape (battery-only remainder) classifies "
            + "SENSOR {battery, identify} — R2(B): presses stay log-visible, "
            + "absent from the device model")
    void batteryOnlyRemainder_classifiesSensorBattery() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0000,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.SENSOR);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "battery", "identify");
    }

    @Test
    @DisplayName("DP-7 boundary: the battery-only arm alone — SENSOR {battery} "
            + "without 0x0003 (fails against Optional.empty() if the arm is deleted)")
    void batteryOnlyWithoutIdentify_classifiesSensorBatteryAlone() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x9999, List.of(0x0001)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.SENSOR);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder("battery");
    }

    @Test
    @DisplayName("DP-7 boundary: none of onOff/level/occupancy/ias/battery still "
            + "classifies EMPTY — never invent an entity")
    void noRecognizedClusters_stillEmpty() {
        assertThat(EndpointClassifier.classify(
                endpoint(0x9999, List.of(0x0000, 0x0020)))).isEmpty();
    }
}

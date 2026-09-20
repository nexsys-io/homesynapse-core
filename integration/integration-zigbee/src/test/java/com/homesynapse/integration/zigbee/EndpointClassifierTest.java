/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.EntityType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
 * installs {@code contact}; unlearned/MOTION stay {@code motion} — DP-6), and
 * the fallback gains the battery-only remainder ({@code SENSOR} +
 * {@code battery} — DP-7, the R2(B) SNZB-01P ruling). Occupancy still outranks
 * IAS regardless of zone type (the measured 03P dual-path rule).
 *
 * <p>ENERGY-READ: a learned WATER_LEAK/SMOKE/VIBRATION installs
 * {@code binary_state} (R1, IR-15 — the DP-6 limitation row is retired);
 * {@code power_meter}/{@code energy_meter} attach by the CLUSTERS present
 * (0x0B04/0x0702) on every arm, the device type a hint (R2, IR-24); one
 * {@code zigbee.endpoint_classified} INFO per classification (R6).
 *
 * <p>ENERGY-READ-b row 2 (the b5 ruling R-5): an endpoint NO arm classified that
 * lists 0x0702 is an {@code ENERGY_METER}; 0x0B04 alone stays {@code SENSOR}
 * (the metering-only pin is re-pinned from {@code SENSOR}).
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
    @DisplayName("T1 (IR-15): a learned WATER_LEAK / SMOKE / VIBRATION installs "
            + "binary_state — the capability whose schema admits the 'active' key "
            + "IasZoneHandler emits; CONTACT and MOTION are unchanged")
    void iasWaterLeakSmokeVibration_classifyToBinaryState() {
        // The re-pin of the retired DP-6 limitation row
        // (iasNonContactLearned_staysMotion asserted "motion" here): adoption is
        // a one-way door, so the classifier must install the capability the
        // handler's key belongs to.
        for (ZoneType zoneType : List.of(ZoneType.WATER_LEAK, ZoneType.SMOKE,
                ZoneType.VIBRATION)) {
            Optional<EndpointClassifier.Classification> classified =
                    EndpointClassifier.classify(endpoint(0x0402,
                            List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)),
                            zoneType);

            assertThat(classified.orElseThrow().entityType())
                    .as("%s", zoneType).isEqualTo(EntityType.BINARY_SENSOR);
            assertThat(capabilityIds(classified)).as("%s", zoneType)
                    .containsExactlyInAnyOrder("binary_state", "battery",
                            "identify");
            CapabilityInstance binaryState = classified.orElseThrow()
                    .capabilities().stream()
                    .filter(c -> c.capabilityId().equals("binary_state"))
                    .findFirst().orElseThrow();
            assertThat(binaryState.attributes())
                    .as("%s: the schema admits the handler's key", zoneType)
                    .containsKey("active");
        }
        assertThat(capabilityIds(EndpointClassifier.classify(endpoint(0x0402,
                List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)),
                ZoneType.CONTACT)))
                .containsExactlyInAnyOrder("contact", "battery", "identify");
        assertThat(capabilityIds(EndpointClassifier.classify(endpoint(0x0402,
                List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)),
                ZoneType.MOTION)))
                .containsExactlyInAnyOrder("motion", "battery", "identify");
    }

    @Test
    @DisplayName("T1 (IR-15): the 0x0107 arm's IAS-without-occupancy path installs "
            + "binary_state for a learned SMOKE too — both IAS call sites share "
            + "the one selection")
    void occupancyArmWithoutOccupancy_smokeLearned_installsBinaryState() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500)),
                        ZoneType.SMOKE);

        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "binary_state", "battery", "identify");
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

    // ── ENERGY-READ R2 (IR-24) — meters attach by the CLUSTERS present ──────

    /**
     * The owned Gen4's recorded signature (PLUG-DOSSIER row 16 / DEVICE-SET
     * §1): EP1, the eight input clusters — the device type is the parameter.
     */
    private static EndpointDescriptor gen4(int deviceTypeId) {
        return endpoint(deviceTypeId, List.of(0x0000, 0x0003, 0x0004, 0x0005,
                0x0006, 0x0702, 0x0B04, 0xFC21));
    }

    @Test
    @DisplayName("T2 (IR-24): the Gen4 signature (device type 0x010A, NOT the "
            + "0x0051 the plug arm keys on) classifies SWITCH {on_off, "
            + "power_meter, energy_meter} (+ identify: the signature lists 0x0003)")
    void gen4Signature_switchWithMeters() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(gen4(0x010A));

        assertThat(classified.orElseThrow().entityType())
                .as("Doc 08 §3.5: a metering switch stays a switch")
                .isEqualTo(EntityType.SWITCH);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "on_off", "power_meter", "energy_meter", "identify");
    }

    @Test
    @DisplayName("T2 (IR-24): the same clusters under device type 0x0051 classify "
            + "IDENTICALLY — the type is a hint, never the key")
    void gen4Signature_sameWithDeviceType0x0051() {
        assertThat(EndpointClassifier.classify(gen4(0x0051)))
                .isEqualTo(EndpointClassifier.classify(gen4(0x010A)));
        assertThat(capabilityIds(EndpointClassifier.classify(gen4(0x0051))))
                .containsExactlyInAnyOrder("on_off", "power_meter",
                        "energy_meter", "identify");
    }

    @Test
    @DisplayName("ENERGY-READ-b row 2 (the b5 ruling R-5; re-pinned from SENSOR): an "
            + "endpoint no other arm classified that lists 0x0702 classifies "
            + "ENERGY_METER {energy_meter} — the meter alone makes the entity, "
            + "nothing else is invented")
    void meteringOnlyEndpoint_energyMeterWithEnergyMeterOnly() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x9999,
                        List.of(0x0000, 0x0702)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.ENERGY_METER);
        assertThat(capabilityIds(classified))
                .containsExactlyInAnyOrder("energy_meter");
    }

    @Test
    @DisplayName("ENERGY-READ-b row 2 boundary: 0x0702 beside 0x0B04 and nothing else "
            + "is still ENERGY_METER {power_meter, energy_meter} — the energy "
            + "register decides, the power measurement is the type's optional half")
    void bothMetersOnlyEndpoint_energyMeterWithBothMeters() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x9999,
                        List.of(0x0000, 0x0702, 0x0B04)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.ENERGY_METER);
        assertThat(capabilityIds(classified))
                .containsExactlyInAnyOrder("power_meter", "energy_meter");
    }

    @Test
    @DisplayName("ENERGY-READ-b row 2 boundary: a battery-only remainder that lists "
            + "0x0702 was classified by the DP-7 arm — it stays SENSOR {battery, "
            + "energy_meter}; ENERGY_METER is for the endpoint NO arm classified")
    void batteryRemainderWithMetering_staysSensor() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x9999,
                        List.of(0x0000, 0x0001, 0x0702)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.SENSOR);
        assertThat(capabilityIds(classified))
                .containsExactlyInAnyOrder("battery", "energy_meter");
    }

    @Test
    @DisplayName("T2 (IR-24) / ENERGY-READ-b row 2 (R-5): an endpoint with 0x0B04 "
            + "ONLY classifies SENSOR {power_meter} — a power measurement "
            + "without an energy register is not an ENERGY_METER")
    void electricalOnlyEndpoint_sensorWithPowerMeterOnly() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x9999,
                        List.of(0x0000, 0x0B04)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.SENSOR);
        assertThat(capabilityIds(classified))
                .containsExactlyInAnyOrder("power_meter");
    }

    @Test
    @DisplayName("T2 (IR-24): the meters attach on EVERY arm — a metering dimmable "
            + "light (the SP 244 shape) stays LIGHT and gains power_meter")
    void meteringLight_staysLightAndGainsPowerMeter() {
        Optional<EndpointClassifier.Classification> classified =
                EndpointClassifier.classify(endpoint(0x0101,
                        List.of(0x0000, 0x0006, 0x0008, 0x0B04)));

        assertThat(classified.orElseThrow().entityType())
                .isEqualTo(EntityType.LIGHT);
        assertThat(capabilityIds(classified)).containsExactlyInAnyOrder(
                "on_off", "brightness", "power_meter");
    }

    @Test
    @DisplayName("R6: ONE zigbee.endpoint_classified INFO per classification prints "
            + "the device type, the input clusters and the chosen entity type + "
            + "capabilities (DEVICE-SET note 1 — the G4-3 instrument)")
    void classification_logsOneEndpointClassifiedInfo() {
        Logger classifierLogger =
                (Logger) LoggerFactory.getLogger(EndpointClassifier.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        classifierLogger.addAppender(capture);
        try {
            EndpointClassifier.classify(gen4(0x010A));
            EndpointClassifier.classify(endpoint(0x9999, List.of(0x0000, 0x0020)));
        } finally {
            classifierLogger.detachAppender(capture);
        }

        assertThat(capture.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("zigbee.endpoint_classified"))
                .toList())
                .containsExactly(
                        "zigbee.endpoint_classified: endpoint=1 deviceType=0x10a "
                                + "inputClusters=[0x0, 0x3, 0x4, 0x5, 0x6, 0x702, "
                                + "0xb04, 0xfc21] entityType=SWITCH "
                                + "capabilities=[on_off, power_meter, "
                                + "energy_meter, identify]",
                        "zigbee.endpoint_classified: endpoint=1 deviceType=0x9999 "
                                + "inputClusters=[0x0, 0x20] entityType=none "
                                + "capabilities=[]");
    }
}

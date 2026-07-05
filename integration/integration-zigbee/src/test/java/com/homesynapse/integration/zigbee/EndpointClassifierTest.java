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
 */
@DisplayName("EndpointClassifier — identify attachment (M9.4b §3.2, SD-3)")
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
}

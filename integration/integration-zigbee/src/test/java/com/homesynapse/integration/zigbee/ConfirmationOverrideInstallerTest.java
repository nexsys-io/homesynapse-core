/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfirmationOverrideInstaller} — the DP-a pin-1 home: the adapter maps the matched
 * profile's AMD-97 {@code confirmation[]} characterizations onto the per-entity
 * {@link CapabilityInstance} list ONCE, at adoption (INV-CE-04 — all protocol vocabulary
 * stays in this class; the core read paths are untouched). Values are consumed from the
 * BUNDLED {@code zigbee-profiles.json} via the real loader (P38 — never re-typed from
 * prose). Since M9.4b §3.2 the classifier attaches the {@code identify} capability
 * (cluster 0x0003), so the bundled Hue {@code identify} characterization MATCHES and
 * installs; only {@code effect} (color_loop) still names no classified capability —
 * the UNCONFIRMABLE mapping arm keeps its synthetic coverage.
 */
@DisplayName("ConfirmationOverrideInstaller — adoption-installed per-device confirmation (DP-a)")
class ConfirmationOverrideInstallerTest {

    private DeviceProfile hueProfile;
    private DeviceProfile snzbProfile;
    private List<CapabilityInstance> hueCapabilities;
    private List<CapabilityInstance> snzbCapabilities;

    @BeforeEach
    void setUp() {
        StandardDeviceProfileRegistry registry = new StandardDeviceProfileRegistry();
        registry.register(new ZigbeeProfileLoader().loadBundled());
        hueProfile = registry.findProfile(MeasuredCorpusValues.HUE_MANUFACTURER,
                MeasuredCorpusValues.HUE_MODEL).orElseThrow();
        snzbProfile = registry.findProfile(MeasuredCorpusValues.SNZB_MANUFACTURER,
                MeasuredCorpusValues.SNZB_MODEL).orElseThrow();
        hueCapabilities = EndpointClassifier.classify(new EndpointDescriptor(
                MeasuredCorpusValues.HUE_ENDPOINT, 0x0104, 0x010D,
                List.of(0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0008, 0x0300,
                        0x1000, 0xFC01, 0xFC04),
                List.of(0x0019))).orElseThrow().capabilities();
        snzbCapabilities = EndpointClassifier.classify(new EndpointDescriptor(
                MeasuredCorpusValues.SNZB_ENDPOINT, 0x0104, 0x0107,
                List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500, 0xFC57),
                List.of(0x0003, 0x0019))).orElseThrow().capabilities();
    }

    private static CapabilityInstance byId(List<CapabilityInstance> capabilities, String id) {
        return capabilities.stream()
                .filter(capability -> capability.capabilityId().equals(id))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Hue color_temperature: the measured 15000 ms window lands on the policy AND every CommandDefinition; TOLERANCE ±50 retained")
    void hueColorTemperature_tunedTimeout_toleranceRetained() {
        List<CapabilityInstance> tuned =
                ConfirmationOverrideInstaller.apply(hueProfile, hueCapabilities);

        CapabilityInstance colorTemperature = byId(tuned, "color_temperature");
        ConfirmationPolicy policy = colorTemperature.confirmation();
        assertThat(policy.mode()).isEqualTo(ConfirmationMode.TOLERANCE);
        assertThat(policy.authoritativeAttributes()).containsExactly("color_temp_kelvin");
        assertThat(policy.defaultTolerance()).isEqualTo(50);
        assertThat(policy.defaultTimeoutMs()).isEqualTo(15000L);
        // The P17 precedence trick: the executor reads CommandDefinition.defaultTimeout,
        // so the per-device window must land there too — zero executor change.
        CommandDefinition setColorTemperature =
                colorTemperature.commands().get("set_color_temperature");
        assertThat(setColorTemperature.defaultTimeout())
                .isEqualTo(Duration.ofMillis(15000));
        assertThat(colorTemperature.featureMap()).isZero();
    }

    @Test
    @DisplayName("Hue on_off and brightness: CONFIRMABLE keeps the mode, timeout = the measured 5000 ms")
    void hueOnOffAndBrightness_confirmableTuning() {
        List<CapabilityInstance> tuned =
                ConfirmationOverrideInstaller.apply(hueProfile, hueCapabilities);

        ConfirmationPolicy onOff = byId(tuned, "on_off").confirmation();
        assertThat(onOff.mode()).isEqualTo(ConfirmationMode.EXACT_MATCH);
        assertThat(onOff.authoritativeAttributes()).containsExactly("on");
        assertThat(onOff.defaultTimeoutMs()).isEqualTo(5000L);

        ConfirmationPolicy brightness = byId(tuned, "brightness").confirmation();
        assertThat(brightness.mode()).isEqualTo(ConfirmationMode.TOLERANCE);
        assertThat(brightness.defaultTolerance()).isEqualTo(2);
        assertThat(brightness.defaultTimeoutMs()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("a characterization naming no classified capability (effect) is skipped with "
            + "a WARN, never a failure; identify now matches and installs (M9.4b §3.2)")
    void unknownCharacterizationIds_skippedTolerantly() {
        List<CapabilityInstance> tuned =
                ConfirmationOverrideInstaller.apply(hueProfile, hueCapabilities);

        assertThat(tuned)
                .extracting(CapabilityInstance::capabilityId)
                .containsExactlyInAnyOrder("on_off", "brightness", "color_temperature",
                        "identify");
        // The bundled Hue identify characterization (UNCONFIRMABLE) installs the
        // DISABLED never-tracked policy on the now-classified identify capability
        // (idempotent — DISABLED is already the capability root; AMD-97-INV-01).
        assertThat(byId(tuned, "identify").confirmation().mode())
                .isEqualTo(ConfirmationMode.DISABLED);
    }

    @Test
    @DisplayName("a profile with no characterizations leaves the instance data identical (SNZB)")
    void noCharacterizations_untouched() {
        List<CapabilityInstance> tuned =
                ConfirmationOverrideInstaller.apply(snzbProfile, snzbCapabilities);

        assertThat(tuned).isEqualTo(snzbCapabilities);
    }

    @Test
    @DisplayName("UNCONFIRMABLE maps to ConfirmationPolicy(DISABLED, [], null, <capability default>) — never-tracked is structural (AMD-97-INV-01)")
    void unconfirmable_mapsToDisabledPolicy() {
        // Synthetic arm coverage: the bundled UNCONFIRMABLE entries (identify/effect)
        // name no classified capability, so pin the mapping against on_off directly.
        ConfirmationCharacterization unconfirmable = new ConfirmationCharacterization(
                "on_off", ConfirmationMode.DISABLED, null,
                ReportsAuthoritative.NONE, ReportingPosture.NONE,
                Confirmability.UNCONFIRMABLE, 0L,
                Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED), null);
        DeviceProfile profile = new DeviceProfile("synthetic_unconfirmable",
                Set.of(new ExactModel("Acme", "NOPE-1")), DeviceCategory.STANDARD_ZCL,
                null, null, null, null, null, null, List.of(unconfirmable));
        long originalTimeout = byId(hueCapabilities, "on_off")
                .confirmation().defaultTimeoutMs();

        List<CapabilityInstance> tuned =
                ConfirmationOverrideInstaller.apply(profile, hueCapabilities);

        CapabilityInstance onOff = byId(tuned, "on_off");
        assertThat(onOff.confirmation().mode()).isEqualTo(ConfirmationMode.DISABLED);
        assertThat(onOff.confirmation().authoritativeAttributes()).isEmpty();
        assertThat(onOff.confirmation().defaultTolerance()).isNull();
        assertThat(onOff.confirmation().defaultTimeoutMs()).isEqualTo(originalTimeout);
        // Commands are NOT rebuilt on the UNCONFIRMABLE arm (nothing is ever tracked).
        assertThat(onOff.commands()).isEqualTo(byId(hueCapabilities, "on_off").commands());
        // BEST_EFFORT sanity on the same synthetic path: mode kept, timeout tuned.
        ConfirmationCharacterization bestEffort = new ConfirmationCharacterization(
                "on_off", ConfirmationMode.EXACT_MATCH, "OnOff/0x0000",
                ReportsAuthoritative.VERIFIED_REPORTS, ReportingPosture.ON_CHANGE,
                Confirmability.BEST_EFFORT, 7000L, Set.of(), null);
        DeviceProfile bestEffortProfile = new DeviceProfile("synthetic_best_effort",
                Set.of(new ExactModel("Acme", "NOPE-2")), DeviceCategory.STANDARD_ZCL,
                null, null, null, null, null, null, List.of(bestEffort));
        CapabilityInstance tunedBestEffort = byId(
                ConfirmationOverrideInstaller.apply(bestEffortProfile, hueCapabilities),
                "on_off");
        assertThat(tunedBestEffort.confirmation().mode())
                .isEqualTo(ConfirmationMode.EXACT_MATCH);
        assertThat(tunedBestEffort.confirmation().defaultTimeoutMs()).isEqualTo(7000L);
        assertThat(tunedBestEffort.commands().get("turn_on").defaultTimeout())
                .isEqualTo(Duration.ofMillis(7000));
    }

    @Test
    @DisplayName("a null confirmation block (read-only device) returns the input unchanged")
    void nullConfirmationBlock_untouched() {
        DeviceProfile readOnly = new DeviceProfile("synthetic_read_only",
                Set.of(new ExactModel("Acme", "RO-1")), DeviceCategory.STANDARD_ZCL,
                null, null, null, null, null, null, null);

        assertThat(ConfirmationOverrideInstaller.apply(readOnly, hueCapabilities))
                .isEqualTo(hueCapabilities);
    }
}

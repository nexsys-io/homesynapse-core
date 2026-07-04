/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.event.DeviceDiscoveredEvent;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ZigbeeAdoptionSlice} tests — the Doc 02 §3.12 three-stage pipeline
 * scoped inside the zigbee ingestion layer: detection publishes
 * {@code device_discovered}; proposal dedups on the IEEE hardware identifier
 * (match ⇒ re-link + {@code availability_changed}, NO new adoption); adoption
 * mints identity (identity-UNGATED, DP-B), registers device + entities +
 * capabilities, and publishes {@code device_adopted}.
 */
class ZigbeeAdoptionSliceTest {

    private static final IEEEAddress SNZB = new IEEEAddress(0x00124B0012345678L);
    private static final IEEEAddress HUE = new IEEEAddress(0x0017880109AB12CDL);

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private StandardDeviceProfileRegistry profileRegistry;
    private ZigbeeAdoptionSlice slice;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        profileRegistry = new StandardDeviceProfileRegistry();
        profileRegistry.register(new ZigbeeProfileLoader().loadBundled());
        slice = new ZigbeeAdoptionSlice(
                new IntegrationId(UlidFactory.generate(clock)),
                deviceRegistry, entityRegistry, profileRegistry, publisher, clock);
    }

    private static InterviewResult snzbInterview() {
        return new InterviewResult(SNZB, 0x6B9A,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500,
                                0xFC57),
                        List.of(0x0003, 0x0019))),
                "eWeLink", "SNZB-03P", 3, InterviewStatus.COMPLETE);
    }

    private static InterviewResult hueInterview() {
        return new InterviewResult(HUE, 0x260F,
                new NodeDescriptor(1, 0x100B, 82, 142),
                List.of(new EndpointDescriptor(11, 0x0104, 0x010D,
                        List.of(0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0008,
                                0x0300, 0x1000, 0xFC01, 0xFC04),
                        List.of(0x0019))),
                "Signify Netherlands B.V.", "LCA017", 1,
                InterviewStatus.COMPLETE);
    }

    @Test
    @DisplayName("Stage 1 detection: device_discovered publishes with protocol identity")
    void detectionPublishesDeviceDiscovered() {
        ZigbeeAdoptionSlice.DiscoveryOutcome outcome =
                slice.onDeviceDiscovered(snzbInterview(),
                        MeasuredCorpusValues.SNZB_PROFILE_ID);

        assertThat(outcome)
                .isEqualTo(ZigbeeAdoptionSlice.DiscoveryOutcome.PROPOSED);
        List<com.homesynapse.event.EventEnvelope> discovered =
                publisher.ofType(EventTypes.DEVICE_DISCOVERED).toList();
        assertThat(discovered).hasSize(1);
        DeviceDiscoveredEvent payload =
                (DeviceDiscoveredEvent) discovered.get(0).payload();
        assertThat(payload.protocolAddress()).isEqualTo(SNZB.toHexString());
        assertThat(payload.manufacturer()).isEqualTo("eWeLink");
        assertThat(payload.model()).isEqualTo("SNZB-03P");
    }

    @Test
    @DisplayName("a PARTIAL interview with empty identity publishes the sentinel, never blank")
    void partialIdentityUsesSentinel() {
        InterviewResult partial = new InterviewResult(SNZB, 0x6B9A,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0406), List.of())),
                "", "", 0, InterviewStatus.PARTIAL);

        slice.onDeviceDiscovered(partial, null);

        DeviceDiscoveredEvent payload = (DeviceDiscoveredEvent) publisher
                .ofType(EventTypes.DEVICE_DISCOVERED).toList().get(0).payload();
        assertThat(payload.manufacturer()).isEqualTo("unknown");
        assertThat(payload.model()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("Stage 3 adoption: mints identity, registers device + entities, publishes device_adopted")
    void adoptionRegistersAndPublishes() {
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);

        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(SNZB);

        Device device = deviceRegistry.getDevice(adopted.deviceId());
        assertThat(device.manufacturer()).isEqualTo("eWeLink");
        assertThat(device.hardwareIdentifiers())
                .extracting(h -> h.namespace() + ":" + h.value())
                .containsExactly("zigbee:" + SNZB.toHexString());
        List<Entity> entities =
                entityRegistry.listEntitiesByDevice(adopted.deviceId());
        assertThat(entities).hasSize(1);
        Entity entity = entities.get(0);
        assertThat(entity.entityType()).isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(entity.capabilities())
                .extracting(c -> c.capabilityId())
                .containsExactlyInAnyOrder("occupancy", "battery");
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).toList()).hasSize(1);
        assertThat(slice.entityFor(SNZB, 1)).contains(entity.entityId());
    }

    @Test
    @DisplayName("the Hue endpoint adopts as a LIGHT with on_off + brightness + color_temperature")
    void hueAdoptsAsLight() {
        slice.onDeviceDiscovered(hueInterview(),
                MeasuredCorpusValues.HUE_PROFILE_ID);

        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(HUE);

        List<Entity> entities =
                entityRegistry.listEntitiesByDevice(adopted.deviceId());
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).entityType()).isEqualTo(EntityType.LIGHT);
        assertThat(entities.get(0).capabilities())
                .extracting(c -> c.capabilityId())
                .containsExactlyInAnyOrder("on_off", "brightness",
                        "color_temperature");
        assertThat(entities.get(0).endpointIndex()).isEqualTo(11);
    }

    @Test
    @DisplayName("Stage 2 dedup: an IEEE match re-links — availability_changed, NO new adoption")
    void ieeeMatchRelinksNotReadopts() {
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);
        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(SNZB);

        ZigbeeAdoptionSlice.DiscoveryOutcome outcome =
                slice.onDeviceDiscovered(snzbInterview(),
                        MeasuredCorpusValues.SNZB_PROFILE_ID);

        assertThat(outcome).isEqualTo(ZigbeeAdoptionSlice.DiscoveryOutcome.LINKED);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).toList())
                .as("re-pairing produces no new adoption event")
                .hasSize(1);
        assertThat(publisher.ofType(EventTypes.AVAILABILITY_CHANGED).toList())
                .hasSize(1);
        assertThat(deviceRegistry.listAllDevices()).hasSize(1);
        assertThat(slice.entityFor(SNZB, 1))
                .as("the entity link survives the re-link")
                .isPresent();
        assertThat(slice.deviceIdFor(SNZB)).contains(adopted.deviceId());
    }

    @Test
    @DisplayName("adopting an unproposed device is a caller error")
    void adoptUnproposedFails() {
        assertThatThrownBy(() -> slice.adopt(HUE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proposed");
    }

    // ── M9.4a §2 — adoption-installed confirmation overrides (DP-a) ─────────

    @Test
    @DisplayName("§2.2: adoption installs the matched profile's tuning — the Hue CT capability carries the measured 15 s window")
    void adoptionInstallsConfirmationOverrides() {
        slice.onDeviceDiscovered(hueInterview(),
                MeasuredCorpusValues.HUE_PROFILE_ID);

        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(HUE);

        Entity entity = entityRegistry
                .listEntitiesByDevice(adopted.deviceId()).get(0);
        var colorTemperature = entity.capabilities().stream()
                .filter(c -> c.capabilityId().equals("color_temperature"))
                .findFirst().orElseThrow();
        assertThat(colorTemperature.confirmation().defaultTimeoutMs())
                .isEqualTo(15000L);
        assertThat(colorTemperature.commands().get("set_color_temperature")
                .defaultTimeout().toMillis()).isEqualTo(15000L);
        assertThat(slice.matchedProfileIdFor(HUE))
                .contains(MeasuredCorpusValues.HUE_PROFILE_ID);
        assertThat(slice.bindingFor(entity.entityId()))
                .contains(new ZigbeeAdoptionSlice.EntityBinding(HUE, 11));
    }

    @Test
    @DisplayName("§2.3 (DP-a pin 2): the re-link path re-installs overrides from the matched profile id")
    void relinkReinstallsOverrides() {
        // Adopt WITHOUT a profile match: standard defaults land (CT window 5000 ms).
        slice.onDeviceDiscovered(hueInterview(), null);
        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(HUE);
        Entity beforeRelink = entityRegistry
                .listEntitiesByDevice(adopted.deviceId()).get(0);
        assertThat(beforeRelink.capabilities().stream()
                .filter(c -> c.capabilityId().equals("color_temperature"))
                .findFirst().orElseThrow().confirmation().defaultTimeoutMs())
                .isEqualTo(5000L);

        // Re-pairing rediscovers WITH the match: relink must re-install the tuning.
        slice.onDeviceDiscovered(hueInterview(),
                MeasuredCorpusValues.HUE_PROFILE_ID);

        Entity afterRelink = entityRegistry
                .listEntitiesByDevice(adopted.deviceId()).get(0);
        assertThat(afterRelink.capabilities().stream()
                .filter(c -> c.capabilityId().equals("color_temperature"))
                .findFirst().orElseThrow().confirmation().defaultTimeoutMs())
                .isEqualTo(15000L);
        // Idempotent: a second re-link with the same profile changes nothing further,
        // publishes availability only, and never a new adoption event.
        slice.onDeviceDiscovered(hueInterview(),
                MeasuredCorpusValues.HUE_PROFILE_ID);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).toList()).hasSize(1);
        assertThat(publisher.ofType(EventTypes.AVAILABILITY_CHANGED).toList())
                .hasSize(2);
    }
}

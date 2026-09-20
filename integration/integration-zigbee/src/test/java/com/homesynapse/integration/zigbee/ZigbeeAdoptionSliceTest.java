/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.DeviceDiscoveredEvent;
import com.homesynapse.event.DeviceRegisteredEvent;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

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
    private IntegrationId integrationId;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private RegistryProjection registryProjection;
    private StandardDeviceProfileRegistry profileRegistry;
    private ZigbeeAdoptionSlice slice;
    private ListAppender<ILoggingEvent> sliceLogCapture;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        integrationId = new IntegrationId(UlidFactory.generate(clock));
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        registryProjection = new RegistryProjection(deviceRegistry, entityRegistry);
        profileRegistry = new StandardDeviceProfileRegistry();
        profileRegistry.register(new ZigbeeProfileLoader().loadBundled());
        slice = new ZigbeeAdoptionSlice(
                integrationId,
                deviceRegistry, entityRegistry,
                registryProjection,
                profileRegistry, publisher, clock);
        sliceLogCapture = new ListAppender<>();
        sliceLogCapture.start();
        sliceLogger().addAppender(sliceLogCapture);
    }

    @AfterEach
    void tearDown() {
        sliceLogger().detachAppender(sliceLogCapture);
    }

    private static Logger sliceLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeAdoptionSlice.class);
    }

    private List<String> sliceMessages(Level level, String prefix) {
        return sliceLogCapture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
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
                // + identify: the SNZB carries cluster 0x0003 (M9.4b §3.2, SD-3).
                .containsExactlyInAnyOrder("occupancy", "battery", "identify");
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
                // + identify: the Hue carries cluster 0x0003 (M9.4b §3.2, SD-3).
                .containsExactlyInAnyOrder("on_off", "brightness",
                        "color_temperature", "identify");
        assertThat(entities.get(0).endpointIndex()).isEqualTo(11);
    }

    // ── M9.7-W2 §4 — the learned-zoneType classification seam ───────────────

    /** The SNZB-04P dossier shape: IAS + battery, NO occupancy (deviceType 0x0402). */
    private static final IEEEAddress CONTACT_SENSOR =
            new IEEEAddress(0x00124B00AA0004B4L);

    private static InterviewResult contactSensorInterview() {
        return new InterviewResult(CONTACT_SENSOR, 0x7C21,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0402,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500, 0xFC11,
                                0xFC57),
                        List.of(0x0003, 0x0006, 0x0019))),
                "eWeLink", "SNZB-04P", 3, InterviewStatus.COMPLETE);
    }

    @Test
    @DisplayName("§4: adoption under a learned-CONTACT zoneType source lands a "
            + "BINARY_SENSOR whose installed capability set carries contact")
    void learnedContactZoneType_adoptsContactEntity() {
        ZigbeeAdoptionSlice contactSlice = new ZigbeeAdoptionSlice(
                integrationId, deviceRegistry, entityRegistry, registryProjection,
                profileRegistry, publisher, clock,
                ieee -> Optional.of(ZoneType.CONTACT));
        contactSlice.onDeviceDiscovered(contactSensorInterview(),
                "sonoff_snzb_04p");

        ZigbeeAdoptionSlice.AdoptedDevice adopted =
                contactSlice.adopt(CONTACT_SENSOR);

        List<Entity> entities =
                entityRegistry.listEntitiesByDevice(adopted.deviceId());
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).entityType())
                .isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(entities.get(0).capabilities())
                .extracting(c -> c.capabilityId())
                .containsExactlyInAnyOrder("contact", "battery", "identify");
    }

    @Test
    @DisplayName("§4 boundary: the no-source constructor adopts the same shape "
            + "as motion — unlearned classification is byte-equivalent to today")
    void unlearnedZoneType_adoptsMotionEntity() {
        // The 7-arg constructor binds the empty zone-type source (the DP-6
        // motion fallback) — the pre-W2 semantics every existing caller keeps.
        slice.onDeviceDiscovered(contactSensorInterview(), null);

        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(CONTACT_SENSOR);

        List<Entity> entities =
                entityRegistry.listEntitiesByDevice(adopted.deviceId());
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).capabilities())
                .extracting(c -> c.capabilityId())
                .containsExactlyInAnyOrder("motion", "battery", "identify");
    }

    @Test
    @DisplayName("Stage 2 dedup: an IEEE match re-links — ZERO availability events "
            + "(WU-AVAIL-SEED DP-3 STOP), NO new adoption")
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
                .as("the relink asserts nothing about availability — boot truth "
                        + "is owned by the tracker seed (WU-AVAIL-SEED DP-3)")
                .isEmpty();
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
    @DisplayName("DP-4 (AMD-99): re-link never mutates the registry — the maps still rebuild")
    void relinkNeverMutatesTheRegistry() {
        // Adopt WITHOUT a profile match: standard defaults land (CT window 5000 ms).
        slice.onDeviceDiscovered(hueInterview(), null);
        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(HUE);
        Entity beforeRelink = entityRegistry
                .listEntitiesByDevice(adopted.deviceId()).get(0);
        assertThat(beforeRelink.capabilities().stream()
                .filter(c -> c.capabilityId().equals("color_temperature"))
                .findFirst().orElseThrow().confirmation().defaultTimeoutMs())
                .isEqualTo(5000L);

        // Re-pairing rediscovers WITH the match. Post-DUR the re-link rebuilds
        // the adapter maps only: a profile-file change silently mutating the
        // registries is exactly what REG-INV-1 bans — the adoption-time tuning
        // persists via replay, and a genuine capability change rides an
        // entity_registered re-emit from a real re-interview (future work).
        slice.onDeviceDiscovered(hueInterview(),
                MeasuredCorpusValues.HUE_PROFILE_ID);

        Entity afterRelink = entityRegistry
                .listEntitiesByDevice(adopted.deviceId()).get(0);
        assertThat(afterRelink)
                .as("the registry-held entity is byte-identical across the re-link")
                .isEqualTo(beforeRelink);
        assertThat(slice.entityFor(HUE, 11))
                .as("the adapter maps still rebuild on re-link")
                .contains(beforeRelink.entityId());
        assertThat(slice.deviceIdFor(HUE)).contains(adopted.deviceId());
        assertThat(slice.matchedProfileIdFor(HUE))
                .as("the re-matched profile id is recorded for the command path")
                .contains(MeasuredCorpusValues.HUE_PROFILE_ID);
        // A second re-link stays idempotent: no events at all (WU-AVAIL-SEED
        // DP-3 STOP), never a new adoption event, never a registry write.
        slice.onDeviceDiscovered(hueInterview(),
                MeasuredCorpusValues.HUE_PROFILE_ID);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).toList()).hasSize(1);
        assertThat(publisher.ofType(EventTypes.AVAILABILITY_CHANGED).toList())
                .as("no relink — first or repeated — publishes availability")
                .isEmpty();
        assertThat(entityRegistry.listEntitiesByDevice(adopted.deviceId()).get(0))
                .isEqualTo(beforeRelink);
    }

    // ── M9.5-DUR (AMD-99) — the DP-3 emission contract ──────────────────────

    @Test
    @DisplayName("DP-3: adopt() publishes device_registered, then entity_registered per "
            + "entity, then device_adopted LAST — full-fidelity payloads")
    void adoptEmitsRegistrationFactsInCausalOrder() {
        slice.onDeviceDiscovered(hueInterview(),
                MeasuredCorpusValues.HUE_PROFILE_ID);

        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(HUE);

        List<String> registrationSequence = publisher.published().stream()
                .map(com.homesynapse.event.EventEnvelope::eventType)
                .filter(type -> type.equals(EventTypes.DEVICE_REGISTERED)
                        || type.equals(EventTypes.ENTITY_REGISTERED)
                        || type.equals(EventTypes.DEVICE_ADOPTED))
                .toList();
        assertThat(registrationSequence).containsExactly(
                EventTypes.DEVICE_REGISTERED,
                EventTypes.ENTITY_REGISTERED,
                EventTypes.DEVICE_ADOPTED);

        // Full fidelity (REG-INV-1): the payloads alone reconstruct EXACTLY the
        // registry rows the projection applied — the log is the source of truth.
        DeviceRegisteredEvent deviceRegistered = (DeviceRegisteredEvent) publisher
                .ofType(EventTypes.DEVICE_REGISTERED).toList().get(0).payload();
        assertThat(RegistryEventMapper.toDevice(deviceRegistered))
                .isEqualTo(deviceRegistry.getDevice(adopted.deviceId()));
        EntityRegisteredEvent entityRegistered = (EntityRegisteredEvent) publisher
                .ofType(EventTypes.ENTITY_REGISTERED).toList().get(0).payload();
        Entity registryEntity = entityRegistry
                .listEntitiesByDevice(adopted.deviceId()).get(0);
        assertThat(RegistryEventMapper.toEntity(entityRegistered))
                .isEqualTo(registryEntity);
        // The installed DP-a tuning rides the payload (the trust-product fact).
        assertThat(RegistryEventMapper.toEntity(entityRegistered).capabilities()
                .stream()
                .filter(c -> c.capabilityId().equals("color_temperature"))
                .findFirst().orElseThrow().confirmation().defaultTimeoutMs())
                .isEqualTo(15000L);
        // Subject refs: device-scoped / entity-scoped respectively (AMD-99 §3).
        assertThat(publisher.ofType(EventTypes.DEVICE_REGISTERED).toList().get(0)
                .subjectRef().id()).isEqualTo(adopted.deviceId().value());
        assertThat(publisher.ofType(EventTypes.ENTITY_REGISTERED).toList().get(0)
                .subjectRef().id()).isEqualTo(registryEntity.entityId().value());
    }

    // ── M9.4b §6.5 (F-11) + §6.6 (N-8) + the F-8 hook seam ──────────────────

    @Test
    @DisplayName("§6.5 F-11: two racing adopts — exactly one claims; the loser gets the no-proposal ISE, no torn registry state")
    void concurrentAdoptClaimsExactlyOnce() throws InterruptedException {
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<ZigbeeAdoptionSlice.AdoptedDevice> adopted =
                new CopyOnWriteArrayList<>();
        List<IllegalStateException> rejected = new CopyOnWriteArrayList<>();
        Runnable racer = () -> {
            ready.countDown();
            try {
                start.await();
                adopted.add(slice.adopt(SNZB));
            } catch (IllegalStateException e) {
                rejected.add(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = new Thread(racer, "adopt-racer-1");
        Thread second = new Thread(racer, "adopt-racer-2");
        first.start();
        second.start();
        ready.await();

        start.countDown();
        first.join();
        second.join();

        assertThat(adopted)
                .as("exactly one racer claims the proposal")
                .hasSize(1);
        assertThat(rejected)
                .as("the loser finds the proposal already claimed")
                .hasSize(1);
        assertThat(rejected.get(0)).hasMessageContaining("proposed");
        assertThat(deviceRegistry.listAllDevices()).hasSize(1);
        assertThat(entityRegistry
                .listEntitiesByDevice(adopted.get(0).deviceId())).hasSize(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).toList())
                .hasSize(1);
    }

    @Test
    @DisplayName("§6.6 N-8: a 25 h old proposal is stale — adopt rejects naming device, age, and max; re-discovery re-offers")
    void staleProposalRejectsUntilRediscovered() {
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);
        clock.advance(Duration.ofHours(25));

        assertThatThrownBy(() -> slice.adopt(SNZB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SNZB.toHexString())
                .hasMessageContaining("PT25H")
                .hasMessageContaining(
                        ZigbeeAdoptionSlice.PROPOSAL_MAX_AGE.toString());
        assertThat(deviceRegistry.listAllDevices())
                .as("a stale offer registers nothing")
                .isEmpty();

        // Re-discovery replaces the entry (the superseding put refreshes
        // offeredAt) — adoption then succeeds.
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);
        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(SNZB);

        assertThat(deviceRegistry.getDevice(adopted.deviceId())).isNotNull();
        assertThat(slice.entityFor(SNZB, 1)).isPresent();
    }

    @Test
    @DisplayName("the F-8 hook fires with the adopted IEEE on success — never on the ISE paths")
    void onAdoptedHookFiresOnSuccessOnly() {
        List<IEEEAddress> invalidated = new ArrayList<>();
        slice.onAdopted(invalidated::add);

        // Unproposed: the no-proposal ISE path fires nothing.
        assertThatThrownBy(() -> slice.adopt(HUE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invalidated).isEmpty();

        // Stale: the N-8 ISE path fires nothing.
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);
        clock.advance(Duration.ofHours(25));
        assertThatThrownBy(() -> slice.adopt(SNZB))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invalidated).isEmpty();

        // Success: fires exactly once with the device IEEE.
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);
        slice.adopt(SNZB);
        assertThat(invalidated).containsExactly(SNZB);
    }

    // ── M9.5-DURb §1 — the durable half-registration repair (DP-B1/DP-B2) ───

    /**
     * Seeds the projection the way the kill window leaves the log: a durable
     * {@code device_registered} with NO {@code entity_registered} siblings —
     * the state boot replay faithfully rebuilds after a process death between
     * the device publish and its entities' publishes.
     */
    private DeviceId seedHalfRegisteredDevice(IEEEAddress ieee) {
        DeviceId seeded = new DeviceId(UlidFactory.generate(clock));
        String hex = ieee.toHexString();
        registryProjection.applyDeviceRegistered(RegistryEventMapper.toPayload(
                new Device(
                        seeded,
                        "zigbee-" + hex.toLowerCase(Locale.ROOT),
                        "eWeLink SNZB-03P",
                        "eWeLink", "SNZB-03P",
                        null, null, null,
                        integrationId,
                        null, null, List.of(),
                        Set.of(new HardwareIdentifier(
                                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE, hex)),
                        clock.instant())));
        return seeded;
    }

    @Test
    @DisplayName("DP-B1: a half-registered device (zero registry entities) is NOT "
            + "re-linked — ONE repair WARN, and the discovery falls through to a "
            + "fresh proposal")
    void halfRegisteredDevice_reProposedForRepair() {
        DeviceId seeded = seedHalfRegisteredDevice(SNZB);

        ZigbeeAdoptionSlice.DiscoveryOutcome outcome =
                slice.onDeviceDiscovered(snzbInterview(),
                        MeasuredCorpusValues.SNZB_PROFILE_ID);

        assertThat(outcome)
                .as("the entity-less LINKED arm is the permanent hole — the repair "
                        + "is a fresh proposal through the normal adoption flow")
                .isEqualTo(ZigbeeAdoptionSlice.DiscoveryOutcome.PROPOSED);
        assertThat(sliceMessages(Level.WARN, "zigbee.half_registration_detected"))
                .containsExactly("zigbee.half_registration_detected: device="
                        + SNZB.toHexString() + " deviceId=" + seeded
                        + " — re-proposing for repair");
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).toList())
                .as("the proposal publishes normally").hasSize(1);
        assertThat(publisher.ofType(EventTypes.AVAILABILITY_CHANGED).toList())
                .as("no re-link ran — the repair arm never rides relink()")
                .isEmpty();
    }

    @Test
    @DisplayName("DP-B2: the repair adoption REUSES the durable deviceId (idempotent "
            + "re-emit, AMD-99 F1) and mints fresh entity ids — the registries are whole")
    void repairAdoptionReusesTheDeviceId() {
        DeviceId seeded = seedHalfRegisteredDevice(SNZB);
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);

        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(SNZB);

        assertThat(adopted.deviceId())
                .as("identity continuity: the hardware-id match reuses the durable id")
                .isEqualTo(seeded);
        assertThat(deviceRegistry.listAllDevices())
                .as("the re-emit upserts — never a duplicate device")
                .hasSize(1);
        List<Entity> entities = entityRegistry.listEntitiesByDevice(seeded);
        assertThat(entities)
                .as("fresh entity ids repair the hole — none were ever durable")
                .hasSize(1);
        // The re-emitted device_registered rides the SAME device subject and its
        // payload reconstructs the upserted registry row (full fidelity holds
        // through the repair).
        List<com.homesynapse.event.EventEnvelope> reEmits =
                publisher.ofType(EventTypes.DEVICE_REGISTERED).toList();
        assertThat(reEmits).hasSize(1);
        assertThat(reEmits.get(0).subjectRef().id()).isEqualTo(seeded.value());
        DeviceRegisteredEvent reEmit =
                (DeviceRegisteredEvent) reEmits.get(0).payload();
        assertThat(RegistryEventMapper.toDevice(reEmit))
                .isEqualTo(deviceRegistry.getDevice(seeded));

        // The idempotent-replay leg: applying the re-emitted fact again (the live
        // bus self-delivery / a boot replay) is an upsert by identity — no
        // duplicate device, the repaired entities untouched.
        registryProjection.applyDeviceRegistered(reEmit);
        assertThat(deviceRegistry.listAllDevices()).hasSize(1);
        assertThat(entityRegistry.listEntitiesByDevice(seeded))
                .containsExactlyElementsOf(entities);
    }

    @Test
    @DisplayName("DP-B1 happy-path regression: an existing device WITH entities still "
            + "LINKs — no re-propose, no repair WARN")
    void healthyRelink_noRepairWarn() {
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);
        slice.adopt(SNZB);

        ZigbeeAdoptionSlice.DiscoveryOutcome outcome =
                slice.onDeviceDiscovered(snzbInterview(),
                        MeasuredCorpusValues.SNZB_PROFILE_ID);

        assertThat(outcome).isEqualTo(ZigbeeAdoptionSlice.DiscoveryOutcome.LINKED);
        assertThat(sliceMessages(Level.WARN, "zigbee.half_registration_detected"))
                .as("a whole registration never trips the repair arm")
                .isEmpty();
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).toList())
                .as("no second proposal — the healthy re-link is unchanged")
                .hasSize(1);
    }

    // ── ENERGY-READ-b row 1 — the classify call site names the device ───────

    @Test
    @DisplayName("ENERGY-READ-b row 1: adopt() prints ONE zigbee.endpoint_classified "
            + "INFO per classified endpoint FROM THE SLICE, carrying device= in the "
            + "zigbee.device_proposed rendering")
    void adoptLogsEndpointClassifiedWithDevice() {
        slice.onDeviceDiscovered(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);

        slice.adopt(SNZB);

        assertThat(sliceMessages(Level.INFO, "zigbee.endpoint_classified: "))
                .containsExactly("zigbee.endpoint_classified: "
                        + "device=0x00124B0012345678 endpoint=1 "
                        + "entityType=BINARY_SENSOR "
                        + "capabilities=[occupancy, battery, identify]");
        assertThat(sliceMessages(Level.INFO, "zigbee.device_proposed: "))
                .as("device= renders as the adjacent proposal line renders it")
                .singleElement().asString()
                .startsWith("zigbee.device_proposed: device=0x00124B0012345678 ");
    }

    @Test
    @DisplayName("ENERGY-READ-b row 1 boundary: an endpoint that classifies to nothing "
            + "prints NO endpoint_classified line from the slice — the "
            + "endpoint_unclassified WARN stays its one line")
    void unclassifiedEndpoint_printsNoClassifiedLine() {
        InterviewResult twoEndpoints = new InterviewResult(HUE, 0x260F,
                new NodeDescriptor(1, 0x100B, 82, 142),
                List.of(new EndpointDescriptor(11, 0x0104, 0x010D,
                                List.of(0x0000, 0x0003, 0x0004, 0x0005, 0x0006,
                                        0x0008, 0x0300),
                                List.of(0x0019)),
                        // none of the recognized clusters — the classifier's
                        // noRecognizedClusters_stillEmpty shape
                        new EndpointDescriptor(2, 0x0104, 0x9999,
                                List.of(0x0000, 0x0020), List.of())),
                "Signify Netherlands B.V.", "LCA017", 1,
                InterviewStatus.COMPLETE);
        slice.onDeviceDiscovered(twoEndpoints, MeasuredCorpusValues.HUE_PROFILE_ID);

        slice.adopt(HUE);

        assertThat(sliceMessages(Level.INFO, "zigbee.endpoint_classified: "))
                .containsExactly("zigbee.endpoint_classified: "
                        + "device=0x0017880109AB12CD endpoint=11 entityType=LIGHT "
                        + "capabilities=[on_off, brightness, color_temperature, "
                        + "identify]");
        assertThat(sliceMessages(Level.WARN, "zigbee.endpoint_unclassified: "))
                .hasSize(1);
    }
}

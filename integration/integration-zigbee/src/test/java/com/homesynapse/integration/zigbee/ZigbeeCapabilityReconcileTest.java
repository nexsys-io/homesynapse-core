/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.integration.CapabilityPublisher;
import com.homesynapse.integration.CapabilityRemovalReason;
import com.homesynapse.integration.DiscoveryServices;
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * IR-67 — the boot-time capability reconcile: the slice pass over the cached
 * endpoints (T5–T7c, the slice fixture of {@code ZigbeeAdoptionSliceTest}) and
 * the adapter's {@code initialize()} slot through {@code context.discovery()}
 * (T9, a driven-mode adapter over a cache file the test seeds directly).
 */
@DisplayName("IR-67: the boot-time capability reconcile — the slice pass and the initialize() slot")
class ZigbeeCapabilityReconcileTest {

    /** The Hue SML003 shape on the 0x0107 arm: occupancy + illuminance + battery + identify. */
    private static final IEEEAddress MOTION_LUX = new IEEEAddress(0x0017880104ABCDEFL);
    private static final int MOTION_LUX_NWK = 0x3F2A;
    /** The SNZB-04P shape: IAS + battery + identify on the fallback arm (deviceType 0x0402). */
    private static final IEEEAddress CONTACT = new IEEEAddress(0x00124B00AA0004B4L);
    private static final int CONTACT_NWK = 0x7C21;
    private static final String ILLUMINANCE =
            StandardCapabilities.illuminanceMeasurement().capabilityId();

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private IntegrationId integrationId;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private RegistryProjection registryProjection;
    private StandardDeviceProfileRegistry profileRegistry;
    /** The slice's zone-type source (the :228–:231 lambda form), mutable per test. */
    private final AtomicReference<Optional<ZoneType>> learnedZoneType =
            new AtomicReference<>(Optional.empty());
    private ZigbeeAdoptionSlice slice;
    private RecordingCapabilityPublisher fake;
    private ListAppender<ILoggingEvent> sliceLogCapture;
    private ListAppender<ILoggingEvent> classifierLogCapture;
    private ListAppender<ILoggingEvent> adapterLogCapture;
    private Level priorAdapterLevel;

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
                integrationId, deviceRegistry, entityRegistry, registryProjection,
                profileRegistry, publisher, clock,
                ieee -> learnedZoneType.get());
        fake = new RecordingCapabilityPublisher();
        sliceLogCapture = attach(sliceLogger());
        classifierLogCapture = attach(classifierLogger());
        // INFO so an asserted ZERO is never vacuous (E2); restored in tearDown.
        priorAdapterLevel = adapterLogger().getLevel();
        adapterLogger().setLevel(Level.INFO);
        adapterLogCapture = attach(adapterLogger());
    }

    @AfterEach
    void tearDown() {
        sliceLogger().detachAppender(sliceLogCapture);
        classifierLogger().detachAppender(classifierLogCapture);
        adapterLogger().detachAppender(adapterLogCapture);
        adapterLogger().setLevel(priorAdapterLevel);
    }

    // ── T9: the adapter's initialize() slot ─────────────────────────────────

    @Test
    @DisplayName("T9: initialize() reconciles every cached endpoint through "
            + "context.discovery() — ONE add for the entity lacking illuminance, nothing "
            + "for the learned-contact IAS entity, ONE zigbee.capability_reconcile: INFO "
            + "before zigbee.initialized")
    void initialize_reconcilesFromTheCache_throughDiscovery() throws Exception {
        // The cache file the adapter loads: two interviewed devices; the contact
        // sensor's zone type learned on the wire and persisted (DP-LP-3), so the
        // learned type reaches the pass through the FILE, never a test lambda.
        ZigbeeDeviceCache seed = new ZigbeeDeviceCache(
                tempDir.resolve("zigbee-devices.json"), clock);
        seed.recordInterview(motionLuxInterview(), null);
        seed.recordInterview(contactInterview(), null);
        seed.recordLearnedZoneType(CONTACT, ZoneType.CONTACT.zclId());
        seed.flush();
        // The registries as Phase 3 rebuilds them: the motion/lux entity adopted
        // BEFORE the illuminance handler existed (no illuminance id); the contact
        // entity adopted under its learned zone type (equal ⇒ nothing).
        EntityId motionLuxEntity = seedRegistry(MOTION_LUX, 1, List.of(
                StandardCapabilities.occupancy(), StandardCapabilities.battery(),
                StandardCapabilities.identify()));
        EntityId contactEntity = seedRegistry(CONTACT, 1, List.of(
                StandardCapabilities.contact(), StandardCapabilities.battery(),
                StandardCapabilities.identify()));
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                contextWith(new DiscoveryServices(fake)), deviceRegistry,
                registryProjection, tempDir, clock,
                candidate -> {
                    throw new IllegalStateException(
                            "initialize() opens no channel in driven mode (row 27)");
                });

        adapter.initialize();

        assertThat(fake.added())
                .as("exactly ONE add: illuminance on the motion/lux entity")
                .extracting(RecordingCapabilityPublisher.Added::entityId,
                        added -> added.instance().capabilityId(),
                        added -> added.instance().featureMap())
                .containsExactly(tuple(motionLuxEntity, ILLUMINANCE, 0));
        assertThat(capabilityIds(motionLuxEntity))
                .as("the write-ahead apply appended the id exactly ONCE")
                .containsExactly("occupancy", "battery", "identify", ILLUMINANCE);
        assertThat(capabilityIds(contactEntity))
                .as("the learned type reproduces the adoption-time classification")
                .containsExactly("contact", "battery", "identify");
        List<String> adapterLines = adapterLogCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(adapterLines.stream()
                .filter(m -> m.startsWith("zigbee.capability_reconcile:")).toList())
                .containsExactly("zigbee.capability_reconcile: endpoints=2 entities=2 "
                        + "added=1 shrink=0 relearned=0 unbound=0");
        assertThat(adapterLines.stream()
                .filter(m -> m.startsWith("zigbee.capability_reconcile_skipped")).toList())
                .as("production never takes the skip arm: DiscoveryServices is present")
                .isEmpty();
        int reconcileAt = indexOf(adapterLines, "zigbee.capability_reconcile:");
        int initializedAt = indexOf(adapterLines, "zigbee.initialized");
        assertThat(reconcileAt).as("the reconcile INFO printed").isGreaterThanOrEqualTo(0);
        assertThat(initializedAt)
                .as("zigbee.initialized follows the pass (the slot is immediately before it)")
                .isGreaterThan(reconcileAt);
        assertThat(messages(sliceLogCapture, Level.INFO, "zigbee.capability_added"))
                .hasSize(1);
        assertThat(messages(sliceLogCapture, Level.WARN, "zigbee.capability_reconcile"))
                .as("no shrink, no re-learn")
                .isEmpty();
        assertThat(messages(classifierLogCapture, Level.INFO, "zigbee.endpoint_classified"))
                .as("row 5b: the pass never prints the adoption evidence token")
                .isEmpty();
    }

    // ── T5–T7c: the slice pass ──────────────────────────────────────────────

    @Test
    @DisplayName("T5: a handler added after adoption — the missing id is published ONCE "
            + "through the handed-in publisher, applied write-ahead, logged ONCE; the "
            + "slice's own publisher gains nothing; no endpoint_classified line")
    void missingId_publishesOnce_andAppliesWriteAhead() {
        EntityId entityId = adopt(motionLuxInterview(), null);
        // The "handler added after adoption" state: the registry is test-owned, so
        // the direct write is legal here.
        dropCapability(entityId, ILLUMINANCE);
        int publishedBefore = publisher.published().size();
        clearCaptures();

        ZigbeeAdoptionSlice.ReconcileSummary summary = slice.reconcileCapabilities(
                List.of(record(motionLuxInterview(), null)), fake);

        assertThat(fake.added())
                .extracting(RecordingCapabilityPublisher.Added::entityId,
                        added -> added.instance().capabilityId())
                .containsExactly(tuple(entityId, ILLUMINANCE));
        assertThat(capabilityIds(entityId))
                .as("the write-ahead apply appended the id exactly ONCE")
                .containsExactly("occupancy", "battery", "identify", ILLUMINANCE);
        assertThat(summary).isEqualTo(
                new ZigbeeAdoptionSlice.ReconcileSummary(1, 1, 1, 0, 0, 0));
        assertThat(messages(sliceLogCapture, Level.INFO, "zigbee.capability_added"))
                .containsExactly("zigbee.capability_added: device=" + MOTION_LUX
                        + " endpoint=1 entity=" + entityId + " capability=" + ILLUMINANCE);
        assertThat(messages(sliceLogCapture, Level.INFO, "zigbee.endpoint_classified"))
                .as("row 5b: the slice's call-site line is adoption's, not the pass's")
                .isEmpty();
        assertThat(messages(classifierLogCapture, Level.INFO, "zigbee.endpoint_classified"))
                .as("row 5b: classifySilently prints nothing")
                .isEmpty();
        assertThat(publisher.published())
                .as("the seam is the publisher handed in — the slice's own gains nothing")
                .hasSize(publishedBefore);
    }

    @Test
    @DisplayName("T6: nothing missing — ZERO calls, the zero summary, ZERO capability_added; "
            + "a second pass is identical (idempotent)")
    void nothingMissing_publishesNothing() {
        EntityId entityId = adopt(motionLuxInterview(), null);
        clearCaptures();
        ZigbeeDeviceRecord record = record(motionLuxInterview(), null);

        ZigbeeAdoptionSlice.ReconcileSummary first =
                slice.reconcileCapabilities(List.of(record), fake);
        ZigbeeAdoptionSlice.ReconcileSummary second =
                slice.reconcileCapabilities(List.of(record), fake);

        assertThat(fake.added()).isEmpty();
        assertThat(first).isEqualTo(new ZigbeeAdoptionSlice.ReconcileSummary(1, 1, 0, 0, 0, 0));
        assertThat(second).isEqualTo(first);
        assertThat(messages(sliceLogCapture, Level.INFO, "zigbee.capability_added")).isEmpty();
        assertThat(messages(sliceLogCapture, Level.WARN, "zigbee.capability_reconcile"))
                .isEmpty();
        assertThat(capabilityIds(entityId))
                .containsExactly("occupancy", "battery", ILLUMINANCE, "identify");
    }

    @Test
    @DisplayName("T7: a shrink — an extra id the classifier does not yield: ZERO calls, "
            + "ONE WARN naming the id, NO event, NO registry write (removal=none)")
    void shrink_warnsOnce_publishesNothing_removesNothing() {
        EntityId entityId = adopt(motionLuxInterviewWithoutBattery(), null);
        assertThat(capabilityIds(entityId)).containsExactly("occupancy", ILLUMINANCE, "identify");
        addCapability(entityId, StandardCapabilities.battery());
        clearCaptures();

        ZigbeeAdoptionSlice.ReconcileSummary summary = slice.reconcileCapabilities(
                List.of(record(motionLuxInterviewWithoutBattery(), null)), fake);

        assertThat(fake.added()).isEmpty();
        assertThat(summary).isEqualTo(new ZigbeeAdoptionSlice.ReconcileSummary(1, 1, 0, 1, 0, 0));
        assertThat(messages(sliceLogCapture, Level.WARN, "zigbee.capability_reconcile_shrink"))
                .containsExactly("zigbee.capability_reconcile_shrink: device=" + MOTION_LUX
                        + " endpoint=1 entity=" + entityId + " missing=[battery]");
        assertThat(messages(sliceLogCapture, Level.INFO, "zigbee.capability_added")).isEmpty();
        assertThat(capabilityIds(entityId))
                .as("the registry entity STILL carries the extra id — removal=none")
                .containsExactly("occupancy", ILLUMINANCE, "identify", "battery");
    }

    @Test
    @DisplayName("T7b: an IAS endpoint adopted under the motion fallback whose zone type "
            + "was learned later (contact) is a RE-LEARN — ZERO calls, relearned 1, no add, "
            + "no shrink, ONE WARN; the entity unchanged (E5)")
    void iasRelearned_warnsOnce_addsNothing_shrinksNothing() {
        learnedZoneType.set(Optional.empty());
        EntityId entityId = adopt(contactInterview(), "sonoff_snzb_04p");
        assertThat(capabilityIds(entityId)).containsExactly("motion", "battery", "identify");
        learnedZoneType.set(Optional.of(ZoneType.CONTACT));
        clearCaptures();

        ZigbeeAdoptionSlice.ReconcileSummary summary = slice.reconcileCapabilities(
                List.of(record(contactInterview(), "sonoff_snzb_04p")), fake);

        assertThat(fake.added()).isEmpty();
        assertThat(summary).isEqualTo(new ZigbeeAdoptionSlice.ReconcileSummary(1, 1, 0, 0, 1, 0));
        assertThat(messages(sliceLogCapture, Level.WARN,
                "zigbee.capability_reconcile_ias_relearned"))
                .containsExactly("zigbee.capability_reconcile_ias_relearned: device=" + CONTACT
                        + " endpoint=1 entity=" + entityId + " have=motion want=contact");
        assertThat(messages(sliceLogCapture, Level.WARN, "zigbee.capability_reconcile_shrink"))
                .isEmpty();
        assertThat(messages(sliceLogCapture, Level.INFO, "zigbee.capability_added")).isEmpty();
        assertThat(capabilityIds(entityId)).containsExactly("motion", "battery", "identify");
    }

    @Test
    @DisplayName("T7c: a cache-only device (interviewed, never adopted) — ZERO calls, every "
            + "endpoint counted unbound, no WARN")
    void cacheOnlyDevice_countsUnbound_publishesNothing() {
        ZigbeeDeviceRecord record = record(unadoptedTwoEndpointInterview(), null);

        ZigbeeAdoptionSlice.ReconcileSummary summary =
                slice.reconcileCapabilities(List.of(record), fake);

        assertThat(fake.added()).isEmpty();
        assertThat(summary).isEqualTo(new ZigbeeAdoptionSlice.ReconcileSummary(2, 0, 0, 0, 0, 2));
        assertThat(messages(sliceLogCapture, Level.WARN, "")).as("never a WARN").isEmpty();
        assertThat(messages(sliceLogCapture, Level.INFO, "zigbee.capability_added")).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private EntityId adopt(InterviewResult interview, String matchedProfileId) {
        slice.onDeviceDiscovered(interview, matchedProfileId);
        ZigbeeAdoptionSlice.AdoptedDevice adopted = slice.adopt(interview.ieeeAddress());
        return adopted.entityIds().get(interview.endpoints().get(0).endpointId());
    }

    private void dropCapability(EntityId entityId, String capabilityId) {
        Entity entity = entityRegistry.getEntity(entityId);
        entityRegistry.updateEntity(withCapabilities(entity, entity.capabilities().stream()
                .filter(instance -> !instance.capabilityId().equals(capabilityId))
                .toList()));
    }

    private void addCapability(EntityId entityId, Capability capability) {
        Entity entity = entityRegistry.getEntity(entityId);
        List<CapabilityInstance> capabilities = new ArrayList<>(entity.capabilities());
        capabilities.add(instance(capability));
        entityRegistry.updateEntity(withCapabilities(entity, capabilities));
    }

    private static Entity withCapabilities(Entity entity, List<CapabilityInstance> capabilities) {
        return new Entity(entity.entityId(), entity.entitySlug(), entity.entityType(),
                entity.displayName(), entity.deviceId(), entity.endpointIndex(),
                entity.areaId(), entity.enabled(), entity.labels(), capabilities,
                entity.entityRole(), entity.createdAt());
    }

    private ZigbeeDeviceRecord record(InterviewResult interview, String matchedProfileId) {
        return new ZigbeeDeviceRecord(interview.ieeeAddress(), interview.networkAddress(),
                interview.nodeDescriptor(), interview.endpoints(),
                interview.manufacturerName(), interview.modelIdentifier(),
                interview.powerSource(), clock.instant(), interview.interviewStatus(),
                matchedProfileId);
    }

    private void clearCaptures() {
        sliceLogCapture.list.clear();
        classifierLogCapture.list.clear();
        adapterLogCapture.list.clear();
    }

    private static InterviewResult motionLuxInterview() {
        return new InterviewResult(MOTION_LUX, MOTION_LUX_NWK,
                new NodeDescriptor(2, 0x100B, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0400, 0x0406),
                        List.of(0x0019))),
                "Signify Netherlands B.V.", "SML003", 3, InterviewStatus.COMPLETE);
    }

    /** The same shape without the PowerConfiguration cluster (0x0001) — T7's endpoint. */
    private static InterviewResult motionLuxInterviewWithoutBattery() {
        return new InterviewResult(MOTION_LUX, MOTION_LUX_NWK,
                new NodeDescriptor(2, 0x100B, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0003, 0x0400, 0x0406),
                        List.of(0x0019))),
                "Signify Netherlands B.V.", "SML003", 3, InterviewStatus.COMPLETE);
    }

    /** A device the slice never adopted — two endpoints, both counted unbound (T7c). */
    private static InterviewResult unadoptedTwoEndpointInterview() {
        return new InterviewResult(new IEEEAddress(0x00124B00DEADBEEFL), 0x5A5A,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(
                        new EndpointDescriptor(1, 0x0104, 0x0107,
                                List.of(0x0000, 0x0001, 0x0003, 0x0406), List.of(0x0019)),
                        new EndpointDescriptor(2, 0x0104, 0x0302,
                                List.of(0x0000, 0x0001, 0x0003, 0x0402), List.of(0x0019))),
                "eWeLink", "SNZB-TWO", 3, InterviewStatus.COMPLETE);
    }

    private static InterviewResult contactInterview() {
        return new InterviewResult(CONTACT, CONTACT_NWK,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0402,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0500, 0xFC11,
                                0xFC57),
                        List.of(0x0003, 0x0006, 0x0019))),
                "eWeLink", "SNZB-04P", 3, InterviewStatus.COMPLETE);
    }

    /** The classifier's seven-arg instance form (featureMap 0 — row 23). */
    private static CapabilityInstance instance(Capability capability) {
        return new CapabilityInstance(capability.capabilityId(), capability.version(),
                capability.namespace(), 0, capability.attributeSchemas(),
                capability.commandDefinitions(), capability.confirmationPolicy());
    }

    /**
     * Seeds one device + one BINARY_SENSOR entity the way the Phase-3 rebuild
     * leaves them: the device carries this integration's id and the zigbee
     * hardware identifier, the entity's endpointIndex is the endpoint id — the
     * relink join of {@code rehydrateAdoptionMaps} (:1345–:1364 → :548–:554).
     */
    private EntityId seedRegistry(IEEEAddress ieee, int endpoint,
            List<Capability> capabilities) {
        String hex = ieee.toHexString();
        DeviceId deviceId = new DeviceId(UlidFactory.generate(clock));
        deviceRegistry.createDevice(new Device(
                deviceId, "zigbee-" + hex.toLowerCase(Locale.ROOT), "seeded " + hex,
                "seeded", "SEEDED", null, null, null, integrationId, null, null,
                List.of(),
                Set.of(new HardwareIdentifier(ZigbeeAdoptionSlice.HARDWARE_NAMESPACE, hex)),
                clock.instant()));
        EntityId entityId = new EntityId(UlidFactory.generate(clock));
        entityRegistry.createEntity(new Entity(
                entityId, "zigbee-" + hex.toLowerCase(Locale.ROOT) + "-ep" + endpoint,
                EntityType.BINARY_SENSOR, "seeded " + hex, deviceId, endpoint, null, true,
                List.of(),
                capabilities.stream().map(ZigbeeCapabilityReconcileTest::instance).toList(),
                clock.instant()));
        return entityId;
    }

    private List<String> capabilityIds(EntityId entityId) {
        return entityRegistry.getEntity(entityId).capabilities().stream()
                .map(CapabilityInstance::capabilityId).toList();
    }

    private static int indexOf(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> messages(ListAppender<ILoggingEvent> capture, Level level,
            String prefix) {
        return capture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
    }

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        return capture;
    }

    private static Logger sliceLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeAdoptionSlice.class);
    }

    private static Logger classifierLogger() {
        return (Logger) LoggerFactory.getLogger(EndpointClassifier.class);
    }

    private static Logger adapterLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);
    }

    private IntegrationContext contextWith(DiscoveryServices discovery) {
        return new IntegrationContext(
                integrationId, "zigbee", publisher,
                entityRegistry, unusedQueryService(),
                unusedHealthReporter(), emptyConfig(),
                null, null, null, null, discovery);
    }

    private static ConfigurationAccess emptyConfig() {
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return Map.of();
            }

            @Override
            public Optional<String> getString(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> getInt(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> getBoolean(String key) {
                return Optional.empty();
            }
        };
    }

    private static StateQueryService unusedQueryService() {
        return new StateQueryService() {
            @Override
            public Optional<EntityState> getState(EntityId entityId) {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public StateSnapshot getSnapshot() {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public long getViewPosition() {
                return 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }
        };
    }

    private static HealthReporter unusedHealthReporter() {
        return new HealthReporter() {
            @Override
            public void reportHeartbeat() {
            }

            @Override
            public void reportKeepalive(Instant lastSuccess) {
            }

            @Override
            public void reportError(Throwable error) {
            }

            @Override
            public void reportHealthTransition(HealthState state, String reason) {
            }
        };
    }

    /**
     * The §3 row 10 fake: lists every {@code (entityId, instance)} add; the typed
     * overload is not the reconcile's path and {@code publishRemoved} THROWS —
     * {@code removal=none} is pinned by construction.
     */
    private static final class RecordingCapabilityPublisher implements CapabilityPublisher {

        record Added(EntityId entityId, CapabilityInstance instance) {
        }

        private final List<Added> added = new ArrayList<>();

        @Override
        public void publishAdded(EntityId entityId, CapabilityInstance instance) {
            added.add(new Added(entityId, instance));
        }

        @Override
        public void publishAdded(EntityId entityId, Class<? extends Capability> capability) {
            throw new AssertionError("the reconcile publishes instances, never permit "
                    + "classes: " + capability.getName());
        }

        @Override
        public void publishRemoved(EntityId entityId, String capabilityId,
                CapabilityRemovalReason reason) {
            throw new AssertionError("removal=none (IR-67): publishRemoved must never "
                    + "be called — " + capabilityId + " on " + entityId);
        }

        List<Added> added() {
            return List.copyOf(added);
        }
    }
}

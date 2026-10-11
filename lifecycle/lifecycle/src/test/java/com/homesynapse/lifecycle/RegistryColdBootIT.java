/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.Device;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.Permission;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.integration.IntegrationEvents;
import com.homesynapse.persistence.PersistenceFactory;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;
import com.homesynapse.value.AttributeType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * REGISTRY-COLD-1 (IR-148) — the registry's cold boot on a store that already holds rows, the
 * way {@link RealCoreFixture} and {@link ProcessRestartIdentityIT} boot one: a standalone
 * {@link PersistenceFactory} seeds the log (no bus, no subscribers, so no checkpoint exists), then
 * the REAL composition root opens the same file.
 *
 * <ul>
 *   <li><b>K-1</b> — N registry rows among M ≥ 50 × N others: ONE
 *       {@code registry.projection_rebuilt: rows=N head=H skipped=0} line, ONE
 *       {@code registry.projection_live} line with the right counts, and the registry
 *       subscriber's checkpoint ≥ H once LIVE (the bus started at H, never at 0).</li>
 *   <li><b>K-2</b> — the comparison: the same store rebuilt the OLD way (every envelope of the
 *       {@code readFrom} page walk that the filter matches, fed in order to a fresh subscriber's
 *       {@code onEvent}, a throwing row parked and the fifth SUSPENDING — the bus supervisor's
 *       arms modeled) and the NEW way ({@code rebuildFromIndex}) yield EQUAL registries, record
 *       for record. With four rows whose apply throws both ways skip the same rows and the boot's
 *       INFO reads {@code skipped=4}; with five both ways fail.</li>
 *   <li><b>K-3</b> — a restart on the same store keeps identity: the same ids and records, the
 *       same {@code devices= entities=} counts.</li>
 * </ul>
 *
 * <p>The clock is the frozen {@link TestClock} (self-enforced {@code NO_DIRECT_TIME_ACCESS} for
 * this module's test sources): the {@code ms=} of the INFO line and every
 * {@code recordSubsystem} duration read 0 here and are never asserted. Await loops are real-time
 * polls, clock-independent.</p>
 */
@DisplayName("RegistryColdBootIT — REGISTRY-COLD-1 (IR-148): the registries rebuild from the type"
        + " index up to H, the checkpoint starts at H, the bus replays only what follows")
final class RegistryColdBootIT {

    static final String DB_FILE = "homesynapse-events.db";
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAD"));
    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");
    private static final DeviceId DEVICE_ID = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final EntityId ENTITY_1_ID = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final EntityId ENTITY_2_ID = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE2");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    /** K-2's five devices and their entities. */
    private static final List<DeviceId> FIVE_DEVICE_IDS = List.of(
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAF1"),
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAF2"),
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAF3"),
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAF4"),
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAF5"));
    private static final List<EntityId> FIVE_ENTITY_IDS = List.of(
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAG1"),
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAG2"),
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAG3"),
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAG4"),
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAG5"));

    /** K-1's filler: 4 gaps × 50 = 200 non-registry rows around 3 registry rows (≥ 50 × 3). */
    private static final int K1_FILLER_PER_GAP = 50;
    private static final int K2_FILLER_PER_GAP = 20;

    /** The bus's page ({@code ReplayDriver.MAX_REPLAY_PAGE}, package-private to the bus). */
    static final int REPLAY_PAGE = 500;
    /**
     * The bus supervisor's circuit breaker ({@code SubscriberSupervisor.CIRCUIT_BREAKER_THRESHOLD},
     * private to the bus): the fifth crash SUSPENDs. K-2's five-row case fails if either this
     * model or {@code RegistryProjectionSubscriber.REBUILD_CRASH_THRESHOLD} drifts from the other.
     */
    static final int BUS_CIRCUIT_BREAKER_THRESHOLD = 5;

    private static final String REBUILT = "registry.projection_rebuilt: ";
    private static final String LIVE = "registry.projection_live: ";
    private static final String APPLY_FAILED = "registry.projection_rebuild_apply_failed: ";
    private static final String STARTUP_FAILED = "lifecycle.startup_failed: ";
    /** {@code HomeSynapseCore.recommendationFor}'s default arm — the device-model init has no arm of its own. */
    private static final String DEFAULT_RECOMMENDATION =
            "inspect the log and the JFR recording; report with the phase and subsystem named";

    private HomeSynapseCore core;
    private ListAppender<ILoggingEvent> coreLog;
    private ListAppender<ILoggingEvent> subscriberLog;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RegistryColdBootIT() {
    }

    @BeforeEach
    void attachLogCapture() {
        coreLog = attach(HomeSynapseCore.class);
        subscriberLog = attach(RegistryProjectionSubscriber.class);
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
            core = null;
        }
        detach(HomeSynapseCore.class, coreLog);
        detach(RegistryProjectionSubscriber.class, subscriberLog);
    }

    // ════════════════════════════════════════════════════════════════════════
    // K-1
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K-1: 3 registry rows among 200 others → ONE registry.projection_rebuilt: rows=3"
            + " head=H skipped=0 line, ONE registry.projection_live: devices=1 entities=2 line,"
            + " and the registry subscriber's checkpoint ≥ H once LIVE")
    void k1_coldBoot_rebuildsFromTheIndexUpToH_andCheckpointsH(@TempDir Path tempDir)
            throws Exception {
        Seed seed = seedOneDeviceTwoEntities(tempDir.resolve(DB_FILE));
        assertThat(seed.head() - seed.registryRows())
                .as("the fixture's own premise: M ≥ 50 × N non-registry rows")
                .isGreaterThanOrEqualTo(50L * seed.registryRows());

        bootCore(tempDir, new InMemoryDeviceRegistry());
        SubscriberSnapshot live = awaitRegistryLive();
        awaitTrue(() -> !lines(subscriberLog, LIVE).isEmpty(), "the registry.projection_live INFO");

        assertThat(lines(coreLog, REBUILT))
                .as("the boot's positive evidence: the rows read, the head, nothing skipped")
                .singleElement().asString()
                .startsWith(REBUILT + "rows=" + seed.registryRows() + " head=" + seed.head()
                        + " skipped=0 ms=");
        assertThat(lines(subscriberLog, LIVE))
                .as("today's line, today's meaning: position= is the last registry row applied")
                .containsExactly(LIVE + "devices=1 entities=2 position="
                        + seed.lastRegistrationPosition());
        assertThat(live.checkpoint())
                .as("the checkpoint starts at H (Step 3.1 writes it; the bus can only raise it)")
                .isGreaterThanOrEqualTo(seed.head());
        assertThat(core.deviceRegistry().findDevice(DEVICE_ID)).isPresent();
        assertThat(core.entityRegistry().listAllEntities())
                .extracting(Entity::entityId)
                .containsExactlyInAnyOrder(ENTITY_1_ID, ENTITY_2_ID);

        // §9 of the instruction: the recordSubsystem timings this boot shows. Under the frozen
        // TestClock every duration is PT0S — printed for the return, claimed for nothing.
        System.out.println("registry-cold.k1: " + lines(coreLog, REBUILT).get(0));
        core.subsystemStates().forEach((name, state) -> System.out.println(
                "registry-cold.k1: subsystem=" + name + " phase=" + state.phase()
                        + " init_ms=" + state.initializationDuration().toMillis()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // K-2
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K-2 THE COMPARISON: the same store rebuilt the OLD way (the readFrom page walk,"
            + " filter, onEvent in order) and the NEW way (rebuildFromIndex) → the device and"
            + " entity registries EQUAL, record for record")
    void k2_oldWayAndNewWay_yieldEqualRegistries(@TempDir Path tempDir) throws Exception {
        try (PersistenceFactory seed = standaloneFactory(tempDir.resolve(DB_FILE))) {
            seedFiveDevices(seed.eventPublisher());
            EventStore store = seed.eventStore();
            long head = store.latestPosition();

            OldWay old = rebuildTheOldWay(store, new InMemoryDeviceRegistry());
            NewWay fresh = rebuildTheNewWay(store, head, new InMemoryDeviceRegistry());

            assertThat(old.suspended()).isFalse();
            assertThat(old.parked()).isZero();
            assertThat(fresh.skipped()).isZero();
            assertThat(fresh.passed()).as("the ten registry rows").isEqualTo(10);
            assertThat(fresh.devices())
                    .containsExactlyInAnyOrderElementsOf(old.devices())
                    .hasSize(5);
            assertThat(fresh.entities())
                    .containsExactlyInAnyOrderElementsOf(old.entities())
                    .hasSize(5);
            System.out.println("registry-cold.k2: head=" + head + " old_way_rows_walked="
                    + old.rowsWalked() + " new_way_rows_passed=" + fresh.passed());
        }
    }

    @Test
    @DisplayName("K-2 FOUR BAD ROWS (parity): four device_registered rows whose apply throws →"
            + " both ways yield the same registries minus those rows, and the booted core's INFO"
            + " line reads skipped=4 with four registry.projection_rebuild_apply_failed WARNs")
    void k2_fourBadRows_bothWaysSkipTheSameRows_infoSaysSkipped4(@TempDir Path tempDir)
            throws Exception {
        Set<DeviceId> poisoned = new HashSet<>(FIVE_DEVICE_IDS.subList(0, 4));
        OldWay old;
        long head;
        try (PersistenceFactory seed = standaloneFactory(tempDir.resolve(DB_FILE))) {
            seedFiveDevices(seed.eventPublisher());
            EventStore store = seed.eventStore();
            head = store.latestPosition();

            old = rebuildTheOldWay(store, poisoned(poisoned));
            NewWay fresh = rebuildTheNewWay(store, head, poisoned(poisoned));

            assertThat(old.parked()).isEqualTo(4);
            assertThat(old.suspended()).isFalse();
            assertThat(fresh.skipped()).isEqualTo(4);
            assertThat(fresh.devices())
                    .containsExactlyInAnyOrderElementsOf(old.devices())
                    .extracting(Device::deviceId)
                    .containsExactly(FIVE_DEVICE_IDS.get(4));
            assertThat(fresh.entities())
                    .containsExactlyInAnyOrderElementsOf(old.entities())
                    .hasSize(5);
        }
        // The subscriber-level run above wrote its four WARNs to the same class logger; the
        // boot's four are the ones under assertion.
        subscriberLog.list.clear();

        bootCore(tempDir, poisoned(poisoned));
        awaitRegistryLive();

        assertThat(lines(coreLog, REBUILT))
                .singleElement().asString()
                .startsWith(REBUILT + "rows=10 head=" + head + " skipped=4 ms=");
        assertThat(lines(subscriberLog, APPLY_FAILED)).hasSize(4);
        assertThat(core.deviceRegistry().listAllDevices())
                .containsExactlyInAnyOrderElementsOf(old.devices());
    }

    @Test
    @DisplayName("K-2 FIVE BAD ROWS (parity): five device_registered rows whose apply throws →"
            + " the old way's model reports SUSPENDED, rebuildFromIndex throws"
            + " IllegalStateException, and the boot fails inside Step 3.1 reporting"
            + " (CORE_DOMAIN, device-model) with no INFO line")
    void k2_fiveBadRows_bothWaysFail(@TempDir Path tempDir) throws Exception {
        Set<DeviceId> poisoned = new HashSet<>(FIVE_DEVICE_IDS);
        try (PersistenceFactory seed = standaloneFactory(tempDir.resolve(DB_FILE))) {
            seedFiveDevices(seed.eventPublisher());
            EventStore store = seed.eventStore();
            long head = store.latestPosition();

            OldWay old = rebuildTheOldWay(store, poisoned(poisoned));
            assertThat(old.suspended()).as("the fifth crash trips the bus's breaker").isTrue();
            assertThat(old.parked()).isEqualTo(BUS_CIRCUIT_BREAKER_THRESHOLD);

            assertThatThrownBy(() -> rebuildTheNewWay(store, head, poisoned(poisoned)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("5 ");
        }
        subscriberLog.list.clear();

        core = newCore(tempDir, poisoned(poisoned));
        Throwable fatal = catchThrowable(core::start);

        assertThat(fatal)
                .as("the rebuild's own exception, unwrapped, out of start()")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5 ");
        assertThat(core.lastStartupFailure()).contains(new StartupFailureReport(
                LifecyclePhase.CORE_DOMAIN, "device-model", DEFAULT_RECOMMENDATION));
        assertThat(lines(coreLog, STARTUP_FAILED)).singleElement().asString()
                .startsWith(STARTUP_FAILED + "phase=CORE_DOMAIN subsystem=device-model ");
        assertThat(lines(coreLog, REBUILT))
                .as("the boot fails inside Step 3.1, before the INFO line")
                .isEmpty();
        assertThat(lines(subscriberLog, APPLY_FAILED)).hasSize(5);
    }

    // ════════════════════════════════════════════════════════════════════════
    // K-3
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K-3: a restart on the same store keeps identity — the same deviceIds and"
            + " entityIds with equal records, and the same devices=1 entities=2 as before the stop")
    void k3_restart_keepsIdentity(@TempDir Path tempDir) throws Exception {
        Seed seed = seedOneDeviceTwoEntities(tempDir.resolve(DB_FILE));
        bootCore(tempDir, new InMemoryDeviceRegistry());
        awaitRegistryLive();
        awaitTrue(() -> !lines(subscriberLog, LIVE).isEmpty(), "the first boot's LIVE INFO");
        List<Device> devicesBefore = List.copyOf(core.deviceRegistry().listAllDevices());
        List<Entity> entitiesBefore = List.copyOf(core.entityRegistry().listAllEntities());
        String liveBefore = lines(subscriberLog, LIVE).get(0);
        assertThat(liveBefore).startsWith(LIVE + "devices=1 entities=2 position=");
        core.stop();
        core = null;
        coreLog.list.clear();
        subscriberLog.list.clear();

        bootCore(tempDir, new InMemoryDeviceRegistry());
        awaitRegistryLive();
        awaitTrue(() -> !lines(subscriberLog, LIVE).isEmpty(), "the second boot's LIVE INFO");

        assertThat(core.deviceRegistry().listAllDevices())
                .as("the SAME deviceId resolves with an EQUAL record")
                .containsExactlyInAnyOrderElementsOf(devicesBefore)
                .hasSize(1);
        assertThat(core.entityRegistry().listAllEntities())
                .as("the SAME entityIds resolve with EQUAL records")
                .containsExactlyInAnyOrderElementsOf(entitiesBefore)
                .hasSize(2);
        assertThat(lines(subscriberLog, LIVE))
                .singleElement().asString()
                .startsWith(LIVE + "devices=1 entities=2 position=");
        assertThat(lines(coreLog, REBUILT))
                .singleElement().asString()
                .startsWith(REBUILT + "rows=" + seed.registryRows() + " head=");
    }

    // ════════════════════════════════════════════════════════════════════════
    // The two ways — shared with RegistryRealStoreComparisonIT (K-4)
    // ════════════════════════════════════════════════════════════════════════

    /** What the bus's path yields: the registries, the parked count, whether the fifth suspended. */
    record OldWay(List<Device> devices, List<Entity> entities, int parked, boolean suspended,
            long rowsWalked) {
    }

    /** What {@code rebuildFromIndex} yields: the registries, the rows passed, the rows skipped. */
    record NewWay(List<Device> devices, List<Entity> entities, long passed, long skipped) {
    }

    /**
     * The OLD way, modeled: every envelope of the {@code readFrom} page walk that the
     * subscriber's own filter matches goes to a fresh subscriber's {@code onEvent} in log order;
     * a {@code RuntimeException} parks the row (the supervisor's PARKED arm) and the fifth
     * SUSPENDs — the model stops and reports it.
     */
    static OldWay rebuildTheOldWay(EventStore store, DeviceRegistry deviceRegistry) {
        InMemoryEntityRegistry entityRegistry = new InMemoryEntityRegistry();
        RegistryProjectionSubscriber subscriber = new RegistryProjectionSubscriber(
                new RegistryProjection(deviceRegistry, entityRegistry), deviceRegistry,
                entityRegistry);
        SubscriptionFilter filter = RegistryProjectionSubscriber.subscriptionFilter();
        int parked = 0;
        long after = 0L;
        long walked = 0L;
        while (true) {
            EventPage page = store.readFrom(after, REPLAY_PAGE);
            if (page.events().isEmpty()) {
                break;
            }
            for (EventEnvelope row : page.events()) {
                walked++;
                if (filter.matches(row)) {
                    try {
                        subscriber.onEvent(row);
                    } catch (RuntimeException parkedRow) {
                        parked++;
                        if (parked >= BUS_CIRCUIT_BREAKER_THRESHOLD) {
                            return new OldWay(List.copyOf(deviceRegistry.listAllDevices()),
                                    List.copyOf(entityRegistry.listAllEntities()), parked, true,
                                    walked);
                        }
                    }
                }
                after = row.globalPosition();
            }
        }
        return new OldWay(List.copyOf(deviceRegistry.listAllDevices()),
                List.copyOf(entityRegistry.listAllEntities()), parked, false, walked);
    }

    /** The NEW way: {@code rebuildFromIndex} on a fresh subscriber over fresh registries. */
    static NewWay rebuildTheNewWay(EventStore store, long head, DeviceRegistry deviceRegistry) {
        InMemoryEntityRegistry entityRegistry = new InMemoryEntityRegistry();
        RegistryProjectionSubscriber subscriber = new RegistryProjectionSubscriber(
                new RegistryProjection(deviceRegistry, entityRegistry), deviceRegistry,
                entityRegistry);
        long passed = subscriber.rebuildFromIndex(store, head);
        return new NewWay(List.copyOf(deviceRegistry.listAllDevices()),
                List.copyOf(entityRegistry.listAllEntities()), passed, subscriber.skipped());
    }

    /** The event-class manifest {@link HomeSynapseCore} builds — the seed's payloads round-trip. */
    static List<Class<? extends DomainEvent>> eventClasses() {
        return Stream.of(
                        EventTypes.CORE_PRODUCTION_EVENT_CLASSES,
                        IntegrationEvents.LIFECYCLE_EVENT_CLASSES,
                        IntegrationEvents.CAPABILITY_EVENT_CLASSES)
                .flatMap(List::stream)
                .toList();
    }

    // ════════════════════════════════════════════════════════════════════════
    // The seeds (a standalone PersistenceFactory: no bus, no subscribers, no checkpoint)
    // ════════════════════════════════════════════════════════════════════════

    /** The seed's shape as the standalone publisher assigned it. */
    private record Seed(long head, long lastRegistrationPosition, int registryRows) {
    }

    private static PersistenceFactory standaloneFactory(Path dbPath) {
        return PersistenceFactory.start(dbPath, HomeSynapseConfig.testing().persistence(),
                TestClock.createDefault(), TEST_HOME_ID, eventClasses(), null);
    }

    /** One device, two entities, 50 filler rows before each registry row and after the last. */
    private static Seed seedOneDeviceTwoEntities(Path dbPath) throws Exception {
        try (PersistenceFactory seed = standaloneFactory(dbPath)) {
            EventPublisher publisher = seed.eventPublisher();
            filler(publisher, K1_FILLER_PER_GAP);
            publishRegistration(publisher, device(DEVICE_ID, "0011223344556677"));
            filler(publisher, K1_FILLER_PER_GAP);
            publishRegistration(publisher, sensorEntity(ENTITY_1_ID, DEVICE_ID, 1));
            filler(publisher, K1_FILLER_PER_GAP);
            long last = publishRegistration(publisher, sensorEntity(ENTITY_2_ID, DEVICE_ID, 2))
                    .globalPosition();
            filler(publisher, K1_FILLER_PER_GAP);
            return new Seed(seed.eventStore().latestPosition(), last, 3);
        }
    }

    /** Five devices, one entity each, 20 filler rows before every registry row. */
    private static void seedFiveDevices(EventPublisher publisher) throws Exception {
        for (int i = 0; i < FIVE_DEVICE_IDS.size(); i++) {
            filler(publisher, K2_FILLER_PER_GAP);
            publishRegistration(publisher, device(FIVE_DEVICE_IDS.get(i),
                    String.format("00112233445566%02X", 0x80 + i)));
            filler(publisher, K2_FILLER_PER_GAP);
            publishRegistration(publisher,
                    sensorEntity(FIVE_ENTITY_IDS.get(i), FIVE_DEVICE_IDS.get(i), 1));
        }
    }

    private static EventEnvelope publishRegistration(EventPublisher publisher, Device device)
            throws Exception {
        return publisher.publishRoot(new EventDraft(
                EventTypes.DEVICE_REGISTERED, 1, null, SubjectRef.device(device.deviceId()),
                EventPriority.NORMAL, EventOrigin.INTEGRATION,
                RegistryEventMapper.toPayload(device), null, null));
    }

    private static EventEnvelope publishRegistration(EventPublisher publisher, Entity entity)
            throws Exception {
        return publisher.publishRoot(new EventDraft(
                EventTypes.ENTITY_REGISTERED, 1, null, SubjectRef.entity(entity.entityId()),
                EventPriority.NORMAL, EventOrigin.INTEGRATION,
                RegistryEventMapper.toPayload(entity), null, null));
    }

    /** The log's bulk — {@code state_reported} rows for a sensor the seed registers. */
    private static void filler(EventPublisher publisher, int rows) throws Exception {
        for (int i = 0; i < rows; i++) {
            publisher.publishRoot(new EventDraft(
                    EventTypes.STATE_REPORTED, 1, null, SubjectRef.entity(ENTITY_2_ID),
                    EventPriority.DIAGNOSTIC, EventOrigin.DEVICE_AUTONOMOUS,
                    new StateReportedEvent("temperature_c", (i % 2 == 0) ? "21.5" : "21.6",
                            null, null, null),
                    null, null));
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Device device(DeviceId deviceId, String ieee) {
        return new Device(
                deviceId, "zigbee-" + ieee, "Signify Hue Bulb",
                "Signify", "LWA021", null, "1.108.7", null,
                INTEGRATION_ID, null, null, List.of(),
                Set.of(new HardwareIdentifier("zigbee", ieee)),
                CREATED_AT);
    }

    private static Entity sensorEntity(EntityId entityId, DeviceId deviceId, int endpoint) {
        AttributeSchema temperature = new AttributeSchema(
                "temperature_c", AttributeType.FLOAT, -40.0d, 125.0d, null, null,
                "°C", "°C", Set.of(Permission.READ), false, true);
        CapabilityInstance measurement = new CapabilityInstance(
                "temperature_measurement", 1, "core", 0,
                Map.of("temperature_c", temperature),
                Map.of(),
                new ConfirmationPolicy(ConfirmationMode.DISABLED, List.of(), null, 5000L));
        return new Entity(
                entityId, "zigbee-" + deviceId + "-ep" + endpoint, EntityType.SENSOR,
                "Signify Hue Bulb", deviceId, endpoint, null, true, List.of(),
                List.of(measurement), EntityRole.DIAGNOSTIC, CREATED_AT);
    }

    private static DeviceRegistry poisoned(Set<DeviceId> ids) {
        return new RegistryProjectionSubscriberTest.ThrowingDeviceRegistry(
                new InMemoryDeviceRegistry(), ids);
    }

    // ── the boot and its awaits ─────────────────────────────────────────────

    private HomeSynapseCore newCore(Path tempDir, DeviceRegistry deviceRegistry) throws Exception {
        Files.createDirectories(tempDir.resolve("config"));
        return new HomeSynapseCore(
                tempDir.resolve(DB_FILE),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                TestClock.createDefault(),
                TEST_HOME_ID,
                null,
                List.of(),
                deviceRegistry);
    }

    private void bootCore(Path tempDir, DeviceRegistry deviceRegistry) throws Exception {
        core = newCore(tempDir, deviceRegistry);
        core.start();
    }

    private Optional<SubscriberSnapshot> registrySnapshot() {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> RegistryProjectionSubscriber.SUBSCRIBER_ID
                        .equals(snapshot.subscriberId()))
                .findFirst();
    }

    private SubscriberSnapshot awaitRegistryLive() {
        for (int poll = 0; poll < 500; poll++) {
            Optional<SubscriberSnapshot> snapshot = registrySnapshot();
            if (snapshot.isPresent() && snapshot.get().mode() == SubscriberMode.LIVE) {
                return snapshot.get();
            }
            sleepBriefly();
        }
        throw new AssertionError("the registry projection did not reach LIVE within ~10 s;"
                + " snapshot=" + registrySnapshot());
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        for (int poll = 0; poll < 500; poll++) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("timed out awaiting " + what);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the cold boot", ex);
        }
    }

    // ── log capture ─────────────────────────────────────────────────────────

    private static List<String> lines(ListAppender<ILoggingEvent> appender, String prefix) {
        return new ArrayList<>(appender.list).stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
    }

    private static ListAppender<ILoggingEvent> attach(Class<?> loggerClass) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger(loggerClass).addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        if (appender != null) {
            logger(loggerClass).detachAppender(appender);
            appender.stop();
        }
    }

    private static ch.qos.logback.classic.Logger logger(Class<?> loggerClass) {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerClass);
    }
}

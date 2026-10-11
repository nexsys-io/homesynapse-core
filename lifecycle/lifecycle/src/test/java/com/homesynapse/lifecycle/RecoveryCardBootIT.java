/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.Permission;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.ProbeAnsweredEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.persistence.PersistenceFactory;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.RecoveryCardProjection;
import com.homesynapse.state.RecoveryCardQueryService;
import com.homesynapse.state.RecoveryCardRecord;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * AVAIL-API-1 — the recovery-card projection at the composition root (AMD-103 R-F; §4.5, §4.8),
 * booted the way {@link RegistryColdBootIT} boots: a standalone {@link PersistenceFactory} seeds
 * the log, then the REAL composition root opens the same file.
 *
 * <ul>
 *   <li><b>B-1</b> (V-1 at the root) — the first boot catches up from the type index after the
 *       registry is LIVE: ONE {@code RecoveryCardProjection caught up at position H;
 *       projection.replay.duration_ms=… events_replayed=N} line (N = the three types' rows ≤ H),
 *       the view holds the device's record, the projection reaches LIVE and its bus checkpoint
 *       is ≥ H (durable and coupled: the bus started at H).</li>
 *   <li><b>B-2</b> (V-7) — a restart on the persisted checkpoint does NOT re-run the catch-up:
 *       no {@code caught up at position} line, one {@code restored at position} line, the view
 *       intact.</li>
 *   <li><b>B-3</b> (V-4) — a failed catch-up does not fail the boot: the composition root's arm
 *       ({@link HomeSynapseCore#bootRecoveryCard}) on a store that throws on {@code readByType}
 *       logs {@code recovery_card.catchup_failed} ONCE, leaves the projection not LIVE and
 *       returns without throwing — the boot continues (the arm modeled as K-2 modeled the bus).</li>
 * </ul>
 *
 * <p>The clock is the frozen {@link TestClock}: {@code duration_ms} reads 0 and is never
 * asserted. Await loops are real-time polls, clock-independent.</p>
 */
@DisplayName("RecoveryCardBootIT — AVAIL-API-1: the recovery-card projection catches up from the"
        + " type index once, restores on a restart, and a failed catch-up never fails the boot")
final class RecoveryCardBootIT {

    static final String DB_FILE = "homesynapse-events.db";
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAD"));
    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");
    private static final DeviceId DEVICE_ID = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final EntityId ENTITY_ID = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T0 = Instant.parse("2026-10-11T08:00:00Z");
    private static final int FILLER_PER_GAP = 20;

    private static final String CAUGHT_UP = "RecoveryCardProjection caught up at position ";
    private static final String RESTORED = "RecoveryCardProjection restored at position ";
    private static final String CATCHUP_FAILED = "recovery_card.catchup_failed: ";

    private HomeSynapseCore core;
    private ListAppender<ILoggingEvent> projectionLog;
    private ListAppender<ILoggingEvent> coreLog;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RecoveryCardBootIT() {
    }

    @BeforeEach
    void attachLogCapture() {
        projectionLog = attach(RecoveryCardProjection.class);
        coreLog = attach(HomeSynapseCore.class);
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
            core = null;
        }
        detach(RecoveryCardProjection.class, projectionLog);
        detach(HomeSynapseCore.class, coreLog);
    }

    @Test
    @DisplayName("B-1: the first boot catches up from the type index — ONE caught-up line with"
            + " events_replayed=3, the view holds the device's record, LIVE, the bus checkpoint ≥ H")
    void b1_firstBoot_catchesUpOnce(@TempDir Path tempDir) throws Exception {
        Seed seed = seedLog(tempDir.resolve(DB_FILE));

        bootCore(tempDir);
        awaitTrue(() -> core.recoveryCardQueryService().isLive(), "the recovery-card projection LIVE");

        assertThat(lines(projectionLog, CAUGHT_UP))
                .singleElement().asString()
                .startsWith(CAUGHT_UP + seed.head() + "; projection.replay.duration_ms=")
                .endsWith(" events_replayed=3");
        assertThat(lines(projectionLog, RESTORED)).isEmpty();
        assertThat(lines(coreLog, CATCHUP_FAILED)).isEmpty();
        RecoveryCardQueryService cards = core.recoveryCardQueryService();
        RecoveryCardRecord record = cards.recordFor(DEVICE_ID).orElseThrow();
        assertThat(record.probeAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(record.probesAnswered()).isEqualTo(1);
        assertThat(record.availabilityClass()).isEqualTo("mains-floor");
        assertThat(record.contractAt()).isEqualTo(T0);
        assertThat(record.availabilityChangedAt())
                .as("the entity's availability_changed, attributed to its owning device")
                .isEqualTo(T0.plusSeconds(90));
        assertThat(cards.askedCount()).isEqualTo(1);
        assertThat(cards.position()).isGreaterThanOrEqualTo(seed.head());
        SubscriberSnapshot snapshot = awaitSubscriberLive();
        assertThat(snapshot.checkpoint())
                .as("the coupled checkpoint starts at H (the catch-up wrote it; the bus can only raise it)")
                .isGreaterThanOrEqualTo(seed.head());
        System.out.println("recovery-card.b1: " + lines(projectionLog, CAUGHT_UP).get(0));
    }

    @Test
    @DisplayName("B-2 (V-7): a restart on the persisted checkpoint restores and does NOT re-run the"
            + " catch-up — no caught-up line, one restored line, the view intact")
    void b2_restart_restoresWithoutCatchUp(@TempDir Path tempDir) throws Exception {
        Seed seed = seedLog(tempDir.resolve(DB_FILE));
        bootCore(tempDir);
        awaitTrue(() -> core.recoveryCardQueryService().isLive(), "the first boot LIVE");
        RecoveryCardRecord before = core.recoveryCardQueryService().recordFor(DEVICE_ID).orElseThrow();
        long positionBefore = core.recoveryCardQueryService().position();
        core.stop();
        core = null;
        projectionLog.list.clear();
        coreLog.list.clear();

        bootCore(tempDir);
        awaitTrue(() -> core.recoveryCardQueryService().isLive(), "the second boot LIVE");

        assertThat(lines(projectionLog, CAUGHT_UP))
                .as("durable and coupled: the catch-up ran ONCE")
                .isEmpty();
        assertThat(lines(projectionLog, RESTORED))
                .singleElement().asString()
                .startsWith(RESTORED + positionBefore + ";");
        assertThat(core.recoveryCardQueryService().recordFor(DEVICE_ID)).contains(before);
        assertThat(core.recoveryCardQueryService().position()).isGreaterThanOrEqualTo(seed.head());
        assertThat(lines(coreLog, CATCHUP_FAILED)).isEmpty();
    }

    @Test
    @DisplayName("B-3 (V-4): a store that throws on readByType — the composition root's arm logs"
            + " recovery_card.catchup_failed ONCE, the projection stays not LIVE, nothing throws")
    void b3_failedCatchUp_doesNotFailTheBoot(@TempDir Path tempDir) throws Exception {
        try (PersistenceFactory factory = standaloneFactory(tempDir.resolve(DB_FILE))) {
            seedRows(factory.eventPublisher());
            RecoveryCardProjection projection = new RecoveryCardProjection(
                    factory.viewCheckpointStore(), factory.atomicCheckpointSink(),
                    () -> null, TestClock.createDefault());

            boolean subscribed = HomeSynapseCore.bootRecoveryCard(projection,
                    new ThrowingTypeReadStore(factory.eventStore()));

            assertThat(subscribed).as("not subscribed: the keys stay null").isFalse();
            assertThat(lines(coreLog, CATCHUP_FAILED)).hasSize(1);
            assertThat(lines(coreLog, CATCHUP_FAILED).get(0)).contains("readByType unavailable");
            assertThat(projection.isLive()).isFalse();
            assertThat(projection.position()).isZero();
            assertThat(projection.recordFor(DEVICE_ID)).isEmpty();
            assertThat(factory.viewCheckpointStore()
                    .readLatestCheckpoint(RecoveryCardProjection.SUBSCRIBER_ID))
                    .as("no checkpoint was written").isEmpty();
        }
    }

    // ── the seed (a standalone PersistenceFactory: no bus, no subscribers, no checkpoint) ──

    private record Seed(long head) {
    }

    private static PersistenceFactory standaloneFactory(Path dbPath) {
        return PersistenceFactory.start(dbPath, HomeSynapseConfig.testing().persistence(),
                TestClock.createDefault(), TEST_HOME_ID, RegistryColdBootIT.eventClasses(), null);
    }

    private static Seed seedLog(Path dbPath) throws Exception {
        try (PersistenceFactory seed = standaloneFactory(dbPath)) {
            seedRows(seed.eventPublisher());
            return new Seed(seed.eventStore().latestPosition());
        }
    }

    /** The registrations, then the three types among fillers: a contract, a probe, an availability_changed. */
    private static void seedRows(EventPublisher publisher) throws Exception {
        publisher.publishRoot(new EventDraft(EventTypes.DEVICE_REGISTERED, 1, null,
                SubjectRef.device(DEVICE_ID), EventPriority.NORMAL, EventOrigin.INTEGRATION,
                RegistryEventMapper.toPayload(device()), null, null));
        publisher.publishRoot(new EventDraft(EventTypes.ENTITY_REGISTERED, 1, null,
                SubjectRef.entity(ENTITY_ID), EventPriority.NORMAL, EventOrigin.INTEGRATION,
                RegistryEventMapper.toPayload(sensorEntity()), null, null));
        filler(publisher, FILLER_PER_GAP);
        publisher.publishRoot(draft(EventTypes.AVAILABILITY_CONTRACT_DECLARED,
                SubjectRef.device(DEVICE_ID), EventPriority.NORMAL,
                new AvailabilityContractDeclaredEvent("mains-floor", null, 60, 2, 3600), T0));
        filler(publisher, FILLER_PER_GAP);
        publisher.publishRoot(draft(EventTypes.PROBE_ANSWERED, SubjectRef.device(DEVICE_ID),
                EventPriority.DIAGNOSTIC,
                new ProbeAnsweredEvent(T0.plusSeconds(60), T0, 1, 0, null, null, null),
                T0.plusSeconds(60)));
        publisher.publishRoot(draft(EventTypes.AVAILABILITY_CHANGED, SubjectRef.entity(ENTITY_ID),
                EventPriority.NORMAL, new AvailabilityChangedEvent("unknown", "online",
                        "first_contact", T0.plusSeconds(90), null, null, null),
                T0.plusSeconds(90)));
        filler(publisher, FILLER_PER_GAP);
    }

    private static EventDraft draft(String type, SubjectRef subject, EventPriority priority,
            DomainEvent payload, Instant eventTime) {
        return new EventDraft(type, 1, eventTime, subject, priority, EventOrigin.INTEGRATION,
                payload, null, null);
    }

    private static void filler(EventPublisher publisher, int rows) throws Exception {
        for (int i = 0; i < rows; i++) {
            publisher.publishRoot(new EventDraft(
                    EventTypes.STATE_REPORTED, 1, null, SubjectRef.entity(ENTITY_ID),
                    EventPriority.DIAGNOSTIC, EventOrigin.DEVICE_AUTONOMOUS,
                    new StateReportedEvent("temperature_c", (i % 2 == 0) ? "21.5" : "21.6",
                            null, null, null),
                    null, null));
        }
    }

    private static Device device() {
        return new Device(
                DEVICE_ID, "zigbee-0011223344556677", "Signify Hue Bulb",
                "Signify", "LWA021", null, "1.108.7", null,
                INTEGRATION_ID, null, null, List.of(),
                Set.of(new HardwareIdentifier("zigbee", "0011223344556677")),
                CREATED_AT);
    }

    private static Entity sensorEntity() {
        AttributeSchema temperature = new AttributeSchema(
                "temperature_c", AttributeType.FLOAT, -40.0d, 125.0d, null, null,
                "°C", "°C", Set.of(Permission.READ), false, true);
        CapabilityInstance measurement = new CapabilityInstance(
                "temperature_measurement", 1, "core", 0,
                Map.of("temperature_c", temperature),
                Map.of(),
                new ConfirmationPolicy(ConfirmationMode.DISABLED, List.of(), null, 5000L));
        return new Entity(
                ENTITY_ID, "zigbee-" + DEVICE_ID + "-ep1", EntityType.SENSOR,
                "Signify Hue Bulb", DEVICE_ID, 1, null, true, List.of(),
                List.of(measurement), EntityRole.DIAGNOSTIC, CREATED_AT);
    }

    // ── the boot and its awaits ─────────────────────────────────────────────

    private void bootCore(Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("config"));
        core = new HomeSynapseCore(
                tempDir.resolve(DB_FILE),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                TestClock.createDefault(),
                TEST_HOME_ID,
                null,
                List.of(),
                new InMemoryDeviceRegistry());
        core.start();
    }

    private Optional<SubscriberSnapshot> recoverySnapshot() {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> RecoveryCardProjection.SUBSCRIBER_ID
                        .equals(snapshot.subscriberId()))
                .findFirst();
    }

    private SubscriberSnapshot awaitSubscriberLive() {
        for (int poll = 0; poll < 500; poll++) {
            Optional<SubscriberSnapshot> snapshot = recoverySnapshot();
            if (snapshot.isPresent() && snapshot.get().mode() == SubscriberMode.LIVE) {
                return snapshot.get();
            }
            sleepBriefly();
        }
        throw new AssertionError("the recovery-card subscriber did not reach LIVE within ~10 s;"
                + " snapshot=" + recoverySnapshot());
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
            throw new IllegalStateException("interrupted awaiting the boot", ex);
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

    /** A store whose type read throws — V-4's instrument at the composition root. */
    static final class ThrowingTypeReadStore implements EventStore {
        private final EventStore delegate;

        ThrowingTypeReadStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            return delegate.readFrom(afterPosition, maxCount);
        }

        @Override
        public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
            return delegate.readBySubject(subject, afterSequence, maxCount);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
            return delegate.readByCorrelation(correlationId);
        }

        @Override
        public EventPage readByType(String eventType, long afterPosition, int maxCount) {
            throw new IllegalStateException("readByType unavailable: " + eventType);
        }

        @Override
        public EventPage readByTimeRange(Instant from, Instant to, long afterPosition,
                int maxCount) {
            return delegate.readByTimeRange(from, to, afterPosition, maxCount);
        }

        @Override
        public long latestPosition() {
            return delegate.latestPosition();
        }
    }
}

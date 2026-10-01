/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.CapabilityAdded;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * IR-67 T11 — the registry projection's bus consumer carries {@code capability.added}
 * beside the three registration/removal types, and routes it through the single
 * apply path (REG-INV-1): replay and live delivery run the identical apply; an
 * entity the registry lacks is the orphan no-op with ONE WARN (the DP-7 posture).
 */
@DisplayName("RegistryProjectionSubscriber — the capability.added consumer (IR-67)")
final class RegistryProjectionSubscriberTest {

    private static final DeviceId DEVICE_ID =
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final EntityId ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");

    private TestClock clock;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private RegistryProjection projection;
    private RegistryProjectionSubscriber subscriber;
    private ListAppender<ILoggingEvent> subscriberLog;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RegistryProjectionSubscriberTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        projection = new RegistryProjection(deviceRegistry, entityRegistry);
        subscriber = new RegistryProjectionSubscriber(projection, deviceRegistry,
                entityRegistry);
        // The entity the log carries: registered through the single apply path
        // with ONE capability, so an appended id is observable.
        projection.applyDeviceRegistered(RegistryEventMapper.toPayload(device()));
        projection.applyEntityRegistered(RegistryEventMapper.toPayload(
                entity(List.of(instance(StandardCapabilities.occupancy())))));
        subscriberLog = new ListAppender<>();
        subscriberLog.start();
        subscriberLogger().addAppender(subscriberLog);
    }

    @AfterEach
    void tearDown() {
        subscriberLogger().detachAppender(subscriberLog);
        subscriberLog.stop();
    }

    @Test
    @DisplayName("T11: the subscription filter carries capability.added beside the three "
            + "registration/removal types — replay AND live route through this subscriber")
    void filterCarriesCapabilityAdded() {
        assertThat(RegistryProjectionSubscriber.subscriptionFilter().eventTypes())
                .containsExactlyInAnyOrder(
                        EventTypes.DEVICE_REGISTERED,
                        EventTypes.ENTITY_REGISTERED,
                        EventTypes.DEVICE_REMOVED,
                        EventTypes.CAPABILITY_ADDED);
    }

    @Test
    @DisplayName("T11: onEvent with a CapabilityAdded payload appends the id to the "
            + "registry's entity through RegistryProjection.applyCapabilityAdded")
    void onEvent_capabilityAdded_appliesToTheEntity() {
        CapabilityInstance illuminance =
                instance(StandardCapabilities.illuminanceMeasurement());

        subscriber.onEvent(envelope(7L,
                new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, ENTITY_ID, illuminance)));

        assertThat(entityRegistry.getEntity(ENTITY_ID).capabilities())
                .extracting(CapabilityInstance::capabilityId)
                .containsExactly("occupancy", "illuminance_measurement");
        assertThat(messages(Level.WARN)).as("no WARN on the happy path").isEmpty();
    }

    @Test
    @DisplayName("T11: a CapabilityAdded for an entity the registry lacks is the orphan "
            + "no-op — no throw, ONE registry.capability_added_orphan WARN naming the position")
    void onEvent_orphan_noThrow() {
        EntityId orphan = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE9");
        CapabilityInstance illuminance =
                instance(StandardCapabilities.illuminanceMeasurement());

        assertThatCode(() -> subscriber.onEvent(envelope(9L,
                new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, orphan, illuminance))))
                .doesNotThrowAnyException();

        assertThat(messages(Level.WARN))
                .containsExactly("registry.capability_added_orphan: entity=" + orphan
                        + " capability=illuminance_measurement position=9");
        assertThat(entityRegistry.listAllEntities())
                .as("the orphan arm writes nothing")
                .hasSize(1);
        assertThat(entityRegistry.getEntity(ENTITY_ID).capabilities()).hasSize(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** An envelope built by hand — the {@code NotifyingEventPublisherTest} :164 form. */
    private EventEnvelope envelope(long position, CapabilityAdded payload) {
        Ulid ulid = UlidFactory.generate(clock);
        return new EventEnvelope(
                EventId.of(ulid),
                EventTypes.CAPABILITY_ADDED,
                1,
                clock.instant(),
                clock.instant(),
                SubjectRef.entity(payload.entityId()),
                1L,
                position,
                EventPriority.NORMAL,
                EventOrigin.INTEGRATION,
                List.of(EventCategory.DEVICE_STATE),
                CausalContext.root(ulid),
                null,
                payload);
    }

    private static CapabilityInstance instance(Capability capability) {
        return new CapabilityInstance(capability.capabilityId(), capability.version(),
                capability.namespace(), 0, capability.attributeSchemas(),
                capability.commandDefinitions(), capability.confirmationPolicy());
    }

    private Device device() {
        return new Device(
                DEVICE_ID,
                "zigbee-0011223344556677",
                "Hue motion",
                "Signify",
                "SML003",
                null,
                null,
                null,
                INTEGRATION_ID,
                null,
                null,
                List.of(),
                Set.of(new HardwareIdentifier("zigbee", "0011223344556677")),
                clock.instant());
    }

    private Entity entity(List<CapabilityInstance> capabilities) {
        return new Entity(
                ENTITY_ID,
                "zigbee-0011223344556677-ep1",
                EntityType.BINARY_SENSOR,
                "Hue motion",
                DEVICE_ID,
                1,
                null,
                true,
                List.of(),
                capabilities,
                clock.instant());
    }

    private List<String> messages(Level level) {
        return subscriberLog.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static Logger subscriberLogger() {
        return (Logger) LoggerFactory.getLogger(RegistryProjectionSubscriber.class);
    }
}

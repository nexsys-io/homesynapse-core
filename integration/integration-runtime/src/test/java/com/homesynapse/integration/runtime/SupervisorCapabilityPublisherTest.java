/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CustomCapability;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.IlluminanceMeasurement;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.CapabilityAdded;
import com.homesynapse.integration.CapabilityRemovalReason;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IR-67 T1–T3 — {@link SupervisorCapabilityPublisher}, the DISCOVERY family's
 * publisher (AMD-59): one {@code capability.added} root draft on the ENTITY
 * subject per call, the entity resolved from the shared registry (unknown or
 * device-less → {@code IllegalArgumentException}, never a silent publish),
 * the typed overload over the standard instances, {@code publishRemoved}
 * mechanically shut, and the {@code SequenceConflictException} caught.
 *
 * <p>The WARN token of the conflict arm is NOT asserted: integration-runtime's
 * test classpath carries no logback (E1) and this lane edits no build file.</p>
 */
@DisplayName("SupervisorCapabilityPublisher — the DISCOVERY family's publisher (IR-67, AMD-59)")
final class SupervisorCapabilityPublisherTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");
    private static final DeviceId DEVICE_ID =
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final EntityId ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final EntityId SECOND_ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE2");
    private static final EntityId HELPER_ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE3");
    private static final EntityId UNKNOWN_ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE9");

    private TestClock clock;
    private InMemoryEntityRegistry entityRegistry;
    private RecordingPublisher publisher;
    private SupervisorCapabilityPublisher capabilityPublisher;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    SupervisorCapabilityPublisherTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.at(T0);
        entityRegistry = new InMemoryEntityRegistry();
        entityRegistry.createEntity(entity(ENTITY_ID, DEVICE_ID, 1));
        entityRegistry.createEntity(entity(SECOND_ENTITY_ID, DEVICE_ID, 2));
        // A helper entity: deviceId is null by contract (Entity.java :41/:100).
        entityRegistry.createEntity(entity(HELPER_ENTITY_ID, null, 0));
        publisher = new RecordingPublisher(clock);
        capabilityPublisher = new SupervisorCapabilityPublisher(
                INTEGRATION_ID, publisher, entityRegistry, clock);
    }

    // ── T1 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T1: publishAdded(instance) publishes ONE capability.added root draft on "
            + "the ENTITY subject — schema 1, origin INTEGRATION, priority NORMAL, "
            + "eventTime = the clock, payload CapabilityAdded(integration, the entity's "
            + "device, entity, instance)")
    void publishAdded_instance_publishesCapabilityAddedOnTheEntity() {
        CapabilityInstance illuminance = instance(StandardCapabilities.illuminanceMeasurement());

        capabilityPublisher.publishAdded(ENTITY_ID, illuminance);

        assertThat(publisher.drafts()).hasSize(1);
        EventDraft draft = publisher.drafts().get(0);
        assertThat(draft.eventType()).isEqualTo(EventTypes.CAPABILITY_ADDED);
        assertThat(draft.schemaVersion()).isEqualTo(1);
        assertThat(draft.subjectRef()).isEqualTo(SubjectRef.entity(ENTITY_ID));
        assertThat(draft.origin()).isEqualTo(EventOrigin.INTEGRATION);
        assertThat(draft.priority()).isEqualTo(EventPriority.NORMAL);
        assertThat(draft.eventTime()).isEqualTo(T0);
        assertThat(draft.actorRef()).isNull();
        assertThat(draft.idempotencyKey()).isNull();
        assertThat(draft.payload()).isEqualTo(
                new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, ENTITY_ID, illuminance));
    }

    @Test
    @DisplayName("T1: an entity the registry lacks → IllegalArgumentException, ZERO drafts "
            + "(never a silent publish)")
    void publishAdded_unknownEntity_throwsAndPublishesNothing() {
        assertThatThrownBy(() -> capabilityPublisher.publishAdded(UNKNOWN_ENTITY_ID,
                instance(StandardCapabilities.illuminanceMeasurement())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown entity");
        assertThat(publisher.drafts()).isEmpty();
    }

    @Test
    @DisplayName("T1: a helper entity (deviceId null) → IllegalArgumentException, ZERO "
            + "drafts — never the record constructor's NPE (E8)")
    void publishAdded_helperEntityWithoutDevice_throwsAndPublishesNothing() {
        assertThatThrownBy(() -> capabilityPublisher.publishAdded(HELPER_ENTITY_ID,
                instance(StandardCapabilities.illuminanceMeasurement())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no device");
        assertThat(publisher.drafts()).isEmpty();
    }

    @Test
    @DisplayName("T1: a SequenceConflictException from publishRoot is caught (ONE WARN), "
            + "never thrown — the next publishAdded on a sound publisher still drafts")
    void publishAdded_sequenceConflict_isCaughtNotThrown() {
        SupervisorCapabilityPublisher conflicting = new SupervisorCapabilityPublisher(
                INTEGRATION_ID, new ConflictingPublisher(), entityRegistry, clock);

        assertThatCode(() -> conflicting.publishAdded(ENTITY_ID,
                instance(StandardCapabilities.illuminanceMeasurement())))
                .doesNotThrowAnyException();

        capabilityPublisher.publishAdded(ENTITY_ID,
                instance(StandardCapabilities.illuminanceMeasurement()));
        assertThat(publisher.drafts()).hasSize(1);
    }

    // ── T2 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T2: publishAdded(permit class) resolves the standard instance — "
            + "illuminance_measurement with featureMap 0 (row 23); a CustomCapability "
            + "class has no standard instance → IllegalArgumentException")
    void publishAdded_class_resolvesTheStandardInstance() {
        capabilityPublisher.publishAdded(ENTITY_ID, IlluminanceMeasurement.class);

        assertThat(publisher.drafts()).hasSize(1);
        CapabilityAdded payload = (CapabilityAdded) publisher.drafts().get(0).payload();
        assertThat(payload.instance().capabilityId())
                .isEqualTo(StandardCapabilities.illuminanceMeasurement().capabilityId());
        assertThat(payload.instance().featureMap()).isZero();
        assertThat(payload.instance())
                .isEqualTo(instance(StandardCapabilities.illuminanceMeasurement()));

        assertThatThrownBy(() -> capabilityPublisher.publishAdded(ENTITY_ID,
                CustomCapability.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(publisher.drafts()).hasSize(1);
    }

    @Test
    @DisplayName("T2: publishRemoved throws UnsupportedOperationException — removal=none "
            + "is mechanical, not a convention; ZERO drafts")
    void publishRemoved_throws() {
        assertThatThrownBy(() -> capabilityPublisher.publishRemoved(ENTITY_ID, "x",
                CapabilityRemovalReason.DEVICE_REPLACED))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(publisher.drafts()).isEmpty();
    }

    // ── T3 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T3: two threads publishing for two entities → two drafts (stateless over "
            + "the shared publisher — a smoke row)")
    void publishAdded_isThreadAgnostic() throws InterruptedException {
        Thread first = Thread.ofVirtual().name("capability-publisher-1").start(
                () -> capabilityPublisher.publishAdded(ENTITY_ID,
                        instance(StandardCapabilities.illuminanceMeasurement())));
        Thread second = Thread.ofVirtual().name("capability-publisher-2").start(
                () -> capabilityPublisher.publishAdded(SECOND_ENTITY_ID,
                        instance(StandardCapabilities.temperatureMeasurement())));
        first.join(5_000L);
        second.join(5_000L);

        assertThat(publisher.drafts()).hasSize(2);
        assertThat(publisher.drafts()).extracting(EventDraft::subjectRef)
                .containsExactlyInAnyOrder(
                        SubjectRef.entity(ENTITY_ID), SubjectRef.entity(SECOND_ENTITY_ID));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** The classifier's seven-arg instance form (featureMap 0 — row 23). */
    private static CapabilityInstance instance(Capability capability) {
        return new CapabilityInstance(capability.capabilityId(), capability.version(),
                capability.namespace(), 0, capability.attributeSchemas(),
                capability.commandDefinitions(), capability.confirmationPolicy());
    }

    private static Entity entity(EntityId entityId, DeviceId deviceId, int endpoint) {
        return new Entity(
                entityId,
                "zigbee-0011223344556677-ep" + endpoint,
                EntityType.SENSOR,
                "Hue motion",
                deviceId,
                endpoint,
                null,
                true,
                List.of(),
                List.of(),
                T0);
    }

    /** A two-method {@link EventPublisher} fake recording every draft (thread-safe — T3). */
    private static final class RecordingPublisher implements EventPublisher {

        private final Clock clock;
        private final List<EventDraft> drafts = new CopyOnWriteArrayList<>();
        private final AtomicLong position = new AtomicLong();

        private RecordingPublisher(Clock clock) {
            this.clock = clock;
        }

        @Override
        public EventEnvelope publish(EventDraft draft, CausalContext cause) {
            return record(draft, cause);
        }

        @Override
        public EventEnvelope publishRoot(EventDraft draft) {
            return record(draft, null);
        }

        List<EventDraft> drafts() {
            return List.copyOf(drafts);
        }

        private EventEnvelope record(EventDraft draft, CausalContext cause) {
            drafts.add(draft);
            long at = position.incrementAndGet();
            EventId eventId = EventId.of(UlidFactory.generate(clock));
            return new EventEnvelope(
                    eventId,
                    draft.eventType(),
                    draft.schemaVersion(),
                    clock.instant(),
                    draft.eventTime(),
                    draft.subjectRef(),
                    at,
                    at,
                    draft.priority(),
                    draft.origin(),
                    List.of(EventCategory.DEVICE_STATE),
                    cause != null ? cause : CausalContext.root(eventId.value()),
                    draft.actorRef(),
                    draft.payload());
        }
    }

    /**
     * The E1 fake: both methods throw the CHECKED {@link SequenceConflictException}
     * ({@code EventPublisher.java} :96/:127) — the catch in the publisher is mandatory.
     */
    private static final class ConflictingPublisher implements EventPublisher {

        @Override
        public EventEnvelope publish(EventDraft draft, CausalContext cause)
                throws SequenceConflictException {
            throw new SequenceConflictException(draft.subjectRef(), 1L);
        }

        @Override
        public EventEnvelope publishRoot(EventDraft draft) throws SequenceConflictException {
            throw new SequenceConflictException(draft.subjectRef(), 1L);
        }
    }
}

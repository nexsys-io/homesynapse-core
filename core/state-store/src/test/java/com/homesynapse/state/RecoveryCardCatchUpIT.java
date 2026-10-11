/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.ProbeAnsweredEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.test.InMemoryViewCheckpointStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * AVAIL-API-1 V-1 and V-7 (AMD-103 R-F; D-v104-18's comparison form) — the catch-up on path (b)
 * equals a full replay: the view the type-scoped catch-up builds on a store of the three types
 * interleaved with others equals the view a full replay (every row from position 0 through the
 * bus's subscriber path — the filter, then {@code onEvent}, modeled as REGISTRY-COLD-1's K-2
 * modeled it) produces; the checkpoint is H; the rows applied are the three types' rows ≤ H.
 * V-7: a projection built over the persisted checkpoint restores and does NOT catch up.
 */
@DisplayName("RecoveryCardCatchUpIT — the catch-up equals a full replay; a restart restores "
        + "(AVAIL-API-1 V-1, V-7)")
final class RecoveryCardCatchUpIT {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);
    private static final DeviceId D1 = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final DeviceId D2 = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");
    private static final EntityId E1 = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final EntityId E2 = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE2");
    private static final Instant T0 = Instant.parse("2026-10-11T08:00:00Z");
    private static final int REPLAY_PAGE = 500;

    private InMemoryEventStore store;
    private InMemoryViewCheckpointStore checkpoints;
    private InMemoryEntityRegistry registry;
    private int threeTypeRows;
    private long lastThreeTypeRow;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RecoveryCardCatchUpIT() {
    }

    @BeforeEach
    void seed() {
        store = new InMemoryEventStore(CLOCK);
        checkpoints = new InMemoryViewCheckpointStore(CLOCK);
        registry = new InMemoryEntityRegistry();
        registry.createEntity(RecoveryCardProjectionTest.entity(E1, D1));
        registry.createEntity(RecoveryCardProjectionTest.entity(E2, D2));
        // The log: fillers around the three types, two devices, the newest rows not last per type.
        fillers(20);
        contract(D1, new AvailabilityContractDeclaredEvent("mains-floor", null, 60, 2, 3600), T0);
        contract(D2, new AvailabilityContractDeclaredEvent("passive", null, 90_000, 2, null), T0);
        fillers(7);
        probe(D1, T0.plusSeconds(60), 1, 0);
        fillers(3);
        availability(E1, T0.plusSeconds(90));
        availability(E2, T0.plusSeconds(95));
        fillers(11);
        probe(D1, T0.plusSeconds(3660), 60, 1);
        contract(D1, new AvailabilityContractDeclaredEvent("mains-metered", 600, 660, 2, 3600),
                T0.plusSeconds(3700));
        fillers(5);
        availability(E1, T0.plusSeconds(4000));
        // an older at at a later position: never wins; the last row of the three types
        lastThreeTypeRow = probe(D1, T0.plusSeconds(3000), 9, 9).globalPosition();
        fillers(9);
        threeTypeRows = 9;
    }

    private RecoveryCardProjection newProjection() {
        return new RecoveryCardProjection(checkpoints, AtomicCheckpointSink.viewOnly(checkpoints),
                () -> registry, CLOCK);
    }

    @Test
    @DisplayName("V-1: the catch-up's view equals the full replay's, per device; the checkpoint = H; "
            + "the rows applied = the three types' rows ≤ H")
    void catchUpEqualsFullReplay() {
        RecoveryCardProjection catchUp = newProjection();
        long head = store.latestPosition();

        long applied = catchUp.catchUp(store);

        assertThat(applied).isEqualTo(threeTypeRows);
        assertThat(catchUp.position()).isEqualTo(head);
        assertThat(checkpoints.readLatestCheckpoint(RecoveryCardProjection.SUBSCRIBER_ID))
                .map(CheckpointRecord::position).contains(head);

        RecoveryCardProjection replay = fullReplay();

        assertThat(catchUp.view()).hasSize(2).containsExactlyInAnyOrderEntriesOf(replay.view());
        assertThat(replay.position())
                .as("the bus path moves the projection position on delivered rows only")
                .isEqualTo(lastThreeTypeRow);
        RecoveryCardRecord d1 = catchUp.view().get(D1);
        assertThat(d1.probeAt()).isEqualTo(T0.plusSeconds(3660));
        assertThat(d1.probesAnswered()).isEqualTo(60);
        assertThat(d1.availabilityClass()).isEqualTo("mains-metered");
        assertThat(d1.availabilityChangedAt()).isEqualTo(T0.plusSeconds(4000));
        assertThat(catchUp.askedCount()).isEqualTo(1);
        System.out.println("recovery-card.v1: head=" + head + " events_replayed=" + applied
                + " devices=" + catchUp.view().size());
    }

    @Test
    @DisplayName("V-7: a projection over the persisted checkpoint RESTORES — the view intact, the "
            + "position H, no catch-up; a later row applies and re-checkpoints; a third restores "
            + "at the new position")
    void restartRestoresTheCheckpoint_noCatchUp() {
        RecoveryCardProjection first = newProjection();
        first.catchUp(store);
        long head = store.latestPosition();

        RecoveryCardProjection second = newProjection();
        assertThat(second.restoreFromCheckpoint()).isTrue();
        assertThat(second.position()).isEqualTo(head);
        assertThat(second.view()).containsExactlyInAnyOrderEntriesOf(first.view());
        assertThat(second.askedCount()).isEqualTo(1);

        EventEnvelope later = probe(D2, T0.plusSeconds(5000), 3, 0);
        second.onEvent(later);
        assertThat(second.position()).isEqualTo(later.globalPosition());

        RecoveryCardProjection third = newProjection();
        assertThat(third.restoreFromCheckpoint()).isTrue();
        assertThat(third.position()).isEqualTo(later.globalPosition());
        assertThat(third.view().get(D2).probesAnswered()).isEqualTo(3);
        assertThat(third.view().get(D1)).isEqualTo(first.view().get(D1));
    }

    @Test
    @DisplayName("V-7: with no checkpoint, restoreFromCheckpoint() is false and the view empty")
    void noCheckpoint_restoreIsFalse() {
        RecoveryCardProjection fresh = newProjection();

        assertThat(fresh.restoreFromCheckpoint()).isFalse();
        assertThat(fresh.view()).isEmpty();
        assertThat(fresh.position()).isZero();
    }

    /** The bus's path modeled: every row of the page walk the filter matches, to onEvent in order. */
    private RecoveryCardProjection fullReplay() {
        InMemoryViewCheckpointStore own = new InMemoryViewCheckpointStore(CLOCK);
        RecoveryCardProjection projection = new RecoveryCardProjection(own,
                AtomicCheckpointSink.viewOnly(own), () -> registry, CLOCK);
        SubscriptionFilter filter = RecoveryCardProjection.subscriptionFilter();
        long after = 0L;
        while (true) {
            EventPage page = store.readFrom(after, REPLAY_PAGE);
            if (page.events().isEmpty()) {
                return projection;
            }
            for (EventEnvelope row : page.events()) {
                if (filter.matches(row)) {
                    projection.onEvent(row);
                }
                after = row.globalPosition();
            }
        }
    }

    private void fillers(int rows) {
        for (int i = 0; i < rows; i++) {
            publish(EventTypes.STATE_REPORTED, SubjectRef.entity(i % 2 == 0 ? E1 : E2),
                    EventPriority.DIAGNOSTIC,
                    new StateReportedEvent("temperature_c", "21.5", null, null, null), null);
        }
    }

    private EventEnvelope probe(DeviceId device, Instant at, int answered, int missed) {
        return publish(EventTypes.PROBE_ANSWERED, SubjectRef.device(device),
                EventPriority.DIAGNOSTIC,
                new ProbeAnsweredEvent(at, at.minusSeconds(3600), answered, missed, null, null,
                        null), at);
    }

    private EventEnvelope contract(DeviceId device, AvailabilityContractDeclaredEvent contract,
            Instant at) {
        return publish(EventTypes.AVAILABILITY_CONTRACT_DECLARED, SubjectRef.device(device),
                EventPriority.NORMAL, contract, at);
    }

    private EventEnvelope availability(EntityId entity, Instant at) {
        return publish(EventTypes.AVAILABILITY_CHANGED, SubjectRef.entity(entity),
                EventPriority.NORMAL, new AvailabilityChangedEvent("offline", "online",
                        "frame_received", at, 180, -50, at), at);
    }

    private EventEnvelope publish(String type, SubjectRef subject, EventPriority priority,
            DomainEvent payload, Instant eventTime) {
        try {
            return store.publishRoot(new EventDraft(type, 1, eventTime, subject, priority,
                    EventOrigin.INTEGRATION, payload, null, null));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}

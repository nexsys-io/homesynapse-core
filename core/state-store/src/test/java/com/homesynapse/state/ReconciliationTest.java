/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.StringValue;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.test.InMemoryViewCheckpointStore;
import com.homesynapse.test.TestClock;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for {@link StateProjection}'s reconciliation pass
 * (AMD-41 §3.2.4). Complements the broader {@code InMemoryStateProjectionTest}
 * by exercising the four behavioral variants the brief enumerated: discards
 * checkpoint on upgrade mismatch, honors the {@code allow_stale_snapshots}
 * escape hatch, is idempotent across repeated mismatches, and discards
 * checkpoint on downgrade mismatch (symmetric to upgrade).
 *
 * <p>One brief test — {@code reconciliationRecordsMetadataInDataSlot} —
 * is intentionally NOT implemented here. {@link StateProjection#writeCheckpoint}
 * currently passes {@code null} for {@code reconciledAt},
 * {@code reconciledFromVersion}, and {@code reconciledToVersion} when
 * delegating to {@link StateCheckpointSource#serializeCheckpoint(int)}; the
 * source has no API to receive reconciliation metadata. Recording
 * reconciliation metadata in the data slot is a deliberate feature gap
 * tracked separately — flagged as a design-level finding in the M3.6d-a
 * Completion Report.</p>
 *
 * <h2>Test fixture pattern</h2>
 *
 * <p>Each test wires a fresh {@link StateProjection} with a custom
 * {@link StateCheckpointSource} whose {@code loadedProjectionVersion()}
 * controls whether reconciliation fires. The projection's lazy {@code initialize()}
 * is triggered by the first {@link StateProjection#onEvent(EventEnvelope)}
 * call (in LIVE mode); after that one event, the reconciliation outcome can
 * be inspected via the state store contents and the projection's
 * {@link StateProjection#cursorPosition()}.</p>
 *
 * <p>The {@code allow_stale_snapshots} system property is cleared in
 * {@code @AfterEach} to prevent cross-test pollution.</p>
 *
 * @see StateProjection
 * @see StateCheckpointSource
 */
@DisplayName("StateProjection reconciliation pass (AMD-41 §3.2.4)")
class ReconciliationTest {

    private static final String VIEW_NAME = "reconcile-test";
    private static final byte[] SEEDED_PAYLOAD = new byte[]{1, 2, 3};

    private TestClock clock;
    private InMemoryStateStore stateStore;
    private InMemoryEventStore eventStore;
    private InMemoryViewCheckpointStore checkpointStore;
    private InMemoryProjectionAdvancer advancer;
    private DerivationRule noopRule;
    private DerivedPublishGate publishGate;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        stateStore = new InMemoryStateStore();
        eventStore = new InMemoryEventStore(clock);
        checkpointStore = new InMemoryViewCheckpointStore(clock);
        advancer = new InMemoryProjectionAdvancer(eventStore);
        noopRule = ctx -> List.of();
        publishGate = DerivedPublishGate.unbounded();
    }

    @AfterEach
    void clearSystemProperties() {
        // Reset the reconciliation escape hatch so it does not leak between
        // tests. Some tests deliberately set the property; clearing it after
        // every test (not only after the setters) is defence in depth.
        System.clearProperty(StateProjection.ALLOW_STALE_SNAPSHOTS_PROPERTY);
    }

    // ──────────────────────────────────────────────────────────────────
    // Tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("upgrade mismatch (persisted=1, runtime=2) discards checkpoint and clears state")
    void reconciliationDiscardsCheckpointOnVersionMismatch() throws SequenceConflictException {
        // Seed a pre-existing checkpoint at position 100 — the projection's
        // initialize() will consult the source and detect the version mismatch.
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        // Pre-existing entity that reconciliation must wipe.
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        stateStore.put(staleEntity, freshEntityState(staleEntity, 42L));

        StateProjection projection = createProjection(
                2 /* runtime version */,
                fixedSource(SEEDED_PAYLOAD, 1 /* persisted version */));
        projection.setMode(SubscriberMode.LIVE);

        // Drive lazy init via a single event for a fresh entity at position 1.
        EventEnvelope inbound = publishStateReported(freshSubject(), 1L);
        projection.onEvent(inbound);

        assertThat(stateStore.get(staleEntity))
                .as("upgrade mismatch must clear all pre-existing entities")
                .isEmpty();
        assertThat(projection.cursorPosition())
                .as("cursor reset to 0 by reconciliation, then advanced to 1 by the inbound event")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("allow_stale_snapshots=true preserves checkpoint despite version mismatch")
    void reconciliationHonorsAllowStaleSnapshotsFlag() throws SequenceConflictException {
        System.setProperty(StateProjection.ALLOW_STALE_SNAPSHOTS_PROPERTY, "true");
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        EntityId preserved = new EntityId(UlidFactory.generate());
        stateStore.put(preserved, freshEntityState(preserved, 42L));

        StateProjection projection = createProjection(
                2, fixedSource(SEEDED_PAYLOAD, 1));
        projection.setMode(SubscriberMode.LIVE);

        EventEnvelope inbound = publishStateReported(freshSubject(), 1L);
        projection.onEvent(inbound);

        assertThat(stateStore.get(preserved))
                .as("escape hatch preserves pre-existing entities")
                .isPresent();
        assertThat(projection.cursorPosition())
                .as("cursor restored from the persisted checkpoint (100), "
                        + "then advanced past the inbound event")
                .isGreaterThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("reconciliation is idempotent — repeated mismatched-version init is a no-op")
    void reconciliationIsIdempotent() throws SequenceConflictException {
        // First projection instance discovers and reconciles the mismatch.
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        stateStore.put(staleEntity, freshEntityState(staleEntity, 42L));

        StateProjection first = createProjection(
                2, fixedSource(SEEDED_PAYLOAD, 1));
        first.setMode(SubscriberMode.LIVE);
        first.onEvent(publishStateReported(freshSubject(), 1L));

        assertThat(stateStore.get(staleEntity))
                .as("first reconciliation clears state")
                .isEmpty();
        long firstCursor = first.cursorPosition();

        // A second projection instance (same view name, same version mismatch)
        // observes the same persisted checkpoint and reconciles again. The
        // operation must be a clean no-op — no exceptions, idempotent state
        // mutation. We seed a fresh "stale" entity to verify the second pass
        // also clears it without surprises.
        EntityId secondStale = new EntityId(UlidFactory.generate());
        stateStore.put(secondStale, freshEntityState(secondStale, 99L));

        StateProjection second = createProjection(
                2, fixedSource(SEEDED_PAYLOAD, 1));
        second.setMode(SubscriberMode.LIVE);
        second.onEvent(publishStateReported(freshSubject(), 2L));

        assertThat(stateStore.get(secondStale))
                .as("second reconciliation pass also clears state — idempotent")
                .isEmpty();
        assertThat(second.cursorPosition())
                .as("second cursor also reset to 0 then advanced by its inbound event")
                .isEqualTo(2L);
        // First projection's cursor is unchanged — independent projection
        // instances do not interfere.
        assertThat(first.cursorPosition())
                .as("first projection's cursor is independent of the second's")
                .isEqualTo(firstCursor);
    }

    @Test
    @DisplayName("downgrade mismatch (persisted=2, runtime=1) also discards checkpoint")
    void reconciliationOnDowngradeAlsoDiscards() throws SequenceConflictException {
        // Symmetric to upgrade: persisted version 2 > runtime version 1
        // also triggers reconciliation. The check is != not <, so downgrades
        // are treated identically to upgrades.
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        stateStore.put(staleEntity, freshEntityState(staleEntity, 42L));

        StateProjection projection = createProjection(
                1 /* runtime version (downgrade) */,
                fixedSource(SEEDED_PAYLOAD, 2 /* persisted version */));
        projection.setMode(SubscriberMode.LIVE);

        EventEnvelope inbound = publishStateReported(freshSubject(), 1L);
        projection.onEvent(inbound);

        assertThat(stateStore.get(staleEntity))
                .as("downgrade mismatch must clear state just like an upgrade mismatch")
                .isEmpty();
        assertThat(projection.cursorPosition())
                .as("cursor reset to 0 by reconciliation, then advanced to 1")
                .isEqualTo(1L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private StateProjection createProjection(int projectionVersion,
                                             StateCheckpointSource source) {
        return new StateProjection(
                new ProjectionId(VIEW_NAME),
                projectionVersion,
                checkpointStore,
                source,
                stateStore,
                noopRule,
                eventStore,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate,
                new SelfProducedFilter(clock, Duration.ofSeconds(60)));
    }

    private static SubjectRef freshSubject() {
        return SubjectRef.entity(new EntityId(UlidFactory.generate()));
    }

    private EventEnvelope publishStateReported(SubjectRef subject, long expectedPosition)
            throws SequenceConflictException {
        EventDraft draft = new EventDraft(
                EventTypes.STATE_REPORTED,
                1,
                clock.instant(),
                subject,
                EventPriority.NORMAL,
                EventOrigin.PHYSICAL,
                new StateReportedEvent("k", "v", null, null, null),
                null,
                null);
        EventEnvelope env = eventStore.publishRoot(draft);
        // Verify the test's positional assumption — caller passes the
        // expected globalPosition for clarity in failure messages.
        assertThat(env.globalPosition())
                .as("positional check for test clarity")
                .isEqualTo(expectedPosition);
        return env;
    }

    private EntityState freshEntityState(EntityId id, long version) {
        var now = clock.instant();
        Map<String, AttributeValue> attrs = Map.of("k", new StringValue("v"));
        return new EntityState(
                id, attrs, Availability.AVAILABLE,
                version, now, now, now, null, false);
    }

    private static StateCheckpointSource fixedSource(byte[] payload, int loadedVersion) {
        return new StateCheckpointSource() {
            @Override
            public byte[] serializeCheckpoint(int projectionVersion) {
                return payload;
            }

            @Override
            public int loadedProjectionVersion() {
                return loadedVersion;
            }
        };
    }

}

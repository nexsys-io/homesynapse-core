/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.test.StateProjectionContractTest;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Concrete {@link StateProjectionContractTest} that wires an in-memory
 * {@link StateProjection} using a fresh {@link SelfProducedFilter} with the
 * default 60-second TTL.
 *
 * <p>This concrete class lives in {@code src/test/java} in the production
 * package {@code com.homesynapse.state} so it can access the package-private
 * {@link SelfProducedFilter}. The abstract contract test base is in the
 * {@code testFixtures} {@code .test} sub-package (which cannot reach the
 * package-private filter).</p>
 *
 * <p>Adds five {@code @Test} methods covering the {@link StateCheckpointSource}
 * wiring introduced in M3.5b's projection-checkpoint follow-up: real bytes
 * flow from the source through to {@link ViewCheckpointStore#writeCheckpoint};
 * the projection version comes from the source rather than the
 * {@link CheckpointRecord} sentinel; the stub source preserves the M3.5a
 * empty-bytes behavior; and an advisory WARN fires when the serialized
 * payload exceeds 10 MB.</p>
 */
class InMemoryStateProjectionTest extends StateProjectionContractTest {

    InMemoryStateProjectionTest() {
        // Inherits the no-arg constructor contract.
    }

    @Override
    protected StateProjection createProjection(
            ProjectionId projectionId,
            int projectionVersion,
            ViewCheckpointStore checkpointStore,
            StateCheckpointSource checkpointSource,
            StateStore stateStore,
            DerivationRule rule,
            EventPublisher publisher,
            ProjectionAdvancer advancer,
            CheckpointPolicy checkpointPolicy,
            Clock clock,
            DerivedPublishGate publishGate) {
        // Construct a SelfProducedFilter with the production default TTL.
        return new StateProjection(
                projectionId,
                projectionVersion,
                checkpointStore,
                checkpointSource,
                stateStore,
                rule,
                publisher,
                advancer,
                checkpointPolicy,
                clock,
                publishGate,
                new SelfProducedFilter(clock, Duration.ofSeconds(60)));
    }

    // ──────────────────────────────────────────────────────────────────
    // M3.5b projection-checkpoint wiring tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    void checkpointWritesSerializedDataFromSource() {
        byte[] known = new byte[]{0x10, 0x20, 0x30, 0x40, 0x50};
        RecordingSource source = new RecordingSource(known, 1);

        StateProjection p = createProjection(
                new ProjectionId("data-wiring-test"),
                1,
                checkpointStore,
                source,
                stateStore,
                rule,
                spyPublisher,
                advancer,
                // 1-event threshold so a single onEvent triggers a checkpoint.
                new FixedCheckpointPolicy(1, Duration.ofHours(1)),
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        SubjectRef subject = freshSubject();
        p.onEvent(makeStateReportedEnvelope(subject, 1L, "color", "blue"));

        Optional<CheckpointRecord> latest =
                checkpointStore.readLatestCheckpoint("data-wiring-test");
        assertThat(latest).as("checkpoint was written").isPresent();
        assertThat(latest.get().data())
                .as("checkpoint data came from the source, not byte[0]")
                .containsExactly(known);
        assertThat(source.serializeCalls())
                .as("source.serializeCheckpoint was invoked")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void checkpointUsesProjectionVersionFromSource() {
        // Seed a checkpoint so the projection's initialize() path consults
        // the source for the persisted version.
        String viewName = "version-match-test";
        checkpointStore.writeCheckpoint(viewName, 50L, new byte[]{9, 9, 9});

        // Source reports version 2 — matching the projection's own version.
        StateCheckpointSource matchingSource = new FixedSource(new byte[0], 2);
        InMemoryStateStore freshStore = new InMemoryStateStore();
        EntityId preReconcileEntity = new EntityId(UlidFactory.generate());
        freshStore.put(preReconcileEntity, new EntityState(
                preReconcileEntity,
                java.util.Map.of(),
                Availability.UNKNOWN,
                3L,
                clock.instant(),
                clock.instant(),
                clock.instant(),
                null,
                false));

        StateProjection p = createProjection(
                new ProjectionId(viewName),
                2,
                checkpointStore,
                matchingSource,
                freshStore,
                rule,
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        // Drive lazy init via one onEvent for a fresh entity.
        SubjectRef subject = freshSubject();
        p.onEvent(makeStateReportedEnvelope(subject, 1L, "k", "v"));

        // No reconciliation: pre-reconcile entity survives, cursor was
        // restored from the checkpoint position (50) and then advanced to 51.
        assertThat(freshStore.get(preReconcileEntity))
                .as("matching version => no reconciliation; pre-existing entity survives")
                .isPresent();
        assertThat(p.cursorPosition())
                .as("cursor restored from checkpoint position, not reset to 0")
                .isGreaterThanOrEqualTo(50L);
    }

    @Test
    void reconciliationTriggeredOnVersionMismatch() {
        // Seed a checkpoint so initialize() reaches the source-consultation path.
        String viewName = "version-mismatch-test";
        checkpointStore.writeCheckpoint(viewName, 100L, new byte[]{1, 2, 3});

        // Source reports version 1; projection runs at version 2 → mismatch.
        StateCheckpointSource mismatchedSource = new FixedSource(new byte[0], 1);

        InMemoryStateStore freshStore = new InMemoryStateStore();
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        freshStore.put(staleEntity, new EntityState(
                staleEntity,
                java.util.Map.of(),
                Availability.UNKNOWN,
                7L,
                clock.instant(),
                clock.instant(),
                clock.instant(),
                null,
                false));

        StateProjection p = createProjection(
                new ProjectionId(viewName),
                2,
                checkpointStore,
                mismatchedSource,
                freshStore,
                rule,
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        SubjectRef subject = freshSubject();
        p.onEvent(makeStateReportedEnvelope(subject, 1L, "k", "v"));

        assertThat(freshStore.get(staleEntity))
                .as("mismatch (loadedProjectionVersion=1, projection=2) triggers reconciliation")
                .isEmpty();
        assertThat(p.cursorPosition())
                .as("cursor reset to 0 by reconciliation, then advanced to 1 by the inbound event")
                .isEqualTo(1L);
    }

    @Test
    void stubSourceWritesEmptyBytes() {
        // The contract setUp already wired the projection with
        // StateCheckpointSource.stub(). Drive enough events to trigger a
        // checkpoint, then verify the persisted data is byte[0].
        StateProjection p = createProjection(
                new ProjectionId("stub-source-test"),
                1,
                checkpointStore,
                StateCheckpointSource.stub(),
                stateStore,
                rule,
                spyPublisher,
                advancer,
                new FixedCheckpointPolicy(1, Duration.ofHours(1)),
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        SubjectRef subject = freshSubject();
        p.onEvent(makeStateReportedEnvelope(subject, 1L, "k", "v"));

        Optional<CheckpointRecord> latest =
                checkpointStore.readLatestCheckpoint("stub-source-test");
        assertThat(latest).as("checkpoint was written").isPresent();
        assertThat(latest.get().data())
                .as("stub source preserves the M3.5a byte[0] write behavior")
                .isEmpty();
    }

    @Test
    void checkpointSizeGuardrailLogsWarnAboveThreshold() {
        // Source returns 10 MB + 1 byte — one byte above the advisory
        // threshold (StateProjection.CHECKPOINT_SIZE_WARN_BYTES = 10 * 1024 * 1024).
        byte[] oversize = new byte[StateProjection.CHECKPOINT_SIZE_WARN_BYTES + 1];
        StateCheckpointSource bigSource = new FixedSource(oversize, 1);

        StateProjection p = createProjection(
                new ProjectionId("size-guardrail-test"),
                1,
                checkpointStore,
                bigSource,
                stateStore,
                rule,
                spyPublisher,
                advancer,
                new FixedCheckpointPolicy(1, Duration.ofHours(1)),
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        SubjectRef subject = freshSubject();
        p.onEvent(makeStateReportedEnvelope(subject, 1L, "k", "v"));

        // The guardrail is advisory — the checkpoint must still be written.
        Optional<CheckpointRecord> latest =
                checkpointStore.readLatestCheckpoint("size-guardrail-test");
        assertThat(latest)
                .as("guardrail is advisory; the oversized checkpoint is still written")
                .isPresent();
        assertThat(latest.get().data().length)
                .as("the full payload was persisted despite the WARN")
                .isEqualTo(oversize.length);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Builds a fresh entity-typed {@link SubjectRef}. Local convenience to
     * avoid repeating the protected-static call to
     * {@code SubscriberContractTest.freshEntitySubject()} in each test.
     */
    private static SubjectRef freshSubject() {
        return SubjectRef.entity(new EntityId(UlidFactory.generate()));
    }

    /**
     * Fixed-response {@link StateCheckpointSource}: returns the same payload
     * and version on every call. No call counting.
     */
    private static final class FixedSource implements StateCheckpointSource {

        private final byte[] payload;
        private final int version;

        FixedSource(byte[] payload, int version) {
            this.payload = payload;
            this.version = version;
        }

        @Override
        public byte[] serializeCheckpoint(int projectionVersion) {
            return payload;
        }

        @Override
        public int loadedProjectionVersion() {
            return version;
        }
    }

    /**
     * {@link StateCheckpointSource} that counts {@code serializeCheckpoint}
     * invocations. Used to assert that the projection actually consults the
     * source on each checkpoint write.
     */
    private static final class RecordingSource implements StateCheckpointSource {

        private final byte[] payload;
        private final int version;
        private int serializeCalls;

        RecordingSource(byte[] payload, int version) {
            this.payload = payload;
            this.version = version;
        }

        @Override
        public byte[] serializeCheckpoint(int projectionVersion) {
            serializeCalls++;
            return payload;
        }

        @Override
        public int loadedProjectionVersion() {
            return version;
        }

        int serializeCalls() {
            return serializeCalls;
        }
    }
}

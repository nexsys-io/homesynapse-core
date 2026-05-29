/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.AtomicCheckpointSink;
import com.homesynapse.state.DerivationRule;
import com.homesynapse.state.DerivedPublishGate;
import com.homesynapse.state.FixedCheckpointPolicy;
import com.homesynapse.state.InMemoryProjectionAdvancer;
import com.homesynapse.state.InMemoryStateStore;
import com.homesynapse.state.ProjectionId;
import com.homesynapse.state.StateCheckpointSource;
import com.homesynapse.state.StateProjection;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Heap-budget integration test exercising the real {@link StateProjection}
 * against the file-based SQLite stack under the Pi 4 JVM profile.
 *
 * <p>Publishes one {@code state_reported} event per entity for 3,000 distinct
 * entities, drives them through the real
 * {@code InProcessEventBus} → {@code StateProjection} pipeline, and asserts
 * that the post-GC heap stays within the {@code -Xmx256m} budget. The
 * test validates two memory-bounded subsystems simultaneously:</p>
 * <ul>
 *   <li>{@code InMemoryStateStore} — one {@code EntityState} per materialised
 *       entity (3,000 entries).</li>
 *   <li>{@code SelfProducedFilter} — the 60-second TTL ring of
 *       projection-published derived-event IDs (zero entries in this scenario
 *       because the harness uses a no-op {@link DerivationRule}, but the
 *       allocation surface must still fit in budget).</li>
 * </ul>
 *
 * <p>The test does NOT exercise sustained throughput — that's
 * {@code Pi4SustainedLoadIT} in M3.4b. Here the cadence is "publish as fast
 * as the writer accepts, then measure". The 256 MB budget under {@code -Xmx}
 * is the binary fail-mode: any unbounded retention would trigger
 * {@code OutOfMemoryError} before the measurement runs.</p>
 */
@DisplayName("Heap budget — 3,000 entities under 256 MB")
class HeapBudgetIT {

    private static final int ENTITY_COUNT = 3_000;
    /** Hard ceiling per Pi 4 budget; matches {@code -Xmx256m} JVM flag. */
    private static final long HEAP_BUDGET_BYTES = 256L * 1024L * 1024L;
    /** Wall-clock ceiling for the full publish + delivery cycle. */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofMinutes(5);

    private static final String COUNTER_SUBSCRIBER_ID = "heap-budget-counter";
    private static final ProjectionId PROJECTION_ID =
            new ProjectionId("heap-budget-projection");

    @TempDir
    Path tempDir;

    private Clock clock;
    private IntegrationTestHarness harness;
    private InMemoryStateStore stateStore;
    private StateProjection projection;

    HeapBudgetIT() {
        // Explicit no-arg constructor for -Xlint:all -Werror.
    }

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        harness = IntegrationTestHarness.start(
                tempDir.resolve("homesynapse-heap-budget.db"), clock);

        stateStore = new InMemoryStateStore();
        // No-op derivation: the projection materialises state but does not
        // publish derived events. This keeps the test focused on storage
        // memory, not on the bus's derived-write path.
        DerivationRule noDerivation = ctx -> List.of();
        InMemoryProjectionAdvancer advancer =
                new InMemoryProjectionAdvancer(harness.eventStore());

        projection = StateProjection.create(
                PROJECTION_ID,
                1,
                harness.viewCheckpointStore(),
                StateCheckpointSource.stub(),
                AtomicCheckpointSink.viewOnly(harness.viewCheckpointStore()),
                stateStore,
                noDerivation,
                harness.eventPublisher(),
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                DerivedPublishGate.unbounded());
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void threeThousandEntitiesStayWithinHeapBudget()
            throws SequenceConflictException, InterruptedException {

        // Register the projection as a bus subscriber. The bus will deliver
        // each published event to projection.onEvent on the projection's
        // dedicated VT, where it materialises into the InMemoryStateStore.
        //
        // The bus tracks its own per-subscriber SubscriberMode FSM internally;
        // StateProjection also carries an INTERNAL mode field that gates
        // onEvent (COLD → no-op return). The bus does not drive that field
        // yet (composition-root wiring is M3.6), so we set it to LIVE here.
        // M3.5a tests follow the same pattern.
        projection.setMode(SubscriberMode.LIVE);
        harness.eventBus().subscribeRuntime(
                new SubscriberInfo(PROJECTION_ID.value(),
                        SubscriptionFilter.all(), false),
                projection);

        // A parallel counter subscriber provides a deterministic
        // "all-delivered" signal independent of projection internals.
        CountDownLatch allDelivered = new CountDownLatch(ENTITY_COUNT);
        harness.eventBus().subscribeRuntime(
                new SubscriberInfo(COUNTER_SUBSCRIBER_ID,
                        SubscriptionFilter.all(), false),
                env -> allDelivered.countDown());

        // Publish exactly one state_reported per fresh entity. Drop the
        // SubjectRef reference after publish so the EventDraft and the
        // entity ULID become eligible for GC immediately — keeping the
        // entity ID set on the heap would dwarf the projection's footprint.
        for (int i = 0; i < ENTITY_COUNT; i++) {
            SubjectRef subject = SubjectRef.entity(
                    new EntityId(UlidFactory.generate(clock)));
            EventDraft draft = new EventDraft(
                    EventTypes.STATE_REPORTED,
                    1,
                    null,
                    subject,
                    EventPriority.DIAGNOSTIC,
                    EventOrigin.PHYSICAL,
                    new StateReportedEvent(
                            "level", "v" + i, null, null, null),
                    null,
                    null);
            EventEnvelope envelope = harness.eventPublisher().publishRoot(draft);
            harness.eventBus().notifyEvent(envelope.globalPosition());
        }

        // Wait for delivery to both subscribers. The counter is the cheap
        // signal; the projection's materialisation completes inside the same
        // delivery callback.
        boolean delivered = allDelivered.await(
                DELIVERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(delivered)
                .as("counter subscriber received all %d events within %s",
                        ENTITY_COUNT, DELIVERY_TIMEOUT)
                .isTrue();

        // Projection's cursor must converge to the head. Brief poll: the
        // counter latch fires inside the counter's onEvent, which runs on a
        // different subscriber VT than the projection's — they're loosely
        // synchronised.
        long projectionCursor = waitForProjectionCursor(
                ENTITY_COUNT, Duration.ofMinutes(2));
        assertThat(projectionCursor)
                .as("projection cursor reached the published head")
                .isEqualTo(ENTITY_COUNT);

        // The InMemoryStateStore should now hold one EntityState per entity.
        assertThat(stateStore.getAll())
                .as("projection materialised one EntityState per entity")
                .hasSize(ENTITY_COUNT);

        // Force a full GC before measuring heap. Two passes with a short
        // pause is the standard pattern for releasing soft references and
        // running any pending finalisers before the measurement.
        forceFullGc();

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
        assertThat(heapUsed)
                .as("post-GC heap with %d materialised entities", ENTITY_COUNT)
                .isLessThanOrEqualTo(HEAP_BUDGET_BYTES);
    }

    private long waitForProjectionCursor(long target, Duration timeout)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long current = projection.cursorPosition();
        while (current < target && System.nanoTime() < deadlineNanos) {
            Thread.sleep(50L);
            current = projection.cursorPosition();
        }
        return current;
    }

    private static void forceFullGc() throws InterruptedException {
        System.gc();
        Thread.sleep(100L);
        System.gc();
        Thread.sleep(100L);
    }
}

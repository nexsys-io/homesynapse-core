/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Abstract contract test for {@link WriteCoordinator}.
 *
 * <p>Defines the 11-method behavioral contract that ALL {@code WriteCoordinator}
 * implementations must satisfy. Both {@code InMemoryWriteCoordinator} (test fixture)
 * and the future {@code PlatformThreadWriteCoordinator} (production) extend this
 * class and inherit the same test suite.</p>
 *
 * <p>This class lives in the {@code com.homesynapse.persistence} package (same as
 * the interface) within the {@code testFixtures} source set. This gives it
 * package-private access to {@link WriteCoordinator} and {@link WritePriority}
 * without requiring those types to be public.</p>
 *
 * <p>The contract validated here covers:</p>
 * <ul>
 *   <li>Per-priority submission and result retrieval</li>
 *   <li>Generic return type support</li>
 *   <li>Error handling: unchecked exception propagation, checked exception wrapping</li>
 *   <li>Failure isolation: a failed operation does not block subsequent submissions</li>
 *   <li>Lifecycle: shutdown prevents further submissions</li>
 *   <li>Concurrency safety: concurrent submissions all complete correctly</li>
 * </ul>
 *
 * <p>Subclasses must implement {@link #coordinator()} and {@link #resetCoordinator()}.
 * This abstract class calls {@link #resetCoordinator()} in {@code @BeforeEach}
 * to ensure test isolation.</p>
 *
 * @see WriteCoordinator
 * @see WritePriority
 */
@DisplayName("WriteCoordinator Contract")
public abstract class WriteCoordinatorContractTest {

    /** Subclass constructor. */
    protected WriteCoordinatorContractTest() {
        // Abstract — subclasses provide implementation.
    }

    /**
     * Returns the {@link WriteCoordinator} under test.
     *
     * <p>Called by every test method — must return a consistent instance
     * within a single test execution.</p>
     */
    protected abstract WriteCoordinator coordinator();

    /**
     * Resets the coordinator to a fresh state for test isolation.
     * Called in {@link #setUp()}.
     */
    protected abstract void resetCoordinator();

    @BeforeEach
    void setUp() {
        resetCoordinator();
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 1: Per-Priority Submission
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 1 — Per-Priority Submission")
    class PerPrioritySubmission {

        /** Creates a new test instance. */
        PerPrioritySubmission() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("submit at EVENT_PUBLISH priority returns result")
        void submit_eventPublish_returnsResult() {
            String result = coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> "event-result");

            assertThat(result).isEqualTo("event-result");
        }

        @Test
        @DisplayName("submit at STATE_PROJECTION priority returns result")
        void submit_stateProjection_returnsResult() {
            String result = coordinator().submit(
                    WritePriority.STATE_PROJECTION, () -> "projection-result");

            assertThat(result).isEqualTo("projection-result");
        }

        @Test
        @DisplayName("submit at WAL_CHECKPOINT priority returns result")
        void submit_walCheckpoint_returnsResult() {
            String result = coordinator().submit(
                    WritePriority.WAL_CHECKPOINT, () -> "wal-result");

            assertThat(result).isEqualTo("wal-result");
        }

        @Test
        @DisplayName("submit at RETENTION priority returns result")
        void submit_retention_returnsResult() {
            String result = coordinator().submit(
                    WritePriority.RETENTION, () -> "retention-result");

            assertThat(result).isEqualTo("retention-result");
        }

        @Test
        @DisplayName("submit at BACKUP priority returns result")
        void submit_backup_returnsResult() {
            String result = coordinator().submit(
                    WritePriority.BACKUP, () -> "backup-result");

            assertThat(result).isEqualTo("backup-result");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 2: Generic Return Types
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 2 — Generic Return Types")
    class GenericReturnTypes {

        /** Creates a new test instance. */
        GenericReturnTypes() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("submit supports different return types: String, Integer, Long, Boolean, Void")
        void submit_genericReturnType_worksForDifferentTypes() {
            String stringResult = coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> "text");
            assertThat(stringResult).isEqualTo("text");

            Integer intResult = coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> 42);
            assertThat(intResult).isEqualTo(42);

            Long longResult = coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> 999L);
            assertThat(longResult).isEqualTo(999L);

            Boolean boolResult = coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> true);
            assertThat(boolResult).isTrue();

            // Void return — Callable<Void> returning null
            Void voidResult = coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> null);
            assertThat(voidResult).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 3: Error Handling
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 3 — Error Handling")
    class ErrorHandling {

        /** Creates a new test instance. */
        ErrorHandling() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("submit propagates RuntimeException directly")
        void submit_failedOperation_throwsRuntimeException() {
            assertThatThrownBy(() -> coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> {
                        throw new IllegalArgumentException("bad argument");
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("bad argument");
        }

        @Test
        @DisplayName("submit wraps checked Exception in RuntimeException")
        void submit_checkedExceptionOperation_wrapsInRuntimeException() {
            assertThatThrownBy(() -> coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> {
                        throw new Exception("checked failure");
                    }))
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(Exception.class)
                    .hasRootCauseMessage("checked failure");
        }

        @Test
        @DisplayName("failed operation does not block subsequent submissions")
        void submit_failedOperation_doesNotBlockSubsequent() {
            // First call: fails
            try {
                coordinator().submit(WritePriority.EVENT_PUBLISH, () -> {
                    throw new RuntimeException("intentional failure");
                });
            } catch (RuntimeException ignored) {
                // Expected — the failure should not prevent subsequent operations
            }

            // Second call: succeeds
            String result = coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> "recovered");

            assertThat(result).isEqualTo("recovered");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 4: Lifecycle and Concurrency
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 4 — Lifecycle and Concurrency")
    class LifecycleAndConcurrency {

        /** Creates a new test instance. */
        LifecycleAndConcurrency() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("shutdown causes subsequent submit to throw IllegalStateException")
        void shutdown_subsequentSubmit_throwsIllegalState() {
            coordinator().shutdown();

            assertThatThrownBy(() -> coordinator().submit(
                    WritePriority.EVENT_PUBLISH, () -> "too late"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("concurrent submit from 4 threads completes all 100 operations")
        void concurrentSubmit_allOperationsComplete() throws InterruptedException {
            int threadCount = 4;
            int opsPerThread = 25;
            var executor = Executors.newFixedThreadPool(threadCount);
            var latch = new CountDownLatch(threadCount);
            var errors = new CopyOnWriteArrayList<Throwable>();
            var results = new CopyOnWriteArrayList<String>();

            WritePriority[] priorities = WritePriority.values();

            for (int t = 0; t < threadCount; t++) {
                int threadIdx = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            int opIdx = i;
                            // Rotate through priorities
                            WritePriority priority =
                                    priorities[(threadIdx + opIdx) % priorities.length];
                            String result = coordinator().submit(priority,
                                    () -> "result-" + threadIdx + "-" + opIdx);
                            results.add(result);
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as("All threads should complete within 5 seconds")
                    .isTrue();
            assertThat(errors).isEmpty();
            assertThat(results).hasSize(threadCount * opsPerThread);
            executor.shutdown();
        }
    }
}

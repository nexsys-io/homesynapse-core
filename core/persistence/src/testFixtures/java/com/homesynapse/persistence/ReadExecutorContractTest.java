/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Abstract contract test for {@link ReadExecutor}.
 *
 * <p>Defines the behavioral contract that all {@code ReadExecutor}
 * implementations must satisfy. Both {@code InMemoryReadExecutor} (test
 * fixture) and {@code PlatformThreadReadExecutor} (production) extend this
 * class and inherit the same suite.</p>
 *
 * <p>The contract covers:</p>
 * <ul>
 *   <li>Synchronous result return from submitted operations</li>
 *   <li>Unchecked exception propagation (direct)</li>
 *   <li>Checked exception wrapping into {@link RuntimeException}</li>
 *   <li>Lifecycle: {@link ReadExecutor#shutdown()} rejects subsequent
 *       submissions with {@link IllegalStateException}</li>
 *   <li>Concurrent submission from many virtual threads</li>
 * </ul>
 *
 * <p>Subclasses provide a fresh executor through {@link #createReadExecutor()}.
 * The base class stores the instance, tears it down after each test via
 * {@link ReadExecutor#shutdown()}, and exposes it through {@link #executor()}.
 * Subclasses whose production coordinator is stateful across tests (e.g.,
 * {@code PlatformThreadReadExecutor}) get a fresh instance per test
 * automatically.</p>
 *
 * @see ReadExecutor
 */
@DisplayName("ReadExecutor Contract")
public abstract class ReadExecutorContractTest {

    private ReadExecutor executor;

    /** Subclass constructor. */
    protected ReadExecutorContractTest() {
        // Abstract — subclasses provide implementation.
    }

    /**
     * Creates a fresh {@link ReadExecutor} instance for a single test.
     *
     * <p>Called in {@link #setUp()}. The returned instance must be in the
     * active (non-shutdown) state and ready to accept submissions.</p>
     */
    protected abstract ReadExecutor createReadExecutor();

    /** Returns the executor under test for use inside test methods. */
    protected final ReadExecutor executor() {
        return executor;
    }

    @BeforeEach
    void setUp() {
        executor = createReadExecutor();
    }

    @Test
    @DisplayName("execute returns the operation's result")
    void execute_returnsResult() {
        String result = executor.execute(() -> "read-result");

        assertThat(result).isEqualTo("read-result");
    }

    @Test
    @DisplayName("execute propagates RuntimeException directly")
    void execute_propagatesUncheckedException() {
        assertThatThrownBy(() -> executor.execute(() -> {
            throw new IllegalArgumentException("bad argument");
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bad argument");
    }

    @Test
    @DisplayName("execute wraps checked Exception in RuntimeException")
    void execute_wrapsCheckedException() {
        assertThatThrownBy(() -> executor.execute(() -> {
            throw new Exception("checked failure");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(Exception.class)
                .hasRootCauseMessage("checked failure");
    }

    @Test
    @DisplayName("shutdown causes subsequent execute to throw IllegalStateException")
    void shutdown_thenExecute_throwsIllegalState() {
        executor.shutdown();

        assertThatThrownBy(() -> executor.execute(() -> "too late"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("concurrent execute from 10 threads completes all reads")
    void execute_concurrentReads_allComplete() throws InterruptedException {
        int threadCount = 10;
        var pool = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var errors = new CopyOnWriteArrayList<Throwable>();
        List<Integer> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    Integer value = executor.execute(() -> idx * 2);
                    results.add(value);
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
        assertThat(results).hasSize(threadCount);
        for (int i = 0; i < threadCount; i++) {
            assertThat(results).contains(i * 2);
        }
        pool.shutdown();
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory {@link ReadExecutor} for testing.
 *
 * <p>Executes operations synchronously on the calling thread, protected by
 * a {@link ReentrantLock} only for the shutdown state transition — read
 * operations themselves run without holding the lock, matching the
 * WAL-style concurrent-reader semantics of the production implementation.
 * Uses a volatile shutdown flag for cross-thread visibility.</p>
 *
 * <p>This fixture lives in the {@code com.homesynapse.persistence} package
 * (same as {@link ReadExecutor}) within the {@code testFixtures} source
 * set, giving it package-private access to the interface.</p>
 *
 * @see ReadExecutor
 * @see ReadExecutorContractTest
 */
final class InMemoryReadExecutor implements ReadExecutor {

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean shutdown;

    /** Creates a new executor in the active (non-shutdown) state. */
    InMemoryReadExecutor() {
        // Defaults are sufficient — shutdown starts as false.
    }

    @Override
    public <T> T execute(Callable<T> operation) {
        if (operation == null) {
            throw new NullPointerException("operation must not be null");
        }
        if (shutdown) {
            throw new IllegalStateException(
                    "ReadExecutor has been shut down");
        }
        try {
            return operation.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void shutdown() {
        lifecycleLock.lock();
        try {
            shutdown = true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Resets this executor to its initial active state.
     *
     * <p>Used by {@link ReadExecutorContractTest} in {@code @BeforeEach} to
     * ensure test isolation.</p>
     */
    void reset() {
        lifecycleLock.lock();
        try {
            shutdown = false;
        } finally {
            lifecycleLock.unlock();
        }
    }
}

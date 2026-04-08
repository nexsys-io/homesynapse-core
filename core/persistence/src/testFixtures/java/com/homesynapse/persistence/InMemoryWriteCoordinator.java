/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory {@link WriteCoordinator} for testing.
 *
 * <p>Executes operations synchronously under a {@link ReentrantLock} to
 * validate the serialization contract without requiring a platform thread
 * executor. Uses a volatile shutdown flag for visibility across threads.</p>
 *
 * <p>This class lives in the {@code com.homesynapse.persistence} package
 * (same as {@link WriteCoordinator}) within the {@code testFixtures} source
 * set, giving it package-private access to both {@link WriteCoordinator} and
 * {@link WritePriority}.</p>
 *
 * @see WriteCoordinator
 * @see WriteCoordinatorContractTest
 */
final class InMemoryWriteCoordinator implements WriteCoordinator {

    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean shutdown;

    /** Creates a new coordinator in the active (non-shutdown) state. */
    InMemoryWriteCoordinator() {
        // Defaults are sufficient — shutdown starts as false.
    }

    @Override
    public <T> T submit(WritePriority priority, Callable<T> operation) {
        if (priority == null) {
            throw new NullPointerException("priority must not be null");
        }
        if (operation == null) {
            throw new NullPointerException("operation must not be null");
        }
        if (shutdown) {
            throw new IllegalStateException(
                    "WriteCoordinator has been shut down");
        }

        lock.lock();
        try {
            // Double-check after acquiring lock — another thread may have
            // called shutdown() between the volatile read and the lock acquire.
            if (shutdown) {
                throw new IllegalStateException(
                        "WriteCoordinator has been shut down");
            }
            return operation.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets this coordinator to its initial active state.
     *
     * <p>Used by {@link WriteCoordinatorContractTest} in {@code @BeforeEach}
     * to ensure test isolation.</p>
     */
    void reset() {
        lock.lock();
        try {
            shutdown = false;
        } finally {
            lock.unlock();
        }
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.util.concurrent.Callable;

/**
 * Dedicated platform-thread read executor per subscriber (DP-4, INV-SUB-ISO-02).
 *
 * <p>Each subscriber gets its own executor that wraps a dedicated platform thread
 * and SQLite read connection. This ensures per-subscriber isolation and prevents
 * carrier-thread pinning on virtual threads during sqlite-jdbc JNI calls.</p>
 *
 * <p>Implementations must be safe for concurrent submission from the subscriber's
 * virtual thread. The executor serializes operations onto its dedicated platform
 * thread internally.</p>
 *
 * @see SubscriberReadConnectionFactory
 */
public interface SubscriberReadExecutor extends AutoCloseable {

    /**
     * Submits a read operation to the subscriber's dedicated platform thread.
     *
     * <p>The calling virtual thread parks until the platform thread completes
     * the operation and produces a result.</p>
     *
     * @param task the read operation to execute
     * @param <T>  the result type
     * @return the result produced by the task
     * @throws Exception if the task throws
     */
    <T> T executeRead(Callable<T> task) throws Exception;

    /**
     * Closes the executor, releasing the dedicated platform thread and
     * the underlying SQLite connection.
     */
    @Override
    void close();
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.concurrent.Callable;

/**
 * Routes read operations to platform threads to prevent sqlite-jdbc JNI
 * calls from pinning virtual thread carriers (AMD-26).
 *
 * <p>Symmetric to {@link WriteCoordinator} but without priority ordering —
 * all reads are equal priority. Implementations use a bounded pool of
 * platform threads matching WAL concurrent reader capacity (AMD-27
 * default: 2). Virtual thread callers submit work via
 * {@link #execute(Callable)} and park while the platform thread runs the
 * operation, then resume when the result is available.</p>
 *
 * <p>This split between write and read executors reflects the SQLite WAL
 * concurrency model: a single writer serialized through
 * {@link WriteCoordinator}, and multiple readers running in parallel on the
 * read pool. Callers (SqliteEventStore, SqliteCheckpointStore, etc.) select
 * the correct executor based on whether the operation is a read or a write.
 * No executor sees every database operation.</p>
 *
 * <p><strong>Thread safety:</strong> {@link #execute} may be called
 * concurrently from any number of virtual threads. Implementations handle
 * all internal synchronization.</p>
 *
 * <p>This interface is package-private — external modules interact with
 * the higher-level read APIs (e.g., {@code EventStore.query()}) which route
 * through {@code ReadExecutor} internally.</p>
 *
 * @see WriteCoordinator
 */
interface ReadExecutor {

    /**
     * Executes a read operation on a platform read thread and blocks the
     * calling thread until the result is available.
     *
     * <p>If the operation throws a checked exception, it is wrapped in a
     * {@link RuntimeException} (the checked exception is the cause) and
     * rethrown. Unchecked exceptions propagate directly. Errors propagate
     * directly.</p>
     *
     * @param <T>       the result type
     * @param operation the read operation to execute; never {@code null}
     * @return the operation's result
     * @throws NullPointerException  if {@code operation} is {@code null}
     * @throws IllegalStateException if this executor has been
     *                               {@link #shutdown() shut down}
     * @throws RuntimeException      if the operation fails (checked
     *                               exceptions are wrapped)
     */
    <T> T execute(Callable<T> operation);

    /**
     * Shuts down the read executor, allowing in-flight operations to
     * complete but rejecting new submissions.
     *
     * <p>After shutdown, subsequent {@link #execute(Callable)} calls throw
     * {@link IllegalStateException}. This method is idempotent — calling it
     * multiple times has no additional effect.</p>
     */
    void shutdown();
}

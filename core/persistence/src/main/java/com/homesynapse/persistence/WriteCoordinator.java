/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.concurrent.Callable;

/**
 * Serializes all write operations against the persistence layer's SQLite databases.
 *
 * <p>The WriteCoordinator ensures that writes are serialized (no parallel writes)
 * and prioritized (event publishing is never starved by maintenance). In production,
 * this wraps a single platform thread executor (AMD-26/AMD-27) — all sqlite-jdbc
 * JNI calls route through this interface because JNI pins carrier threads on ALL
 * Java versions.</p>
 *
 * <p>The coordinator accepts {@link Callable} operations because write operations
 * return results (e.g., {@link com.homesynapse.event.EventEnvelope} from publishing,
 * {@link RetentionResult} from retention). For void operations, callers use
 * {@code Callable<Void>} returning {@code null}.</p>
 *
 * <p><strong>Thread safety:</strong> {@link #submit} may be called from any thread
 * (including virtual threads). The coordinator handles serialization internally.
 * In production, virtual threads park while the platform write thread executes the
 * sqlite-jdbc JNI call, then resume when the result is available.</p>
 *
 * <p>This interface is package-private — external modules interact with
 * {@link com.homesynapse.event.EventPublisher},
 * {@link com.homesynapse.event.bus.CheckpointStore}, and
 * {@link MaintenanceService} instead.</p>
 *
 * @see WritePriority
 */
interface WriteCoordinator {

    /**
     * Submits a write operation at the given priority level.
     *
     * <p>The operation executes on the write thread (or immediately in test
     * implementations). The calling thread blocks until the operation completes
     * and the result is available.</p>
     *
     * <p>If the operation throws a checked exception, it is wrapped in a
     * {@link RuntimeException} and rethrown. Unchecked exceptions propagate
     * directly.</p>
     *
     * @param <T>       the result type
     * @param priority  the write priority tier; never {@code null}
     * @param operation the write operation to execute; never {@code null}
     * @return the result of the operation
     * @throws NullPointerException  if {@code priority} or {@code operation} is
     *                               {@code null}
     * @throws IllegalStateException if the coordinator has been
     *                               {@link #shutdown() shut down}
     * @throws RuntimeException      if the operation fails (checked exceptions
     *                               are wrapped)
     */
    <T> T submit(WritePriority priority, Callable<T> operation);

    /**
     * Shuts down the coordinator, draining any queued operations in priority order.
     *
     * <p>After shutdown, subsequent {@link #submit} calls throw
     * {@link IllegalStateException}. This method is idempotent — calling it
     * multiple times has no additional effect.</p>
     */
    void shutdown();
}

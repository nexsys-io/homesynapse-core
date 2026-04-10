/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link WriteCoordinator} backed by a single daemon platform
 * thread servicing a priority queue.
 *
 * <p>All write operations — including every sqlite-jdbc JNI call — execute
 * on the dedicated write thread {@code hs-write-0}. Callers (typically on
 * virtual threads) submit via {@link #submit} and park on a
 * {@link CompletableFuture} until the write thread completes the operation.
 * This routing pattern is the AMD-26 mitigation for sqlite-jdbc JNI carrier
 * pinning: virtual threads never make sqlite-jdbc calls directly, so they
 * never pin their carrier.</p>
 *
 * <p>The work queue is a {@link PriorityBlockingQueue} ordered by
 * {@link WritePriority#rank()} (lower rank dequeues first), with an
 * {@link AtomicLong} sequence number as a FIFO tiebreaker within the same
 * priority. This guarantees that {@link WritePriority#EVENT_PUBLISH}
 * operations are never starved by background maintenance (AMD-06,
 * AMD-32).</p>
 *
 * <p><strong>Shutdown model:</strong> {@link #shutdown()} sets a shutdown
 * flag, enqueues a poison-pill work item to unblock the writer loop, and
 * joins the platform thread with a bounded timeout. Subsequent submissions
 * throw {@link IllegalStateException}. Shutdown is idempotent.</p>
 *
 * <p>This class is package-private — it is wired into {@link DatabaseExecutor}
 * at startup and never referenced directly by module consumers.</p>
 *
 * @see WriteCoordinator
 * @see WritePriority
 */
final class PlatformThreadWriteCoordinator implements WriteCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PlatformThreadWriteCoordinator.class);

    private static final long SHUTDOWN_JOIN_TIMEOUT_MS = 5_000L;

    private final PriorityBlockingQueue<WorkItem> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Thread writerThread;
    private volatile boolean shutdown;

    /** Creates and starts the write coordinator with its dedicated platform thread. */
    PlatformThreadWriteCoordinator() {
        this.writerThread = Thread.ofPlatform()
                .name("hs-write-0")
                .daemon(true)
                .unstarted(this::runLoop);
        this.writerThread.start();
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
            throw new IllegalStateException("WriteCoordinator has been shut down");
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        WorkItem item = new WorkItem(
                priority.rank(),
                sequence.getAndIncrement(),
                operation,
                future,
                false);
        queue.offer(item);

        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted while waiting for write operation to complete", e);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    @Override
    public void shutdown() {
        lifecycleLock.lock();
        try {
            if (shutdown) {
                return;
            }
            shutdown = true;
            // Poison pill unblocks the writer loop if it is parked on take().
            // Use the lowest priority so it runs after any already-queued work.
            queue.offer(new WorkItem(
                    Integer.MAX_VALUE,
                    sequence.getAndIncrement(),
                    null,
                    null,
                    true));
        } finally {
            lifecycleLock.unlock();
        }

        try {
            writerThread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
            if (writerThread.isAlive()) {
                log.warn("Write thread did not terminate within {}ms of shutdown",
                        SHUTDOWN_JOIN_TIMEOUT_MS);
                writerThread.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for write thread to terminate");
        }
    }

    // ------------------------------------------------------------------
    // Writer loop
    // ------------------------------------------------------------------

    private void runLoop() {
        while (true) {
            WorkItem item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                log.debug("Write thread interrupted; exiting loop");
                Thread.currentThread().interrupt();
                drainAndCancel();
                return;
            }

            if (item.poison) {
                drainAndCancel();
                return;
            }

            try {
                Object result = item.operation.call();
                completeUnchecked(item.future, result);
            } catch (Throwable t) {
                item.future.completeExceptionally(t);
            }
        }
    }

    private void drainAndCancel() {
        WorkItem remaining;
        while ((remaining = queue.poll()) != null) {
            if (remaining.poison) {
                continue;
            }
            if (remaining.future != null) {
                remaining.future.completeExceptionally(
                        new IllegalStateException("WriteCoordinator has been shut down"));
            }
        }
    }

    /**
     * Completes a future with a value of unknown static type. The caller has
     * received {@code Object} from a {@code Callable<T>.call()} invocation
     * whose declared {@code T} is erased to {@code Object} inside this loop.
     * The cast is safe because the future was constructed with the same
     * type parameter as the callable.
     */
    @SuppressWarnings("unchecked")
    private static <T> void completeUnchecked(CompletableFuture<T> future, Object value) {
        future.complete((T) value);
    }

    /**
     * Unwraps an {@link ExecutionException} into the appropriate exception
     * to throw to the caller, matching the {@link WriteCoordinator#submit}
     * contract: {@link RuntimeException} and {@link Error} propagate
     * directly; checked exceptions are wrapped in a {@link RuntimeException}.
     */
    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            return new RuntimeException("Write operation failed with no cause", e);
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new RuntimeException(cause);
    }

    // ------------------------------------------------------------------
    // Work item
    // ------------------------------------------------------------------

    /**
     * Queue entry for a single write operation. Implements
     * {@link Comparable} for priority ordering: lower {@code rank} dequeues
     * first, with {@code sequence} as the FIFO tiebreaker within the same
     * priority. Poison items carry {@code null} callable and future and
     * signal shutdown to the writer loop.
     */
    private static final class WorkItem implements Comparable<WorkItem> {
        final int rank;
        final long sequence;
        final Callable<?> operation;
        final CompletableFuture<Object> future;
        final boolean poison;

        @SuppressWarnings("unchecked")
        WorkItem(
                int rank,
                long sequence,
                Callable<?> operation,
                CompletableFuture<?> future,
                boolean poison) {
            this.rank = rank;
            this.sequence = sequence;
            this.operation = operation;
            this.future = (CompletableFuture<Object>) future;
            this.poison = poison;
        }

        @Override
        public int compareTo(WorkItem other) {
            int byRank = Integer.compare(this.rank, other.rank);
            if (byRank != 0) {
                return byRank;
            }
            return Long.compare(this.sequence, other.sequence);
        }
    }

    // ------------------------------------------------------------------
    // Test-only accessors
    // ------------------------------------------------------------------

    /**
     * Waits up to the given duration for the writer thread to terminate.
     * Intended for test shutdown assertions.
     */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        writerThread.join(unit.toMillis(timeout));
        return !writerThread.isAlive();
    }
}

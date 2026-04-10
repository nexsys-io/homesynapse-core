/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link ReadExecutor} backed by a bounded pool of daemon
 * platform threads.
 *
 * <p>All read operations — including every sqlite-jdbc JNI read — execute
 * on the pool threads named {@code hs-read-0}, {@code hs-read-1}, …. Virtual
 * thread callers submit via {@link #execute(Callable)} and park on
 * {@link Future#get()} until a pool thread completes the operation. The
 * split between this class and {@link PlatformThreadWriteCoordinator}
 * mirrors SQLite's WAL concurrency model: a single serialized writer, N
 * parallel readers.</p>
 *
 * <p>The default pool size of 2 matches AMD-27's guidance for Raspberry Pi
 * 4/5 hardware; larger values can be passed when WAL read concurrency
 * requirements justify it.</p>
 *
 * <p><strong>Shutdown model:</strong> {@link #shutdown()} calls
 * {@link ExecutorService#shutdown()} (draining in-flight operations) and
 * waits with a bounded timeout. Subsequent submissions throw
 * {@link IllegalStateException}. Shutdown is idempotent.</p>
 *
 * <p>This class is package-private — it is wired into {@link DatabaseExecutor}
 * at startup and never referenced directly by module consumers.</p>
 *
 * @see ReadExecutor
 */
final class PlatformThreadReadExecutor implements ReadExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlatformThreadReadExecutor.class);

    private static final long SHUTDOWN_AWAIT_TIMEOUT_MS = 5_000L;

    private final ExecutorService pool;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean shutdown;

    /**
     * Creates a read executor backed by {@code threadCount} daemon platform
     * threads.
     *
     * @param threadCount number of platform read threads; must be {@code >= 1}
     * @throws IllegalArgumentException if {@code threadCount < 1}
     */
    PlatformThreadReadExecutor(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException(
                    "threadCount must be >= 1, got " + threadCount);
        }
        this.pool = Executors.newFixedThreadPool(threadCount, new ReadThreadFactory());
    }

    @Override
    public <T> T execute(Callable<T> operation) {
        if (operation == null) {
            throw new NullPointerException("operation must not be null");
        }
        if (shutdown) {
            throw new IllegalStateException("ReadExecutor has been shut down");
        }

        Future<T> future;
        try {
            future = pool.submit(operation);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Pool may have been shut down between the volatile check and submit.
            throw new IllegalStateException("ReadExecutor has been shut down", e);
        }

        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException(
                    "Interrupted while waiting for read operation to complete", e);
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
            pool.shutdown();
        } finally {
            lifecycleLock.unlock();
        }

        try {
            if (!pool.awaitTermination(SHUTDOWN_AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("Read pool did not terminate within {}ms; forcing shutdown",
                        SHUTDOWN_AWAIT_TIMEOUT_MS);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            log.warn("Interrupted while waiting for read pool to terminate");
        }
    }

    /**
     * Unwraps an {@link ExecutionException} into the appropriate exception
     * to throw to the caller, matching the {@link ReadExecutor#execute}
     * contract: {@link RuntimeException} and {@link Error} propagate
     * directly; checked exceptions are wrapped in a {@link RuntimeException}.
     */
    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            return new RuntimeException("Read operation failed with no cause", e);
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new RuntimeException(cause);
    }

    /**
     * Thread factory that produces named daemon platform threads for the
     * read pool. Threads are named {@code hs-read-0}, {@code hs-read-1}, …
     * in the order the pool creates them.
     */
    private static final class ReadThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        ReadThreadFactory() {
            // Defaults.
        }

        @Override
        public Thread newThread(Runnable r) {
            return Thread.ofPlatform()
                    .name("hs-read-" + counter.getAndIncrement())
                    .daemon(true)
                    .unstarted(r);
        }
    }
}

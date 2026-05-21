/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.SubscriberReadExecutor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated platform-thread {@link SubscriberReadExecutor} that owns one
 * SQLite read {@link Connection} per subscriber (INV-SUB-ISO-02, AMD-26/27,
 * M3.6d-b Gap 3).
 *
 * <p>Each instance encapsulates a single daemon platform thread and a single
 * SQLite read connection. Subscriber virtual threads submit
 * {@link Callable read operations} through {@link #executeRead(Callable)};
 * the VT parks on the returned {@link Future} while the platform thread
 * executes the sqlite-jdbc JNI call, avoiding carrier pinning under the
 * AMD-26/27 mitigation strategy.</p>
 *
 * <p>Thread naming follows {@code "hs-sub-read-<subscriberId>"} so JFR
 * recordings and thread dumps map directly to the owning subscriber. Closing
 * the executor releases both the worker thread and the read connection;
 * subsequent {@link #executeRead} calls throw
 * {@link IllegalStateException}.</p>
 *
 * <p>Package-private — composition wiring constructs instances via
 * {@link SqliteSubscriberReadConnectionFactory}. Consumers interact only
 * through the public {@link SubscriberReadExecutor} interface.</p>
 *
 * @see SqliteSubscriberReadConnectionFactory
 * @see SubscriberReadExecutor
 */
final class SqliteSubscriberReadExecutor implements SubscriberReadExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteSubscriberReadExecutor.class);

    private static final long SHUTDOWN_GRACE_SECONDS = 5L;

    private final Connection connection;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String subscriberId;

    /**
     * Constructs a new executor over the given pre-configured read connection.
     *
     * <p>The connection must already have had its PRAGMAs applied (see
     * {@link SqliteSubscriberReadConnectionFactory}). This constructor takes
     * ownership of the connection — {@link #close()} closes it.</p>
     *
     * @param connection   the per-subscriber read connection; never
     *                     {@code null}
     * @param subscriberId the owning subscriber's stable identifier; used
     *                     for thread naming and diagnostic logging
     */
    SqliteSubscriberReadExecutor(Connection connection, String subscriberId) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.subscriberId = Objects.requireNonNull(subscriberId, "subscriberId");
        this.executor = Executors.newSingleThreadExecutor(threadFactory(subscriberId));
    }

    @Override
    public <T> T executeRead(Callable<T> task) throws Exception {
        Objects.requireNonNull(task, "task");
        if (closed.get()) {
            throw new IllegalStateException(
                    "SubscriberReadExecutor closed: subscriberId=" + subscriberId);
        }

        Future<T> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException(
                    "SubscriberReadExecutor closed: subscriberId=" + subscriberId, e);
        }

        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(
                    "Read operation failed with no cause", e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("Subscriber read executor did not terminate within {}s: "
                                + "subscriberId={}",
                        SHUTDOWN_GRACE_SECONDS, subscriberId);
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warn("Failed to close subscriber read connection: subscriberId={}, error={}",
                    subscriberId, e.getMessage(), e);
        }
    }

    private static ThreadFactory threadFactory(String subscriberId) {
        return runnable -> {
            Thread t = new Thread(runnable, "hs-sub-read-" + subscriberId);
            t.setDaemon(true);
            return t;
        };
    }
}

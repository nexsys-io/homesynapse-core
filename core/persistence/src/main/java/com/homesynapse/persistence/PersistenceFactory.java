/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.bus.CheckpointStore;
import com.homesynapse.event.bus.PersistentDlqWriter;
import com.homesynapse.event.bus.SubscriberReadConnectionFactory;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.state.AtomicCheckpointSink;
import com.homesynapse.state.StateCheckpointSource;
import com.homesynapse.state.StateStore;
import com.homesynapse.state.ViewCheckpointStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.IntSupplier;

/**
 * Public gateway for constructing the persistence subsystem (DEC-M3-16,
 * M3.6d-b Gap 1).
 *
 * <p>Wraps the package-private {@link SqlitePersistenceLifecycle} and exposes
 * its stores through their public interface types. The composition root
 * ({@code HomeSynapseCore}) calls {@link #start} to bring up the persistence
 * layer; {@link #close} flushes the WAL and tears down all connections.</p>
 *
 * <p>Replaces {@code PersistenceTestHarness} for production use — the harness
 * remains for tests that need its decorator hooks
 * ({@code startWithWriteCoordinator}, {@code startThrottled}). Both routes
 * end up at the same {@link SqlitePersistenceLifecycle}.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #start} runs synchronously on the calling thread. It MUST be
 * invoked from a platform thread because {@link JacksonWarmup} requires one
 * (LTD-19). Production wiring satisfies this by calling
 * {@code HomeSynapseCore.start()} from {@code main()}.</p>
 *
 * <h2>Public type surface</h2>
 *
 * <p>Every method returns a public type from an exported module. The
 * {@code -Xlint:exports} verification is satisfied — no package-private
 * type leaks through this gateway.</p>
 *
 * @see SqlitePersistenceLifecycle
 */
public final class PersistenceFactory implements AutoCloseable {

    private final SqlitePersistenceLifecycle lifecycle;
    private volatile boolean abandoned = false;

    private PersistenceFactory(SqlitePersistenceLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Starts the persistence subsystem and returns a factory exposing its
     * stores.
     *
     * <p>Construction is synchronous: when this method returns, all stores
     * are ready to receive traffic. {@link SqlitePersistenceLifecycle#start}
     * returns a {@code CompletableFuture} that is already completed; this
     * method joins it inline so any startup failure surfaces as a
     * {@link RuntimeException} on the caller's stack.</p>
     *
     * @param dbPath       full path to the SQLite database file; never
     *                     {@code null}
     * @param config       persistence configuration (deployment profile,
     *                     retention policy); never {@code null}. Use
     *                     {@link PersistenceConfig#HOME_DEFAULT} for the
     *                     MVP default.
     * @param clock        injected clock; never {@code null}
     * @param homeId       home identity for this installation (AMD-34); never
     *                     {@code null}
     * @param eventClasses domain-event record classes to register for
     *                     polymorphic serialization; must not be empty
     *                     (no classpath scanning per LTD-07)
     * @param payloadCipher the at-rest payload cipher (Doc 15 §3.8 seam,
     *                     M6.3), threaded to the write/read path; <strong>may
     *                     be {@code null}</strong> when at-rest payload
     *                     encryption is unavailable — the M6.2 production
     *                     state and every no-crypto caller. When non-null, the
     *                     sensitive-PII scopes ({@code [identity,
     *                     presence_personal]}) are encrypted-on-write.
     * @return a started factory exposing all persistence stores
     * @throws NullPointerException if any argument other than
     *                              {@code payloadCipher} is {@code null}
     * @throws RuntimeException     if the persistence layer fails to start
     */
    public static PersistenceFactory start(
            Path dbPath,
            PersistenceConfig config,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses,
            PayloadCipher payloadCipher) {
        Objects.requireNonNull(dbPath, "dbPath");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(homeId, "homeId");
        Objects.requireNonNull(eventClasses, "eventClasses");
        // payloadCipher is intentionally nullable (M6.2 state / no-crypto callers).

        SqlitePersistenceLifecycle lifecycle = new SqlitePersistenceLifecycle(
                dbPath, config, clock, homeId, eventClasses, payloadCipher);
        try {
            lifecycle.start().join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(
                    "Failed to start persistence layer", cause != null ? cause : ce);
        }
        return new PersistenceFactory(lifecycle);
    }

    // ─── Store accessors (return public interface types) ───

    /**
     * Returns the event publisher — the durable append path.
     *
     * @return the production {@link EventPublisher}
     */
    public EventPublisher eventPublisher() {
        return lifecycle.eventStore();
    }

    /**
     * Returns the event store — the read interface over the event log.
     *
     * @return the production {@link EventStore}
     */
    public EventStore eventStore() {
        return lifecycle.eventStore();
    }

    /**
     * Returns the subscriber checkpoint store.
     *
     * @return the production {@link CheckpointStore}
     */
    public CheckpointStore checkpointStore() {
        return lifecycle.checkpointStore();
    }

    /**
     * Returns the view checkpoint store used by projections.
     *
     * @return the production {@link ViewCheckpointStore}
     */
    public ViewCheckpointStore viewCheckpointStore() {
        return lifecycle.viewCheckpointStore();
    }

    /**
     * Returns the materialized state store.
     *
     * @return the production {@link StateStore}
     */
    public StateStore stateStore() {
        return lifecycle.stateStore();
    }

    /**
     * Returns the state checkpoint source — the same backing instance as
     * {@link #stateStore()}, exposed under its {@link StateCheckpointSource}
     * role for the projection's checkpoint contract.
     *
     * @return the production {@link StateCheckpointSource}
     */
    public StateCheckpointSource stateCheckpointSource() {
        return lifecycle.stateStore();
    }

    /**
     * Returns the atomic subscriber+view checkpoint sink (AMD-45 §2.1).
     *
     * <p>Backs the {@link AtomicCheckpointSink} state-store interface with the
     * package-private {@code AtomicCheckpointWriter}, which writes the
     * {@code subscriber_checkpoints} position and the {@code view_checkpoints}
     * snapshot in a single SQLite transaction (AMD-45-INV-01). The projection's
     * stable {@code checkpointKey} is used as both the subscriber id and the
     * view name — for the materialized state projection these are the same
     * identifier ({@code "state_projection"}).</p>
     *
     * <p>The returned object is typed as the exported-module
     * {@link AtomicCheckpointSink} interface; the {@code AtomicCheckpointWriter}
     * it captures stays package-private (no persistence type leaks onto the
     * state-store-facing API surface — the inward dependency direction is
     * preserved).</p>
     *
     * @return the production {@link AtomicCheckpointSink}
     */
    public AtomicCheckpointSink atomicCheckpointSink() {
        AtomicCheckpointWriter writer = lifecycle.atomicCheckpointWriter();
        return (checkpointKey, position, viewData) ->
                writer.writeAtomicCheckpoint(checkpointKey, position, checkpointKey, viewData);
    }

    // ─── Infrastructure accessors ───

    /**
     * Returns the per-subscriber read connection factory. Each
     * {@code create(subscriberId)} call opens a dedicated SQLite read
     * connection + platform thread (INV-SUB-ISO-02).
     *
     * @return the production {@link SubscriberReadConnectionFactory}
     */
    public SubscriberReadConnectionFactory subscriberReadConnectionFactory() {
        return lifecycle.subscriberReadConnectionFactory();
    }

    /**
     * Returns an {@link IntSupplier} exposing the current write-queue
     * depth (DEC-M3-14). Surfaced to the event bus and the queue saturation
     * health check so neither holds a direct reference to the persistence
     * module's internal types.
     *
     * @return the writer-queue-depth supplier
     */
    public IntSupplier writeQueueDepthSupplier() {
        WriteCoordinator coordinator = lifecycle.databaseExecutor().writeCoordinator();
        return coordinator::queueSize;
    }

    /**
     * Returns the persistent DLQ writer adapter. Production wiring passes
     * this through the {@link PersistentDlqWriter} functional interface to
     * the bus's subscriber supervisor (AMD-36).
     *
     * @return the production {@link PersistentDlqWriter}
     */
    public PersistentDlqWriter deadLetterWriter() {
        return lifecycle.deadLetterStore()::park;
    }

    // ─── Lifecycle ───

    /**
     * Stops the persistence subsystem. Flushes the WAL via
     * {@code PRAGMA wal_checkpoint(TRUNCATE)} and closes all executors and
     * connections. Idempotent.
     */
    @Override
    public void close() {
        if (abandoned) {
            return;
        }
        lifecycle.stop();
    }

    /**
     * Abandons the persistence layer without performing a WAL checkpoint,
     * releasing all OS-level resources (JDBC connections, executor threads).
     *
     * <p>The WAL and {@code -shm} files remain on disk; SQLite's automatic
     * WAL recovery handles them on next open. All pending writes in the
     * executor queue are discarded.</p>
     *
     * <p>Use for crash simulation in tests and emergency shutdown in
     * production (e.g., imminent power loss, OOM). Normal shutdown MUST use
     * {@link #close()}.</p>
     *
     * <p>Do NOT use for normal shutdown — {@link #close()} flushes the WAL
     * via {@code PRAGMA wal_checkpoint(TRUNCATE)} before closing.</p>
     *
     * <p>Idempotent. Calling this after {@link #close()} is a no-op. Calling
     * {@link #close()} after this is a no-op.</p>
     *
     * @implNote Mutual exclusion with {@link #close()} is enforced via the
     *           {@code abandoned} flag. Both methods check it before acting.
     *           INV-ES-04 is preserved: events already persisted survive
     *           abandon and are replayed from the store on restart.
     */
    public void abandon() {
        if (abandoned) {
            return;
        }
        abandoned = true;
        lifecycle.abandonWithoutCheckpoint();
    }
}

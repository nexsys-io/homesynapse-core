/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event.bus;

/**
 * Durable storage for subscriber checkpoint positions, enabling crash-safe resumption
 * of event processing.
 *
 * <p>Each subscriber in the pull-based subscription model (Doc 01 §3.4) tracks its
 * progress through the event log via a <em>checkpoint</em> — the global position of
 * the last event it successfully processed. After processing a batch of events, a
 * subscriber writes its updated checkpoint through this interface. On restart (or
 * re-registration), the subscriber reads its checkpoint to resume from where it left
 * off, avoiding reprocessing.</p>
 *
 * <p>Checkpoints are stored in the {@code subscriber_checkpoints} table in the same
 * SQLite database file as the domain event store (Doc 01 §4.2). Co-locating
 * checkpoints with events enables atomic checkpoint-and-query within a single
 * database connection — a subscriber can advance its checkpoint and read the next
 * batch of events in the same transaction, eliminating a class of crash-recovery
 * edge cases.</p>
 *
 * <p><strong>Thread safety:</strong> Implementations must be safe for concurrent use.
 * Multiple subscribers may read and write checkpoints concurrently.</p>
 *
 * @see EventBus
 * @see SubscriberInfo
 * @see <a href="Doc 01 §3.4">Subscription Model</a>
 * @see <a href="Doc 01 §4.2">Domain Event Store Schema — subscriber_checkpoints table</a>
 */
public interface CheckpointStore {

    /**
     * Returns the last checkpointed global position for the given subscriber.
     *
     * <p>Returns 0 if no checkpoint exists for the subscriber (i.e., the subscriber
     * has never checkpointed). A return value of 0 means the subscriber should begin
     * processing from the start of the event log.</p>
     *
     * <p>This method is called during subscriber registration
     * ({@link EventBus#subscribe(SubscriberInfo)}) to determine the subscriber's
     * starting position.</p>
     *
     * @param subscriberId the stable string identifier of the subscriber
     * @return the last checkpointed global position, or 0 if none exists
     * @throws NullPointerException if {@code subscriberId} is {@code null}
     */
    long readCheckpoint(String subscriberId);

    /**
     * Atomically writes the subscriber's checkpoint to durable storage.
     *
     * <p>Called by subscribers after successfully processing a batch of events. The
     * checkpoint must be durable before this method returns — in the SQLite
     * implementation, this means the write is committed to the
     * {@code subscriber_checkpoints} table within the same database file as the
     * domain event store (Doc 01 §4.2).</p>
     *
     * @param subscriberId   the stable string identifier of the subscriber
     * @param globalPosition the global position of the last successfully processed event
     * @throws NullPointerException     if {@code subscriberId} is {@code null}
     * @throws IllegalArgumentException if {@code globalPosition} is negative
     */
    void writeCheckpoint(String subscriberId, long globalPosition);
}

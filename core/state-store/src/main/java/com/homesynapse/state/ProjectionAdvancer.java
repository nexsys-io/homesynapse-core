/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

/**
 * Advances a state projection by reading a bounded chunk of events from the
 * event store and applying them to the projection's state model.
 *
 * <p>Each call to {@link #advance} represents a single, short-lived read
 * transaction against the event store. The transaction MUST be opened and
 * closed within the call — no cursors or transactions are held between calls.
 * This bounded-window discipline prevents WAL checkpoint starvation (AMD-38)
 * by ensuring SQLite's {@code wal_checkpoint} can advance past the reader's
 * snapshot at the end of every call.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>{@code maxRows} ceiling: {@link #DEFAULT_MAX_ROWS}. Implementations
 *       must reject larger values via {@link IllegalArgumentException}.</li>
 *   <li>Read transaction duration ceiling: 2 seconds. If a chunk's processing
 *       would exceed this budget, the implementation should return early with
 *       {@code hasMore = true} and let the caller invoke {@code advance} again.</li>
 *   <li>Each call is an independent read transaction — no state is carried
 *       between calls. The caller passes the returned
 *       {@link AdvanceResult#lastProcessedPosition()} as the next call's
 *       {@code fromPosition}.</li>
 *   <li>The caller (projection loop) is responsible for checkpointing per the
 *       active {@link CheckpointPolicy} — the advancer does not write
 *       checkpoints, only state updates.</li>
 * </ul>
 *
 * <p>Implementations may serialize state-model writes against an internal lock
 * or use lock-free data structures (per LTD-11 — no {@code synchronized}).
 * The behavioral contract is the same either way: after {@code advance}
 * returns, the projection's materialized state reflects every event up to and
 * including {@link AdvanceResult#lastProcessedPosition()}.
 *
 * @see CheckpointPolicy
 * @see AdvanceResult
 */
public interface ProjectionAdvancer {

    /**
     * Default maximum rows per advance call. Implementations must reject
     * {@code maxRows} values greater than this constant.
     */
    int DEFAULT_MAX_ROWS = 500;

    /**
     * Reads up to {@code maxRows} events starting after {@code fromPosition}
     * and applies them to the projection.
     *
     * <p>The implementation opens a read transaction, executes a query
     * equivalent to
     * {@code SELECT ... WHERE global_position > fromPosition
     * ORDER BY global_position LIMIT maxRows}, processes each event, and
     * closes the transaction — all within 2 seconds. If processing the full
     * chunk would exceed the duration budget, the implementation may return
     * early with {@code hasMore = true} after processing fewer than
     * {@code maxRows} events.
     *
     * <p>When the projection has caught up to the writer's head, the returned
     * {@link AdvanceResult} has {@code eventsProcessed = 0},
     * {@code lastProcessedPosition = fromPosition}, and {@code hasMore = false}.
     *
     * @param fromPosition the {@code global_position} to read after (exclusive);
     *                     pass {@code 0} to start from the beginning of the log
     * @param maxRows      maximum number of events to process in this call;
     *                     must be between {@code 1} and {@link #DEFAULT_MAX_ROWS}
     *                     inclusive
     * @return the result of the advance, including the last position processed,
     *         the number of events processed, and whether more events remain
     * @throws IllegalArgumentException if {@code fromPosition < 0} or
     *                                  {@code maxRows} is out of range
     *                                  {@code [1, DEFAULT_MAX_ROWS]}
     */
    AdvanceResult advance(long fromPosition, int maxRows);
}

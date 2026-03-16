/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Thrown by {@link EventPublisher} when an append violates the
 * ({@code subject_ref}, {@code subject_sequence}) unique constraint
 * (Doc 01 §4.2, §6.7).
 *
 * <p>This exception indicates an optimistic concurrency conflict — two events
 * attempted to claim the same sequence number for the same subject. Under the
 * single-writer model (LTD-03), this should only occur if the publisher
 * implementation computes an incorrect sequence number or the event store is
 * corrupted. The publisher assigns sequence numbers internally; callers do not
 * choose them.</p>
 *
 * <p><strong>Recovery:</strong> the caller should not retry with the same
 * sequence number. The publisher implementation should read the current maximum
 * sequence for the subject and re-derive the next value.</p>
 *
 * @see EventPublisher#publish(EventDraft, CausalContext)
 * @see EventPublisher#publishRoot(EventDraft, com.homesynapse.platform.identity.Ulid)
 */
public class SequenceConflictException extends Exception {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final transient SubjectRef subjectRef;
    private final long conflictingSequence;

    /**
     * Creates a new sequence conflict exception.
     *
     * @param subjectRef          the subject whose sequence constraint was violated;
     *                            never {@code null}
     * @param conflictingSequence the sequence number that caused the conflict
     * @throws NullPointerException if {@code subjectRef} is {@code null}
     */
    public SequenceConflictException(SubjectRef subjectRef, long conflictingSequence) {
        super("Event sequence conflict: sequence %d already exists for subject %s"
                .formatted(conflictingSequence, subjectRef));
        this.subjectRef = Objects.requireNonNull(subjectRef,
                "subjectRef must not be null");
        this.conflictingSequence = conflictingSequence;
    }

    /**
     * Returns the subject whose sequence constraint was violated.
     *
     * @return the subject reference, never {@code null}
     */
    public SubjectRef subjectRef() {
        return subjectRef;
    }

    /**
     * Returns the sequence number that caused the conflict.
     *
     * @return the conflicting sequence number
     */
    public long conflictingSequence() {
        return conflictingSequence;
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.platform.identity.Ulid;

import java.time.Instant;
import java.util.Objects;

/**
 * A persistent dead-letter entry — a poison event parked durably after a
 * subscriber failed to process it (AMD-36).
 *
 * <p>This record mirrors the {@code subscriber_dead_letters} row shape defined
 * by the V002 migration. Field names map to V002 columns via the conventional
 * snake_case-to-camelCase rule:</p>
 *
 * <table>
 *   <caption>Field-to-column mapping</caption>
 *   <tr><th>Record field</th><th>V002 column</th></tr>
 *   <tr><td>{@code dlqId}</td><td>{@code dlq_id}</td></tr>
 *   <tr><td>{@code subscriberId}</td><td>{@code subscriber_id}</td></tr>
 *   <tr><td>{@code sequenceKey}</td><td>{@code sequence_key}</td></tr>
 *   <tr><td>{@code eventPosition}</td><td>{@code event_position}</td></tr>
 *   <tr><td>{@code eventId}</td><td>{@code event_id}</td></tr>
 *   <tr><td>{@code causeClass}</td><td>{@code cause_class}</td></tr>
 *   <tr><td>{@code causeMessage}</td><td>{@code cause_message}</td></tr>
 *   <tr><td>{@code attemptCount}</td><td>{@code attempt_count}</td></tr>
 *   <tr><td>{@code firstSeenAt}</td><td>{@code first_seen_at}</td></tr>
 *   <tr><td>{@code lastAttemptAt}</td><td>{@code last_attempt_at}</td></tr>
 *   <tr><td>{@code diagnostics}</td><td>{@code diagnostics}</td></tr>
 * </table>
 *
 * <p>Unlike the in-memory {@link SubscriberDlq.DlqEntry} ring, this record
 * carries the full identity context required to look the event up in the
 * domain event store ({@code eventId} as BLOB(16), {@code eventPosition} for
 * the global position) and to group retries per ordering key
 * ({@code sequenceKey}).</p>
 *
 * <p>{@code dlqId} is the SQLite {@code AUTOINCREMENT} primary key assigned at
 * INSERT time. For records constructed on the application side prior to
 * persistence (e.g., to pass through {@link PersistentDlqWriter#park}), use
 * {@code dlqId = 0} as the unset sentinel — the store ignores the supplied
 * value and uses the value SQLite assigns. Read-back records carry the actual
 * row id assigned by SQLite.</p>
 *
 * <p>{@code diagnostics} is nullable — a free-form text payload for operator
 * tooling (e.g., serialized stack trace). All other fields are non-null.</p>
 *
 * @param dlqId         primary key assigned by SQLite on INSERT; {@code 0} on
 *                      application-side construction prior to persist
 * @param subscriberId  stable string identifier of the subscriber whose
 *                      delivery failed; never {@code null}
 * @param sequenceKey   the per-entity sequence key (e.g., subject reference) —
 *                      used by retry tooling to maintain per-key ordering;
 *                      never {@code null}
 * @param eventPosition global position of the failing event; non-negative
 * @param eventId       the ULID identity of the failing event; never
 *                      {@code null}
 * @param causeClass    the exception class name that caused the failure;
 *                      never {@code null}
 * @param causeMessage  the exception message that caused the failure;
 *                      never {@code null} (use empty string when the
 *                      underlying message is null)
 * @param attemptCount  number of delivery attempts so far; must be at least 1
 * @param firstSeenAt   the instant of the first failure for this event;
 *                      never {@code null}
 * @param lastAttemptAt the instant of the most recent failure for this event;
 *                      never {@code null}
 * @param diagnostics   free-form diagnostic text (e.g., serialized stack
 *                      trace); may be {@code null}
 * @see PersistentDlqWriter
 * @see SubscriberMaxRetries
 */
public record DeadLetter(
        long dlqId,
        String subscriberId,
        String sequenceKey,
        long eventPosition,
        Ulid eventId,
        String causeClass,
        String causeMessage,
        int attemptCount,
        Instant firstSeenAt,
        Instant lastAttemptAt,
        String diagnostics
) {

    /**
     * Sentinel value for the {@code dlqId} on application-side construction
     * prior to persistence. SQLite assigns the real {@code dlq_id} via
     * {@code AUTOINCREMENT}.
     */
    public static final long UNASSIGNED_DLQ_ID = 0L;

    /**
     * Compact constructor enforcing the non-null/non-negative invariants from
     * the V002 schema's {@code NOT NULL} constraints.
     *
     * @throws NullPointerException     if any required field is {@code null}
     * @throws IllegalArgumentException if {@code eventPosition < 0} or
     *                                  {@code attemptCount < 1}
     */
    public DeadLetter {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        Objects.requireNonNull(sequenceKey, "sequenceKey must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(causeClass, "causeClass must not be null");
        Objects.requireNonNull(causeMessage, "causeMessage must not be null");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
        Objects.requireNonNull(lastAttemptAt, "lastAttemptAt must not be null");
        if (eventPosition < 0L) {
            throw new IllegalArgumentException(
                    "eventPosition must be >= 0, got " + eventPosition);
        }
        if (attemptCount < 1) {
            throw new IllegalArgumentException(
                    "attemptCount must be >= 1, got " + attemptCount);
        }
    }
}

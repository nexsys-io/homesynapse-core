/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

/**
 * Injection seam for persistent dead-letter storage (AMD-36).
 *
 * <p>The bus module's in-memory {@link SubscriberDlq} ring caps poison events
 * at a small fixed depth (1024 per AMD-42 §3.4.5). The persistent writer
 * extends durability beyond a single process lifetime: production wiring
 * supplies a lambda backed by the persistence module's
 * {@code SqliteDeadLetterStore}, while tests pass a no-op or recording stub.</p>
 *
 * <p>This interface deliberately depends only on {@link DeadLetter} — itself
 * defined in the event-bus module. The bus has no compile-time dependency on
 * the persistence module, preserving the module-graph direction
 * ({@code event-bus} → no {@code persistence}; {@code persistence} → all of
 * {@code event-bus}, {@code state-store}, {@code event-model}).</p>
 *
 * <h2>Idempotency</h2>
 *
 * <p>Implementations must treat {@code (subscriberId, eventPosition)} as the
 * idempotency key — the V002 schema enforces this via
 * {@code UNIQUE(subscriber_id, event_position)}. A second {@code park} call
 * for an entry that already exists updates {@code attempt_count},
 * {@code last_attempt_at}, {@code cause_class}, {@code cause_message}, and
 * {@code diagnostics} on the existing row rather than inserting a duplicate.</p>
 *
 * @see DeadLetter
 * @see SubscriberDlq
 */
@FunctionalInterface
public interface PersistentDlqWriter {

    /**
     * Parks the given dead-letter entry in durable storage.
     *
     * <p>Implementations are expected to be safe to call from any thread. The
     * call may block briefly while the underlying write coordinator schedules
     * the SQLite upsert.</p>
     *
     * @param deadLetter the dead-letter entry; never {@code null}
     */
    void park(DeadLetter deadLetter);

    /**
     * Returns a no-op writer suitable for tests and in-memory-only deployments.
     *
     * @return a writer that silently discards every {@code park} call
     */
    static PersistentDlqWriter noop() {
        return dl -> { };
    }
}

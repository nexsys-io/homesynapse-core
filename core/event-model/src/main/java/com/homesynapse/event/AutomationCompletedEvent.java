/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code automation_completed} events — a Run finished with a terminal
 * status (Doc 07 §3.7; Doc 01 §4.3).
 *
 * <p>Produced by the Automation Engine's {@code RunManager} when a Run reaches a
 * terminal state. Every {@code automation_triggered} event is followed by exactly one
 * {@code automation_completed} carrying the same {@code runId} (Contract C1) — including
 * Runs that ended without executing actions ({@code CONDITION_NOT_MET}) and Runs
 * finalized after an unclean shutdown ({@code INTERRUPTED}, Doc 07 §3.10).</p>
 *
 * <p>Reshaped by AMD-92 (row 2) from the M1-era minimal shape
 * {@code (status, failureReason, durationMs)}. The reshape is regret-proof: zero
 * production publish sites and zero persisted instances existed at the time (AMD-92 §10,
 * E92-2 grep-attested).</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> The payload references only
 * event-resident-or-below types. {@code runId} is a bare {@link Ulid} (the
 * automation-resident {@code RunId} is flattened); {@code finalStatus} is the flattened
 * {@code RunStatus.name()} carried as a {@code String}. No automation-resident type
 * appears here — that would invert the JPMS edge to {@code event -> automation} (a hard
 * cycle).</p>
 *
 * <p>Default priority: {@link EventPriority#NORMAL NORMAL} — the user must be able to
 * learn that a Run completed (and how) even after the DIAGNOSTIC retention window.</p>
 *
 * @param runId        the Run instance identifier (bare ULID — flattened
 *                     {@code RunId}); never {@code null}
 * @param finalStatus  the terminal {@code RunStatus.name()} (e.g. {@code "COMPLETED"},
 *                     {@code "FAILED"}, {@code "ABORTED"}, {@code "CONDITION_NOT_MET"},
 *                     {@code "INTERRUPTED"}); never {@code null} or blank
 * @param durationMs   the wall-clock duration of the Run in milliseconds; {@code >= 0}
 * @param actionCount  the number of actions executed by the Run; {@code >= 0}
 * @param commandCount the number of commands dispatched by the Run; {@code >= 0}
 * @param failureReason a human-readable failure description; {@code null} unless
 *                      {@code finalStatus} is {@code "FAILED"}
 * @param abortReason   the abort cause (e.g. {@code "restart_mode"}, {@code "shutdown"},
 *                      {@code "interrupted_by_crash"}); {@code null} unless the Run was
 *                      aborted or interrupted (§6.6, §3.10)
 * @param skippedUnavailable the number of command targets skipped for unavailability under
 *                      {@code SKIP} policy across the Run (Doc 07 §3.9; IR-95); {@code >= 0};
 *                      {@code 0} for Runs that issued every command, for
 *                      {@code CONDITION_NOT_MET} and for {@code INTERRUPTED} (the crash
 *                      re-derivation re-executes nothing, so the count is unknown and
 *                      written {@code 0}). Appended LAST by J3 T-B (additive; JSON
 *                      {@code skipped_unavailable}); a pre-J3 row reads {@code 0}
 * @see AutomationTriggeredEvent
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_COMPLETED
 */
@EventType(EventTypes.AUTOMATION_COMPLETED)
public record AutomationCompletedEvent(
        Ulid runId,
        String finalStatus,
        long durationMs,
        int actionCount,
        int commandCount,
        String failureReason,
        String abortReason,
        int skippedUnavailable
) implements DomainEvent {

    /**
     * Validates required fields and non-negative counts. The {@code failureReason} and
     * {@code abortReason} fields are nullable.
     *
     * @throws NullPointerException     if {@code runId} or {@code finalStatus} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code finalStatus} is blank, or
     *                                  {@code durationMs}, {@code actionCount},
     *                                  {@code commandCount} or {@code skippedUnavailable} is
     *                                  negative
     */
    public AutomationCompletedEvent {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(finalStatus, "finalStatus must not be null");
        if (finalStatus.isBlank()) {
            throw new IllegalArgumentException("finalStatus must not be blank");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0: " + durationMs);
        }
        if (actionCount < 0) {
            throw new IllegalArgumentException("actionCount must be >= 0: " + actionCount);
        }
        if (commandCount < 0) {
            throw new IllegalArgumentException("commandCount must be >= 0: " + commandCount);
        }
        if (skippedUnavailable < 0) {
            throw new IllegalArgumentException(
                    "skippedUnavailable must be >= 0: " + skippedUnavailable);
        }
    }
}

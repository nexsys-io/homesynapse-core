/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Instant;
import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * One element of the {@code GET /api/v1/runs} entry list — a terminal automation Run,
 * projected from the immutable event log (Doc 16 §3.3, INV-SA-03).
 *
 * <p>This is a read-side projection record assembled by {@link ExplanationService}; it is
 * never persisted and mints no event (SP2). Each summary is derived from one
 * {@code automation_completed} event: the {@code runId}, terminal {@link RunStatus}, and
 * {@code terminalReason} come from the event payload, while {@code automationId} is read
 * from the event <em>envelope</em>'s subject reference (the run-lifecycle events are
 * published on {@code SubjectRef.automation(...)}; the payload carries no automation id),
 * and {@code automationName} is a best-effort lookup against the current
 * {@link AutomationRegistry} (may be {@code null} if the definition is no longer loaded).</p>
 *
 * <p><strong>Status vocabulary:</strong> {@code status} is the internal {@link RunStatus}
 * enum. The rest-api boundary maps it to the frozen public wire vocabulary
 * ({@code COMPLETED|FAILED|SKIPPED|CANCELLED|INTERRUPTED}) per the v1.1 dashboard contract
 * (DP-A1); the internal enum never appears on the wire. The precise engine reason rides
 * {@code terminalReason}.</p>
 *
 * @param runId          the Run's identifier, never {@code null}
 * @param automationId   the owning automation's identifier (from the event subject), never {@code null}
 * @param automationName the automation's display name, or {@code null} if the definition is gone
 * @param triggeredAt    when the Run was triggered (best-effort: the terminal event time minus
 *                       the recorded duration), never {@code null}
 * @param status         the terminal {@link RunStatus}; mapped to the wire vocabulary at the
 *                       rest-api boundary (DP-A1), never {@code null}
 * @param terminalReason the precise engine reason (failure or abort detail), or {@code null}
 */
public record RunSummary(
        RunId runId,
        AutomationId automationId,
        String automationName,
        Instant triggeredAt,
        RunStatus status,
        String terminalReason) {

    /**
     * Validates the structural (non-nullable) components. {@code automationName} and
     * {@code terminalReason} are nullable by contract.
     *
     * @throws NullPointerException if {@code runId}, {@code automationId},
     *                              {@code triggeredAt}, or {@code status} is {@code null}
     */
    public RunSummary {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggeredAt, "triggeredAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code automation_run_skipped} events — a matched trigger was dropped by
 * concurrency-mode enforcement, so no Run was created (Doc 07 §3.6; AMD-92 row 7).
 *
 * <p>Emitted when a trigger fires for an automation that is already at its concurrency
 * ceiling: a {@link com.homesynapse.platform.identity.AutomationId} in
 * {@code SINGLE} mode with an active Run ({@code reason = "mode_busy"}), or a
 * {@code QUEUED}/{@code PARALLEL} automation at {@code maxConcurrent}
 * ({@code reason = "queue_full"}). The drop is observable rather than silent — the user
 * must never infer behavior from the absence of trace events.</p>
 *
 * <p>No {@code runId} is carried: a Run was never created. The event identifies the
 * automation and the dropped trigger's event instead.</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> {@code activeRunId} is a bare
 * {@link Ulid} (the automation-resident {@code RunId} is flattened); the concurrency
 * mode is carried as a {@code String}. No automation-resident type appears here.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}.</p>
 *
 * @param automationId        the automation whose trigger was dropped; never {@code null}
 * @param triggeringEventId   the event whose match was dropped; never {@code null}
 * @param reason              the drop reason: {@code "mode_busy"} or {@code "queue_full"};
 *                            never {@code null} or blank
 * @param mode                the concurrency mode in effect ({@code ConcurrencyMode.name()});
 *                            never {@code null} or blank
 * @param activeRunId         the Run currently holding the slot (bare ULID — flattened
 *                            {@code RunId}); {@code null} when no single Run is
 *                            attributable (e.g. a {@code queue_full} drop)
 * @param maxExceededSeverity the configured drop log severity
 *                            ({@code MaxExceededSeverity.name()}); never {@code null} or
 *                            blank
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_RUN_SKIPPED
 */
@EventType(EventTypes.AUTOMATION_RUN_SKIPPED)
public record AutomationRunSkippedEvent(
        AutomationId automationId,
        EventId triggeringEventId,
        String reason,
        String mode,
        Ulid activeRunId,
        String maxExceededSeverity
) implements DomainEvent {

    /**
     * Validates required fields. The {@code activeRunId} field is nullable.
     *
     * @throws NullPointerException     if {@code automationId}, {@code triggeringEventId},
     *                                  {@code reason}, {@code mode}, or
     *                                  {@code maxExceededSeverity} is {@code null}
     * @throws IllegalArgumentException if {@code reason}, {@code mode}, or
     *                                  {@code maxExceededSeverity} is blank
     */
    public AutomationRunSkippedEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggeringEventId, "triggeringEventId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(maxExceededSeverity, "maxExceededSeverity must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (mode.isBlank()) {
            throw new IllegalArgumentException("mode must not be blank");
        }
        if (maxExceededSeverity.isBlank()) {
            throw new IllegalArgumentException("maxExceededSeverity must not be blank");
        }
    }
}

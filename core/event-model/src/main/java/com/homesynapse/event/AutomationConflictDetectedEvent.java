/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.List;
import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;

/**
 * Payload for {@code automation_conflict_detected} (AMD-92 row 9) — emitted after Runs
 * triggered by the same event are scanned for contradictory commands targeting the same
 * entity (Doc 07 §3.13).
 *
 * <p>Tier-1 conflict detection is report-only (D6): both commands have already executed; this
 * event documents the conflict for monitoring and the explainability view. No suppression or
 * priority resolution occurs.</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> The payload references only
 * event-resident-or-below types — platform-resident {@link EventId}/{@link EntityId}, and the
 * nested {@link ConflictEntry} value record (the AMD-92 R92-2 nested-payload precedent) whose
 * components are platform-resident or {@code String}. No automation-resident type appears
 * here.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}.</p>
 *
 * @param triggeringEventId the event that triggered all the scanned Runs, never {@code null}
 * @param entityRef         the entity both commands targeted, never {@code null}
 * @param conflicts         the conflicting command entries (at least two), unmodifiable
 *                          (defensively copied); never {@code null}
 * @param contradictory     whether the commands are genuinely contradictory (vs. merely
 *                          concurrent to the same entity)
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_CONFLICT_DETECTED
 */
@EventType(EventTypes.AUTOMATION_CONFLICT_DETECTED)
public record AutomationConflictDetectedEvent(
        EventId triggeringEventId,
        EntityId entityRef,
        List<ConflictEntry> conflicts,
        boolean contradictory
) implements DomainEvent {

    /**
     * Validates required fields and deep-copies the conflict list.
     *
     * @throws NullPointerException if {@code triggeringEventId}, {@code entityRef}, or
     *                              {@code conflicts} is {@code null}
     */
    public AutomationConflictDetectedEvent {
        Objects.requireNonNull(triggeringEventId, "triggeringEventId must not be null");
        Objects.requireNonNull(entityRef, "entityRef must not be null");
        Objects.requireNonNull(conflicts, "conflicts must not be null");
        conflicts = List.copyOf(conflicts);
    }

    /**
     * One command participating in a detected conflict. An event-resident value record
     * (AMD-92 R92-2) — not a top-level {@code @EventType}.
     *
     * @param automationId   the automation whose Run issued the command, never {@code null}
     * @param commandEventId the originating command event id, never {@code null}
     * @param commandName    the command issued, never {@code null}
     * @param parameters     the command parameters in a flattened String form, never
     *                       {@code null}
     */
    public record ConflictEntry(
            AutomationId automationId,
            EventId commandEventId,
            String commandName,
            String parameters
    ) {

        /**
         * Validates that all fields are non-null.
         *
         * @throws NullPointerException if any field is {@code null}
         */
        public ConflictEntry {
            Objects.requireNonNull(automationId, "automationId must not be null");
            Objects.requireNonNull(commandEventId, "commandEventId must not be null");
            Objects.requireNonNull(commandName, "commandName must not be null");
            Objects.requireNonNull(parameters, "parameters must not be null");
        }
    }
}

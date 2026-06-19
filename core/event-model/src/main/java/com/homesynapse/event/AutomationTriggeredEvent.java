/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Event emitted when an automation trigger matches and a Run begins (Doc 07 §3.7).
 *
 * <p>Reshaped to the Doc 07 §3.7 Locked payload by AMD-92 (row 1). The M1-era
 * placeholder shape ({@code triggerType}, {@code triggerDetail}) is replaced
 * outright — the reshape is regret-proof because zero production publish sites and
 * zero persisted instances existed at the time (AMD-92 §10, E92-2 grep-attested).</p>
 *
 * <p><strong>Type residency (SD-1 / AMD-92-INV-01).</strong> The payload references
 * only event-resident-or-below types. {@code runId} is a bare {@link Ulid} (the
 * automation-resident {@code RunId} is flattened); {@code cascadeDepth} is a plain
 * {@code int}; {@code matchedTriggers} carries user-facing trigger IDs (AMD-88 §2.5),
 * not raw indices. No automation-resident type appears here — that would invert the
 * JPMS edge to {@code event -> automation} (a hard cycle).</p>
 *
 * <p><strong>C1-interim publish hold (SD-3).</strong> This event has NO production
 * publish site in M7.1: an {@code automation_triggered} must not be published before
 * M7.2's completing {@code automation_completed} side exists (every triggered needs a
 * completed). The reshape + registration land here so the vocabulary is complete.</p>
 *
 * <p>Priority: NORMAL. Doc 07 §3.7; Doc 01 §4.3.</p>
 *
 * @param runId            the Run instance identifier (bare ULID — flattened
 *                         {@code RunId}); never {@code null}
 * @param triggeringEventId the event that caused the trigger to fire; never {@code null}
 * @param matchedTriggers  the trigger IDs (AMD-88) that matched, unmodifiable;
 *                         never {@code null}
 * @param resolvedTargets  the resolved entity sets keyed by selector label,
 *                         unmodifiable (deep-copied); never {@code null}
 * @param definitionHash   the SHA-256 hex of the serialized definition at trigger
 *                         time, for replay verification; never {@code null} or blank
 * @param cascadeDepth     the cascade depth (0 for user/device-initiated Runs); {@code >= 0}
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_TRIGGERED
 */
@EventType(EventTypes.AUTOMATION_TRIGGERED)
public record AutomationTriggeredEvent(
        Ulid runId,
        EventId triggeringEventId,
        List<String> matchedTriggers,
        Map<String, Set<EntityId>> resolvedTargets,
        String definitionHash,
        int cascadeDepth
) implements DomainEvent {

    /**
     * Validates required fields and deep-copies the collection components to
     * guarantee immutability.
     *
     * @throws NullPointerException     if any non-primitive field is {@code null}
     * @throws IllegalArgumentException if {@code definitionHash} is blank or
     *                                  {@code cascadeDepth} is negative
     */
    public AutomationTriggeredEvent {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(triggeringEventId, "triggeringEventId must not be null");
        Objects.requireNonNull(matchedTriggers, "matchedTriggers must not be null");
        Objects.requireNonNull(resolvedTargets, "resolvedTargets must not be null");
        Objects.requireNonNull(definitionHash, "definitionHash must not be null");
        if (definitionHash.isBlank()) {
            throw new IllegalArgumentException("definitionHash must not be blank");
        }
        if (cascadeDepth < 0) {
            throw new IllegalArgumentException(
                    "cascadeDepth must be >= 0: " + cascadeDepth);
        }
        matchedTriggers = List.copyOf(matchedTriggers);
        Map<String, Set<EntityId>> targetsCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<EntityId>> entry : resolvedTargets.entrySet()) {
            targetsCopy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        resolvedTargets = Map.copyOf(targetsCopy);
    }
}

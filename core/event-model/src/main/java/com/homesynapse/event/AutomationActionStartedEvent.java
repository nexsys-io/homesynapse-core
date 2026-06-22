/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.List;
import java.util.Objects;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code automation_action_started} (AMD-92 row 5) — emitted immediately before
 * an action step executes within a Run's virtual thread (Doc 07 §3.9).
 *
 * <p>Paired with an {@code automation_action_completed} (row 6) carrying the same
 * {@code runId}/{@code actionIndex}. Together they bound an action step in the explainability
 * causal chain.</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> {@code runId} is a bare {@link Ulid}
 * (the automation-resident {@code RunId} is flattened); {@code actionType} is the flattened
 * action class name. {@code targetRefs} is a list of platform-resident {@link EntityId}s. No
 * automation-resident type appears here.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}.</p>
 *
 * @param runId       the Run instance identifier (bare ULID — flattened {@code RunId});
 *                    never {@code null}
 * @param actionIndex the 0-based index of this action in the automation's action list;
 *                    {@code >= 0}
 * @param actionType  the action's flattened type name (e.g. {@code "CommandAction"});
 *                    never {@code null} or blank
 * @param targetRefs  the entities this action targets, unmodifiable (defensively copied);
 *                    never {@code null} (may be empty for non-targeted actions such as delay)
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_ACTION_STARTED
 */
@EventType(EventTypes.AUTOMATION_ACTION_STARTED)
public record AutomationActionStartedEvent(
        Ulid runId,
        int actionIndex,
        String actionType,
        List<EntityId> targetRefs
) implements DomainEvent {

    /**
     * Validates required fields, non-negative index, and deep-copies the target list.
     *
     * @throws NullPointerException     if {@code runId}, {@code actionType}, or
     *                                  {@code targetRefs} is {@code null}
     * @throws IllegalArgumentException if {@code actionType} is blank or {@code actionIndex}
     *                                  is negative
     */
    public AutomationActionStartedEvent {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(targetRefs, "targetRefs must not be null");
        if (actionType.isBlank()) {
            throw new IllegalArgumentException("actionType must not be blank");
        }
        if (actionIndex < 0) {
            throw new IllegalArgumentException("actionIndex must be >= 0: " + actionIndex);
        }
        targetRefs = List.copyOf(targetRefs);
    }
}

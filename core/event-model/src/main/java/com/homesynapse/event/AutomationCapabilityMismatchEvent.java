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
 * Event emitted when an automation targets entities that lack a required capability
 * (AMD-92 row 19; Doc 01 §4.6). The {@code automation_capability_mismatch} type string
 * pre-existed as a constant; this record gives it a payload.
 *
 * <p>Priority: NORMAL. Subject: Automation.</p>
 *
 * @param automationId        the automation with the mismatch, never {@code null}
 * @param affectedEntities    the entities lacking the capability, unmodifiable;
 *                            never {@code null}
 * @param missingCapabilityIds the capability identifiers that are missing,
 *                            unmodifiable; never {@code null}
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_CAPABILITY_MISMATCH
 */
@EventType(EventTypes.AUTOMATION_CAPABILITY_MISMATCH)
public record AutomationCapabilityMismatchEvent(
        AutomationId automationId,
        List<EntityId> affectedEntities,
        List<String> missingCapabilityIds
) implements DomainEvent {

    /**
     * Validates required fields and makes the lists unmodifiable.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public AutomationCapabilityMismatchEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(affectedEntities, "affectedEntities must not be null");
        Objects.requireNonNull(missingCapabilityIds, "missingCapabilityIds must not be null");
        affectedEntities = List.copyOf(affectedEntities);
        missingCapabilityIds = List.copyOf(missingCapabilityIds);
    }
}

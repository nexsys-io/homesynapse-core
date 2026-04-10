/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted when an automation trigger condition is met and a run begins.
 * <p>
 * Priority: NORMAL
 * Doc 01 §4.3
 */
@EventType(EventTypes.AUTOMATION_TRIGGERED)
public record AutomationTriggeredEvent(
        String triggerType,
        String triggerDetail
) implements DomainEvent {

    /**
     * Constructs an AutomationTriggeredEvent with validation.
     *
     * @param triggerType   the type of trigger, not null or blank
     * @param triggerDetail JSON string with trigger-specific data, not null
     */
    public AutomationTriggeredEvent {
        Objects.requireNonNull(triggerType, "triggerType cannot be null");
        if (triggerType.isBlank()) {
            throw new IllegalArgumentException("triggerType cannot be blank");
        }
        Objects.requireNonNull(triggerDetail, "triggerDetail cannot be null");
    }
}

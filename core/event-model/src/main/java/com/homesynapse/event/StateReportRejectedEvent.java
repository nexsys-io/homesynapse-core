/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Represents rejection of an attribute value due to validation failure.
 *
 * This diagnostic-priority event is produced by the core (State Projection / Device Model validation)
 * when a state_reported event fails attribute validation. The rejected value does not reach the State Store.
 *
 * Reference: Doc 01 §4.3.
 *
 * @param attributeKey the attribute whose value was rejected. Non-null, not blank.
 * @param reportedValue the value that failed validation, serialized. Non-null.
 * @param reason human-readable description of why the value was rejected. Non-null.
 * @param validationRule the specific validation constraint that was violated. Non-null.
 */
public record StateReportRejectedEvent(
        String attributeKey,
        String reportedValue,
        String reason,
        String validationRule
) implements DomainEvent {

    /**
     * Compact constructor ensuring non-null invariants.
     */
    public StateReportRejectedEvent {
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(reportedValue, "reportedValue must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(validationRule, "validationRule must not be null");
    }
}

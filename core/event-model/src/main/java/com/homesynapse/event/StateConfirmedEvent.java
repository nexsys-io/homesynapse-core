/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Represents confirmation that a command's intended state change was achieved.
 *
 * This normal-priority derived event is produced by the Pending Command Ledger
 * when a reported attribute value matches a pending command's expected outcome.
 *
 * Reference: Doc 01 §4.6 state_confirmed payload.
 *
 * @param commandEventId event ID of the command_issued being confirmed. Non-null.
 * @param reportEventId event ID of the state_reported that matched the expectation. Non-null.
 * @param attributeKey the attribute that was confirmed. Non-null, not blank.
 * @param expectedValue the value the command intended to achieve, serialized. Non-null.
 * @param actualValue the value the device actually reported, serialized. Non-null.
 * @param matchType one of "exact", "within_tolerance", "enum_transition", "any_change".
 *                  Non-null, not blank.
 */
@EventType(EventTypes.STATE_CONFIRMED)
public record StateConfirmedEvent(
        EventId commandEventId,
        EventId reportEventId,
        String attributeKey,
        String expectedValue,
        String actualValue,
        String matchType
) implements DomainEvent {

    /**
     * Compact constructor ensuring non-null invariants.
     */
    public StateConfirmedEvent {
        Objects.requireNonNull(commandEventId, "commandEventId must not be null");
        Objects.requireNonNull(reportEventId, "reportEventId must not be null");
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(expectedValue, "expectedValue must not be null");
        Objects.requireNonNull(actualValue, "actualValue must not be null");
        Objects.requireNonNull(matchType, "matchType must not be null");
    }
}

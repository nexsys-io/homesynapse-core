/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Represents a raw attribute observation from an integration adapter.
 *
 * This diagnostic-priority event captures the canonical state reported by a device,
 * along with the original protocol-native representation for audit and debugging purposes.
 *
 * Reference: Doc 01 §4.6 state_reported payload.
 *
 * @param attributeKey the attribute being reported, as defined by the entity's capability schema.
 *                     Non-null, not blank.
 * @param value the canonical value in serialized form (SI or standard units).
 *              Non-null. In Phase 2, values are represented as their JSON-serialized string form;
 *              Phase 3 introduces typed AttributeValue.
 * @param unit the canonical unit for physical quantities; {@code null} for dimensionless attributes
 *             (e.g., booleans, enums).
 * @param rawProtocolValue the value as received from the protocol before canonical conversion;
 *                         {@code null} when no conversion was performed.
 * @param rawProtocolUnit the protocol-native unit; {@code null} when rawProtocolValue is null.
 *                        Present alongside rawProtocolValue.
 */
@EventType(EventTypes.STATE_REPORTED)
public record StateReportedEvent(
        String attributeKey,
        String value,
        String unit,
        String rawProtocolValue,
        String rawProtocolUnit
) implements DomainEvent {

    /**
     * Compact constructor ensuring non-null invariants.
     */
    public StateReportedEvent {
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}

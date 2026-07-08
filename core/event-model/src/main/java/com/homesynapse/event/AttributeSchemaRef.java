/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.value.AttributeType;

import java.util.List;
import java.util.Objects;

/**
 * Event-local mirror of the device-model {@code AttributeSchema} (AMD-99 §3,
 * full-fidelity — every domain component maps, no silent drops).
 *
 * <p>{@link AttributeType} stays TYPED: it lives in {@code com.homesynapse.value},
 * a leaf module event-model already {@code requires transitive} — no JPMS cycle.
 * {@code permissions} flattens from the domain {@code Set<Permission>} to a
 * sorted list of enum names; {@code validValues} flattens from the nullable
 * domain {@code Set<String>} to a nullable sorted list (deterministic
 * serialization, AMD-99 §3).</p>
 *
 * <p>NOT an event: no {@code EventType} annotation, does not implement
 * {@link DomainEvent}.</p>
 *
 * @param attributeKey the attribute key within the capability, never {@code null}
 * @param type the attribute data type, never {@code null} (stays typed — value-model leaf)
 * @param minimum the minimum bound, {@code null} if unconstrained; canonicalized
 * @param maximum the maximum bound, {@code null} if unconstrained; canonicalized
 * @param step the UI slider granularity, {@code null} if not applicable; canonicalized
 * @param validValues the allowed values for ENUM attributes, {@code null} for
 *        non-enum types; sorted unmodifiable copy when non-null
 * @param unitSymbol the display unit symbol, {@code null} if dimensionless
 * @param canonicalUnitSymbol the canonical unit symbol, {@code null} if not applicable
 * @param permissions the access-mode enum names, never {@code null}; sorted
 *        unmodifiable copy
 * @param nullable whether the attribute may hold a {@code null} value
 * @param persistent whether the attribute persists across device restarts
 * @see CapabilityInstanceRef
 */
public record AttributeSchemaRef(
        String attributeKey,
        AttributeType type,
        Number minimum,
        Number maximum,
        Number step,
        List<String> validValues,
        String unitSymbol,
        String canonicalUnitSymbol,
        List<String> permissions,
        boolean nullable,
        boolean persistent
) {

    /**
     * Validates required components, canonicalizes the numeric bounds, and
     * sorts the Set-derived list components.
     *
     * @throws NullPointerException if {@code attributeKey}, {@code type}, or
     *         {@code permissions} is {@code null}
     */
    public AttributeSchemaRef {
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");
        minimum = PayloadMirrors.canonicalNumber(minimum);
        maximum = PayloadMirrors.canonicalNumber(maximum);
        step = PayloadMirrors.canonicalNumber(step);
        validValues = PayloadMirrors.sortedCopyOrNull(validValues);
        permissions = PayloadMirrors.sortedCopyOrNull(permissions);
    }
}

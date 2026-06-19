/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Locale;
import java.util.OptionalDouble;

import com.homesynapse.state.Availability;
import com.homesynapse.value.AttributeValue;

/**
 * Package-private helpers for projecting typed {@link AttributeValue}s (AMD-47) down
 * to the string/numeric forms that automation trigger and condition predicates compare
 * against. YAML predicates are typed values (never templates, REC-155); the engine
 * compares an entity's typed attribute value to the predicate's literal form.
 */
final class AttributeValues {

    private AttributeValues() {
        // Utility class — non-instantiable.
    }

    /**
     * Returns the canonical string projection of an attribute value, or {@code null}
     * if {@code value} is {@code null}. {@link com.homesynapse.value.StringValue} and
     * {@link com.homesynapse.value.EnumValue} project to their text; booleans to
     * {@code "true"}/{@code "false"}; numerics to their decimal form.
     */
    static String asString(AttributeValue value) {
        return value == null ? null : String.valueOf(value.rawValue());
    }

    /** Tests string-equality of an attribute value against a literal predicate. */
    static boolean stringEquals(AttributeValue value, String expected) {
        return value != null && expected.equals(asString(value));
    }

    /**
     * Projects an attribute value to a {@code double} for numeric comparison.
     * Numeric-typed values ({@code IntValue}/{@code FloatValue}/{@code QuantityValue})
     * project directly; a numeric string parses; anything else yields empty (no match).
     */
    static OptionalDouble asDouble(AttributeValue value) {
        if (value == null) {
            return OptionalDouble.empty();
        }
        Object raw = value.rawValue();
        if (raw instanceof Number number) {
            return OptionalDouble.of(number.doubleValue());
        }
        try {
            return OptionalDouble.of(Double.parseDouble(String.valueOf(raw)));
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Maps an {@code availability_changed} event's wire status string
     * ({@code "online"}/{@code "offline"}/{@code "unknown"}) to the state-store
     * {@link Availability} enum that {@code AvailabilityTrigger}/{@code ReachabilityTrigger}
     * compare against.
     */
    static Availability mapAvailability(String wireStatus) {
        if (wireStatus == null) {
            return Availability.UNKNOWN;
        }
        return switch (wireStatus.toLowerCase(Locale.ROOT)) {
            case "online", "available" -> Availability.AVAILABLE;
            case "offline", "unavailable" -> Availability.UNAVAILABLE;
            default -> Availability.UNKNOWN;
        };
    }
}

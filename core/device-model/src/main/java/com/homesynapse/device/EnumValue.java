/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Objects;

/**
 * An {@link AttributeValue} carrying a value from a constrained string set.
 *
 * <p>Represents a value from the set of valid values defined by the capability
 * schema's {@link AttributeSchema#validValues()}. Validation against the schema
 * occurs at the {@link AttributeValidator} level, not within this record.</p>
 *
 * @param value the enum string value, never {@code null}
 * @see AttributeValue
 * @see AttributeType#ENUM
 * @see AttributeSchema#validValues()
 * @since 1.0
 */
public record EnumValue(String value) implements AttributeValue {

    /**
     * Validates that the enum value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public EnumValue {
        Objects.requireNonNull(value, "EnumValue value must not be null");
    }

    @Override
    public Object rawValue() {
        return value;
    }

    @Override
    public AttributeType attributeType() {
        return AttributeType.ENUM;
    }
}

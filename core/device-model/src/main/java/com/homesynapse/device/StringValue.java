/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Objects;

/**
 * An {@link AttributeValue} carrying a free-form string.
 *
 * <p>Used for attributes whose values are arbitrary text, such as firmware
 * version strings or device-specific status messages.</p>
 *
 * @param value the string attribute value, never {@code null}
 * @see AttributeValue
 * @see AttributeType#STRING
 * @since 1.0
 */
public record StringValue(String value) implements AttributeValue {

    /**
     * Validates that the string value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public StringValue {
        Objects.requireNonNull(value, "StringValue value must not be null");
    }

    @Override
    public Object rawValue() {
        return value;
    }

    @Override
    public AttributeType attributeType() {
        return AttributeType.STRING;
    }
}

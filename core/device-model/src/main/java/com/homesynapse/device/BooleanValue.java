/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * An {@link AttributeValue} carrying a boolean state.
 *
 * <p>Used for binary attributes such as on/off state, contact open/closed,
 * motion detected, and battery-low indicators.</p>
 *
 * @param value the boolean attribute value
 * @see AttributeValue
 * @see AttributeType#BOOLEAN
 * @since 1.0
 */
public record BooleanValue(boolean value) implements AttributeValue {

    @Override
    public Object rawValue() {
        return value;
    }

    @Override
    public AttributeType attributeType() {
        return AttributeType.BOOLEAN;
    }
}

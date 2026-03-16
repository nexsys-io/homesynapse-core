/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * An {@link AttributeValue} carrying an integer measurement or level.
 *
 * <p>Uses {@code long} to accommodate the full range of integer attributes
 * including brightness (0–100), battery percentage (0–100), link quality
 * indicator (0–255), color temperature in kelvin, and RSSI in dBm.</p>
 *
 * @param value the integer attribute value
 * @see AttributeValue
 * @see AttributeType#INT
 * @since 1.0
 */
public record IntValue(long value) implements AttributeValue {

    @Override
    public Object rawValue() {
        return value;
    }

    @Override
    public AttributeType attributeType() {
        return AttributeType.INT;
    }
}

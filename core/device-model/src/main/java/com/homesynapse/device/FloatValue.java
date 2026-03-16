/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * An {@link AttributeValue} carrying a floating-point measurement.
 *
 * <p>Used for continuous measurements such as temperature (°C), relative
 * humidity (%), illuminance (lux), instantaneous power (W), and cumulative
 * energy (Wh).</p>
 *
 * @param value the floating-point attribute value
 * @see AttributeValue
 * @see AttributeType#FLOAT
 * @since 1.0
 */
public record FloatValue(double value) implements AttributeValue {

    @Override
    public Object rawValue() {
        return value;
    }

    @Override
    public AttributeType attributeType() {
        return AttributeType.FLOAT;
    }
}

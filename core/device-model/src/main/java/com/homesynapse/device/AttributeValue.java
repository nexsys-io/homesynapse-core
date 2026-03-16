/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Typed representation of a device attribute's reported or commanded value.
 *
 * <p>The sealed hierarchy ensures that all attribute values belong to one of
 * the five primitive types supported by the capability schema system. This
 * enables exhaustive {@code switch} expressions for type-safe value processing
 * throughout the device model, state store, and automation engine.</p>
 *
 * <p>All implementations are immutable records, making {@code AttributeValue}
 * instances inherently thread-safe.</p>
 *
 * <p>Defined in Doc 02 §8.2.</p>
 *
 * @see AttributeType
 * @see AttributeSchema
 * @since 1.0
 */
public sealed interface AttributeValue
        permits BooleanValue, IntValue, FloatValue, StringValue, EnumValue {

    /**
     * Returns the raw value as an {@link Object} for generic processing.
     *
     * @return the underlying value, never {@code null}
     */
    Object rawValue();

    /**
     * Returns the {@link AttributeType} that classifies this value.
     *
     * @return the attribute type corresponding to this value's concrete type, never {@code null}
     */
    AttributeType attributeType();
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Identifies the primitive data type of a device attribute value.
 *
 * <p>Each attribute in a capability schema declares its type via this enum,
 * which determines the concrete {@link AttributeValue} subtype used to
 * represent reported and commanded values. The type system ensures that
 * attribute values are validated against their schema at ingestion time.</p>
 *
 * <p>Defined in Doc 02 §3.7.</p>
 *
 * @see AttributeValue
 * @see AttributeSchema
 * @since 1.0
 */
public enum AttributeType {

    /** Boolean attribute, represented by {@link BooleanValue}. Maps to Java {@code boolean}. */
    BOOLEAN,

    /** Integer attribute, represented by {@link IntValue}. Maps to Java {@code long} to accommodate the full range of integer attributes (brightness 0–100, battery 0–100, LQI 0–255, etc.). */
    INT,

    /** Floating-point attribute, represented by {@link FloatValue}. Maps to Java {@code double}. Used for continuous measurements (temperature, humidity, power, energy). */
    FLOAT,

    /** Free-form string attribute, represented by {@link StringValue}. Maps to Java {@link String}. */
    STRING,

    /** Enumerated string attribute, represented by {@link EnumValue}. The value must belong to the set of valid values defined in the capability's {@link AttributeSchema}. */
    ENUM
}

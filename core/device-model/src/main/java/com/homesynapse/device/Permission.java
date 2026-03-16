/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Defines the access modes supported by a device attribute within a capability schema.
 *
 * <p>Each attribute in an {@link AttributeSchema} declares one or more permissions
 * that govern how the attribute participates in the system. Permissions are combined
 * as a {@link java.util.Set} on the schema.</p>
 *
 * <p>Defined in Doc 02 §3.7.</p>
 *
 * @see AttributeSchema
 * @see Capability
 * @since 1.0
 */
public enum Permission {

    /** The attribute can be observed — its current value is queryable via the State Store. */
    READ,

    /** The attribute accepts commands — values can be set through the command pipeline. */
    WRITE,

    /** The attribute produces {@code state_reported} events when its value changes on the device. */
    NOTIFY
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Set;

/**
 * Defines the schema for a single attribute within a capability.
 *
 * <p>An attribute schema specifies the data type, valid range or value set,
 * unit of measurement, access permissions, and persistence behavior for one
 * attribute key. Every attribute exposed by a {@link Capability} has a
 * corresponding schema in its {@link Capability#attributeSchemas()} map.</p>
 *
 * <p>Defined in Doc 02 §3.7.</p>
 *
 * @param attributeKey the unique key identifying this attribute within the capability, never {@code null}
 * @param type the data type of this attribute, never {@code null}
 * @param minimum the minimum allowed value for {@link AttributeType#INT} and {@link AttributeType#FLOAT} attributes, {@code null} if unconstrained or not applicable
 * @param maximum the maximum allowed value for {@link AttributeType#INT} and {@link AttributeType#FLOAT} attributes, {@code null} if unconstrained or not applicable
 * @param step the UI slider granularity for numeric attributes, {@code null} if not applicable
 * @param validValues the set of allowed string values for {@link AttributeType#ENUM} attributes, {@code null} for non-enum types; unmodifiable when non-null
 * @param unitSymbol the display unit symbol (e.g., "°C", "W", "%"), {@code null} if dimensionless
 * @param canonicalUnitSymbol the SI canonical unit symbol used for storage normalization, {@code null} if not applicable
 * @param permissions the access modes supported by this attribute, never {@code null}; unmodifiable
 * @param nullable whether this attribute may have a {@code null} value in the state store
 * @param persistent whether this attribute is persisted across device restarts
 * @see Capability#attributeSchemas()
 * @see AttributeType
 * @see Permission
 * @since 1.0
 */
public record AttributeSchema(
        String attributeKey,
        AttributeType type,
        Number minimum,
        Number maximum,
        Number step,
        Set<String> validValues,
        String unitSymbol,
        String canonicalUnitSymbol,
        Set<Permission> permissions,
        boolean nullable,
        boolean persistent
) { }

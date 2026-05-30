/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.AttributeType;
import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.BooleanValue;
import com.homesynapse.device.DegradedAttributeValue;
import com.homesynapse.device.EnumValue;
import com.homesynapse.device.FloatValue;
import com.homesynapse.device.IntValue;
import com.homesynapse.device.QuantityValue;
import com.homesynapse.device.StringValue;
import com.homesynapse.platform.identity.EntityId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schema-driven reconstruction of a serialized attribute value to its declared
 * {@link AttributeValue} variant (AMD-51 §2.6 / AMD-51-INV-05 / DP-H).
 *
 * <p>This is a pure parse keyed by {@link AttributeSchema#type()}, applied
 * <strong>symmetrically to both comparison operands</strong> — the inbound
 * {@code StateReportedEvent.value} and the prior materialized value (always a
 * {@code StringValue}). It is <strong>distinct from</strong> the
 * {@code AttributeValueUpcaster} stored-value-migration SPI, which is left unchanged. The
 * produced typed values are transient: the materialized attribute and the emitted
 * {@code StateChangedEvent} payload remain {@code String} (AMD-51 §2.7).</p>
 *
 * <h2>Per-type behaviour</h2>
 * <ul>
 *   <li>{@code BOOLEAN}/{@code INT}/{@code FLOAT}/{@code STRING}/{@code ENUM} — parse into
 *       the corresponding variant.</li>
 *   <li>{@code QUANTITY} — construct {@code QuantityValue(magnitude, unit)}; the constructor
 *       canonicalises. The unit is the supplied {@code unit} when present and recognised,
 *       else the schema's {@link AttributeSchema#canonicalUnitSymbol()} — and that
 *       fallback logs a WARN (silent-corruption guard, §2.6). If neither yields a recognised
 *       unit, or the magnitude does not parse, the value degrades.</li>
 *   <li>{@code ARRAY} — not reconstructable at M4.0b-3 (no element-type schema metadata);
 *       degrades. The comparator's array semantics are implemented and unit-tested directly.</li>
 *   <li>No schema for the key — string fallback ({@code StringValue}); the comparator then
 *       does an exact string compare, preserving the pre-typed behaviour.</li>
 * </ul>
 *
 * <p>A parse/reconstruction failure yields a {@link DegradedAttributeValue} that the
 * comparator suppresses (inbound Degraded ⇒ never emit), so a malformed report neither
 * halts the projection nor writes a degraded value to canonical state (AMD-47-INV-04).
 * Pure: no clock, no I/O beyond logging, no randomness (AMD-50-INV-03).</p>
 *
 * @see AttributeSchemaResolver
 * @see AttributeValueComparator
 * @since 1.0
 */
final class AttributeValueReconstructor {

    private static final Logger log = LoggerFactory.getLogger(AttributeValueReconstructor.class);

    /**
     * Package-private constructor. The reconstructor is stateless and may be shared freely.
     */
    AttributeValueReconstructor() {
        // Stateless; no initialization required.
    }

    /**
     * Reconstructs {@code serialized} to the variant declared by {@code schema}.
     *
     * @param serialized   the serialized value (the inbound {@code value} or the prior
     *                     {@code StringValue.value()}); never {@code null}
     * @param unit         the unit to use for {@code QUANTITY} reconstruction — the reported
     *                     unit for the inbound side, or the schema canonical unit for the
     *                     prior side; may be {@code null}/blank for non-quantity types
     * @param schema       the declared schema, or {@code null} when no schema is known for
     *                     the key (string fallback)
     * @param attributeKey the attribute key (for diagnostics); never {@code null}
     * @param entityId     the subject entity (for diagnostics); may be {@code null}
     * @return the reconstructed value, never {@code null}; a {@link DegradedAttributeValue}
     *         on an un-reconstructable input
     */
    AttributeValue reconstruct(String serialized, String unit, AttributeSchema schema,
                               String attributeKey, EntityId entityId) {
        if (schema == null) {
            // No schema known for this key: reconstruct as StringValue so the comparator does
            // an exact string compare (the pre-typed behaviour). The materialized prior is
            // itself a StringValue, so this is the natural identity. DEBUG, not WARN — this is
            // the benign empty-resolver / no-arg production() path and would otherwise spam.
            if (log.isDebugEnabled()) {
                log.debug("No schema for attribute {} on entity {}; reconstructing as "
                        + "StringValue (string-compare fallback)", attributeKey, entityId);
            }
            return new StringValue(serialized);
        }

        return switch (schema.type()) {
            case BOOLEAN -> new BooleanValue(Boolean.parseBoolean(serialized.trim()));
            case INT -> parseInt(serialized, attributeKey, entityId);
            case FLOAT -> parseFloat(serialized, attributeKey, entityId);
            case STRING -> new StringValue(serialized);
            case ENUM -> new EnumValue(serialized);
            case QUANTITY -> reconstructQuantity(serialized, unit, schema, attributeKey, entityId);
            case ARRAY -> {
                log.warn("ARRAY attribute {} on entity {} cannot be reconstructed (no "
                        + "element-type schema metadata at M4.0b-3); suppressing as Degraded",
                        attributeKey, entityId);
                yield new DegradedAttributeValue("ArrayValue", serialized,
                        "ARRAY reconstruction requires element-type schema metadata not "
                                + "present at M4.0b-3 for attribute " + attributeKey);
            }
            // Unreachable: AttributeSchema's compact constructor rejects DEGRADED (AMD-47-INV-04),
            // so a DEGRADED-typed schema can never exist. Required for switch exhaustiveness;
            // returns Degraded (no-emit) rather than throwing past the rule.
            case DEGRADED -> new DegradedAttributeValue("DegradedAttributeValue", serialized,
                    "AttributeType.DEGRADED is a sentinel and is never schema-declarable");
        };
    }

    private AttributeValue parseInt(String serialized, String attributeKey, EntityId entityId) {
        try {
            return new IntValue(Long.parseLong(serialized.trim()));
        } catch (NumberFormatException e) {
            return degraded("IntValue", serialized, attributeKey, entityId, AttributeType.INT);
        }
    }

    private AttributeValue parseFloat(String serialized, String attributeKey, EntityId entityId) {
        try {
            return new FloatValue(Double.parseDouble(serialized.trim()));
        } catch (NumberFormatException e) {
            return degraded("FloatValue", serialized, attributeKey, entityId, AttributeType.FLOAT);
        }
    }

    private AttributeValue reconstructQuantity(String serialized, String unit,
                                               AttributeSchema schema, String attributeKey,
                                               EntityId entityId) {
        double magnitude;
        try {
            magnitude = Double.parseDouble(serialized.trim());
        } catch (NumberFormatException e) {
            return degraded("QuantityValue", serialized, attributeKey, entityId,
                    AttributeType.QUANTITY);
        }

        // Prefer the supplied unit (reported unit on the inbound side; canonical unit on the
        // prior side). QuantityValue canonicalises at construction; an identity conversion
        // when the unit is already canonical.
        QuantityValue fromSupplied = tryQuantity(magnitude, unit);
        if (fromSupplied != null) {
            return fromSupplied;
        }

        // The supplied unit was absent/blank/unrecognised. Fall back to the schema canonical
        // unit and WARN — this is the §2.6 silent-corruption guard (an adapter sending a
        // non-canonical magnitude with no unit would be materialised as canonical garbage).
        String canonicalUnit = schema.canonicalUnitSymbol();
        QuantityValue fromCanonical = tryQuantity(magnitude, canonicalUnit);
        if (fromCanonical != null) {
            log.warn("QUANTITY attribute {} on entity {} fell back to the schema canonical "
                    + "unit '{}' because the reported unit '{}' was absent/blank/unrecognised",
                    attributeKey, entityId, canonicalUnit, unit);
            return fromCanonical;
        }

        log.warn("QUANTITY attribute {} on entity {} could not be reconstructed: neither the "
                + "reported unit '{}' nor the canonical unit '{}' is recognised; suppressing "
                + "as Degraded", attributeKey, entityId, unit, canonicalUnit);
        return new DegradedAttributeValue("QuantityValue", serialized,
                "no recognised unit for QUANTITY attribute " + attributeKey);
    }

    /**
     * Attempts to construct a {@link QuantityValue}, returning {@code null} when the unit is
     * absent/blank/unrecognised or the magnitude is non-finite (the constructor fails closed
     * with {@link RuntimeException} in those cases).
     */
    private static QuantityValue tryQuantity(double magnitude, String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        try {
            return new QuantityValue(magnitude, unit);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AttributeValue degraded(String typeName, String serialized, String attributeKey,
                                    EntityId entityId, AttributeType expected) {
        log.warn("Attribute {} on entity {} reported value '{}' is not a valid {}; "
                + "suppressing as Degraded (no emit)", attributeKey, entityId, serialized, expected);
        return new DegradedAttributeValue(typeName, serialized,
                "value '" + serialized + "' is not a valid " + expected + " for attribute "
                        + attributeKey);
    }
}

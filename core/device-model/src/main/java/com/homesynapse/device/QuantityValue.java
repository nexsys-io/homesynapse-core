/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * An {@link AttributeValue} carrying a physical quantity as a canonical-normalized
 * magnitude plus its canonical unit symbol.
 *
 * <p>The stored components are always canonical: the compact constructor takes the
 * supplied {@code (value, unit)}, looks up the dimension for {@code unit}, converts the
 * magnitude to that dimension's canonical unit, and stores the canonical magnitude and
 * canonical unit symbol (e.g. {@code "°C"}, {@code "W"}, {@code "Wh"}, {@code "lux"}).
 * Construction with the canonical unit is the identity conversion. This makes the
 * (value, unit) "moat decision" enforceable at the value layer: the unit travels with the
 * magnitude and is never reinterpreted at storage time.</p>
 *
 * <p>Normalization is hand-rolled, deterministic, and table-driven — there is no external
 * units-of-measure library, no I/O, and no locale or clock dependence (AMD-47-INV-03 /
 * REC-93). Unit matching is exact string equality against the catalogue keys (no
 * locale-folding, no lower-casing). Two {@code QuantityValue}s of the same dimension are
 * therefore directly magnitude-comparable on their canonical {@link #value()}.</p>
 *
 * <p>Construction fails closed and never silently coerces — it never produces a
 * {@link DegradedAttributeValue} (degradation is an upcast/parse fallback, not a
 * construction fallback):</p>
 * <ul>
 *   <li>a {@code null} unit throws {@link NullPointerException};</li>
 *   <li>a blank unit, a non-finite magnitude ({@code NaN} / {@code ±Inf}), or an
 *       unrecognised unit throws {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>Added by AMD-47. Classified by {@link AttributeType#QUANTITY}.</p>
 *
 * @param value the canonical-normalized magnitude (assigned by the compact constructor)
 * @param unit the canonical unit symbol (assigned by the compact constructor), never {@code null} or blank
 * @see AttributeValue
 * @see AttributeType#QUANTITY
 * @since 1.0
 */
public record QuantityValue(double value, String unit) implements AttributeValue {

    /**
     * One dimension's canonical unit symbol together with the conversion from a supplied
     * magnitude (expressed in the keyed input unit) to the canonical magnitude.
     */
    private record Conversion(String canonicalUnit, DoubleUnaryOperator toCanonical) { }

    /**
     * Maps each accepted input unit symbol to its canonical unit and conversion. The
     * canonical unit for a dimension maps to the identity conversion. Hand-rolled per
     * Doc 02 §3.7 (temperature {@code °C}, power {@code W}, energy {@code Wh},
     * illuminance {@code lux}, percent {@code %}) and AMD-47-INV-03.
     */
    private static final Map<String, Conversion> CATALOGUE = buildCatalogue();

    private static Map<String, Conversion> buildCatalogue() {
        Map<String, Conversion> m = new HashMap<>();

        // Temperature — canonical °C. K and °F are affine (offset), not pure scale.
        m.put("°C", new Conversion("°C", v -> v));
        m.put("K", new Conversion("°C", v -> v - 273.15));
        m.put("°F", new Conversion("°C", v -> (v - 32.0) * 5.0 / 9.0));

        // Power — canonical W. Multiplicative.
        m.put("W", new Conversion("W", v -> v));
        m.put("kW", new Conversion("W", v -> v * 1000.0));
        m.put("mW", new Conversion("W", v -> v * 0.001));

        // Energy — canonical Wh. Multiplicative.
        m.put("Wh", new Conversion("Wh", v -> v));
        m.put("kWh", new Conversion("Wh", v -> v * 1000.0));
        m.put("J", new Conversion("Wh", v -> v / 3600.0));
        m.put("kJ", new Conversion("Wh", v -> v / 3.6));

        // Illuminance — canonical lux. Multiplicative.
        m.put("lux", new Conversion("lux", v -> v));
        m.put("klx", new Conversion("lux", v -> v * 1000.0));

        // Ratio / percent — canonical %.
        m.put("%", new Conversion("%", v -> v));

        return Map.copyOf(m);
    }

    /**
     * Normalizes the supplied magnitude and unit to canonical form, failing closed on a
     * null/blank/unrecognised unit or a non-finite magnitude.
     *
     * @throws NullPointerException if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code unit} is blank, {@code value} is
     *         non-finite, or {@code unit} is not in the catalogue
     */
    public QuantityValue {
        Objects.requireNonNull(unit, "QuantityValue unit must not be null");
        if (unit.isBlank()) {
            throw new IllegalArgumentException("QuantityValue unit must not be blank");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(
                    "QuantityValue magnitude must be finite, got " + value);
        }
        Conversion conversion = CATALOGUE.get(unit);
        if (conversion == null) {
            throw new IllegalArgumentException("Unrecognised unit: " + unit);
        }
        value = conversion.toCanonical().applyAsDouble(value);
        unit = conversion.canonicalUnit();
    }

    /**
     * Returns the canonical magnitude boxed as {@link Double}. This keeps a
     * {@code QuantityValue} magnitude-comparable with a {@link FloatValue} for generic
     * processing.
     *
     * @return the canonical magnitude, never {@code null}
     */
    @Override
    public Object rawValue() {
        return value;
    }

    @Override
    public AttributeType attributeType() {
        return AttributeType.QUANTITY;
    }
}

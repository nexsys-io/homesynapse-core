/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared construction helpers for the AMD-99 registration-payload mirror
 * records ({@link AttributeSchemaRef}, {@link ParameterSchemaRef},
 * {@link CapabilityInstanceRef}, {@link ConfirmationPolicyRef}).
 *
 * <p><strong>Number canonicalization.</strong> The mirror records carry
 * {@code Number} components (schema bounds, tolerances). Jackson deserializes
 * a declared-{@code Number} field to {@link Integer}/{@link Long} for integral
 * tokens and {@link Double} for decimal tokens — so a {@link Short} or
 * {@link Float} emitted at adoption time would come back as a different boxed
 * type and break record equality between the emitted payload and its replay
 * (the DP-8 idempotent-by-identity no-op detection). Canonicalizing at mirror
 * construction pins every {@code Number} to exactly the Jackson-native pair:
 * integral values become {@code Integer} (or {@code Long} beyond int range),
 * decimal values become {@code Double}. Numeric VALUE is always preserved;
 * only the boxed Java type narrows to the canonical pair.</p>
 *
 * <p><strong>Deterministic collections.</strong> Set-derived components
 * flatten to sorted lists and map components copy into key-sorted unmodifiable
 * maps, so serialization is deterministic and replay equality never depends on
 * source-collection iteration order (AMD-99 §3).</p>
 */
final class PayloadMirrors {

    private PayloadMirrors() {
        // Static helpers only.
    }

    /**
     * Canonicalizes a nullable {@code Number} to the Jackson-native pair.
     *
     * @param value the number, or {@code null}
     * @return {@code null}, an {@code Integer}, a {@code Long} (beyond int
     *         range), or a {@code Double}
     */
    static Number canonicalNumber(Number value) {
        return switch (value) {
            case null -> null;
            case Integer i -> i;
            case Long l -> {
                // NOT a conditional expression: `cond ? Integer : Long` applies
                // binary numeric promotion and boxes the Integer branch back to
                // Long — the exact defect this helper exists to prevent.
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    yield Integer.valueOf(l.intValue());
                }
                yield l;
            }
            case Short s -> Integer.valueOf(s.intValue());
            case Byte b -> Integer.valueOf(b.intValue());
            case java.math.BigInteger bi -> canonicalNumber(bi.longValueExact());
            // Float -> Double widening preserves the float's exact numeric value.
            default -> Double.valueOf(value.doubleValue());
        };
    }

    /**
     * Returns a sorted unmodifiable copy of a Set-derived string list, or
     * {@code null} when the source is {@code null} (nullable domain sets —
     * e.g. non-enum {@code validValues} — stay {@code null}, never empty).
     */
    static List<String> sortedCopyOrNull(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return List.copyOf(sorted);
    }

    /** Returns a key-sorted unmodifiable copy of a mirror map component. */
    static <V> Map<String, V> sortedMapCopy(Map<String, V> map) {
        return Collections.unmodifiableMap(new TreeMap<>(map));
    }
}

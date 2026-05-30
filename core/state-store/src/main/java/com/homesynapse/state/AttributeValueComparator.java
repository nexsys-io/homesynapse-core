/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.AttributeValue;

/**
 * Typed change-detection comparator over the eight-variant {@link AttributeValue} sealed
 * hierarchy (AMD-51 / AMD-51-INV-04).
 *
 * <p>This is an <strong>emit predicate</strong>, not a symmetric equality: it answers
 * "should a {@code state_changed} be emitted for this transition?", folding in the
 * asymmetric {@code DegradedAttributeValue} rule (§2.4b) and the {@code prior == null}
 * first-report case. It is deliberately NOT a pure {@code equals} — do not treat it as one.</p>
 *
 * <p>Placed in {@code com.homesynapse.state} (state-store), co-located with
 * {@code ProductionDerivationRule} and the inbound-reconstruction step so
 * "reconstruct → compare" is one unit in one module. It carries a {@link ComparisonPolicy}
 * (projection/epsilon policy), which is why it is NOT a polymorphic method on the
 * device-model {@code AttributeValue} type — that would drag projection policy into the data
 * layer (AMD-51-INV-04). The implementation is package-private and reached through the
 * public static factory {@link #structural()} (DEC-M3-16 gateway, mirroring
 * {@code DerivationRule.production()} / {@code StateQueryService.materialized(...)} /
 * {@code StateCheckpointSource.stub()}).</p>
 *
 * @see ComparisonPolicy
 * @see AttributeValueReconstructor
 * @since 1.0
 */
public interface AttributeValueComparator {

    /**
     * Returns {@code true} iff a {@code state_changed} should be emitted for this attribute,
     * given the reconstructed prior canonical value and the reconstructed inbound value,
     * under {@code policy}.
     *
     * <p>Total over the eight-variant {@link AttributeValue} hierarchy. Semantics:</p>
     * <ul>
     *   <li>Inbound {@code DegradedAttributeValue} ⇒ never emit (do not overwrite or
     *       establish canonical state with a degraded value).</li>
     *   <li>{@code prior == null} (first report) ⇒ emit (the inbound establishes the first
     *       canonical value), unless the inbound is degraded.</li>
     *   <li>Prior {@code DegradedAttributeValue} + valid inbound ⇒ emit (recovery).</li>
     *   <li>Boolean/Int/Enum/String ⇒ exact equality.</li>
     *   <li>Float and same-dimension Quantity ⇒ the {@link ComparisonPolicy} total-form
     *       epsilon with IEEE-754 totality.</li>
     *   <li>Quantity with differing canonical unit symbols ⇒ different dimension ⇒ emit
     *       (no unit conversion is performed — operands are canonical at construction).</li>
     *   <li>Array ⇒ size-then-order-sensitive deep compare, recursing element-by-element
     *       under the same {@code policy}.</li>
     *   <li>A variant mismatch between non-degraded prior and inbound ⇒ emit.</li>
     * </ul>
     *
     * <p>Pure: no clock, no I/O, no randomness, no registry (AMD-50-INV-03). Stateless and
     * thread-safe.</p>
     *
     * @param prior   the prior canonical value, or {@code null} for the first report; must
     *                already be reconstructed to its schema-declared variant
     * @param inbound the reconstructed inbound value; never {@code null}
     * @param policy  the comparison tolerances; never {@code null}
     * @return {@code true} if a {@code state_changed} should be emitted
     */
    boolean changed(AttributeValue prior, AttributeValue inbound, ComparisonPolicy policy);

    /**
     * Returns the structural comparator implementing the AMD-51 §2.2 per-variant semantics.
     *
     * <p>DEC-M3-16 gateway into the package-private
     * {@code StructuralAttributeValueComparator}. The returned comparator is stateless,
     * deterministic, and safe to share across threads.</p>
     *
     * @return the structural comparator; never {@code null}
     */
    static AttributeValueComparator structural() {
        return new StructuralAttributeValueComparator();
    }
}

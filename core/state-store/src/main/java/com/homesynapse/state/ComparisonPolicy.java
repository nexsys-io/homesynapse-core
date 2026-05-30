/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

/**
 * Immutable carrier of the floating-point comparison tolerances used by
 * {@link AttributeValueComparator} (AMD-51 §2.3 / AMD-51-INV-02).
 *
 * <p>{@code Float} and same-dimension {@code Quantity} comparison use the pinned
 * <strong>total combined absolute + relative</strong> form:</p>
 *
 * <pre>{@code  changed  <=>  |a - b| > max(absEps, relEps * max(|a|, |b|))}</pre>
 *
 * <p>A pure relative epsilon is rejected (it is undefined/explosive near zero); the
 * combined form is total. The defaults ({@link #FP_NOISE_DEFAULT}, {@code 1e-9} for both)
 * are a <strong>correctness (floating-point-noise) epsilon</strong> — they answer "did the
 * number actually change?", not a perceptual deadband. The {@code 1e-9} magnitude is locked
 * (AMD-51 §9, verified by the conversion-noise ceiling analysis and the §5 #5b tests); it is
 * carried here so it is tunable without an amendment.</p>
 *
 * <h2>Deadband — deferred (REC-95 / DP-J)</h2>
 *
 * <p>A future per-attribute value deadband is NOT implemented in M4.0b-3. Its home is
 * {@code AttributeSchema} (the per-attribute metadata carrier), and it will thread through a
 * future field on this policy. This record reserves the <em>shape</em> only — no deadband
 * field exists now.</p>
 *
 * @param absEps the absolute tolerance; must be finite and {@code >= 0}
 * @param relEps the relative tolerance; must be finite and {@code >= 0}
 * @see AttributeValueComparator
 * @since 1.0
 */
public record ComparisonPolicy(double absEps, double relEps) {

    /**
     * The locked floating-point-noise default: {@code absEps = relEps = 1e-9} (AMD-51 §2.3).
     */
    public static final ComparisonPolicy FP_NOISE_DEFAULT = new ComparisonPolicy(1e-9, 1e-9);

    /**
     * Validates that both tolerances are finite and non-negative.
     *
     * @throws IllegalArgumentException if {@code absEps} or {@code relEps} is non-finite
     *         ({@code NaN}/{@code Inf}) or negative
     */
    public ComparisonPolicy {
        if (!Double.isFinite(absEps) || absEps < 0.0) {
            throw new IllegalArgumentException(
                    "absEps must be finite and >= 0, got " + absEps);
        }
        if (!Double.isFinite(relEps) || relEps < 0.0) {
            throw new IllegalArgumentException(
                    "relEps must be finite and >= 0, got " + relEps);
        }
    }
}

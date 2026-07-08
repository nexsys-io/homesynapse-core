/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.value.AttributeValue;

import java.util.Objects;

/**
 * Event-local sealed mirror of the device-model {@code Expectation} hierarchy —
 * four permits, 1:1 with the domain (AMD-99 §3; the AMD-87 lossless discipline
 * applies to the persistence codec).
 *
 * <p>{@link AttributeValue} stays TYPED — it lives in {@code com.homesynapse.value},
 * a leaf module event-model already {@code requires transitive}. The mirror
 * carries the expectation's DATA only; the domain {@code evaluate(...)}
 * behavior stays device-model-side and is reconstructed at apply time by the
 * registry event mapper (exhaustive {@code switch} in both directions).</p>
 *
 * <p>Neither this interface nor any permit is an event: no {@code EventType}
 * annotation, no {@link DomainEvent} — only the two top-level registration
 * events are events (the 41&rarr;43 pin tripwire).</p>
 *
 * @see ExpectedOutcomeRef
 */
public sealed interface ExpectationRef {

    /**
     * Mirror of the domain {@code ExactMatch} — confirmed when the reported
     * value equals the expected value exactly.
     *
     * @param expectedValue the expected attribute value, never {@code null}
     */
    record ExactMatchRef(AttributeValue expectedValue) implements ExpectationRef {
        /**
         * Validates the expected value.
         *
         * @throws NullPointerException if {@code expectedValue} is {@code null}
         */
        public ExactMatchRef {
            Objects.requireNonNull(expectedValue, "expectedValue must not be null");
        }
    }

    /**
     * Mirror of the domain {@code WithinTolerance} — confirmed when a numeric
     * value falls within {@code ±tolerance} of {@code target}.
     *
     * @param target the target magnitude
     * @param tolerance the inclusive tolerance band
     */
    record WithinToleranceRef(double target, double tolerance) implements ExpectationRef {
    }

    /**
     * Mirror of the domain {@code EnumTransition} — confirmed when the enum
     * value matches the expected transition target.
     *
     * @param expectedValue the expected enum value, never {@code null}
     */
    record EnumTransitionRef(String expectedValue) implements ExpectationRef {
        /**
         * Validates the expected value.
         *
         * @throws NullPointerException if {@code expectedValue} is {@code null}
         */
        public EnumTransitionRef {
            Objects.requireNonNull(expectedValue, "expectedValue must not be null");
        }
    }

    /**
     * Mirror of the domain {@code AnyChange} — confirmed when the reported
     * value differs from the pre-command value.
     *
     * @param previousValue the pre-command attribute value, never {@code null}
     */
    record AnyChangeRef(AttributeValue previousValue) implements ExpectationRef {
        /**
         * Validates the previous value.
         *
         * @throws NullPointerException if {@code previousValue} is {@code null}
         */
        public AnyChangeRef {
            Objects.requireNonNull(previousValue, "previousValue must not be null");
        }
    }
}

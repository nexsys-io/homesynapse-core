/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.value;

import java.util.Objects;

/**
 * An {@link AttributeValue} that wraps a stored value which could not be upcast to a
 * current variant — the subtype-level analogue of {@code DegradedEvent}.
 *
 * <p>This is the sealed-hierarchy fallback produced by the lenient mode of an
 * {@link AttributeValueUpcaster}. Core projections run in strict mode, where a failed
 * upcast halts processing and a {@code DegradedAttributeValue} is never written to
 * canonical state. Diagnostic and forensic tools run in lenient mode, where a failed
 * upcast yields a {@code DegradedAttributeValue} that preserves the raw form and failure
 * reason for investigation.</p>
 *
 * <p>{@link #attributeType()} returns the sentinel {@link AttributeType#DEGRADED}: the
 * originally intended type is generally unknown (that is why the value degraded), so
 * {@code DEGRADED} is the honest classifier and keeps exhaustive switches total.
 * {@code AttributeType.DEGRADED} may never appear in an {@link AttributeSchema#type()}
 * (AMD-47-INV-04).</p>
 *
 * <p>Added by AMD-47.</p>
 *
 * @param originalTypeName the stored subtype name the value failed to become, never
 *                         {@code null} or blank
 * @param rawForm the original serialized form before the failed upcast, never
 *                {@code null} (blank is permitted)
 * @param failureReason a description of why the upcast failed, never {@code null} or blank
 * @see AttributeValue
 * @see AttributeValueUpcaster
 * @see AttributeType#DEGRADED
 * @since 1.0
 */
public record DegradedAttributeValue(
        String originalTypeName,
        String rawForm,
        String failureReason
) implements AttributeValue {

    /**
     * Validates the fields: none may be {@code null}, and {@code originalTypeName} and
     * {@code failureReason} must not be blank. {@code rawForm} is non-null only — a blank
     * raw form is permitted.
     *
     * @throws NullPointerException if any field is {@code null}
     * @throws IllegalArgumentException if {@code originalTypeName} or {@code failureReason}
     *         is blank
     */
    public DegradedAttributeValue {
        Objects.requireNonNull(originalTypeName, "originalTypeName must not be null");
        Objects.requireNonNull(rawForm, "rawForm must not be null");
        Objects.requireNonNull(failureReason, "failureReason must not be null");
        if (originalTypeName.isBlank()) {
            throw new IllegalArgumentException("originalTypeName must not be blank");
        }
        if (failureReason.isBlank()) {
            throw new IllegalArgumentException("failureReason must not be blank");
        }
    }

    /**
     * Returns the preserved raw form.
     *
     * @return the raw form, never {@code null}
     */
    @Override
    public Object rawValue() {
        return rawForm;
    }

    @Override
    public AttributeType attributeType() {
        return AttributeType.DEGRADED;
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Validates attribute values against capability-defined schemas.
 *
 * <p>Used at ingestion time to ensure reported attribute values conform to
 * the type, range, and enumeration constraints defined by the capability's
 * {@link AttributeSchema}.</p>
 *
 * <p>Implementations must be safe for concurrent access.</p>
 *
 * <p>Defined in Doc 02 §8.1.</p>
 *
 * @see AttributeSchema
 * @see ValidationResult
 * @since 1.0
 */
public interface AttributeValidator {

    /**
     * Validates a single attribute value against its schema.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @param attributeKey the attribute key, never {@code null}
     * @param value the value to validate, never {@code null}
     * @return the validation result, never {@code null}
     */
    ValidationResult validate(String capabilityId, String attributeKey, AttributeValue value);

    /**
     * Validates multiple attribute values against their schemas.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @param attributes the attribute values keyed by attribute key, never {@code null}
     * @return the aggregated validation result, never {@code null}
     */
    ValidationResult validateAll(String capabilityId, Map<String, AttributeValue> attributes);
}

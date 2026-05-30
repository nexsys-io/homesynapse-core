/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Migration seam for evolving stored attribute values across {@link AttributeValue} type
 * changes — the value-layer analogue of the event upcaster pipeline (Doc 01 §3.10).
 *
 * <p>Implementations are constructor-injected by downstream consumers; this SPI uses no
 * {@code ServiceLoader} (DECIDE-04). It lives in {@code com.homesynapse.device} so that
 * device-model remains the single source of the value-type contract and downstream modules
 * (state-store, integration-runtime) depend on it without a new module edge.</p>
 *
 * <p>The SPI offers two modes, paralleling the event upcaster:</p>
 * <ul>
 *   <li><strong>Strict</strong> ({@link #upcast}) — used by core projections (State Store,
 *       Automation, Pending Command Ledger). A failed upcast throws; a
 *       {@link DegradedAttributeValue} is never produced, and never enters canonical state
 *       (AMD-47-INV-04).</li>
 *   <li><strong>Lenient</strong> ({@link #upcastLenient}) — used by diagnostic and forensic
 *       tools. A failed upcast yields a {@link DegradedAttributeValue} preserving the raw
 *       form and failure reason; it never throws for an un-upcastable value.</li>
 * </ul>
 *
 * <p>When wired into the projection (a later work unit), an upcaster MUST run strictly
 * before {@code DerivationRule.evaluate()} on both the {@code onEvent} and
 * {@code processBatch} paths — no path may reach derivation with an un-upcast stored value
 * (AMD-47-INV-02).</p>
 *
 * @see AttributeValue
 * @see DegradedAttributeValue
 * @since 1.0
 */
public interface AttributeValueUpcaster {

    /**
     * Reports whether this upcaster can transform a stored value of the given subtype name
     * and schema version.
     *
     * @param storedTypeName the stored {@link AttributeValue} subtype name
     * @param fromSchemaVersion the schema version the stored value was written under
     * @return {@code true} if {@link #upcast} can transform such a value
     */
    boolean canUpcast(String storedTypeName, int fromSchemaVersion);

    /**
     * Strict mode: transforms a stored raw form into a current {@link AttributeValue}, or
     * throws if the stored value cannot be upcast (unknown or unsupported type or version,
     * or a malformed raw form). Core projections use this — a failed upcast halts
     * processing and a {@link DegradedAttributeValue} is never produced here.
     *
     * @param storedTypeName the stored {@link AttributeValue} subtype name
     * @param rawForm the original serialized form of the stored value
     * @param fromSchemaVersion the schema version the stored value was written under
     * @return the upcast value, never {@code null}
     * @throws RuntimeException if the stored value cannot be upcast
     */
    AttributeValue upcast(String storedTypeName, String rawForm, int fromSchemaVersion);

    /**
     * Lenient mode (default): transforms a stored raw form into a current
     * {@link AttributeValue}, or returns a {@link DegradedAttributeValue} preserving the raw
     * form and a non-blank failure reason. Never throws for an un-upcastable value.
     * Diagnostic and forensic tools use this. Assumes a non-blank {@code storedTypeName}
     * (a real stored subtype name).
     *
     * @param storedTypeName the stored {@link AttributeValue} subtype name, non-blank
     * @param rawForm the original serialized form of the stored value
     * @param fromSchemaVersion the schema version the stored value was written under
     * @return the upcast value, or a {@link DegradedAttributeValue} on failure, never {@code null}
     */
    default AttributeValue upcastLenient(String storedTypeName, String rawForm, int fromSchemaVersion) {
        try {
            if (!canUpcast(storedTypeName, fromSchemaVersion)) {
                return new DegradedAttributeValue(storedTypeName, rawForm,
                        "no upcaster for type " + storedTypeName + " at version " + fromSchemaVersion);
            }
            return upcast(storedTypeName, rawForm, fromSchemaVersion);
        } catch (RuntimeException e) {
            String reason = (e.getMessage() == null) ? e.toString() : e.getMessage();
            return new DegradedAttributeValue(storedTypeName, rawForm, reason);
        }
    }
}

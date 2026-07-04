/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * Exact-string {@link MatchCriteria}: the profile applies when both the Basic
 * cluster manufacturer name (0x0004) and model identifier (0x0005) match exactly.
 *
 * <p>The middle precedence tier (Doc 18 §3.5(d)): below {@link Fingerprint}, above
 * {@link ModelWildcard}. Carries the same identity pair as the Phase-2
 * {@link ManufacturerModelPair} data carrier; this permit is the sealed-hierarchy
 * form the registry resolves.
 *
 * <p>Zigbee-scoped: this type's vocabulary is deliberately protocol-specific; it is
 * NOT the generic profile contract (Doc 18 §3.5(d) seam note).
 *
 * <p>Thread-safe: immutable record.
 *
 * @param manufacturerName the ZCL Basic cluster manufacturer name, never {@code null}
 * @param modelIdentifier the ZCL Basic cluster model identifier, never {@code null}
 * @see MatchCriteria
 */
public record ExactModel(String manufacturerName, String modelIdentifier)
        implements MatchCriteria {

    /**
     * Creates an exact-model criterion with non-null validation.
     *
     * @param manufacturerName never {@code null}
     * @param modelIdentifier never {@code null}
     */
    public ExactModel {
        Objects.requireNonNull(manufacturerName, "manufacturerName must not be null");
        Objects.requireNonNull(modelIdentifier, "modelIdentifier must not be null");
    }

    @Override
    public boolean matches(String manufacturerName, String modelIdentifier) {
        return this.manufacturerName.equals(manufacturerName)
                && this.modelIdentifier.equals(modelIdentifier);
    }
}

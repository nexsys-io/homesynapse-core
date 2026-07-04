/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * Manufacturer-family prefix {@link MatchCriteria}: the profile applies when the
 * manufacturer name matches exactly and the model identifier starts with the given
 * prefix (the Doc 08 §3.6 {@code ("IKEA of Sweden", "TRADFRI*")} family case, with
 * the {@code *} implied — the prefix field carries no wildcard character).
 *
 * <p>The lowest precedence tier (Doc 18 §3.5(d)): below {@link Fingerprint} and
 * {@link ExactModel}.
 *
 * <p>Zigbee-scoped: this type's vocabulary is deliberately protocol-specific; it is
 * NOT the generic profile contract (Doc 18 §3.5(d) seam note).
 *
 * <p>Thread-safe: immutable record.
 *
 * @param manufacturerName the ZCL Basic cluster manufacturer name, never {@code null}
 * @param modelPrefix the model identifier prefix (without a wildcard character),
 *        never {@code null}
 * @see MatchCriteria
 */
public record ModelWildcard(String manufacturerName, String modelPrefix)
        implements MatchCriteria {

    /**
     * Creates a model-wildcard criterion with non-null validation.
     *
     * @param manufacturerName never {@code null}
     * @param modelPrefix never {@code null}
     */
    public ModelWildcard {
        Objects.requireNonNull(manufacturerName, "manufacturerName must not be null");
        Objects.requireNonNull(modelPrefix, "modelPrefix must not be null");
    }

    @Override
    public boolean matches(String manufacturerName, String modelIdentifier) {
        return this.manufacturerName.equals(manufacturerName)
                && modelIdentifier.startsWith(modelPrefix);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Objects;

/**
 * Endpoint/cluster-signature {@link MatchCriteria}: the highest-precedence tier
 * (Doc 18 §3.5(d) — fingerprints outrank model strings at data scale; the Tuya
 * TS0601 generic-modelID collision is the stress case). Shape pinned from the
 * corpus IR's {@code identity.fingerprint[]}.
 *
 * <p><strong>DEFINED now; matching is Wave-2 (DP-2, the ruled doctrine: freeze the
 * schema, defer the behavior).</strong> {@link #matches(String, String)} throws
 * {@link UnsupportedOperationException} until Wave-2's first fingerprint-bearing
 * corpus entry lands the matching behavior. Call sites switch exhaustively over
 * {@link MatchCriteria} and treat the fingerprint arm as no-match — a fingerprint
 * criterion never silently matches.
 *
 * <p>Zigbee-scoped: this type's vocabulary is deliberately protocol-specific; it is
 * NOT the generic profile contract (Doc 18 §3.5(d) seam note).
 *
 * <p>Thread-safe: immutable record with a defensively copied list.
 *
 * @param manufacturerName the ZCL Basic cluster manufacturer name, never {@code null}
 * @param modelIdentifier the ZCL Basic cluster model identifier, never {@code null}
 * @param endpoints the per-endpoint signatures, never {@code null}, never empty
 * @see MatchCriteria
 * @see EndpointSignature
 */
public record Fingerprint(
        String manufacturerName,
        String modelIdentifier,
        List<EndpointSignature> endpoints) implements MatchCriteria {

    /**
     * Creates a fingerprint criterion with validation and a defensive copy.
     *
     * @param manufacturerName never {@code null}
     * @param modelIdentifier never {@code null}
     * @param endpoints never {@code null}, must not be empty
     */
    public Fingerprint {
        Objects.requireNonNull(manufacturerName, "manufacturerName must not be null");
        Objects.requireNonNull(modelIdentifier, "modelIdentifier must not be null");
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        endpoints = List.copyOf(endpoints);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints must not be empty");
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — fingerprint matching is
     *         delivered with Wave-2's first fingerprint-disambiguated corpus entry;
     *         the permit exists now so that landing it is an additive behavior
     *         change, never a sealed-hierarchy change
     */
    @Override
    public boolean matches(String manufacturerName, String modelIdentifier) {
        throw new UnsupportedOperationException(
                "Fingerprint matching is Wave-2 behavior; the criterion type is "
                        + "frozen now so Wave-2 populates an existing permit "
                        + "(DP-2). Registry call sites treat this arm as no-match.");
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * The sealed device-profile match key (§1.2, DP-2): every way a profile can claim
 * an interviewed device. All three variants are DEFINED now; matching behavior is
 * implemented for {@link ExactModel} and {@link ModelWildcard} only —
 * {@link Fingerprint} matching lands with Wave-2's first TS0601-class corpus entry
 * as an additive behavior change on an already-existing permit, never a
 * sealed-hierarchy change (the expensive kind).
 *
 * <p><strong>Precedence (Doc 18 §3.5(d), Locked):</strong> {@code Fingerprint} &gt;
 * {@code ExactModel} &gt; {@code ModelWildcard}, then an explicit per-profile
 * {@code priority} tiebreak (higher wins), then the profile id's lexicographic
 * order as the final deterministic total-order key. Precedence lives in the
 * REGISTRY's resolution, not in these records.
 *
 * <p>Exhaustive {@code switch} expressions over this hierarchy handle all three
 * permits with no {@code default} arm; a {@code Fingerprint} arm returns
 * no-match/unsupported per call-site semantics, never silently matches.
 *
 * <p>Zigbee-scoped: this type's vocabulary (clusters, endpoints, ZCL data types) is
 * deliberately protocol-specific; it is NOT the generic profile contract. A future
 * cross-protocol profile model is a separate design decision (Doc 18 §3.5(d) seam
 * note).
 *
 * <p>Thread-safe: all permits are immutable records.
 *
 * @see DeviceProfile#matches()
 * @see DeviceProfileRegistry
 */
public sealed interface MatchCriteria permits ExactModel, ModelWildcard, Fingerprint {

    /**
     * Evaluates this criterion against the Basic-cluster identity strings.
     *
     * @param manufacturerName the interviewed manufacturer name, never {@code null}
     * @param modelIdentifier the interviewed model identifier, never {@code null}
     * @return {@code true} if this criterion matches the identity
     * @throws UnsupportedOperationException from the {@link Fingerprint} permit —
     *         fingerprint matching is Wave-2 behavior (call sites switch
     *         exhaustively and treat the fingerprint arm as no-match instead of
     *         invoking this method on it)
     */
    boolean matches(String manufacturerName, String modelIdentifier);
}

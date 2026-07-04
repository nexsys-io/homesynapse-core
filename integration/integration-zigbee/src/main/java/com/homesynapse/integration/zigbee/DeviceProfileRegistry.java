/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Collection;
import java.util.Optional;

/**
 * Manages device profile loading, lookup, and user override merging.
 *
 * <p>Profiles are loaded from bundled {@code zigbee-profiles.json} and an optional
 * user override file at the path configured in {@code integrations.zigbee.profiles_path}.
 * User profiles take precedence over bundled profiles when both match at the same
 * criteria precedence rank.
 *
 * <p><strong>Resolution precedence (Doc 18 §3.5(d), Locked):</strong>
 * {@link Fingerprint} &gt; {@link ExactModel} &gt; {@link ModelWildcard}; within a
 * tier, user source over bundled, then an explicit per-profile {@code priority}
 * (higher wins), then {@code profileId} lexicographic order (ascending) as the
 * final deterministic total-order key.
 *
 * <p><strong>Namespace convention (Doc 18 §3.5(a)/(b), Locked):</strong> bare
 * {@code profileId} = first-party (the bare namespace is reserved to first party);
 * dotted {@code publisher.profile} = third-party, publisher-scoped, immutable by
 * convention. Distinct namespaces never merge — a third-party id can never
 * silently shadow a first-party id; duplicate ids within one load are a loader
 * error.
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Thread-safe: implementations must be safe for concurrent access.
 *
 * @see DeviceProfile
 * @see MatchCriteria
 * @see ManufacturerModelPair
 */
public interface DeviceProfileRegistry {

    /**
     * Looks up a device profile by manufacturer name and model identifier.
     *
     * <p>Resolves {@link ExactModel} and {@link ModelWildcard} criteria only —
     * exact matches take precedence over wildcard matches. {@link Fingerprint}
     * criteria never match on this path (they need the interview's endpoint
     * signatures; see {@link #findProfile(InterviewResult)}).
     *
     * @param manufacturerName the ZCL Basic cluster manufacturer name, never {@code null}
     * @param modelIdentifier the ZCL Basic cluster model identifier, never {@code null}
     * @return the matched device profile, or empty if no profile matches
     */
    Optional<DeviceProfile> findProfile(String manufacturerName, String modelIdentifier);

    /**
     * Looks up a device profile from a completed interview — the fingerprint-capable
     * path (§1.2, additive widening).
     *
     * <p>Resolves all {@link MatchCriteria} kinds against the interview's
     * manufacturer/model strings and endpoint signatures, applying the Doc 18
     * §3.5(d) precedence ({@code Fingerprint > ExactModel > ModelWildcard}).
     * Fingerprint MATCHING behavior is Wave-2 (DP-2): until it lands, fingerprint
     * criteria contribute no matches on this path — they never silently match.
     *
     * @param interview the completed interview result, never {@code null}
     * @return the matched device profile, or empty if no profile matches
     */
    Optional<DeviceProfile> findProfile(InterviewResult interview);

    /**
     * Adds or replaces a device profile in the registry.
     *
     * <p>If a profile with the same {@link DeviceProfile#profileId()} already exists,
     * it is replaced.
     *
     * @param profile the device profile to register, never {@code null}
     */
    void registerProfile(DeviceProfile profile);

    /**
     * Returns all registered device profiles.
     *
     * @return an unmodifiable collection of all profiles, never {@code null}
     */
    Collection<DeviceProfile> allProfiles();
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@link DeviceProfileRegistry} implementation: index-first resolution over
 * {@link ProfileEntry} rows (§F — matching consults only the eagerly parsed
 * criteria; profile bodies materialize on first match).
 *
 * <p><strong>Resolution order (deterministic total order):</strong> criteria tier
 * per Doc 18 §3.5(d) ({@code Fingerprint > ExactModel > ModelWildcard}) → source
 * rank ({@code USER > RUNTIME > BUNDLED}, §I) → explicit priority (higher wins) →
 * {@code profileId} lexicographic ascending. The Fingerprint tier is reserved and
 * currently contributes NO matches (DP-2: matching is Wave-2) — a fingerprint
 * criterion never silently matches.
 *
 * <p><strong>Namespace rule (§C):</strong> profiles key by their full namespaced
 * id; bare (first-party) and dotted (third-party) ids are distinct key spaces
 * that never merge. A same-id registration replaces (the override channel);
 * duplicate ids within one document are the loader's error.
 *
 * <p><strong>Match-time body failure (F-12):</strong> candidates materialize
 * best-first outside the lock; an entry whose body fails to load at match time is
 * that entry's no-match — one WARN names the profile, and the next candidate in
 * the total order still matches. {@link #allProfiles()} keeps the full-parse
 * contract: enumeration propagates the load error.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 *
 * @see ZigbeeProfileLoader
 */
final class StandardDeviceProfileRegistry implements DeviceProfileRegistry {

    /** The reserved fingerprint tier (Wave-2 populates it; no match today). */
    private static final int TIER_FINGERPRINT = 0;
    private static final int TIER_EXACT_MODEL = 1;
    private static final int TIER_MODEL_WILDCARD = 2;
    private static final int NO_MATCH = Integer.MAX_VALUE;

    private static final Logger log =
            LoggerFactory.getLogger(StandardDeviceProfileRegistry.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ProfileEntry> byId = new LinkedHashMap<>();

    /** Creates an empty registry. Performs no I/O. */
    StandardDeviceProfileRegistry() {
    }

    /**
     * Registers loader-indexed entries; a same-id entry replaces the existing one.
     *
     * @param entries the entries to register, never {@code null}
     */
    void register(List<ProfileEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        lock.lock();
        try {
            for (ProfileEntry entry : entries) {
                byId.put(entry.profileId(), entry);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<DeviceProfile> findProfile(String manufacturerName,
            String modelIdentifier) {
        Objects.requireNonNull(manufacturerName, "manufacturerName");
        Objects.requireNonNull(modelIdentifier, "modelIdentifier");
        return resolve(manufacturerName, modelIdentifier);
    }

    @Override
    public Optional<DeviceProfile> findProfile(InterviewResult interview) {
        Objects.requireNonNull(interview, "interview");
        // The interview path exists so fingerprint matching lands here at Wave-2
        // (against interview.endpoints()) without an interface change; until then
        // it resolves the same string criteria — the fingerprint arm stays no-match.
        return resolve(interview.manufacturerName(), interview.modelIdentifier());
    }

    @Override
    public void registerProfile(DeviceProfile profile) {
        Objects.requireNonNull(profile, "profile");
        lock.lock();
        try {
            byId.put(profile.profileId(),
                    ProfileEntry.materialized(profile, ProfileSource.RUNTIME, 0));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Collection<DeviceProfile> allProfiles() {
        List<ProfileEntry> snapshot;
        lock.lock();
        try {
            snapshot = List.copyOf(byId.values());
        } finally {
            lock.unlock();
        }
        // The full-parse path by design: enumerating all profiles materializes
        // every body (outside the lock — materialization is entry-guarded).
        // F-12 deliberately stops at findProfile: enumeration is full-truth,
        // so a load failure here propagates.
        List<DeviceProfile> profiles = new ArrayList<>(snapshot.size());
        for (ProfileEntry entry : snapshot) {
            profiles.add(entry.materialize());
        }
        return List.copyOf(profiles);
    }

    private Optional<DeviceProfile> resolve(String manufacturerName,
            String modelIdentifier) {
        record Candidate(ProfileEntry entry, int tier) {
        }
        List<Candidate> candidates = new ArrayList<>();
        lock.lock();
        try {
            for (ProfileEntry entry : byId.values()) {
                int bestTier = NO_MATCH;
                for (MatchCriteria criteria : entry.criteria()) {
                    int tier = switch (criteria) {
                        // Wave-2 populates the fingerprint tier; until then the
                        // arm is an explicit no-match — never a silent match.
                        case Fingerprint ignored -> NO_MATCH;
                        case ExactModel exact ->
                                exact.matches(manufacturerName, modelIdentifier)
                                        ? TIER_EXACT_MODEL : NO_MATCH;
                        case ModelWildcard wildcard ->
                                wildcard.matches(manufacturerName, modelIdentifier)
                                        ? TIER_MODEL_WILDCARD : NO_MATCH;
                    };
                    bestTier = Math.min(bestTier, tier);
                }
                if (bestTier != NO_MATCH) {
                    candidates.add(new Candidate(entry, bestTier));
                }
            }
        } finally {
            lock.unlock();
        }
        candidates.sort(Comparator
                .comparingInt(Candidate::tier)
                .thenComparingInt(c -> c.entry().source().rank())
                .thenComparing(c -> c.entry().priority(),
                        Comparator.reverseOrder())
                .thenComparing(c -> c.entry().profileId()));
        // F-12: materialize best-first, outside the lock. A body that fails to
        // load at match time is THAT entry's no-match — one WARN, and the next
        // candidate in the total order still matches; propagating would take
        // down the whole match over one bad profile.
        for (Candidate candidate : candidates) {
            try {
                return Optional.of(candidate.entry().materialize());
            } catch (ProfileLoadException e) {
                log.warn("zigbee.profile_body_unloadable: profile={} failed to "
                                + "materialize at match time; treated as no-match: {}",
                        candidate.entry().profileId(), e.getMessage());
            }
        }
        return Optional.empty();
    }
}

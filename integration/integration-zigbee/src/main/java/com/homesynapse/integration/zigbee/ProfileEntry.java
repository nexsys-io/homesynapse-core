/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * One indexed device-profile entry: the eagerly parsed match surface (id,
 * criteria, priority, source) plus the lazily materialized profile body — the
 * §F index-first shape (deCONZ DESC-chunk / Z2M compile-time-index precedent).
 * The registry matches against the index; the full {@link DeviceProfile} parses
 * only on first materialization and is memoized.
 *
 * <p>Thread-safe: the memoization is {@link ReentrantLock}-guarded (LTD-11).
 */
final class ProfileEntry {

    private final String profileId;
    private final Set<MatchCriteria> criteria;
    private final int priority;
    private final ProfileSource source;
    private final JsonNode body;
    private final Function<JsonNode, DeviceProfile> materializer;
    private final ReentrantLock lock = new ReentrantLock();

    private DeviceProfile materialized;

    /**
     * Creates an index entry over an unmaterialized profile body.
     *
     * @param profileId the namespaced profile id, never {@code null}
     * @param criteria the eagerly parsed match criteria, never {@code null}, non-empty
     * @param priority the explicit tiebreak priority (higher wins)
     * @param source where the profile was loaded from, never {@code null}
     * @param body the profile's JSON node, never {@code null}
     * @param materializer parses the body into a {@link DeviceProfile}, never {@code null}
     */
    ProfileEntry(String profileId, Set<MatchCriteria> criteria, int priority,
            ProfileSource source, JsonNode body,
            Function<JsonNode, DeviceProfile> materializer) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.criteria = Set.copyOf(Objects.requireNonNull(criteria, "criteria"));
        this.priority = priority;
        this.source = Objects.requireNonNull(source, "source");
        this.body = Objects.requireNonNull(body, "body");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        if (this.criteria.isEmpty()) {
            throw new IllegalArgumentException(
                    "criteria must not be empty for profile " + profileId);
        }
    }

    /**
     * Creates an already-materialized entry (the {@code registerProfile} path).
     *
     * @param profile the profile, never {@code null}
     * @param source where the profile came from, never {@code null}
     * @param priority the explicit tiebreak priority
     * @return the entry
     */
    static ProfileEntry materialized(DeviceProfile profile, ProfileSource source,
            int priority) {
        Objects.requireNonNull(profile, "profile");
        ProfileEntry entry = new ProfileEntry(profile.profileId(), profile.matches(),
                priority, source,
                com.fasterxml.jackson.databind.node.NullNode.getInstance(),
                node -> profile);
        entry.materialized = profile;
        return entry;
    }

    /** Returns the namespaced profile id. */
    String profileId() {
        return profileId;
    }

    /** Returns the eagerly indexed match criteria. */
    Set<MatchCriteria> criteria() {
        return criteria;
    }

    /** Returns the explicit tiebreak priority (higher wins within a tier). */
    int priority() {
        return priority;
    }

    /** Returns the load source (the §I precedence input). */
    ProfileSource source() {
        return source;
    }

    /**
     * Materializes the full profile, memoizing the result.
     *
     * @return the parsed profile
     * @throws ProfileLoadException if the body does not parse into a valid profile
     */
    DeviceProfile materialize() {
        lock.lock();
        try {
            if (materialized == null) {
                materialized = materializer.apply(body);
            }
            return materialized;
        } finally {
            lock.unlock();
        }
    }
}

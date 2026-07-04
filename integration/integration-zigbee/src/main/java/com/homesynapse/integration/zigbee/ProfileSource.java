/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Where a device profile was loaded from — the §I precedence input: user profiles
 * take precedence over bundled at equal criteria rank; runtime registrations rank
 * between the two (an explicit programmatic override outranks the shipped corpus
 * but not the user's own file).
 *
 * <p>Thread-safe: enum.
 *
 * @see StandardDeviceProfileRegistry
 */
enum ProfileSource {

    /** The user override file at {@code integrations.zigbee.profiles_path}. */
    USER(0),

    /** A profile registered at runtime via {@code registerProfile}. */
    RUNTIME(1),

    /** The bundled {@code zigbee-profiles.json} corpus resource. */
    BUNDLED(2);

    private final int rank;

    ProfileSource(int rank) {
        this.rank = rank;
    }

    /** Returns the resolution rank; lower wins within a criteria tier. */
    int rank() {
        return rank;
    }
}

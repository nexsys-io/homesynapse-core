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
 * User profiles take precedence over bundled profiles when both match the same
 * manufacturer/model pair.
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Thread-safe: implementations must be safe for concurrent access.
 *
 * @see DeviceProfile
 * @see ManufacturerModelPair
 */
public interface DeviceProfileRegistry {

    /**
     * Looks up a device profile by manufacturer name and model identifier.
     *
     * <p>Matching supports optional wildcard prefix matching for manufacturer families
     * (e.g., {@code "TRADFRI*"}). Exact matches take precedence over wildcard matches.
     *
     * @param manufacturerName the ZCL Basic cluster manufacturer name, never {@code null}
     * @param modelIdentifier the ZCL Basic cluster model identifier, never {@code null}
     * @return the matched device profile, or empty if no profile matches
     */
    Optional<DeviceProfile> findProfile(String manufacturerName, String modelIdentifier);

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

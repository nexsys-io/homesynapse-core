/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * Exact string match key for the device profile registry.
 *
 * <p>A manufacturer-model pair uniquely identifies a device SKU for profile matching.
 * The manufacturer name and model identifier are read from the Basic cluster (attributes
 * 0x0004 and 0x0005) during the interview pipeline. Optional wildcard prefix matching
 * for manufacturer families (e.g., {@code "TRADFRI*"}) is a Phase 3 implementation
 * detail; this record is the data carrier.
 *
 * <p>Doc 08 §3.6 — device profile matching key.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param manufacturerName the ZCL Basic cluster manufacturer name attribute, never {@code null}
 * @param modelIdentifier the ZCL Basic cluster model identifier attribute, never {@code null}
 * @see DeviceProfile
 * @see DeviceProfileRegistry
 */
public record ManufacturerModelPair(String manufacturerName, String modelIdentifier) {

    /**
     * Creates a manufacturer-model pair with non-null validation.
     *
     * @param manufacturerName the manufacturer name, never {@code null}
     * @param modelIdentifier the model identifier, never {@code null}
     */
    public ManufacturerModelPair {
        Objects.requireNonNull(manufacturerName, "manufacturerName must not be null");
        Objects.requireNonNull(modelIdentifier, "modelIdentifier must not be null");
    }
}

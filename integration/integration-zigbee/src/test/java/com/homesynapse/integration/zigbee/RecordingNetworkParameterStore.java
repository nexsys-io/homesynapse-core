/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recording fake for the {@link NetworkParameterStore} seam (D-M92-7) — the store
 * side of the INV-SE-03 custody assertions: formation tests verify the key bytes
 * appear HERE and nowhere else observable.
 */
final class RecordingNetworkParameterStore implements NetworkParameterStore {

    private final Map<String, byte[]> keys = new HashMap<>();
    private final List<NetworkParameters> savedParameters = new ArrayList<>();
    private NetworkParameters current;

    RecordingNetworkParameterStore() {
    }

    @Override
    public Optional<NetworkParameters> load() {
        return Optional.ofNullable(current);
    }

    @Override
    public void save(NetworkParameters parameters) {
        current = parameters;
        savedParameters.add(parameters);
    }

    @Override
    public void saveNetworkKey(String keyRef, byte[] keyMaterial) {
        keys.put(keyRef, keyMaterial.clone());
    }

    @Override
    public Optional<byte[]> loadNetworkKey(String keyRef) {
        byte[] key = keys.get(keyRef);
        return key == null ? Optional.empty() : Optional.of(key.clone());
    }

    /** Pre-seeds stored parameters (the resume-path arrangement). */
    void seed(NetworkParameters parameters) {
        current = parameters;
    }

    /** Returns the stored key under {@code keyRef}, or null. */
    byte[] storedKey(String keyRef) {
        byte[] key = keys.get(keyRef);
        return key == null ? null : key.clone();
    }

    /** Returns every save(...) call in order. */
    List<NetworkParameters> savedParameters() {
        return savedParameters;
    }
}

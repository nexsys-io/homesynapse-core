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
 * appear HERE and nowhere else observable. M9.6-SEED adds TCLK-seed custody and a
 * custody-operation journal (the DP-4 atomicity/ordering surface): the production
 * save paths journal; the {@code seed*} arrangement backdoors do not.
 */
final class RecordingNetworkParameterStore implements NetworkParameterStore {

    private final Map<String, byte[]> keys = new HashMap<>();
    private final List<NetworkParameters> savedParameters = new ArrayList<>();
    private final List<String> custodyOperations = new ArrayList<>();
    private NetworkParameters current;
    private byte[] tclkSeed;

    RecordingNetworkParameterStore() {
    }

    @Override
    public Optional<NetworkParameters> load() {
        return Optional.ofNullable(current);
    }

    @Override
    public void save(NetworkParameters parameters) {
        custodyOperations.add("save");
        current = parameters;
        savedParameters.add(parameters);
    }

    @Override
    public void saveNetworkKey(String keyRef, byte[] keyMaterial) {
        custodyOperations.add("saveNetworkKey");
        keys.put(keyRef, keyMaterial.clone());
    }

    @Override
    public Optional<byte[]> loadNetworkKey(String keyRef) {
        byte[] key = keys.get(keyRef);
        return key == null ? Optional.empty() : Optional.of(key.clone());
    }

    @Override
    public void saveTclkSeed(byte[] seedMaterial) {
        custodyOperations.add("saveTclkSeed");
        tclkSeed = seedMaterial.clone();
    }

    @Override
    public Optional<byte[]> loadTclkSeed() {
        return tclkSeed == null ? Optional.empty() : Optional.of(tclkSeed.clone());
    }

    @Override
    public void saveNetworkKeyAndTclkSeed(String keyRef, byte[] keyMaterial,
            byte[] seedMaterial) {
        custodyOperations.add("saveNetworkKeyAndTclkSeed");
        keys.put(keyRef, keyMaterial.clone());
        tclkSeed = seedMaterial.clone();
    }

    /** Pre-seeds stored parameters (the resume-path arrangement). */
    void seed(NetworkParameters parameters) {
        current = parameters;
    }

    /** Pre-seeds key material WITHOUT journaling (arrangement, never production). */
    void seedNetworkKey(String keyRef, byte[] keyMaterial) {
        keys.put(keyRef, keyMaterial.clone());
    }

    /** Pre-seeds the TCLK seed WITHOUT journaling (arrangement, never production). */
    void seedTclkSeed(byte[] seedMaterial) {
        tclkSeed = seedMaterial.clone();
    }

    /** Returns the stored key under {@code keyRef}, or null. */
    byte[] storedKey(String keyRef) {
        byte[] key = keys.get(keyRef);
        return key == null ? null : key.clone();
    }

    /** Returns the stored TCLK seed, or null (M9.6-SEED presence-as-marker). */
    byte[] storedTclkSeed() {
        return tclkSeed == null ? null : tclkSeed.clone();
    }

    /** Returns every save(...) call in order. */
    List<NetworkParameters> savedParameters() {
        return savedParameters;
    }

    /** Custody mutations in call order (the M9.6-SEED DP-4 ordering surface). */
    List<String> custodyOperations() {
        return custodyOperations;
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Optional;

/**
 * Persistence seam for Zigbee network identity and network-key custody (D-M92-7,
 * Doc 08 §3.13).
 *
 * <p><strong>INV-SE-03 custody contract (binding on every implementation):</strong>
 * the network key's only legal path is {@code SecureRandom → this store} (and from the
 * store to the coordinator's security state at formation). The key is NEVER logged,
 * NEVER serialized into plain configuration, NEVER included in any {@code toString()},
 * exception message, or event payload. {@link NetworkParameters#networkKeyRef()} is an
 * opaque reference to material held here — never the material itself.
 *
 * <p>M9.2 ships this seam plus a test fake ONLY. The real binding — SecretStore-backed
 * key custody per INV-SE-03 and config-backed parameters — lands with adapter wiring
 * at M9.3/M9.4.
 *
 * <p>DP-E non-preclusion (D-M92-8): the shape is deliberately keyed key custody +
 * whole-record parameter persistence so a coordinator-backup/export seam (the
 * zigpy/z2m Open-Coordinator-Backup format class) can be added later without
 * reshaping formation or this store — key material stays retrievable by reference,
 * never collapsed into an unexportable in-memory-only form.
 *
 * <p>Thread-safe: implementations must tolerate access from the protocol layer's
 * calling threads (the M9.2 protocol serializes all access under its command lock).
 *
 * @see NetworkFormation
 * @see NetworkParameters
 */
interface NetworkParameterStore {

    /**
     * Loads the persisted network parameters, if a network has been formed.
     *
     * @return the stored parameters, or empty on first run
     */
    Optional<NetworkParameters> load();

    /**
     * Persists the network parameters (channel, PAN ID, extended PAN ID, key
     * reference). Overwrites any previous record.
     *
     * @param parameters the parameters to persist, never {@code null}
     */
    void save(NetworkParameters parameters);

    /**
     * Stores network-key material under {@code keyRef} (INV-SE-03: encrypted at rest
     * in the real binding).
     *
     * @param keyRef the opaque key reference, never {@code null}
     * @param keyMaterial the 16-byte AES-128 network key, never {@code null}
     */
    void saveNetworkKey(String keyRef, byte[] keyMaterial);

    /**
     * Retrieves network-key material by reference.
     *
     * @param keyRef the opaque key reference, never {@code null}
     * @return the key material, or empty if no key is stored under {@code keyRef}
     */
    Optional<byte[]> loadNetworkKey(String keyRef);

    /**
     * Stores the 16-byte generated Trust Center link-key seed under the fixed
     * {@code zigbee.tclk_seed} reference (M9.6-SEED, the SD-5 custody
     * consequence). INV-SE-03 applies to the seed identically to the network
     * key: its only legal path is {@code SecureRandom → this store} (and from
     * the store into the coordinator's initial security state at
     * formation/restore). The seed is NEVER logged, NEVER serialized into
     * plain configuration, NEVER included in any {@code toString()}, exception
     * message, or event payload.
     *
     * @param seedMaterial the 16-byte hashed-TCLK seed, never {@code null}
     */
    void saveTclkSeed(byte[] seedMaterial);

    /**
     * Retrieves the generated Trust Center link-key seed, if present.
     *
     * <p><strong>Presence is the posture marker (M9.6-SEED DP-2):</strong> a
     * present seed means this custody's network was FORMED with the generated
     * seed; an absent seed means it was formed on the well-known root (the
     * pre-SEED bench network exactly), and restore must reproduce AS-FORMED.
     * Recorded scope limit (DP-7): a corrupted custody that lost ONLY the seed
     * secret is indistinguishable from legitimate well-known custody — restore
     * would honestly reproduce the wrong root, surfacing as TCLK mismatches at
     * the devices' key-request legs. Bounded and named; the FRAME-CTR custody
     * schema closes it.
     *
     * @return the seed material, or empty if no seed is stored
     */
    Optional<byte[]> loadTclkSeed();

    /**
     * Atomically stores the network key AND the TCLK seed in one never-torn,
     * durable-before-return write (M9.6-SEED DP-4 — the AMD-68
     * {@code SecretStore.setAll} semantics in the real binding): either both
     * secrets are persisted or neither is, so a first-run mint can never leave
     * half a custody. The INV-SE-03 clauses on {@link #saveNetworkKey} and
     * {@link #saveTclkSeed} apply here identically.
     *
     * @param keyRef the opaque network-key reference, never {@code null}
     * @param keyMaterial the 16-byte AES-128 network key, never {@code null}
     * @param seedMaterial the 16-byte hashed-TCLK seed, never {@code null}
     */
    void saveNetworkKeyAndTclkSeed(String keyRef, byte[] keyMaterial,
            byte[] seedMaterial);
}

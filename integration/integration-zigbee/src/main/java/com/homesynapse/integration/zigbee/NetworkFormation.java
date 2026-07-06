/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.PermanentIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Zigbee network formation and resume orchestration (Doc 08 §3.13), built against the
 * {@link CoordinatorOps} seam and the {@link NetworkParameterStore} seam (D-M92-7).
 *
 * <p>Formation: energy scan → two-tier channel selection (primary 15, 20, 11; fallback
 * 21–26 with a {@code zigbee.channel_fallback_tier} WARN noting the Aqara
 * compatibility risk) → random PAN ID → AES-128 network key from {@link SecureRandom}
 * handed DIRECTLY to the store (INV-SE-03) → coordinator security/formation → parameter
 * persistence. The key never appears in a log, a message, an exception, or
 * {@code toString()}.
 *
 * <p>Write ordering: the key is stored BEFORE the coordinator forms (a formed network
 * with an unsaved key would be unrecoverable; an unused stored key is harmless), and
 * the parameters are stored only AFTER coordinator formation succeeds (so a stored
 * parameter record always denotes a network that existed).
 *
 * <p>Resume (§3.13 reading, documented for PM review — the section prescribes
 * load-and-restore but no explicit mismatch rule): re-read stored parameters, restore
 * the coordinator's network state; if the coordinator lost its network (NVRAM wipe or
 * reflash), re-form deterministically from the STORED parameters and key so existing
 * devices can rejoin; if the coordinator reports a DIFFERENT network than stored,
 * fail permanently ({@code zigbee.network_parameter_mismatch}) — silent re-formation
 * would orphan every paired device, so the conflict is routed to the operator.
 *
 * <p>{@link SecureRandom} is an inline field per the repo precedent
 * ({@code StandardScopeKeyManager}); a package-private constructor accepts a seeded
 * instance for deterministic custody tests.
 *
 * <p>Thread-safe: no — callers serialize (the protocol's command lock).
 *
 * @see NetworkParameterStore
 * @see EzspCoordinatorProtocol
 */
final class NetworkFormation {

    /** Primary channel tier: minimal Wi-Fi overlap, broad compatibility (§3.13). */
    static final List<Integer> PRIMARY_CHANNELS = List.of(15, 20, 11);
    /** Fallback channel tier: least Wi-Fi interference, Aqara risk (§3.13). */
    static final List<Integer> FALLBACK_CHANNELS = List.of(21, 22, 23, 24, 25, 26);
    /**
     * Energy above this RSSI (dBm) marks a channel congested. §3.13 sets no numeric
     * threshold; -75 dBm is a chosen constant, flagged [INFO] in the M9.2 handoff.
     */
    static final int CONGESTION_THRESHOLD_DBM = -75;
    /**
     * Primary channels within this spread (dB) are "comparable", defaulting to
     * channel 15 (§3.13). Chosen constant, flagged [INFO] in the M9.2 handoff.
     */
    static final int COMPARABLE_DELTA_DBM = 6;
    /** The opaque secrets reference under which the network key is held (INV-SE-03). */
    static final String NETWORK_KEY_REF = "zigbee.network_key";

    private static final Logger log = LoggerFactory.getLogger(NetworkFormation.class);
    private static final int NETWORK_KEY_LENGTH_BYTES = 16;

    private final CoordinatorOps ops;
    private final NetworkParameterStore store;
    private final SecureRandom random;

    /**
     * Coordinator-level operations needed by formation/resume — implemented by
     * {@link EzspCoordinatorProtocol} over the EZSP command pipeline, faked in tests.
     */
    interface CoordinatorOps {

        /**
         * Runs an energy scan over {@code channels}.
         *
         * @param channels the channels to scan, never {@code null}
         * @return max observed RSSI (dBm, signed) per channel
         */
        Map<Integer, Integer> energyScan(List<Integer> channels);

        /**
         * Configures security (network key + the well-known Trust Center link key,
         * §3.13 step 5) and forms the network.
         *
         * @param channel the RF channel (11–26)
         * @param panId the 16-bit PAN ID
         * @param extendedPanId the 64-bit extended PAN ID
         * @param networkKey the 16-byte AES-128 network key
         */
        void formNetwork(int channel, int panId, long extendedPanId, byte[] networkKey);

        /**
         * Restores the coordinator's own persisted network state (networkInit).
         *
         * @return {@code true} if the coordinator restored a network; {@code false}
         *         if it has none stored
         */
        boolean resumeFromNvram();

        /**
         * Reads the coordinator's current network state.
         *
         * @return the current network view, never {@code null}
         */
        CoordinatorNetwork currentNetwork();

        /**
         * The coordinator's network state view.
         *
         * @param joined whether the coordinator is up on a network
         * @param channel the RF channel (meaningful when joined)
         * @param panId the PAN ID (meaningful when joined)
         * @param extendedPanId the extended PAN ID (meaningful when joined)
         */
        record CoordinatorNetwork(
                boolean joined, int channel, int panId, long extendedPanId) {
        }
    }

    /**
     * Creates the formation orchestrator with a fresh {@link SecureRandom}.
     *
     * @param ops the coordinator operations seam, never {@code null}
     * @param store the parameter/key store seam, never {@code null}
     */
    NetworkFormation(CoordinatorOps ops, NetworkParameterStore store) {
        this(ops, store, new SecureRandom());
    }

    /**
     * Creates the formation orchestrator with an injected randomness source
     * (deterministic custody tests).
     *
     * @param ops the coordinator operations seam, never {@code null}
     * @param store the parameter/key store seam, never {@code null}
     * @param random the randomness source, never {@code null}
     */
    NetworkFormation(CoordinatorOps ops, NetworkParameterStore store,
            SecureRandom random) {
        this.ops = Objects.requireNonNull(ops, "ops");
        this.store = Objects.requireNonNull(store, "store");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * First-run formation: scans, selects a channel per the two-tier preference,
     * generates PAN identity, and delegates to {@link #form(NetworkParameters)}.
     *
     * @return the formed network's parameters (also persisted)
     */
    NetworkParameters form() {
        Map<Integer, Integer> energy = ops.energyScan(allChannels());
        return formWithFreshIdentity(selectChannel(energy));
    }

    /**
     * First-run formation on an operator-pinned channel (M9.4-TCJ §B): the energy
     * scan never runs — operator intent outranks measurement — and PAN identity,
     * key custody, and persistence ride the shared {@link #form(NetworkParameters)}
     * path unchanged. The resume path never consults the pin: a formed network
     * resumes on its STORED channel regardless.
     *
     * @param pinnedChannel the operator-configured RF channel (11–26)
     * @return the formed network's parameters (also persisted)
     * @throws IllegalArgumentException if the channel is outside 11–26 (the adapter
     *                                  validates first; this is the defensive floor)
     */
    NetworkParameters form(int pinnedChannel) {
        if (pinnedChannel < 11 || pinnedChannel > 26) {
            throw new IllegalArgumentException(
                    "pinnedChannel must be 11-26, got " + pinnedChannel);
        }
        log.info("zigbee.channel_pinned: channel={}", pinnedChannel);
        return formWithFreshIdentity(pinnedChannel);
    }

    private NetworkParameters formWithFreshIdentity(int channel) {
        int panId = 1 + random.nextInt(0xFFFE); // 0x0001–0xFFFE: never broadcast/zero
        long extendedPanId = nonZeroRandomLong();
        return form(new NetworkParameters(channel, panId, extendedPanId,
                NETWORK_KEY_REF));
    }

    /**
     * Forms a network with the given parameters: loads the key by reference or
     * generates and stores a fresh one, configures and forms the coordinator, then
     * persists the parameters.
     *
     * @param requested the parameters to form with, never {@code null}
     * @return {@code requested} (persisted)
     */
    NetworkParameters form(NetworkParameters requested) {
        Objects.requireNonNull(requested, "requested");
        byte[] key = store.loadNetworkKey(requested.networkKeyRef())
                .orElseGet(() -> {
                    byte[] fresh = new byte[NETWORK_KEY_LENGTH_BYTES];
                    random.nextBytes(fresh);
                    store.saveNetworkKey(requested.networkKeyRef(), fresh);
                    return fresh;
                });
        ops.formNetwork(requested.channel(), requested.panId(),
                requested.extendedPanId(), key);
        store.save(requested);
        log.info("zigbee.network_formed: channel={} panId=0x{}",
                requested.channel(), Integer.toHexString(requested.panId()));
        return requested;
    }

    /**
     * Resumes the stored network (§3.13): restore from coordinator NVRAM, re-form
     * from stored parameters if the coordinator lost its network, fail permanently
     * on a parameter mismatch.
     *
     * @return the resumed network's parameters
     * @throws IllegalStateException if no parameters are stored (caller defect —
     *                               form first)
     * @throws PermanentIntegrationException if the stored key is missing or the
     *                                       coordinator is on a different network
     */
    NetworkParameters resume() throws PermanentIntegrationException {
        NetworkParameters stored = store.load().orElseThrow(() ->
                new IllegalStateException(
                        "no stored network parameters; form a network before resuming"));
        boolean restored = ops.resumeFromNvram();
        if (!restored) {
            byte[] key = store.loadNetworkKey(stored.networkKeyRef())
                    .orElseThrow(() -> new PermanentIntegrationException(
                            "zigbee.network_key_missing",
                            "stored network key reference '" + stored.networkKeyRef()
                                    + "' has no key material; the network cannot be "
                                    + "restored"));
            log.warn("zigbee.network_restored_from_parameters: coordinator lost its "
                            + "network state; re-forming channel={} panId=0x{}",
                    stored.channel(), Integer.toHexString(stored.panId()));
            ops.formNetwork(stored.channel(), stored.panId(), stored.extendedPanId(),
                    key);
            return stored;
        }
        CoordinatorOps.CoordinatorNetwork current = ops.currentNetwork();
        if (current.channel() != stored.channel()
                || current.panId() != stored.panId()
                || current.extendedPanId() != stored.extendedPanId()) {
            log.warn("zigbee.network_parameter_mismatch: coordinator channel={} "
                            + "panId=0x{} extendedPanId=0x{}; stored channel={} "
                            + "panId=0x{} extendedPanId=0x{}",
                    current.channel(), Integer.toHexString(current.panId()),
                    Long.toHexString(current.extendedPanId()), stored.channel(),
                    Integer.toHexString(stored.panId()),
                    Long.toHexString(stored.extendedPanId()));
            throw new PermanentIntegrationException(
                    "zigbee.network_parameter_mismatch",
                    String.format(
                            "coordinator network parameters do not match stored "
                                    + "parameters: coordinator channel=%d panId=0x%04X "
                                    + "extendedPanId=0x%016X; stored channel=%d "
                                    + "panId=0x%04X extendedPanId=0x%016X",
                            current.channel(), current.panId(),
                            current.extendedPanId(), stored.channel(),
                            stored.panId(), stored.extendedPanId()));
        }
        return stored;
    }

    /**
     * Two-tier channel selection (§3.13): if every primary channel is congested,
     * pick the least-congested fallback channel (WARN
     * {@code zigbee.channel_fallback_tier}); if the primary channels have comparable
     * interference, default to 15; otherwise pick the least-congested primary.
     *
     * @param energyByChannel max RSSI (dBm) per channel from the energy scan
     * @return the selected channel
     */
    int selectChannel(Map<Integer, Integer> energyByChannel) {
        boolean allPrimaryCongested = PRIMARY_CHANNELS.stream()
                .allMatch(c -> energyDbm(energyByChannel, c) > CONGESTION_THRESHOLD_DBM);
        if (allPrimaryCongested) {
            int fallback = leastCongested(FALLBACK_CHANNELS, energyByChannel);
            log.warn("zigbee.channel_fallback_tier: all primary channels {} exceed "
                            + "{} dBm; selected fallback channel {} — Aqara/Xiaomi "
                            + "devices may refuse to pair on channels above 20",
                    PRIMARY_CHANNELS, CONGESTION_THRESHOLD_DBM, fallback);
            return fallback;
        }
        int min = PRIMARY_CHANNELS.stream()
                .mapToInt(c -> energyDbm(energyByChannel, c)).min().orElseThrow();
        int max = PRIMARY_CHANNELS.stream()
                .mapToInt(c -> energyDbm(energyByChannel, c)).max().orElseThrow();
        if (max - min <= COMPARABLE_DELTA_DBM) {
            return PRIMARY_CHANNELS.get(0); // channel 15, the §3.13 default
        }
        return leastCongested(PRIMARY_CHANNELS, energyByChannel);
    }

    private static int leastCongested(List<Integer> channels,
            Map<Integer, Integer> energyByChannel) {
        int best = channels.get(0);
        int bestEnergy = Integer.MAX_VALUE;
        for (int channel : channels) {
            int energy = energyDbm(energyByChannel, channel);
            if (energy < bestEnergy) {
                bestEnergy = energy;
                best = channel;
            }
        }
        return best;
    }

    private static int energyDbm(Map<Integer, Integer> energyByChannel, int channel) {
        Integer energy = energyByChannel.get(channel);
        // A channel the scan did not report is treated as quiet: prefer measured data
        // but never fail formation over a sparse scan result.
        return energy == null ? Integer.MIN_VALUE : energy;
    }

    private static List<Integer> allChannels() {
        return java.util.stream.IntStream.rangeClosed(11, 26).boxed().toList();
    }

    private long nonZeroRandomLong() {
        long value;
        do {
            value = random.nextLong();
        } while (value == 0);
        return value;
    }
}

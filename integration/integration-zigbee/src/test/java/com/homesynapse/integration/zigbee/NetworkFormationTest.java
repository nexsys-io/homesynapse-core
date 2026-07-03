/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.PermanentIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NetworkFormation} tests (§3.13, D-M92-7): form→store→resume round-trip,
 * the two-tier channel preference order, the INV-SE-03 key-custody assertions, and
 * the mismatch path. Log capture is unavailable in this module (no logging binding —
 * the M9.1 T15 precedent), so custody is asserted on every OBSERVABLE surface: the
 * recording store holds the key; no exception message and no {@code toString()}
 * carries it; the coordinator ops seam is the only other legal recipient.
 */
class NetworkFormationTest {

    private static final String KEY_REF = NetworkFormation.NETWORK_KEY_REF;

    private FakeCoordinatorOps ops;
    private RecordingNetworkParameterStore store;
    private NetworkFormation formation;

    @BeforeEach
    void setUp() {
        ops = new FakeCoordinatorOps();
        store = new RecordingNetworkParameterStore();
        formation = new NetworkFormation(ops, store, new SecureRandom());
    }

    // ------------------------------------------------------------------
    // Two-tier channel preference (§3.13)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all primary channels comparable → channel 15 (the §3.13 default)")
    void selectChannel_primaryComparable_prefers15() {
        Map<Integer, Integer> energy = uniformEnergy(-85);

        assertThat(formation.selectChannel(energy)).isEqualTo(15);
    }

    @Test
    @DisplayName("a clearly quieter primary channel wins")
    void selectChannel_quietestPrimaryWins() {
        Map<Integer, Integer> energy = uniformEnergy(-85);
        energy.put(15, -60); // congested
        energy.put(20, -90); // quietest
        energy.put(11, -80);

        assertThat(formation.selectChannel(energy)).isEqualTo(20);
    }

    @Test
    @DisplayName("all primary channels congested → least-congested fallback "
            + "channel (21-26, the Aqara-risk tier)")
    void selectChannel_allPrimaryCongested_fallback() {
        Map<Integer, Integer> energy = uniformEnergy(-60); // everything loud
        energy.put(24, -95); // quietest fallback

        int selected = formation.selectChannel(energy);

        assertThat(selected).isEqualTo(24);
        assertThat(NetworkFormation.FALLBACK_CHANNELS).contains(selected);
    }

    @Test
    @DisplayName("channels missing from the scan are treated as quiet, "
            + "deterministically")
    void selectChannel_missingScanData() {
        Map<Integer, Integer> energy = new HashMap<>();
        energy.put(15, -60); // the only measured primary is congested

        // 20 and 11 are unmeasured (treated quiet); 20 precedes 11 in the tier.
        assertThat(formation.selectChannel(energy)).isEqualTo(20);
    }

    // ------------------------------------------------------------------
    // Formation and custody
    // ------------------------------------------------------------------

    @Test
    @DisplayName("INV-SE-03: the SecureRandom key lands in the store and the "
            + "coordinator seam ONLY — never in parameters, toString, or messages")
    void form_keyCustody() {
        NetworkParameters formed = formation.form();

        byte[] storedKey = store.storedKey(KEY_REF);
        assertThat(storedKey).isNotNull().hasSize(16);
        assertThat(ops.receivedKey).isEqualTo(storedKey);

        String keyHex = hex(storedKey);
        assertThat(formed.networkKeyRef()).isEqualTo(KEY_REF);
        // H5 (review hardening): check BOTH hex casings — a %02X-formatted leak
        // must not slip past a lowercase-only assertion.
        assertThat(formed.toString().replace(" ", ""))
                .doesNotContain(keyHex)
                .doesNotContain(keyHex.toUpperCase());
        // The parameters record structurally CANNOT carry the key: its only
        // key-related component is the opaque reference.
        assertThat(formed.networkKeyRef())
                .doesNotContain(keyHex)
                .doesNotContain(keyHex.toUpperCase());
    }

    @Test
    @DisplayName("form(): key stored BEFORE coordinator formation; parameters "
            + "persisted only AFTER coordinator success")
    void form_persistOrdering() {
        ops.formNetworkFailure = new EzspCommandException("formNetwork rejected", 0x70);
        NetworkParameters params =
                new NetworkParameters(15, 0x1234, 0xAAL, KEY_REF);

        assertThatThrownBy(() -> formation.form(params))
                .isInstanceOf(EzspCommandException.class);

        // A stored-but-unused key is harmless; stored-but-unformed parameters
        // would corrupt resume — so the key may exist, the parameters must not.
        assertThat(store.load()).isEmpty();
        assertThat(store.savedParameters()).isEmpty();
        assertThat(store.storedKey(KEY_REF)).isNotNull();
    }

    @Test
    @DisplayName("form() with an existing key reference reuses the stored key — "
            + "no fresh generation")
    void form_existingKeyReused() {
        byte[] knownKey = new byte[16];
        for (int i = 0; i < 16; i++) {
            knownKey[i] = (byte) (0x60 + i);
        }
        store.saveNetworkKey(KEY_REF, knownKey);
        CountingSecureRandom counting = new CountingSecureRandom();
        formation = new NetworkFormation(ops, store, counting);

        formation.form(new NetworkParameters(15, 0x1234, 0xAAL, KEY_REF));

        assertThat(ops.receivedKey).isEqualTo(knownKey);
        assertThat(counting.nextBytesCalls).isZero();
    }

    @Test
    @DisplayName("form→store→resume round-trip yields operationally identical "
            + "parameters")
    void form_resume_roundTrip() throws Exception {
        NetworkParameters formed = formation.form();
        ops.joined = true;
        ops.current = new NetworkFormation.CoordinatorOps.CoordinatorNetwork(
                true, formed.channel(), formed.panId(), formed.extendedPanId());

        NetworkParameters resumed =
                new NetworkFormation(ops, store, new SecureRandom()).resume();

        assertThat(resumed.channel()).isEqualTo(formed.channel());
        assertThat(resumed.panId()).isEqualTo(formed.panId());
        assertThat(resumed.extendedPanId()).isEqualTo(formed.extendedPanId());
        assertThat(resumed.networkKeyRef()).isEqualTo(formed.networkKeyRef());
    }

    // ------------------------------------------------------------------
    // Resume paths (§3.13)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resume without stored parameters is a caller defect")
    void resume_noStored_rejected() {
        assertThatThrownBy(() -> formation.resume())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("form a network");
    }

    @Test
    @DisplayName("coordinator lost its network: deterministic re-formation from "
            + "the STORED parameters and key")
    void resume_coordinatorLost_restores() throws Exception {
        NetworkParameters stored =
                new NetworkParameters(20, 0x2B84, 0xBEEFL, KEY_REF);
        byte[] knownKey = new byte[16];
        knownKey[0] = 0x7F;
        store.seed(stored);
        store.saveNetworkKey(KEY_REF, knownKey);
        ops.joined = false; // NVRAM wiped

        NetworkParameters resumed = formation.resume();

        assertThat(resumed).isEqualTo(stored);
        assertThat(ops.formedChannel).isEqualTo(20);
        assertThat(ops.formedPanId).isEqualTo(0x2B84);
        assertThat(ops.receivedKey).isEqualTo(knownKey);
    }

    @Test
    @DisplayName("coordinator lost its network AND the key is gone: permanent")
    void resume_lostNetworkMissingKey_permanent() {
        store.seed(new NetworkParameters(20, 0x2B84, 0xBEEFL, KEY_REF));
        ops.joined = false;

        assertThatThrownBy(() -> formation.resume())
                .isInstanceOf(PermanentIntegrationException.class)
                .satisfies(e -> assertThat(
                        ((PermanentIntegrationException) e).errorCode())
                        .isEqualTo("zigbee.network_key_missing"));
    }

    @Test
    @DisplayName("§3.13 mismatch path: the coordinator is on a DIFFERENT network — "
            + "permanent failure naming both parameter sets, never the key")
    void resume_mismatch_permanent() {
        NetworkParameters stored =
                new NetworkParameters(15, 0x1A62, 0x1234L, KEY_REF);
        byte[] key = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        store.seed(stored);
        store.saveNetworkKey(KEY_REF, key);
        ops.joined = true;
        ops.current = new NetworkFormation.CoordinatorOps.CoordinatorNetwork(
                true, 20, 0x7777, 0x9999L);

        assertThatThrownBy(() -> formation.resume())
                .isInstanceOf(PermanentIntegrationException.class)
                .hasMessageContaining("channel=20")
                .hasMessageContaining("channel=15")
                .satisfies(e -> {
                    assertThat(((PermanentIntegrationException) e).errorCode())
                            .isEqualTo("zigbee.network_parameter_mismatch");
                    // H5: both casings — see form_keyCustody.
                    assertThat(e.getMessage())
                            .doesNotContain(hex(key))
                            .doesNotContain(hex(key).toUpperCase());
                });
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Map<Integer, Integer> uniformEnergy(int dbm) {
        Map<Integer, Integer> energy = new HashMap<>();
        for (int channel = 11; channel <= 26; channel++) {
            energy.put(channel, dbm);
        }
        return energy;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static final class FakeCoordinatorOps
            implements NetworkFormation.CoordinatorOps {

        Map<Integer, Integer> scanResult = new HashMap<>();
        boolean joined;
        CoordinatorNetwork current = new CoordinatorNetwork(false, 0, 0, 0L);
        RuntimeException formNetworkFailure;

        byte[] receivedKey;
        int formedChannel = -1;
        int formedPanId = -1;

        private FakeCoordinatorOps() {
            for (int channel = 11; channel <= 26; channel++) {
                scanResult.put(channel, -85);
            }
        }

        @Override
        public Map<Integer, Integer> energyScan(List<Integer> channels) {
            return scanResult;
        }

        @Override
        public void formNetwork(int channel, int panId, long extendedPanId,
                byte[] networkKey) {
            if (formNetworkFailure != null) {
                throw formNetworkFailure;
            }
            formedChannel = channel;
            formedPanId = panId;
            receivedKey = networkKey.clone();
        }

        @Override
        public boolean resumeFromNvram() {
            return joined;
        }

        @Override
        public CoordinatorNetwork currentNetwork() {
            return current;
        }
    }

    /** SecureRandom subclass counting key-generation calls. */
    private static final class CountingSecureRandom extends SecureRandom {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        int nextBytesCalls;

        @Override
        public void nextBytes(byte[] bytes) {
            nextBytesCalls++;
            super.nextBytes(bytes);
        }
    }
}

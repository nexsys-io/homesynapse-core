/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.PermanentIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NetworkFormation} tests (§3.13, D-M92-7): form→store→resume round-trip,
 * the two-tier channel preference order, the pinned-channel formation (M9.4-TCJ
 * §B), the INV-SE-03 key-custody assertions, and the mismatch path. This class
 * predates the module's test-scoped logback binding (M9.4b) and stays log-free by
 * design: custody is asserted on every OBSERVABLE surface — the recording store
 * holds the key; no exception message and no {@code toString()} carries it; the
 * coordinator ops seam is the only other legal recipient. The
 * {@code zigbee.channel_pinned} INFO is asserted at the adapter level
 * ({@link ZigbeeChannelPinTest}).
 */
class NetworkFormationTest {

    private static final String KEY_REF = NetworkFormation.NETWORK_KEY_REF;

    /** The independent well-known-key literal (never the production constant). */
    private static final byte[] WELL_KNOWN_TC_LINK_KEY =
            "ZigBeeAlliance09".getBytes(StandardCharsets.US_ASCII);

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
    // Pinned-channel formation (M9.4-TCJ §B)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("form(pinnedChannel) forms directly on the pinned channel — the "
            + "energy scan never runs, identity and custody are unchanged")
    void form_pinnedChannel_skipsScan() {
        NetworkParameters formed = formation.form(20);

        assertThat(ops.scanCalls).as("the energy scan is skipped").isZero();
        assertThat(ops.formedChannel).isEqualTo(20);
        assertThat(formed.channel()).isEqualTo(20);
        assertThat(store.load()).as("the pinned formation persists").contains(formed);
        assertThat(store.storedKey(KEY_REF))
                .as("key custody is the shared form path's").isNotNull().hasSize(16);
    }

    @Test
    @DisplayName("form(pinnedChannel) rejects a channel outside 11-26 before any "
            + "coordinator I/O (the adapter validates first; this is the defensive floor)")
    void form_pinnedChannel_outOfRange_rejected() {
        assertThatThrownBy(() -> formation.form(10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11-26");
        assertThatThrownBy(() -> formation.form(27))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11-26");

        assertThat(ops.scanCalls).isZero();
        assertThat(ops.formedChannel).as("no formation was attempted").isEqualTo(-1);
        assertThat(store.load()).isEmpty();
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
            + "no fresh generation (custody fully seeded: key + TCLK seed, M9.6-SEED)")
    void form_existingKeyReused() {
        byte[] knownKey = new byte[16];
        for (int i = 0; i < 16; i++) {
            knownKey[i] = (byte) (0x60 + i);
        }
        store.saveNetworkKey(KEY_REF, knownKey);
        store.seedTclkSeed(new byte[16]); // full custody — no seed mint either
        CountingSecureRandom counting = new CountingSecureRandom();
        formation = new NetworkFormation(ops, store, counting);

        formation.form(new NetworkParameters(15, 0x1234, 0xAAL, KEY_REF));

        assertThat(ops.receivedKey).isEqualTo(knownKey);
        assertThat(counting.nextBytesCalls).isZero();
    }

    // ------------------------------------------------------------------
    // Generated-seed TCLK custody (M9.6-SEED, SD-5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("M9.6-SEED DP-1/DP-4: fresh custody mints key AND seed in ONE "
            + "atomic batch, durable BEFORE the coordinator forms; the seed rides "
            + "the ops seam and nothing observable leaks it")
    void form_freshCustody_mintsSeedAtomicallyBeforeForm() {
        ops.journal = store.custodyOperations();

        NetworkParameters formed = formation.form();

        byte[] seed = store.storedTclkSeed();
        assertThat(seed).isNotNull().hasSize(16);
        assertThat(ops.receivedTcLinkKey)
                .as("the formation arm passes the minted seed, never the "
                        + "well-known root")
                .isEqualTo(seed)
                .isNotEqualTo(WELL_KNOWN_TC_LINK_KEY);
        assertThat(seed)
                .as("seed and network key are distinct materials")
                .isNotEqualTo(store.storedKey(KEY_REF));
        // ONE never-torn batch, then formation, then parameters — the DP-1
        // ordering doctrine extended verbatim to every newly-minted secret.
        assertThat(store.custodyOperations())
                .containsExactly("saveNetworkKeyAndTclkSeed", "formNetwork", "save");
        // H5 discipline: both hex casings on every observable surface.
        String seedHex = hex(seed);
        assertThat(formed.toString().replace(" ", ""))
                .doesNotContain(seedHex)
                .doesNotContain(seedHex.toUpperCase());
    }

    @Test
    @DisplayName("M9.6-SEED DP-1: an existing seed is REUSED — no re-mint, no "
            + "second persist")
    void form_existingSeed_reusedNeverReminted() {
        byte[] knownKey = new byte[16];
        byte[] knownSeed = new byte[16];
        for (int i = 0; i < 16; i++) {
            knownKey[i] = (byte) (0x60 + i);
            knownSeed[i] = (byte) (0xA0 + i);
        }
        store.seedNetworkKey(KEY_REF, knownKey);
        store.seedTclkSeed(knownSeed);
        CountingSecureRandom counting = new CountingSecureRandom();
        formation = new NetworkFormation(ops, store, counting);
        ops.journal = store.custodyOperations();

        formation.form(new NetworkParameters(15, 0x1234, 0xAAL, KEY_REF));

        assertThat(ops.receivedTcLinkKey).isEqualTo(knownSeed);
        assertThat(counting.nextBytesCalls).as("nothing is re-minted").isZero();
        assertThat(store.custodyOperations())
                .as("no custody persist happens for fully-present material")
                .containsExactly("formNetwork", "save");
    }

    @Test
    @DisplayName("M9.6-SEED DP-4 asymmetric custody: key present + seed absent — "
            + "only the missing seed is minted, persisted via a SINGLE save")
    void form_keyPresentSeedAbsent_mintsOnlySeed() {
        byte[] knownKey = new byte[16];
        for (int i = 0; i < 16; i++) {
            knownKey[i] = (byte) (0x60 + i);
        }
        store.seedNetworkKey(KEY_REF, knownKey);
        CountingSecureRandom counting = new CountingSecureRandom();
        formation = new NetworkFormation(ops, store, counting);
        ops.journal = store.custodyOperations();

        formation.form(new NetworkParameters(15, 0x1234, 0xAAL, KEY_REF));

        assertThat(ops.receivedKey).isEqualTo(knownKey);
        assertThat(counting.nextBytesCalls).as("one mint: the seed").isEqualTo(1);
        assertThat(store.storedTclkSeed()).isNotNull().hasSize(16);
        assertThat(store.custodyOperations())
                .containsExactly("saveTclkSeed", "formNetwork", "save");
    }

    @Test
    @DisplayName("M9.6-SEED DP-4 asymmetric custody: seed present + key absent — "
            + "only the missing key is minted, persisted via a SINGLE save")
    void form_seedPresentKeyAbsent_mintsOnlyKey() {
        byte[] knownSeed = new byte[16];
        for (int i = 0; i < 16; i++) {
            knownSeed[i] = (byte) (0xA0 + i);
        }
        store.seedTclkSeed(knownSeed);
        CountingSecureRandom counting = new CountingSecureRandom();
        formation = new NetworkFormation(ops, store, counting);
        ops.journal = store.custodyOperations();

        formation.form(new NetworkParameters(15, 0x1234, 0xAAL, KEY_REF));

        assertThat(ops.receivedTcLinkKey).isEqualTo(knownSeed);
        assertThat(counting.nextBytesCalls).as("one mint: the key").isEqualTo(1);
        assertThat(store.storedKey(KEY_REF)).isNotNull().hasSize(16);
        assertThat(store.custodyOperations())
                .containsExactly("saveNetworkKey", "formNetwork", "save");
    }

    @Test
    @DisplayName("M9.6-SEED DP-7: restore with a seed IN CUSTODY re-forms with "
            + "the CUSTODY seed (AS-FORMED reproduction)")
    void resume_restoreWithSeed_passesCustodySeed() throws Exception {
        NetworkParameters stored =
                new NetworkParameters(20, 0x2B84, 0xBEEFL, KEY_REF);
        byte[] knownKey = new byte[16];
        knownKey[0] = 0x7F;
        byte[] knownSeed = new byte[16];
        for (int i = 0; i < 16; i++) {
            knownSeed[i] = (byte) (0xA0 + i);
        }
        store.seed(stored);
        store.seedNetworkKey(KEY_REF, knownKey);
        store.seedTclkSeed(knownSeed);
        ops.joined = false; // NVRAM wiped

        formation.resume();

        assertThat(ops.receivedTcLinkKey).isEqualTo(knownSeed);
        assertThat(ops.receivedKey).isEqualTo(knownKey);
    }

    @Test
    @DisplayName("M9.6-SEED DP-7: restore WITHOUT a seed passes the well-known "
            + "root (AS-FORMED — the pre-SEED bench custody) and NEVER mints")
    void resume_restoreWithoutSeed_passesWellKnown_neverMints() throws Exception {
        store.seed(new NetworkParameters(20, 0x2B84, 0xBEEFL, KEY_REF));
        store.seedNetworkKey(KEY_REF, new byte[16]);
        CountingSecureRandom counting = new CountingSecureRandom();
        formation = new NetworkFormation(ops, store, counting);
        ops.joined = false; // NVRAM wiped

        formation.resume();

        assertThat(ops.receivedTcLinkKey).isEqualTo(WELL_KNOWN_TC_LINK_KEY);
        assertThat(counting.nextBytesCalls).as("never a mint on restore").isZero();
        assertThat(store.storedTclkSeed()).as("restore writes no seed").isNull();
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
    @DisplayName("coordinator lost its network AND the key is gone: permanent — "
            + "the message never carries the custody seed (INV-SE-03)")
    void resume_lostNetworkMissingKey_permanent() {
        store.seed(new NetworkParameters(20, 0x2B84, 0xBEEFL, KEY_REF));
        byte[] seed = new byte[16];
        for (int i = 0; i < 16; i++) {
            seed[i] = (byte) (0xA0 + i);
        }
        store.seedTclkSeed(seed);
        ops.joined = false;

        assertThatThrownBy(() -> formation.resume())
                .isInstanceOf(PermanentIntegrationException.class)
                .satisfies(e -> {
                    assertThat(((PermanentIntegrationException) e).errorCode())
                            .isEqualTo("zigbee.network_key_missing");
                    // H5: both casings — the seed never rides an exception.
                    assertThat(e.getMessage())
                            .doesNotContain(hex(seed))
                            .doesNotContain(hex(seed).toUpperCase());
                });
    }

    @Test
    @DisplayName("§3.13 mismatch path: the coordinator is on a DIFFERENT network — "
            + "permanent failure naming both parameter sets, never the key")
    void resume_mismatch_permanent() {
        NetworkParameters stored =
                new NetworkParameters(15, 0x1A62, 0x1234L, KEY_REF);
        byte[] key = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] seed = new byte[16];
        for (int i = 0; i < 16; i++) {
            seed[i] = (byte) (0xA0 + i);
        }
        store.seed(stored);
        store.saveNetworkKey(KEY_REF, key);
        store.seedTclkSeed(seed);
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
                    // H5: both casings — see form_keyCustody; the seed takes
                    // the key's exact discipline (M9.6-SEED, INV-SE-03).
                    assertThat(e.getMessage())
                            .doesNotContain(hex(key))
                            .doesNotContain(hex(key).toUpperCase())
                            .doesNotContain(hex(seed))
                            .doesNotContain(hex(seed).toUpperCase());
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
        byte[] receivedTcLinkKey;
        int formedChannel = -1;
        int formedPanId = -1;
        int scanCalls;
        /** Shared with the store's custody journal for cross-object ordering. */
        List<String> journal;

        private FakeCoordinatorOps() {
            for (int channel = 11; channel <= 26; channel++) {
                scanResult.put(channel, -85);
            }
        }

        @Override
        public Map<Integer, Integer> energyScan(List<Integer> channels) {
            scanCalls++;
            return scanResult;
        }

        @Override
        public void formNetwork(int channel, int panId, long extendedPanId,
                byte[] networkKey, byte[] trustCenterLinkKey) {
            if (journal != null) {
                journal.add("formNetwork");
            }
            if (formNetworkFailure != null) {
                throw formNetworkFailure;
            }
            formedChannel = channel;
            formedPanId = panId;
            receivedKey = networkKey.clone();
            receivedTcLinkKey = trustCenterLinkKey.clone();
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

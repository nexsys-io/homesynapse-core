/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EzspCoordinatorProtocol} tests over the full deterministic stack
 * (protocol → transport → ASH session → fake channel → {@link FakeNcp}):
 * negotiation-first ordering and the AMD-96 band tiers, single-in-flight
 * serialization under concurrent virtual-thread submitters (D-M92-5), per-command
 * timeouts, the 30 s-idle watchdog keepalive, formation/resume through the storage
 * seam, and the D-M92-6 stub inventory.
 */
class EzspProtocolTest {

    private static final String KEY_REF = NetworkFormation.NETWORK_KEY_REF;

    private TestClock clock;
    private FakeSerialByteChannel channel;
    private FakeNcp ncp;
    private EzspAshTransport transport;
    private RecordingNetworkParameterStore store;
    private EzspCoordinatorProtocol protocol;
    private int ncpVersion;

    private void connect(int version) {
        ncpVersion = version;
        clock = TestClock.createDefault();
        channel = new FakeSerialByteChannel(clock);
        ncp = new FakeNcp();
        channel.onWrite(ncp);
        ncp.onEzspCommand(this::defaultHandler);
        transport = new EzspAshTransport(clock, arg -> channel);
        transport.open(new Object());
        store = new RecordingNetworkParameterStore();
        protocol = new EzspCoordinatorProtocol(transport, store, clock);
    }

    /**
     * Negotiates the session, converting the checked terminal exception to a
     * test failure (the direct-call sites expect negotiation to succeed).
     */
    private void startSessionOrFail() {
        try {
            protocol.startSession();
        } catch (PermanentIntegrationException e) {
            throw new AssertionError("startSession failed unexpectedly", e);
        }
    }

    // ------------------------------------------------------------------
    // Version negotiation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("negotiation runs first, in the legacy format, opening at v13")
    void negotiation_v13_acceptedFirstCommand() {
        connect(13);

        startSessionOrFail();

        assertThat(protocol.negotiatedVersion()).isEqualTo(13);
        List<byte[]> commands = ncp.receivedEzspCommands();
        assertThat(commands).hasSize(1); // matched: no renegotiation
        byte[] first = commands.get(0);
        assertThat(first[1]).isEqualTo((byte) 0x00); // legacy frame control
        assertThat(first[2]).isEqualTo((byte) 0x00); // legacy frame ID = version
        assertThat(first[3]).isEqualTo((byte) 13);
    }

    @Test
    @DisplayName("v14: above the narrowed band (M9.4 consolidated amendment) — PIE naming the "
            + "unknown dialect + the AMD-96 reflash contingency")
    void negotiation_v14_permanentUntilDialectCharacterized() {
        // The v14 0x0034/0x0045 dialect is uncharacterized on owned silicon (DP-d:
        // "partially deaf is a three-hour sniffer session, PIE is a one-line diagnosis").
        connect(14);

        assertThatThrownBy(() -> protocol.startSession())
                .isInstanceOf(PermanentIntegrationException.class)
                .hasMessageContaining("14")
                .hasMessageContaining("13-13")
                .hasMessageContaining("the frame dialect is unknown to this adapter")
                .hasMessageContaining("reflash");
    }

    @Test
    @DisplayName("v12: WARN zigbee.ezsp_legacy_version tier — proceeds best-effort")
    void negotiation_v12_proceedsBestEffort() {
        connect(12);

        startSessionOrFail();

        // The WARN itself is not capturable (no logging binding in this module —
        // the M9.1 T15 precedent); the tier's observable contract is: proceed.
        assertThat(protocol.negotiatedVersion()).isEqualTo(12);
        assertThat(protocol.ping()).isTrue();
    }

    @Test
    @DisplayName("v7: below the frame-format floor — PermanentIntegrationException")
    void negotiation_v7_permanent() {
        connect(7);

        assertThatThrownBy(() -> protocol.startSession())
                .isInstanceOf(PermanentIntegrationException.class)
                .hasMessageContaining("7")
                .hasMessageContaining("8");
    }

    @Test
    @DisplayName("v15: above the supported band — PermanentIntegrationException")
    void negotiation_v15_permanent() {
        connect(15);

        assertThatThrownBy(() -> protocol.startSession())
                .isInstanceOf(PermanentIntegrationException.class)
                .hasMessageContaining("band");
    }

    @Test
    @DisplayName("negotiation runs exactly once per session")
    void negotiation_exactlyOnce() {
        connect(13);

        startSessionOrFail();
        startSessionOrFail();

        assertThat(ncp.receivedEzspCommands()).hasSize(1);
    }

    @Test
    @DisplayName("resetSession enables renegotiation after a transport reopen "
            + "(the M9.1 relaunch discipline)")
    void resetSession_enablesRenegotiation() {
        connect(13);
        startSessionOrFail();
        assertThat(ncp.receivedEzspCommands()).hasSize(1);

        // The real reopen flow: transport closed and reopened (fresh ASH handshake,
        // decode back in legacy mode), THEN the protocol session resets.
        transport.close();
        transport.open(new Object());
        protocol.resetSession();

        // Commands are gated again until the session renegotiates…
        assertThatThrownBy(() -> protocol.ping())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startSession");
        assertThat(protocol.negotiatedVersion()).isEqualTo(-1);

        // …and startSession renegotiates from scratch (a second version exchange).
        startSessionOrFail();
        assertThat(protocol.negotiatedVersion()).isEqualTo(13);
        assertThat(ncp.receivedEzspCommands()).hasSize(2);
        assertThat(protocol.ping()).isTrue();
    }

    @Test
    @DisplayName("commands before startSession are rejected")
    void commandBeforeStart_rejected() {
        connect(13);

        assertThatThrownBy(() -> protocol.ping())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startSession");
    }

    @Test
    @DisplayName("the EZSP sequence byte wraps 0xFF -> 0x00 without a correlation "
            + "miss (review hardening H3 — 300 commands cross the boundary)")
    void sequence_wrapsMod256() {
        connect(13);
        startSessionOrFail();

        for (int i = 0; i < 300; i++) {
            assertThat(protocol.ping())
                    .as("ping %d must correlate across the sequence wrap", i)
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Command pipeline
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ping: true on nop response, false on nop timeout")
    void ping_trueOnResponse_falseOnTimeout() {
        connect(13);
        startSessionOrFail();
        assertThat(protocol.ping()).isTrue();

        ncp.onEzspCommand(command -> isLegacyVersion(command)
                ? defaultHandler(command) : null); // silence: every command times out

        assertThat(protocol.ping()).isFalse();
    }

    @Test
    @DisplayName("D-M92-5: single-in-flight under concurrent virtual-thread "
            + "submitters — the NCP never observes an overlapping command")
    void singleInFlight_underConcurrentSubmitters() throws InterruptedException {
        connect(13);
        startSessionOrFail();

        AtomicInteger successes = new AtomicInteger();
        List<Thread> submitters = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            submitters.add(Thread.ofVirtual().start(() -> {
                if (protocol.ping()) {
                    successes.incrementAndGet();
                }
            }));
        }
        for (Thread submitter : submitters) {
            submitter.join(5000);
            assertThat(submitter.isAlive()).isFalse();
        }

        assertThat(successes.get()).isEqualTo(8);
        assertThat(ncp.overlapDetected()).isFalse();
        assertThat(ncp.receivedEzspCommands()).hasSize(1 + 8); // version + 8 nops
    }

    @Test
    @DisplayName("per-command timeout surfaces the frame ID and elapsed time")
    void perCommandTimeout_surfacesFrameIdAndElapsed() {
        connect(13);
        startSessionOrFail();
        ncp.onEzspCommand(command -> isLegacyVersion(command)
                ? defaultHandler(command) : null);

        assertThatThrownBy(() -> protocol.permitJoin(60))
                .isInstanceOf(EzspCommandTimeoutException.class)
                .hasMessageContaining("0022")
                .hasMessageContaining("ms");
    }

    @Test
    @DisplayName("permitJoin encodes the duration byte and accepts status SUCCESS")
    void permitJoin_encodesDuration() {
        connect(13);
        startSessionOrFail();

        protocol.permitJoin(60);

        byte[] command = lastCommandWithFrameId(0x0022);
        assertThat(command).isNotNull();
        assertThat(extendedParameters(command)).containsExactly(0x3C);
    }

    @Test
    @DisplayName("permitJoin surfaces a non-success NCP status")
    void permitJoin_statusFailure() {
        connect(13);
        startSessionOrFail();
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command) && frameIdOf(command) == 0x0022) {
                return List.of(extendedResponse(command[0] & 0xFF, 0x0022,
                        new byte[] {(byte) 0xA8}));
            }
            return defaultHandler(command);
        });

        assertThatThrownBy(() -> protocol.permitJoin(60))
                .isInstanceOf(EzspCommandException.class)
                .hasMessageContaining("A8");
    }

    @Test
    @DisplayName("permitJoin validates the 0-254 range (Zigbee spec max)")
    void permitJoin_rangeValidation() {
        connect(13);
        startSessionOrFail();

        assertThatThrownBy(() -> protocol.permitJoin(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.permitJoin(255))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Watchdog keepalive
    // ------------------------------------------------------------------

    @Test
    @DisplayName("watchdog: nop() fires at 30 s idle, not one millisecond earlier")
    void watchdog_firesAt30sIdleOnly() {
        connect(13);
        startSessionOrFail();

        clock.advance(Duration.ofMillis(29_999));
        protocol.maybeSendKeepalive();
        assertThat(countCommands(0x0005)).isZero();

        clock.advance(Duration.ofMillis(1));
        protocol.maybeSendKeepalive();
        assertThat(countCommands(0x0005)).isEqualTo(1);
    }

    @Test
    @DisplayName("watchdog: a missed nop response feeds ASH-liveness accounting, "
            + "never a direct throw")
    void watchdog_missedResponse_feedsLiveness() {
        connect(13);
        startSessionOrFail();
        ncp.onEzspCommand(command -> isLegacyVersion(command)
                ? defaultHandler(command) : null);
        clock.advance(Duration.ofMillis(30_000));

        protocol.maybeSendKeepalive(); // must not throw

        assertThat(protocol.keepaliveMisses()).isEqualTo(1);
    }

    @Test
    @DisplayName("watchdog: never interleaves an in-flight command (tryLock)")
    void watchdog_neverInterleavesInFlightCommand() throws InterruptedException {
        connect(13);
        startSessionOrFail();
        clock.advance(Duration.ofMillis(31_000)); // idle threshold already passed

        CountDownLatch inHandler = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command) && frameIdOf(command) == 0x0022) {
                inHandler.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(extendedResponse(command[0] & 0xFF, 0x0022,
                        statusBytes(0)));
            }
            return defaultHandler(command);
        });

        Thread commandThread =
                Thread.ofVirtual().start(() -> protocol.permitJoin(60));
        assertThat(inHandler.await(5, TimeUnit.SECONDS)).isTrue();

        protocol.maybeSendKeepalive(); // in-flight command holds the pipeline lock

        release.countDown();
        commandThread.join(5000);
        assertThat(commandThread.isAlive()).isFalse();
        assertThat(countCommands(0x0005)).isZero(); // no nop interleaved
    }

    // ------------------------------------------------------------------
    // Formation / resume through the storage seam
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formNetwork: security state (TC link key + custody key) then "
            + "formation struct, then parameter persistence")
    void formNetwork_endToEnd() {
        connect(13);
        startSessionOrFail();
        NetworkParameters params =
                new NetworkParameters(15, 0x1A62, 0x00124B0012345678L, KEY_REF);

        protocol.formNetwork(params);

        byte[] security = lastCommandWithFrameId(0x0068);
        assertThat(security).isNotNull();
        byte[] struct = extendedParameters(security);
        assertThat(struct).hasSize(43);
        // Bitmask 0x1B04 LE (HAVE_PRECONFIGURED_KEY | HAVE_NETWORK_KEY |
        // TRUST_CENTER_GLOBAL_LINK_KEY | REQUIRE_ENCRYPTED_KEY |
        // NO_FRAME_COUNTER_RESET — the bellows formation baseline).
        assertThat(struct[0]).isEqualTo((byte) 0x04); // bitmask LE low
        assertThat(struct[1]).isEqualTo((byte) 0x1B); // bitmask LE high
        // Independent ZigBeeAlliance09 ASCII literal — deliberately NOT the
        // production constant, so a corrupted constant cannot self-confirm.
        assertThat(Arrays.copyOfRange(struct, 2, 18)).containsExactly(
                0x5A, 0x69, 0x67, 0x42, 0x65, 0x65, 0x41, 0x6C,
                0x6C, 0x69, 0x61, 0x6E, 0x63, 0x65, 0x30, 0x39);
        byte[] storedKey = store.storedKey(KEY_REF);
        assertThat(storedKey).isNotNull().hasSize(16);
        assertThat(Arrays.copyOfRange(struct, 18, 34)).isEqualTo(storedKey);

        byte[] form = lastCommandWithFrameId(0x001E);
        assertThat(form).isNotNull();
        byte[] network = extendedParameters(form);
        assertThat(network).hasSize(20);
        assertThat(network[8]).isEqualTo((byte) 0x62);  // panId LE low
        assertThat(network[9]).isEqualTo((byte) 0x1A);  // panId LE high
        assertThat(network[11]).isEqualTo((byte) 15);   // radioChannel
        assertThat(network[16]).isEqualTo((byte) 0x00); // channels = 1 << 15
        assertThat(network[17]).isEqualTo((byte) 0x80);

        assertThat(store.load()).contains(params);
    }

    @Test
    @DisplayName("formNetworkAutomatically: energy scan drives two-tier channel "
            + "selection (quietest primary wins)")
    void formNetworkAutomatically_scanSelectsChannel() {
        connect(13);
        startSessionOrFail();
        ncp.onEzspCommand(this::scanCapableHandler);

        NetworkParameters formed = protocol.formNetworkAutomatically();

        // 15/-60 and 11/-60 are congested; 20/-95 is the quietest primary.
        assertThat(formed.channel()).isEqualTo(20);
        byte[] form = lastCommandWithFrameId(0x001E);
        assertThat(form).isNotNull();
        assertThat(extendedParameters(form)[11]).isEqualTo((byte) 20);
        assertThat(store.load()).contains(formed);
    }

    @Test
    @DisplayName("resumeNetwork: coordinator NVRAM restored, parameters match")
    void resumeNetwork_restored() {
        connect(13);
        startSessionOrFail();
        NetworkParameters params =
                new NetworkParameters(15, 0x1A62, 0x00124B0012345678L, KEY_REF);
        store.seed(params);

        protocol.resumeNetwork(); // default handler reports a matching network
    }

    // resumeNetwork_restored_v14WideStatus was DELETED at M9.4a (format #12 declared
    // delta): it reached the wide-status seam only through v14 negotiation acceptance,
    // which the narrowed band now rejects. The H4 wide-struct offset coverage survives
    // at the codec seam (EzspCodecTest.statusSeam_v14_fourByteLittleEndian), which the
    // band narrowing deliberately leaves untouched — Wave-2 recharacterizes on silicon.

    /** Seeds a stored network + an NCP reporting a DIFFERENT live network. */
    private void mismatchScenario() {
        connect(13);
        startSessionOrFail();
        NetworkParameters params =
                new NetworkParameters(15, 0x1A62, 0x00124B0012345678L, KEY_REF);
        store.seed(params);
        store.saveNetworkKey(KEY_REF, new byte[16]);
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command) && frameIdOf(command) == 0x0028) {
                return List.of(extendedResponse(command[0] & 0xFF, 0x0028,
                        networkParametersStruct(20, 0x7777, 0x0BADCAFEL)));
            }
            return defaultHandler(command);
        });
    }

    @Test
    @DisplayName("resumeStored (the adapter path): coordinator on a DIFFERENT "
            + "network — permanent mismatch failure as the CHECKED type, no key "
            + "material in the message (INV-SE-03)")
    void resumeStored_mismatch_permanentChecked() {
        mismatchScenario();

        assertThatThrownBy(() -> protocol.resumeStored())
                .isInstanceOf(PermanentIntegrationException.class)
                .hasMessageContaining("channel=20")
                .hasMessageContaining("channel=15")
                .satisfies(e -> assertThat(
                        ((PermanentIntegrationException) e).errorCode())
                        .isEqualTo("zigbee.network_parameter_mismatch"));
    }

    @Test
    @DisplayName("resumeNetwork (the frozen no-throws surface): the same permanent "
            + "classification arrives unchecked, cause-chained")
    void resumeNetwork_mismatch_wrappedOnFrozenSurface() {
        mismatchScenario();

        assertThatThrownBy(() -> protocol.resumeNetwork())
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(PermanentIntegrationException.class)
                .hasMessageContaining("channel=20");
    }

    @Test
    @DisplayName("resumeNetwork: coordinator lost its network — deterministic "
            + "re-formation from the STORED parameters and key")
    void resumeNetwork_coordinatorLost_reforms() {
        connect(13);
        startSessionOrFail();
        NetworkParameters params =
                new NetworkParameters(15, 0x1A62, 0x00124B0012345678L, KEY_REF);
        byte[] knownKey = new byte[16];
        for (int i = 0; i < 16; i++) {
            knownKey[i] = (byte) (0x40 + i);
        }
        store.seed(params);
        store.saveNetworkKey(KEY_REF, knownKey);
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command) && frameIdOf(command) == 0x0017) {
                return List.of(extendedResponse(command[0] & 0xFF, 0x0017,
                        new byte[] {(byte) 0x93})); // EMBER_NOT_JOINED
            }
            return defaultHandler(command);
        });

        protocol.resumeNetwork();

        byte[] security = lastCommandWithFrameId(0x0068);
        assertThat(security).isNotNull();
        assertThat(Arrays.copyOfRange(extendedParameters(security), 18, 34))
                .isEqualTo(knownKey);
        byte[] form = lastCommandWithFrameId(0x001E);
        assertThat(extendedParameters(form)[11]).isEqualTo((byte) 15);
    }

    // ------------------------------------------------------------------
    // D-M92-6 stubs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D-M92-6 stub inventory: topologyScan names its completing milestone "
            + "(interview landed at M9.3; sendZclFrame filled at M9.4a — format #12 delta)")
    void stubs_nameCompletingMilestone() {
        connect(13);

        assertThatThrownBy(() -> protocol.topologyScan())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("M9.4");
    }

    // ------------------------------------------------------------------
    // sendZclFrame — the M9.4a command write path (§3.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sendZclFrame rides the bench-proven v13 unicast layout: tag u8 at "
            + "[14], length at [15], ZCL header + payload after [16]")
    void sendZclFrame_v13WireLayout() {
        connect(13);
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command)
                    && frameIdOf(command) == EzspCoordinatorProtocol.FRAME_SEND_UNICAST) {
                return List.of(extendedResponse(command[0] & 0xFF,
                        EzspCoordinatorProtocol.FRAME_SEND_UNICAST, statusBytes(0)));
            }
            return defaultHandler(command);
        });
        startSessionOrFail();

        boolean accepted = protocol.sendZclFrame(
                new ZclFrame(1, 11, 0x0006, 0x01, true, 0, new byte[0]), 0x260F);

        assertThat(accepted).isTrue();
        List<byte[]> commands = ncp.receivedEzspCommands();
        byte[] unicast = commands.get(commands.size() - 1);
        assertThat(frameIdOf(unicast))
                .isEqualTo(EzspCoordinatorProtocol.FRAME_SEND_UNICAST);
        byte[] parameters = extendedParameters(unicast);
        assertThat(parameters[0]).isEqualTo((byte) 0x00);          // DIRECT
        assertThat(parameters[1]).isEqualTo((byte) 0x0F);          // nwk LE lo
        assertThat(parameters[2]).isEqualTo((byte) 0x26);          // nwk LE hi
        assertThat(parameters[3]).isEqualTo((byte) 0x04);          // HA profile LE lo
        assertThat(parameters[4]).isEqualTo((byte) 0x01);          // HA profile LE hi
        assertThat(parameters[5]).isEqualTo((byte) 0x06);          // cluster LE lo
        assertThat(parameters[6]).isEqualTo((byte) 0x00);          // cluster LE hi
        assertThat(parameters[7]).isEqualTo((byte) 1);             // source EP
        assertThat(parameters[8]).isEqualTo((byte) 11);            // destination EP
        assertThat(parameters[14]).isEqualTo(parameters[13]);      // tag mirrors seq (u8)
        assertThat(parameters[15]).isEqualTo((byte) 3);            // ZCL length: fc+tsn+cmd
        assertThat(parameters[16]).isEqualTo((byte) 0x01);         // fc: cluster-specific
        assertThat(parameters[18]).isEqualTo((byte) 0x01);         // commandId: On
    }

    @Test
    @DisplayName("a non-zero sendUnicast status surfaces as false — the honest failure seam")
    void sendZclFrame_rejectedStatus_returnsFalse() {
        connect(13);
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command)
                    && frameIdOf(command) == EzspCoordinatorProtocol.FRAME_SEND_UNICAST) {
                return List.of(extendedResponse(command[0] & 0xFF,
                        EzspCoordinatorProtocol.FRAME_SEND_UNICAST,
                        statusBytes(0x66)));   // EMBER_NETWORK_DOWN-class rejection
            }
            return defaultHandler(command);
        });
        startSessionOrFail();

        assertThat(protocol.sendZclFrame(
                new ZclFrame(1, 11, 0x0006, 0x01, true, 0, new byte[0]), 0x260F))
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Handler plumbing
    // ------------------------------------------------------------------

    private List<byte[]> defaultHandler(byte[] command) {
        if (isLegacyVersion(command)) {
            return List.of(new byte[] {
                command[0], (byte) 0x80, 0x00, (byte) ncpVersion, 0x02, 0x30, 0x74
            });
        }
        int seq = command[0] & 0xFF;
        return switch (frameIdOf(command)) {
            case 0x0005 -> List.of(extendedResponse(seq, 0x0005, new byte[0]));
            case 0x0017, 0x001E, 0x0022, 0x0068 ->
                    List.of(extendedResponse(seq, frameIdOf(command),
                            statusBytes(0)));
            case 0x0018 -> List.of(extendedResponse(seq, 0x0018,
                    new byte[] {0x02})); // JOINED_NETWORK (1 byte on all versions)
            case 0x0028 -> List.of(extendedResponse(seq, 0x0028,
                    networkParametersStruct(15, 0x1A62, 0x00124B0012345678L)));
            default -> List.of();
        };
    }

    /** Default handler plus a scripted energy scan (v13 widths). */
    private List<byte[]> scanCapableHandler(byte[] command) {
        if (!isLegacyVersion(command) && frameIdOf(command) == 0x001A) {
            List<byte[]> frames = new ArrayList<>();
            frames.add(extendedResponse(command[0] & 0xFF, 0x001A, statusBytes(0)));
            for (int channel = 11; channel <= 26; channel++) {
                int rssi = channel == 20 ? -95 : -60;
                frames.add(new byte[] {
                    0x00, (byte) 0x90, 0x01, 0x48, 0x00,
                    (byte) channel, (byte) rssi
                });
            }
            frames.add(new byte[] {
                0x00, (byte) 0x90, 0x01, 0x1C, 0x00, 0x00, 0x00
            });
            return frames;
        }
        return defaultHandler(command);
    }

    private static boolean isLegacyVersion(byte[] command) {
        return command.length == 4 && command[1] == 0x00 && command[2] == 0x00;
    }

    private static int frameIdOf(byte[] extendedCommand) {
        return (extendedCommand[3] & 0xFF) | ((extendedCommand[4] & 0xFF) << 8);
    }

    private static byte[] extendedParameters(byte[] extendedCommand) {
        return Arrays.copyOfRange(extendedCommand, 5, extendedCommand.length);
    }

    private static byte[] extendedResponse(int seq, int frameId, byte[] parameters) {
        byte[] frame = new byte[5 + parameters.length];
        frame[0] = (byte) seq;
        frame[1] = (byte) 0x80;
        frame[2] = 0x01;
        frame[3] = (byte) (frameId & 0xFF);
        frame[4] = (byte) ((frameId >> 8) & 0xFF);
        System.arraycopy(parameters, 0, frame, 5, parameters.length);
        return frame;
    }

    private byte[] statusBytes(int status) {
        return ncpVersion >= 14
                ? new byte[] {(byte) status, 0x00, 0x00, 0x00}
                : new byte[] {(byte) status};
    }

    private byte[] networkParametersStruct(int channel, int panId,
            long extendedPanId) {
        byte[] parameters = new byte[statusBytes(0).length + 1 + 20];
        int offset = statusBytes(0).length; // status SUCCESS zeros
        parameters[offset] = 0x01; // nodeType: coordinator
        offset++;
        for (int i = 0; i < 8; i++) {
            parameters[offset + i] = (byte) ((extendedPanId >> (8 * i)) & 0xFF);
        }
        parameters[offset + 8] = (byte) (panId & 0xFF);
        parameters[offset + 9] = (byte) ((panId >> 8) & 0xFF);
        parameters[offset + 10] = 0x08;
        parameters[offset + 11] = (byte) channel;
        int channels = 1 << channel;
        parameters[offset + 16] = (byte) (channels & 0xFF);
        parameters[offset + 17] = (byte) ((channels >> 8) & 0xFF);
        parameters[offset + 18] = (byte) ((channels >> 16) & 0xFF);
        parameters[offset + 19] = (byte) ((channels >> 24) & 0xFF);
        return parameters;
    }

    private byte[] lastCommandWithFrameId(int frameId) {
        byte[] match = null;
        for (byte[] command : ncp.receivedEzspCommands()) {
            if (!isLegacyVersion(command) && frameIdOf(command) == frameId) {
                match = command;
            }
        }
        return match;
    }

    private long countCommands(int frameId) {
        return ncp.receivedEzspCommands().stream()
                .filter(c -> !isLegacyVersion(c) && frameIdOf(c) == frameId)
                .count();
    }
}

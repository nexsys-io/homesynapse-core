/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.EventTypes;
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M9.4-TCJ §A — Trust Center join enablement: the window-open path gains the TC
 * join policy + wildcard well-known transient link key (policy → transient key →
 * permitJoin, all riding the operator's {@code permit_join_duration} key), and the
 * {@code trustCenterJoinHandler} (0x0024) / {@code childJoinHandler} (0x0023)
 * callbacks become honest log-only observability.
 *
 * <p><strong>The north star:</strong> a device that fails key exchange must never
 * render as joined. Adoption stays Device_annce-gated; the join handler NEVER
 * creates a device, NEVER schedules an interview, NEVER publishes an event —
 * scenarios §A.3/§A.4 pin the never-synthesize contract, §A.5 pins that the
 * existing announce→interview→discovery chain fires exactly once beside it.
 *
 * <p>Frame ids, policy decisions, and callback layouts are BENCH-VERIFY
 * (bellows-derived): assertions bind to the SAME named constants the production
 * code uses — a wrong-but-consistent constant passes here and is corrected on
 * silicon as a one-constant edit (the 0x0019/0x90 model). The well-known key is
 * the ONE independent test literal (the M9.2 hardening discipline).
 */
@DisplayName("ZigbeeIntegrationAdapter — Trust Center join enablement (M9.4-TCJ §A)")
class ZigbeeTrustCenterJoinTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;
    private static final int FRAME_SEND_UNICAST = 0x0034;

    /** The independent well-known-key literal (never the production constant). */
    private static final byte[] WELL_KNOWN_TC_LINK_KEY =
            "ZigBeeAlliance09".getBytes(StandardCharsets.US_ASCII);

    /** The scripted joiner — the bench-corpus SNZB-03P identity (rig provenance). */
    private static final long SNZB_IEEE = 0x00124B0012345678L;
    private static final int SNZB_NWK = 0x6B9A;
    private static final int SNZB_ENDPOINT = 1;

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private ListAppender<ILoggingEvent> ingestionLogCapture;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        ingestionLogCapture = new ListAppender<>();
        ingestionLogCapture.start();
        ingestionLogger().addAppender(ingestionLogCapture);
    }

    @AfterEach
    void tearDown() {
        ingestionLogger().detachAppender(ingestionLogCapture);
    }

    // ── §A-1 window-open order: policy → transient key → permitJoin ─────────

    @Test
    @DisplayName("§A-1: window-open emits the policy frames, then the transient-key "
            + "frame, then the 0x0022 permitJoin — in order, none during boot, and the "
            + "transient key is the wildcard-partnered well-known key")
    void windowOpen_emitsEnablementBeforePermitJoin_inOrder() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> tcjHandler(ncp, command, List.of()));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, 200);

        assertThat(enablementFrameIds(ncp))
                .as("no enablement or join frame is emitted during boot")
                .isEmpty();

        adapter.openPermitJoinWindow();

        assertThat(enablementFrameIds(ncp))
                .as("policy → policy → transient key → permitJoin, exactly once each")
                .containsExactly(
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY,
                        FRAME_PERMIT_JOINING);

        List<byte[]> policies =
                framesWithId(ncp, EzspCoordinatorProtocol.FRAME_SET_POLICY);
        assertThat(parametersOf(policies.get(0)))
                .as("the TC join policy: policyId u8 + decision u16 LE")
                .isEqualTo(policyParameters(
                        EzspCoordinatorProtocol.POLICY_TRUST_CENTER,
                        EzspCoordinatorProtocol.DECISION_ALLOW_PRECONFIGURED_KEY_JOINS));
        assertThat(parametersOf(policies.get(1)))
                .as("the TC key-request policy: allow TC key requests")
                .isEqualTo(policyParameters(
                        EzspCoordinatorProtocol.POLICY_TC_KEY_REQUEST,
                        EzspCoordinatorProtocol.DECISION_ALLOW_TC_KEY_REQUESTS));

        byte[] transientKey = parametersOf(framesWithId(ncp,
                EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY).get(0));
        assertThat(transientKey).hasSize(25);
        byte[] wildcard = new byte[8];
        Arrays.fill(wildcard, (byte) 0xFF);
        assertThat(Arrays.copyOfRange(transientKey, 0, 8))
                .as("the partner EUI64 is the all-0xFF wildcard, never a real EUI")
                .isEqualTo(wildcard);
        assertThat(Arrays.copyOfRange(transientKey, 8, 24))
                .as("the key material is the well-known ZigBeeAlliance09 key "
                        + "(independent literal)")
                .isEqualTo(WELL_KNOWN_TC_LINK_KEY);
        assertThat(transientKey[24])
                .as("the security-manager flags byte")
                .isEqualTo((byte) EzspCoordinatorProtocol.TRANSIENT_KEY_FLAGS_NONE);
        assertThat(adapter.isPermitJoinActive()).as("the window is open").isTrue();
    }

    // ── §A-2 key absent ⇒ zero enablement frames ────────────────────────────

    @Test
    @DisplayName("§A-2: an absent permit_join_duration key emits ZERO policy and "
            + "transient-key frames — the enablement rides the window (conservative "
            + "default is LAW)")
    void keyAbsent_emitsNoEnablementFrames() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> tcjHandler(ncp, command, List.of()));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        adapter.openPermitJoinWindow();

        assertThat(enablementFrameIds(ncp))
                .as("no key ⇒ no policy, no transient key, no permitJoin — ever")
                .isEmpty();
        assertThat(adapter.isPermitJoinActive()).isFalse();
    }

    // ── §A-3 secured join ⇒ one INFO, ZERO synthesis ────────────────────────

    @Test
    @DisplayName("§A-3: a scripted secured 0x0024 logs one INFO zigbee.device_join and "
            + "synthesizes NOTHING — zero devices, zero interviews, zero events")
    void securedJoin_logsInfo_synthesizesNothing() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> tcjHandler(ncp, command, List.of(
                trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                        EzspCoordinatorProtocol.DEVICE_UPDATE_UNSECURED_JOIN,
                        EzspCoordinatorProtocol.JOIN_DECISION_USE_PRECONFIGURED_KEY))));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, 200);
        adapter.openPermitJoinWindow();

        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.INFO, "zigbee.device_join"))
                .as("one honest join observation")
                .containsExactly("zigbee.device_join: device=0x00124B0012345678 "
                        + "nwk=0x6b9a status=UNSECURED_JOIN "
                        + "decision=USE_PRECONFIGURED_KEY");
        assertThat(adapter.allDevices())
                .as("the join handler NEVER creates a device").isEmpty();
        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("the join handler NEVER starts an interview").isZero();
        assertThat(publisher.published())
                .as("the join handler NEVER publishes an event").isEmpty();
    }

    // ── §A-4 denied join ⇒ one WARN, ZERO synthesis ─────────────────────────

    @Test
    @DisplayName("§A-4: a scripted denied 0x0024 logs one WARN zigbee.device_join_failed "
            + "and synthesizes NOTHING — a device that fails key exchange must never "
            + "render as joined")
    void deniedJoin_logsWarn_synthesizesNothing() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> tcjHandler(ncp, command, List.of(
                trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                        EzspCoordinatorProtocol.DEVICE_UPDATE_UNSECURED_JOIN,
                        EzspCoordinatorProtocol.JOIN_DECISION_DENY_JOIN))));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, 200);
        adapter.openPermitJoinWindow();

        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.WARN, "zigbee.device_join_failed"))
                .as("one honest failed-join surfacing")
                .containsExactly("zigbee.device_join_failed: "
                        + "device=0x00124B0012345678 status=UNSECURED_JOIN "
                        + "decision=DENY_JOIN");
        assertThat(ingestionMessages(Level.INFO, "zigbee.device_join")).isEmpty();
        assertThat(adapter.allDevices()).isEmpty();
        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64)).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    // ── §A-5 the happy path is unchanged (no double-drive) ──────────────────

    @Test
    @DisplayName("§A-5: a secured 0x0024 THEN a Device_annce drives the EXISTING "
            + "announce→interview→discovery chain exactly once — the join handler "
            + "neither preempts nor double-drives it")
    void securedJoinThenAnnounce_existingChainFiresOnce() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> tcjHandler(ncp, command, List.of(
                trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                        EzspCoordinatorProtocol.DEVICE_UPDATE_UNSECURED_JOIN,
                        EzspCoordinatorProtocol.JOIN_DECISION_USE_PRECONFIGURED_KEY),
                deviceAnnounceCallback(SNZB_IEEE, SNZB_NWK))));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, 200);
        adapter.openPermitJoinWindow();

        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.INFO, "zigbee.device_join"))
                .as("the join observation logs beside the chain").hasSize(1);
        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("exactly ONE interview walk started — announce-driven, once")
                .isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count())
                .as("exactly ONE discovery proposal published")
                .isEqualTo(1);
        assertThat(adapter.device(new IEEEAddress(SNZB_IEEE)))
                .as("the announce-driven chain recorded the device").isPresent();
    }

    // ── §A-6 enablement failure honesty ─────────────────────────────────────

    @Test
    @DisplayName("§A-6: a stack that NAKs setPolicy propagates the failure, the 0x0022 "
            + "is never sent, and isPermitJoinActive() stays false — a half-open door "
            + "is a lie (never-false-ALIVE)")
    void enablementNak_propagates_windowStaysClosed() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command)
                    && frameIdOf(command) == EzspCoordinatorProtocol.FRAME_SET_POLICY) {
                // EZSP_ERROR_INVALID_CALL-class rejection: status != 0.
                return List.of(extendedResponse(command[0] & 0xFF,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY, new byte[] {0x01}));
            }
            return tcjHandler(ncp, command, List.of());
        });
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, 200);

        assertThatThrownBy(adapter::openPermitJoinWindow)
                .as("the rejection surfaces — TRANSIENT at the supervisor, never a "
                        + "silent half-open window")
                .isInstanceOf(EzspCommandException.class)
                .hasMessageContaining("setPolicy");

        assertThat(countFrames(ncp, FRAME_PERMIT_JOINING))
                .as("the MAC window is never opened behind a failed enablement")
                .isZero();
        assertThat(adapter.isPermitJoinActive())
                .as("the window honestly reads closed").isFalse();
    }

    // ── §A-7 child joins (0x0023): log-only observability ───────────────────

    @Test
    @DisplayName("§A-7: a scripted 0x0023 child join logs one INFO zigbee.child_join "
            + "and synthesizes nothing (the same never-synthesize rule)")
    void childJoin_logsInfo_synthesizesNothing() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> tcjHandler(ncp, command, List.of(
                childJoinCallback(SNZB_IEEE, SNZB_NWK, true, 0x04))));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, 200);
        adapter.openPermitJoinWindow();

        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.INFO, "zigbee.child_join"))
                .containsExactly("zigbee.child_join: child=0x00124B0012345678 "
                        + "nwk=0x6b9a type=SLEEPY_END_DEVICE");
        assertThat(adapter.allDevices()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    // ── harness (the ZigbeePermitJoinTest production-ladder idiom) ──────────

    private static PortCandidate coordinatorCandidate() {
        return new PortCandidate("/dev/ttyUSB7",
                "/dev/serial/by-id/usb-ITEAD_SONOFF_20240001-if00-port0",
                PortLocator.VENDOR_SILICON_LABS_CP210X,
                PortLocator.PRODUCT_CP210X_UART_BRIDGE, null);
    }

    private FakeSerialByteChannel channelOver(FakeNcp ncp) {
        FakeSerialByteChannel channel = new FakeSerialByteChannel(clock);
        channel.onWrite(ncp);
        return channel;
    }

    private IntegrationContext context(ConfigurationAccess configAccess) {
        return new IntegrationContext(
                new IntegrationId(UlidFactory.generate(clock)), "zigbee", publisher,
                new InMemoryEntityRegistry(), unusedQueryService(),
                unusedHealthReporter(), configAccess,
                null, null, null, null, null);
    }

    /**
     * The rig's deliver idiom: callbacks riding a prior response sit unread in the
     * channel until the protocol next reads — the nop keepalive's response loop
     * parks them in the callback queue, and the §G cycle drains them.
     */
    private static void deliverAndCycle(ZigbeeIntegrationAdapter adapter) {
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();
    }

    /** Boots a production adapter through the full §5.1 ladder to a formed network. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp,
            Integer permitJoinDuration) throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(permitJoinDuration)),
                new InMemoryDeviceRegistry(),
                new RegistryProjection(new InMemoryDeviceRegistry(),
                        new InMemoryEntityRegistry()),
                tempDir, clock, null,
                () -> List.of(coordinatorCandidate()),
                candidate -> channels.pop());
        adapter.initialize();
        PortCandidate port = adapter.resolvePort();
        adapter.bindTransport(port);
        adapter.coordinatorProtocol().startSession();
        adapter.resumeOrForm();
        adapter.coordinatorProtocol().awaitNetworkUp();
        return adapter;
    }

    // ── scripted NCP (v13 dialect; interview-capable for §A-5) ──────────────

    /**
     * Formation + enablement + interview-capable handler. {@code permitJoinRiders}
     * are callback frames riding the 0x0022 response — the established idiom for
     * unsolicited callbacks (they park in the protocol's callback queue and reach
     * the ingestion drain on the next cycle).
     */
    private List<byte[]> tcjHandler(FakeNcp ncp, byte[] command,
            List<byte[]> permitJoinRiders) {
        if (isLegacyVersion(command)) {
            return List.of(new byte[] {
                command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74
            });
        }
        int seq = command[0] & 0xFF;
        return switch (frameIdOf(command)) {
            case FRAME_START_SCAN -> {
                List<byte[]> frames = new ArrayList<>();
                frames.add(extendedResponse(seq, FRAME_START_SCAN, new byte[] {0x00}));
                for (int channel = 11; channel <= 26; channel++) {
                    int rssi = channel == 20 ? -95 : -60;
                    frames.add(new byte[] {
                        0x00, (byte) 0x90, 0x01, 0x48, 0x00, (byte) channel, (byte) rssi
                    });
                }
                frames.add(new byte[] {0x00, (byte) 0x90, 0x01, 0x1C, 0x00, 0x00, 0x00});
                yield frames;
            }
            case FRAME_FORM_NETWORK -> List.of(
                    extendedResponse(seq, FRAME_FORM_NETWORK, new byte[] {0x00}),
                    new byte[] {0x00, (byte) 0x90, 0x01, 0x19, 0x00, (byte) 0x90});
            case FRAME_PERMIT_JOINING -> {
                List<byte[]> frames = new ArrayList<>();
                frames.add(extendedResponse(seq, FRAME_PERMIT_JOINING,
                        new byte[] {0x00}));
                frames.addAll(permitJoinRiders);
                yield frames;
            }
            case FRAME_SEND_UNICAST ->
                    handleUnicast(seq, extendedParameters(command));
            default -> defaultResponses(seq, command);
        };
    }

    private List<byte[]> defaultResponses(int seq, byte[] command) {
        int frameId = frameIdOf(command);
        if (frameId == EzspCoordinatorProtocol.FRAME_SET_POLICY) {
            return List.of(extendedResponse(seq, frameId, new byte[] {0x00}));
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY) {
            // sl_Status SL_STATUS_OK (u32 LE) — the expected v13 silicon shape.
            return List.of(extendedResponse(seq, frameId,
                    new byte[] {0x00, 0x00, 0x00, 0x00}));
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64) {
            return List.of(extendedResponse(seq, frameId, new byte[] {
                (byte) (SNZB_NWK & 0xFF), (byte) ((SNZB_NWK >> 8) & 0xFF)}));
        }
        return switch (frameId) {
            case 0x0005 -> List.of(extendedResponse(seq, 0x0005, new byte[0]));
            case FRAME_NETWORK_INIT, FRAME_FORM_NETWORK, FRAME_PERMIT_JOINING,
                    FRAME_SET_INITIAL_SECURITY_STATE ->
                    List.of(extendedResponse(seq, frameId, new byte[] {0x00}));
            case 0x0018 -> List.of(extendedResponse(seq, 0x0018, new byte[] {0x02}));
            default -> List.of();
        };
    }

    /** The SNZB interview walk (the rig's scripted shapes, SNZB branch only). */
    private List<byte[]> handleUnicast(int seq, byte[] parameters) {
        int profile = (parameters[3] & 0xFF) | ((parameters[4] & 0xFF) << 8);
        int cluster = (parameters[5] & 0xFF) | ((parameters[6] & 0xFF) << 8);
        byte[] message = new byte[parameters[15] & 0xFF];
        System.arraycopy(parameters, 16, message, 0, message.length);

        List<byte[]> frames = new ArrayList<>();
        frames.add(extendedResponse(seq, FRAME_SEND_UNICAST,
                new byte[] {0x00, parameters[13]}));    // EMBER_SUCCESS + echoed tag

        if (profile == EzspCoordinatorProtocol.ZDO_PROFILE_ID) {
            int tsn = message[0] & 0xFF;
            byte[] reply = zdoReply(cluster, tsn);
            if (reply != null) {
                frames.add(incomingMessage(EzspCoordinatorProtocol.ZDO_PROFILE_ID,
                        cluster | 0x8000, 0, reply));
            }
            return frames;
        }
        if (cluster == 0x0000 && (message[0] & 0x03) == 0x00) {  // interview Basic read
            int tsn = message[1] & 0xFF;
            frames.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                    0x0000, SNZB_ENDPOINT, basicReply(tsn)));
        }
        return frames;
    }

    private static byte[] zdoReply(int cluster, int tsn) {
        int nwkLo = SNZB_NWK & 0xFF;
        int nwkHi = (SNZB_NWK >> 8) & 0xFF;
        return switch (cluster) {
            case ZdoCodec.CLUSTER_NODE_DESC_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi,
                    0x02, 0x40, (byte) 0x80,
                    (byte) 0x86, 0x12,      // manufacturer code LE
                    82, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            case ZdoCodec.CLUSTER_ACTIVE_EP_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi, 0x01,
                    (byte) SNZB_ENDPOINT};
            case ZdoCodec.CLUSTER_SIMPLE_DESC_REQ -> simpleDescriptor(tsn);
            default -> null;
        };
    }

    private static byte[] simpleDescriptor(int tsn) {
        int[] inClusters = {0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500};
        int[] outClusters = {0x0003, 0x0019};
        int length = 1 + 2 + 2 + 1 + 1 + inClusters.length * 2
                + 1 + outClusters.length * 2;
        byte[] reply = new byte[5 + length];
        int i = 0;
        reply[i++] = (byte) tsn;
        reply[i++] = 0x00;
        reply[i++] = (byte) (SNZB_NWK & 0xFF);
        reply[i++] = (byte) ((SNZB_NWK >> 8) & 0xFF);
        reply[i++] = (byte) length;
        reply[i++] = (byte) SNZB_ENDPOINT;
        reply[i++] = 0x04;                              // HA profile 0x0104 LE
        reply[i++] = 0x01;
        reply[i++] = 0x07;                              // device type 0x0107 LE
        reply[i++] = 0x01;
        reply[i++] = 0x01;                              // application version
        reply[i++] = (byte) inClusters.length;
        for (int clusterId : inClusters) {
            reply[i++] = (byte) (clusterId & 0xFF);
            reply[i++] = (byte) ((clusterId >> 8) & 0xFF);
        }
        reply[i++] = (byte) outClusters.length;
        for (int clusterId : outClusters) {
            reply[i++] = (byte) (clusterId & 0xFF);
            reply[i++] = (byte) ((clusterId >> 8) & 0xFF);
        }
        return reply;
    }

    private static byte[] basicReply(int tsn) {
        String manufacturer = "eWeLink";
        String model = "SNZB-03P";
        String build = "0x01000D08";
        byte[] reply = new byte[3
                + 5 + manufacturer.length()
                + 5 + model.length()
                + 5
                + 5 + build.length()];
        int i = 0;
        reply[i++] = 0x18;                              // global, server-to-client
        reply[i++] = (byte) tsn;
        reply[i++] = 0x01;                              // Read Attributes Response
        i = stringRecord(reply, i, 0x0004, manufacturer);
        i = stringRecord(reply, i, 0x0005, model);
        reply[i++] = 0x07;                              // powerSource, enum8
        reply[i++] = 0x00;
        reply[i++] = 0x00;
        reply[i++] = 0x30;
        reply[i++] = 0x03;                              // battery
        stringRecord(reply, i, 0x4000, build);
        return reply;
    }

    private static int stringRecord(byte[] buffer, int offset, int attributeId,
            String value) {
        buffer[offset++] = (byte) (attributeId & 0xFF);
        buffer[offset++] = (byte) ((attributeId >> 8) & 0xFF);
        buffer[offset++] = 0x00;                        // SUCCESS
        buffer[offset++] = 0x42;                        // character string
        buffer[offset++] = (byte) value.length();
        for (char c : value.toCharArray()) {
            buffer[offset++] = (byte) c;
        }
        return offset;
    }

    // ── callback fixtures (BENCH-VERIFY layouts — production-constant frame ids) ─

    /** A 0x0024 trustCenterJoinHandler callback: nodeId, EUI64, status, decision, parent. */
    private static byte[] trustCenterJoinCallback(long ieee, int nwk, int status,
            int decision) {
        byte[] parameters = new byte[14];
        parameters[0] = (byte) (nwk & 0xFF);
        parameters[1] = (byte) ((nwk >> 8) & 0xFF);
        for (int i = 0; i < 8; i++) {
            parameters[2 + i] = (byte) (ieee >> (8 * i));
        }
        parameters[10] = (byte) status;
        parameters[11] = (byte) decision;
        parameters[12] = 0x00;                          // parent: the coordinator
        parameters[13] = 0x00;
        return callbackFrame(
                EzspCoordinatorProtocol.FRAME_TRUST_CENTER_JOIN_HANDLER, parameters);
    }

    /** A 0x0023 childJoinHandler callback: index, joining, childId, EUI64, type. */
    private static byte[] childJoinCallback(long ieee, int nwk, boolean joining,
            int childType) {
        byte[] parameters = new byte[13];
        parameters[0] = 0x00;                           // child table index
        parameters[1] = (byte) (joining ? 0x01 : 0x00);
        parameters[2] = (byte) (nwk & 0xFF);
        parameters[3] = (byte) ((nwk >> 8) & 0xFF);
        for (int i = 0; i < 8; i++) {
            parameters[4 + i] = (byte) (ieee >> (8 * i));
        }
        parameters[12] = (byte) childType;
        return callbackFrame(
                EzspCoordinatorProtocol.FRAME_CHILD_JOIN_HANDLER, parameters);
    }

    /** A ZDP Device_annce riding the 0x0045 incomingMessageHandler (the rig layout). */
    private static byte[] deviceAnnounceCallback(long ieee, int nwk) {
        byte[] message = new byte[12];
        message[0] = 0x41;                              // ZDO TSN
        message[1] = (byte) (nwk & 0xFF);
        message[2] = (byte) ((nwk >> 8) & 0xFF);
        for (int i = 0; i < 8; i++) {
            message[3 + i] = (byte) (ieee >> (8 * i));
        }
        message[11] = (byte) 0x80;                      // MAC capability: end device
        return incomingMessage(EzspCoordinatorProtocol.ZDO_PROFILE_ID,
                ZdoCodec.CLUSTER_DEVICE_ANNOUNCE, 0, message);
    }

    /** The 0x0045 incomingMessageHandler callback layout (v13 — bench-proven). */
    private static byte[] incomingMessage(int profile, int cluster,
            int sourceEndpoint, byte[] message) {
        byte[] parameters = new byte[19 + message.length];
        parameters[0] = 0x00;                           // EMBER_INCOMING_UNICAST
        parameters[1] = (byte) (profile & 0xFF);
        parameters[2] = (byte) ((profile >> 8) & 0xFF);
        parameters[3] = (byte) (cluster & 0xFF);
        parameters[4] = (byte) ((cluster >> 8) & 0xFF);
        parameters[5] = (byte) sourceEndpoint;
        parameters[6] = (byte) (sourceEndpoint == 0 ? 0 : 1);
        parameters[12] = (byte) 176;                    // LQI
        parameters[13] = (byte) -56;                    // RSSI
        parameters[14] = (byte) (SNZB_NWK & 0xFF);
        parameters[15] = (byte) ((SNZB_NWK >> 8) & 0xFF);
        parameters[16] = (byte) 0xFF;                   // no binding index
        parameters[17] = (byte) 0xFF;
        parameters[18] = (byte) message.length;
        System.arraycopy(message, 0, parameters, 19, message.length);
        return callbackFrame(
                EzspCoordinatorProtocol.FRAME_INCOMING_MESSAGE_HANDLER, parameters);
    }

    private static byte[] callbackFrame(int frameId, byte[] parameters) {
        byte[] frame = new byte[5 + parameters.length];
        frame[0] = 0x00;
        frame[1] = (byte) 0x90;                         // callback
        frame[2] = 0x01;
        frame[3] = (byte) (frameId & 0xFF);
        frame[4] = (byte) ((frameId >> 8) & 0xFF);
        System.arraycopy(parameters, 0, frame, 5, parameters.length);
        return frame;
    }

    // ── v13 frame helpers (test-local mirrors of the EzspProtocolTest idiom) ─

    private static boolean isLegacyVersion(byte[] command) {
        return command.length == 4 && command[1] == 0x00 && command[2] == 0x00;
    }

    private static int frameIdOf(byte[] extendedCommand) {
        return (extendedCommand[3] & 0xFF) | ((extendedCommand[4] & 0xFF) << 8);
    }

    private static byte[] extendedParameters(byte[] extendedCommand) {
        byte[] parameters = new byte[extendedCommand.length - 5];
        System.arraycopy(extendedCommand, 5, parameters, 0, parameters.length);
        return parameters;
    }

    private static byte[] parametersOf(byte[] extendedCommand) {
        return extendedParameters(extendedCommand);
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

    /** The expected setPolicy parameters: policyId u8 + decision u16 LE. */
    private static byte[] policyParameters(int policyId, int decision) {
        return new byte[] {(byte) policyId, (byte) (decision & 0xFF),
                (byte) ((decision >> 8) & 0xFF)};
    }

    /** Every non-legacy command with the given 16-bit frame id, in send order. */
    private static List<byte[]> framesWithId(FakeNcp ncp, int frameId) {
        List<byte[]> matches = new ArrayList<>();
        for (byte[] command : ncp.receivedEzspCommands()) {
            if (!isLegacyVersion(command) && frameIdOf(command) == frameId) {
                matches.add(command);
            }
        }
        return matches;
    }

    private static int countFrames(FakeNcp ncp, int frameId) {
        return framesWithId(ncp, frameId).size();
    }

    /** The enablement/join frame ids in send order (the §A-1 ordering surface). */
    private static List<Integer> enablementFrameIds(FakeNcp ncp) {
        List<Integer> ids = new ArrayList<>();
        for (byte[] command : ncp.receivedEzspCommands()) {
            if (isLegacyVersion(command)) {
                continue;
            }
            int frameId = frameIdOf(command);
            if (frameId == EzspCoordinatorProtocol.FRAME_SET_POLICY
                    || frameId == EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY
                    || frameId == FRAME_PERMIT_JOINING) {
                ids.add(frameId);
            }
        }
        return ids;
    }

    private List<String> ingestionMessages(Level level, String prefix) {
        return ingestionLogCapture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
    }

    private static Logger ingestionLogger() {
        return (Logger) LoggerFactory.getLogger(ZclIngestionUnit.class);
    }

    // ── inert context stubs (the adapter never touches these paths here) ────

    private static ConfigurationAccess configAccess(Integer permitJoinDuration) {
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return Map.of();
            }

            @Override
            public Optional<String> getString(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> getInt(String key) {
                return ZigbeeIntegrationAdapter.PERMIT_JOIN_DURATION_KEY.equals(key)
                        ? Optional.ofNullable(permitJoinDuration)
                        : Optional.empty();
            }

            @Override
            public Optional<Boolean> getBoolean(String key) {
                return Optional.empty();
            }
        };
    }

    private static StateQueryService unusedQueryService() {
        return new StateQueryService() {
            @Override
            public Optional<EntityState> getState(EntityId entityId) {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public StateSnapshot getSnapshot() {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public long getViewPosition() {
                return 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }
        };
    }

    private static HealthReporter unusedHealthReporter() {
        return new HealthReporter() {
            @Override
            public void reportHeartbeat() {
            }

            @Override
            public void reportKeepalive(Instant lastSuccess) {
            }

            @Override
            public void reportError(Throwable error) {
            }

            @Override
            public void reportHealthTransition(
                    com.homesynapse.integration.HealthState state, String reason) {
            }
        };
    }
}

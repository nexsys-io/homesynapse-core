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
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.PermanentIntegrationException;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M9.4-NCFG — NCP session configuration: {@code startSession()} gains the
 * {@code configureNcp()} prelude (bench iteration 1 proved the M9.4-TCJ enablement
 * silicon-accepted yet joins produced total silence — the NCP resets to firmware
 * defaults every launch and was never configured, while every reference host stack
 * writes a config batch before stack-up).
 *
 * <p><strong>The north star:</strong> never-false-ALIVE extends to the config
 * surface — a coordinator that opens a permit-join window it cannot actually
 * surface joins through is a half-open-door lie. A REQUIRED write NAK or a
 * REQUIRED read-back mismatch fails the session honestly BEFORE any
 * {@code networkInit}/{@code formNetwork}; the SUPPORTING tail is best-effort
 * (7.4.x firmware self-manages some values and legitimately rejects).
 *
 * <p>Config ids and values are BENCH-VERIFY (bellows/UG100-derived): assertions
 * bind to the SAME named constants the production code uses — a
 * wrong-but-consistent constant passes here and is corrected on silicon as a
 * one-constant edit (the 0x0019/0x90 model). The §1.2 read-back is the decisive
 * instrument: the {@code zigbee.ncp_configured} INFO logs the values THE NCP
 * REPORTS, closing the accepted-but-not-applied hole.
 */
@DisplayName("EzspCoordinatorProtocol — NCP session configuration (M9.4-NCFG)")
class ZigbeeNcpConfigurationTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private ListAppender<ILoggingEvent> protocolLogCapture;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        protocolLogCapture = new ListAppender<>();
        protocolLogCapture.start();
        protocolLogger().addAppender(protocolLogCapture);
    }

    @AfterEach
    void tearDown() {
        protocolLogger().detachAppender(protocolLogCapture);
    }

    // ── §T1 the batch: REQUIRED-first, read-backs, ONE INFO, before formation ─

    @Test
    @DisplayName("§T1: after the version frame and before ANY formation frame, the batch "
            + "is written REQUIRED-first, the three REQUIRED ids are read back, and ONE "
            + "zigbee.ncp_configured INFO logs the read-back values")
    void sessionStart_configuresBeforeFormation_readsBackAndLogs() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::formationHandler);

        bootProduction(ncp, null);

        List<byte[]> extended = nonLegacyCommands(ncp);
        assertThat(frameIdOf(extended.get(0)))
                .as("config writes are the FIRST extended commands after negotiation")
                .isEqualTo(EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE);
        assertThat(configIds(ncp, EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE))
                .as("the write batch is REQUIRED-first, then the best-effort tail")
                .containsExactly(
                        EzspCoordinatorProtocol.CONFIG_STACK_PROFILE,
                        EzspCoordinatorProtocol.CONFIG_SECURITY_LEVEL,
                        EzspCoordinatorProtocol.CONFIG_APPLICATION_ZDO_FLAGS,
                        EzspCoordinatorProtocol.CONFIG_KEY_TABLE_SIZE,
                        EzspCoordinatorProtocol.CONFIG_ADDRESS_TABLE_SIZE,
                        EzspCoordinatorProtocol.CONFIG_TRUST_CENTER_ADDRESS_CACHE_SIZE,
                        EzspCoordinatorProtocol.CONFIG_MAX_END_DEVICE_CHILDREN,
                        EzspCoordinatorProtocol.CONFIG_INDIRECT_TRANSMISSION_TIMEOUT,
                        EzspCoordinatorProtocol.CONFIG_PACKET_BUFFER_COUNT,
                        EzspCoordinatorProtocol.CONFIG_MULTICAST_TABLE_SIZE);
        assertThat(configWrites(ncp))
                .as("every write carries its production value, u16 LE")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        EzspCoordinatorProtocol.CONFIG_STACK_PROFILE,
                        EzspCoordinatorProtocol.STACK_PROFILE_ZIGBEE_PRO,
                        EzspCoordinatorProtocol.CONFIG_SECURITY_LEVEL,
                        EzspCoordinatorProtocol.SECURITY_LEVEL_Z30_STANDARD,
                        EzspCoordinatorProtocol.CONFIG_APPLICATION_ZDO_FLAGS,
                        EzspCoordinatorProtocol.ZDO_FLAGS_RECEIVE_AND_HANDLE_UNSUPPORTED,
                        EzspCoordinatorProtocol.CONFIG_KEY_TABLE_SIZE,
                        EzspCoordinatorProtocol.KEY_TABLE_SIZE_VALUE,
                        EzspCoordinatorProtocol.CONFIG_ADDRESS_TABLE_SIZE,
                        EzspCoordinatorProtocol.ADDRESS_TABLE_SIZE_VALUE,
                        EzspCoordinatorProtocol.CONFIG_TRUST_CENTER_ADDRESS_CACHE_SIZE,
                        EzspCoordinatorProtocol.TRUST_CENTER_ADDRESS_CACHE_SIZE_VALUE,
                        EzspCoordinatorProtocol.CONFIG_MAX_END_DEVICE_CHILDREN,
                        EzspCoordinatorProtocol.MAX_END_DEVICE_CHILDREN_VALUE,
                        EzspCoordinatorProtocol.CONFIG_INDIRECT_TRANSMISSION_TIMEOUT,
                        EzspCoordinatorProtocol.INDIRECT_TRANSMISSION_TIMEOUT_VALUE,
                        EzspCoordinatorProtocol.CONFIG_PACKET_BUFFER_COUNT,
                        EzspCoordinatorProtocol.PACKET_BUFFER_COUNT_VALUE,
                        EzspCoordinatorProtocol.CONFIG_MULTICAST_TABLE_SIZE,
                        EzspCoordinatorProtocol.MULTICAST_TABLE_SIZE_VALUE));
        assertThat(configIds(ncp, EzspCoordinatorProtocol.FRAME_GET_CONFIGURATION_VALUE))
                .as("the three REQUIRED ids are read back after the writes")
                .containsExactly(
                        EzspCoordinatorProtocol.CONFIG_STACK_PROFILE,
                        EzspCoordinatorProtocol.CONFIG_SECURITY_LEVEL,
                        EzspCoordinatorProtocol.CONFIG_APPLICATION_ZDO_FLAGS);
        assertThat(lastIndexOf(ncp,
                EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE,
                EzspCoordinatorProtocol.FRAME_GET_CONFIGURATION_VALUE))
                .as("the whole config batch precedes any networkInit/scan/formNetwork")
                .isLessThan(firstIndexOf(ncp, FRAME_NETWORK_INIT, FRAME_START_SCAN,
                        FRAME_FORM_NETWORK));
        assertThat(protocolMessages(Level.INFO, "zigbee.ncp_configured"))
                .as("exactly ONE INFO carrying the values THE NCP REPORTS")
                .containsExactly("zigbee.ncp_configured: zdo_flags=0x3 "
                        + "stack_profile=2 security_level=5");
    }

    // ── §T2 REQUIRED write NAK ⇒ honest failure, no formation, no window ─────

    @Test
    @DisplayName("§T2: a REQUIRED write NAK propagates, ZERO formation/resume frames "
            + "follow, and the permit-join window never opens (never-false-ALIVE)")
    void requiredWriteNak_propagates_noFormation_windowNeverOpens() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command)
                    && frameIdOf(command)
                            == EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE
                    && (command[5] & 0xFF)
                            == EzspCoordinatorProtocol.CONFIG_STACK_PROFILE) {
                // EZSP_ERROR_INVALID_ID-class rejection: status != 0.
                return List.of(extendedResponse(command[0] & 0xFF,
                        EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE,
                        new byte[] {0x35}));
            }
            return formationHandler(command);
        });
        ZigbeeIntegrationAdapter adapter = productionAdapter(ncp, 200);

        assertThatThrownBy(() -> adapter.coordinatorProtocol().startSession())
                .as("a REQUIRED rejection is honest failure — the supervisor classifies")
                .isInstanceOf(EzspCommandException.class)
                .hasMessageContaining("STACK_PROFILE")
                .hasMessageContaining("0x35");

        assertThat(protocolMessages(Level.WARN, "zigbee.ncp_config_rejected"))
                .containsExactly("zigbee.ncp_config_rejected: id=0xc status=0x35");
        assertThat(countFrames(ncp, FRAME_NETWORK_INIT))
                .as("zero resume frames follow the failure").isZero();
        assertThat(countFrames(ncp, FRAME_START_SCAN)).isZero();
        assertThat(countFrames(ncp, FRAME_FORM_NETWORK))
                .as("zero formation frames follow the failure").isZero();
        assertThat(countFrames(ncp, FRAME_PERMIT_JOINING))
                .as("the MAC window is never opened over a misconfigured stack")
                .isZero();
        assertThat(adapter.isPermitJoinActive())
                .as("the window honestly reads closed").isFalse();
        assertThat(protocolMessages(Level.INFO, "zigbee.ncp_configured"))
                .as("a failed batch never claims a configured NCP").isEmpty();
    }

    // ── §T3 REQUIRED read-back mismatch ⇒ WARN + the same honest failure ─────

    @Test
    @DisplayName("§T3: a REQUIRED read-back that mismatches the written value WARNs "
            + "and fails honestly like a write NAK — accepted-but-not-applied is a lie")
    void requiredReadbackMismatch_warnsAndFailsHonestly() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command)
                    && frameIdOf(command)
                            == EzspCoordinatorProtocol.FRAME_GET_CONFIGURATION_VALUE
                    && (command[5] & 0xFF)
                            == EzspCoordinatorProtocol.CONFIG_APPLICATION_ZDO_FLAGS) {
                // Status SUCCESS but the firmware-default value: the write was
                // accepted without being applied.
                return List.of(extendedResponse(command[0] & 0xFF,
                        EzspCoordinatorProtocol.FRAME_GET_CONFIGURATION_VALUE,
                        new byte[] {0x00, 0x00, 0x00}));
            }
            return formationHandler(command);
        });
        ZigbeeIntegrationAdapter adapter = productionAdapter(ncp, 200);

        assertThatThrownBy(() -> adapter.coordinatorProtocol().startSession())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPLICATION_ZDO_FLAGS");

        assertThat(protocolMessages(Level.WARN, "zigbee.ncp_config_readback_mismatch"))
                .containsExactly(
                        "zigbee.ncp_config_readback_mismatch: id=0x2a wrote=3 read=0");
        assertThat(countFrames(ncp, FRAME_NETWORK_INIT)).isZero();
        assertThat(countFrames(ncp, FRAME_START_SCAN)).isZero();
        assertThat(countFrames(ncp, FRAME_FORM_NETWORK)).isZero();
        assertThat(countFrames(ncp, FRAME_PERMIT_JOINING)).isZero();
        assertThat(adapter.isPermitJoinActive()).isFalse();
        assertThat(protocolMessages(Level.INFO, "zigbee.ncp_configured")).isEmpty();
    }

    // ── §T4 SUPPORTING NAK ⇒ one WARN, the batch continues, formation proceeds ─

    @Test
    @DisplayName("§T4: a SUPPORTING NAK logs one zigbee.ncp_config_skipped WARN, the "
            + "batch continues to completion, and formation proceeds")
    void supportingNak_warnsOnce_batchContinues_formationProceeds() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command)
                    && frameIdOf(command)
                            == EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE
                    && (command[5] & 0xFF)
                            == EzspCoordinatorProtocol.CONFIG_PACKET_BUFFER_COUNT) {
                // 7.4.x self-manages packet buffers and legitimately rejects.
                return List.of(extendedResponse(command[0] & 0xFF,
                        EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE,
                        new byte[] {0x35}));
            }
            return formationHandler(command);
        });

        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        assertThat(protocolMessages(Level.WARN, "zigbee.ncp_config_skipped"))
                .as("exactly one WARN names the skipped id and status")
                .containsExactly("zigbee.ncp_config_skipped: id=0x1 status=0x35");
        assertThat(configIds(ncp, EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE))
                .as("the batch continues past the rejection — all ten writes attempted")
                .hasSize(10);
        assertThat(configIds(ncp, EzspCoordinatorProtocol.FRAME_GET_CONFIGURATION_VALUE))
                .hasSize(3);
        assertThat(countFrames(ncp, FRAME_FORM_NETWORK))
                .as("formation proceeds over the best-effort tail").isEqualTo(1);
        assertThat(adapter.networkParameters().channel()).isEqualTo(20);
        assertThat(protocolMessages(Level.INFO, "zigbee.ncp_configured")).hasSize(1);
    }

    // ── §T5 a no-op startSession never re-writes config (G-NCFG6) ────────────

    @Test
    @DisplayName("§T5: startSession on an already-negotiated session is a no-op — the "
            + "config batch is never re-written (G-NCFG6)")
    void noOpStartSession_neverRewritesConfig() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::formationHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE)).isEqualTo(10);

        adapter.coordinatorProtocol().startSession();

        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE))
                .as("a no-op start writes nothing").isEqualTo(10);
        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_GET_CONFIGURATION_VALUE)).isEqualTo(3);
        assertThat(protocolMessages(Level.INFO, "zigbee.ncp_configured")).hasSize(1);
    }

    // ── §T6 resetSession → startSession re-configures (the reopen ladder) ────

    @Test
    @DisplayName("§T6: resetSession + startSession (the watchdog-reopen ladder) "
            + "re-negotiates AND re-writes the batch against the freshly-reset NCP")
    void resetSession_nextStartReconfigures() {
        FakeSerialByteChannel channel = new FakeSerialByteChannel(clock);
        FakeNcp ncp = new FakeNcp();
        channel.onWrite(ncp);
        ncp.onEzspCommand(command -> isLegacyVersion(command)
                ? List.of(new byte[] {
                    command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74})
                : List.<byte[]>of());   // the FakeNcp config model answers the batch
        EzspAshTransport transport = new EzspAshTransport(clock, arg -> channel);
        transport.open(new Object());
        EzspCoordinatorProtocol protocol = new EzspCoordinatorProtocol(transport,
                new RecordingNetworkParameterStore(), clock);
        startSessionOrFail(protocol);
        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE)).isEqualTo(10);

        // The real reopen flow (§7.4): transport closed and reopened, THEN the
        // protocol session resets — the next start renegotiates and reconfigures.
        transport.close();
        transport.open(new Object());
        protocol.resetSession();
        startSessionOrFail(protocol);

        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE))
                .as("the reopened session re-writes the whole batch").isEqualTo(20);
        assertThat(countFrames(ncp,
                EzspCoordinatorProtocol.FRAME_GET_CONFIGURATION_VALUE)).isEqualTo(6);
        assertThat(protocolMessages(Level.INFO, "zigbee.ncp_configured")).hasSize(2);
    }

    // ── harness (the ZigbeeChannelPinTest production-ladder idiom) ───────────

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

    /** Builds + initializes + binds the production adapter, WITHOUT startSession. */
    private ZigbeeIntegrationAdapter productionAdapter(FakeNcp ncp,
            Integer permitJoinDuration) throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(permitJoinDuration)),
                new InMemoryDeviceRegistry(), tempDir, clock, null,
                () -> List.of(coordinatorCandidate()),
                candidate -> channels.pop());
        adapter.initialize();
        adapter.bindTransport(adapter.resolvePort());
        return adapter;
    }

    /** Boots a production adapter through the full §5.1 ladder to a formed network. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp,
            Integer permitJoinDuration) throws Exception {
        ZigbeeIntegrationAdapter adapter = productionAdapter(ncp, permitJoinDuration);
        adapter.coordinatorProtocol().startSession();
        adapter.resumeOrForm();
        adapter.coordinatorProtocol().awaitNetworkUp();
        return adapter;
    }

    private static void startSessionOrFail(EzspCoordinatorProtocol protocol) {
        try {
            protocol.startSession();
        } catch (PermanentIntegrationException e) {
            throw new AssertionError("startSession failed unexpectedly", e);
        }
    }

    // ── scripted NCP (v13 dialect — config rides the FakeNcp built-in model) ─

    /** Formation-capable handler: scan + form + the NETWORK_UP callback. */
    private List<byte[]> formationHandler(byte[] command) {
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
                    // stackStatusHandler(EMBER_NETWORK_UP) rides the formation
                    // response — the §5.3 await consumes it.
                    new byte[] {0x00, (byte) 0x90, 0x01, 0x19, 0x00, (byte) 0x90});
            case 0x0005 -> List.of(extendedResponse(seq, 0x0005, new byte[0]));
            case FRAME_NETWORK_INIT, FRAME_PERMIT_JOINING,
                    FRAME_SET_INITIAL_SECURITY_STATE ->
                    List.of(extendedResponse(seq, frameIdOf(command),
                            new byte[] {0x00}));
            case 0x0018 -> List.of(extendedResponse(seq, 0x0018, new byte[] {0x02}));
            default -> List.of();
        };
    }

    // ── v13 frame helpers (test-local mirrors of the EzspProtocolTest idiom) ─

    private static boolean isLegacyVersion(byte[] command) {
        return command.length == 4 && command[1] == 0x00 && command[2] == 0x00;
    }

    private static int frameIdOf(byte[] extendedCommand) {
        return (extendedCommand[3] & 0xFF) | ((extendedCommand[4] & 0xFF) << 8);
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

    /** Every non-legacy command, in send order. */
    private static List<byte[]> nonLegacyCommands(FakeNcp ncp) {
        List<byte[]> commands = new ArrayList<>();
        for (byte[] command : ncp.receivedEzspCommands()) {
            if (!isLegacyVersion(command)) {
                commands.add(command);
            }
        }
        return commands;
    }

    /** Every non-legacy command with the given 16-bit frame id, in send order. */
    private static List<byte[]> framesWithId(FakeNcp ncp, int frameId) {
        List<byte[]> matches = new ArrayList<>();
        for (byte[] command : nonLegacyCommands(ncp)) {
            if (frameIdOf(command) == frameId) {
                matches.add(command);
            }
        }
        return matches;
    }

    private static int countFrames(FakeNcp ncp, int frameId) {
        return framesWithId(ncp, frameId).size();
    }

    /** The configId byte of every frame with the given id, in send order. */
    private static List<Integer> configIds(FakeNcp ncp, int frameId) {
        return framesWithId(ncp, frameId).stream()
                .map(command -> command[5] & 0xFF)
                .toList();
    }

    /** configId → value (u16 LE) for every setConfigurationValue write. */
    private static Map<Integer, Integer> configWrites(FakeNcp ncp) {
        Map<Integer, Integer> written = new LinkedHashMap<>();
        for (byte[] command : framesWithId(ncp,
                EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE)) {
            written.put(command[5] & 0xFF,
                    (command[6] & 0xFF) | ((command[7] & 0xFF) << 8));
        }
        return written;
    }

    /** The first index (over non-legacy commands) carrying any of the frame ids. */
    private static int firstIndexOf(FakeNcp ncp, int... frameIds) {
        List<byte[]> commands = nonLegacyCommands(ncp);
        for (int i = 0; i < commands.size(); i++) {
            for (int frameId : frameIds) {
                if (frameIdOf(commands.get(i)) == frameId) {
                    return i;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    /** The last index (over non-legacy commands) carrying any of the frame ids. */
    private static int lastIndexOf(FakeNcp ncp, int... frameIds) {
        List<byte[]> commands = nonLegacyCommands(ncp);
        int last = -1;
        for (int i = 0; i < commands.size(); i++) {
            for (int frameId : frameIds) {
                if (frameIdOf(commands.get(i)) == frameId) {
                    last = i;
                }
            }
        }
        return last;
    }

    private List<String> protocolMessages(Level level, String prefix) {
        return protocolLogCapture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
    }

    private static Logger protocolLogger() {
        return (Logger) LoggerFactory.getLogger(EzspCoordinatorProtocol.class);
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

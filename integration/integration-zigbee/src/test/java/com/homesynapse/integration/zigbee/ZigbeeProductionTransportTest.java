/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The M9.4b §5 production transport orchestration over the scripted NCP — the
 * hardware-free halves of the §7 done-when legs:
 *
 * <ul>
 *   <li>port resolution: the {@code serial_port} config key, the VID:PID
 *       locator, and the honest {@code zigbee.transport_unbound} PIE naming
 *       BOTH resolution paths;</li>
 *   <li>§7.6 second-boot resume: the first boot FORMS (0x1B84 byte-asserted)
 *       and persists through the §5.5 store; the second boot over the SAME
 *       data directory RESUMES — zero form frames — with the INV-SE-03
 *       no-key-on-disk assert;</li>
 *   <li>§7.4 reopen honesty: the composed ReopenAction closes, reopens,
 *       resets the session (version renegotiates FIRST — UG100), and RESUMES;
 *       byte-asserted zero formNetwork frames at the FakeNcp.</li>
 * </ul>
 */
@DisplayName("ZigbeeIntegrationAdapter — production transport orchestration (M9.4b §5)")
class ZigbeeProductionTransportTest {

    private static final int FRAME_VERSION_LEGACY = 0x0000;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private final List<FakeSerialByteChannel> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private static PortCandidate coordinatorCandidate() {
        return new PortCandidate("/dev/ttyUSB7",
                "/dev/serial/by-id/usb-ITEAD_SONOFF_20240001-if00-port0",
                PortLocator.VENDOR_SILICON_LABS_CP210X,
                PortLocator.PRODUCT_CP210X_UART_BRIDGE, null);
    }

    private ZigbeeIntegrationAdapter adapter(List<PortCandidate> enumerated,
            Deque<FakeSerialByteChannel> channels, String configuredPort) {
        IntegrationContext context = new IntegrationContext(
                new IntegrationId(UlidFactory.generate(clock)), "zigbee", publisher,
                new InMemoryEntityRegistry(), unusedQueryService(),
                unusedHealthReporter(), configAccess(configuredPort),
                null, null, null, null, null);
        return new ZigbeeIntegrationAdapter(context, new InMemoryDeviceRegistry(),
                new RegistryProjection(new InMemoryDeviceRegistry(),
                        new InMemoryEntityRegistry()),
                tempDir, clock, null,
                () -> enumerated,
                candidate -> {
                    FakeSerialByteChannel channel = channels.pop();
                    opened.add(channel);
                    return channel;
                });
    }

    private FakeSerialByteChannel channelOver(FakeNcp ncp) {
        FakeSerialByteChannel channel = new FakeSerialByteChannel(clock);
        channel.onWrite(ncp);
        return channel;
    }

    /** Boots a production adapter through the full §5.1 ladder. */
    private ZigbeeIntegrationAdapter boot(FakeNcp ncp, String configuredPort)
            throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        ZigbeeIntegrationAdapter adapter = adapter(
                List.of(coordinatorCandidate()), channels, configuredPort);
        adapter.initialize();
        PortCandidate port = adapter.resolvePort();
        adapter.bindTransport(port);
        adapter.coordinatorProtocol().startSession();
        adapter.resumeOrForm();
        adapter.coordinatorProtocol().awaitNetworkUp();
        return adapter;
    }

    // ── port resolution (§5.1 step 1) ───────────────────────────────────────

    @Test
    @DisplayName("a configured serial_port wins and survives non-enumeration (operator intent)")
    void resolvePort_configuredKeyWins() throws Exception {
        ZigbeeIntegrationAdapter adapter = adapter(List.of(),
                new ArrayDeque<>(), "/dev/ttyCONF");
        adapter.initialize();

        assertThat(adapter.resolvePort().systemPath()).isEqualTo("/dev/ttyCONF");
    }

    @Test
    @DisplayName("no key set: the VID:PID locator resolves the coordinator bridge")
    void resolvePort_locatorResolves() throws Exception {
        ZigbeeIntegrationAdapter adapter = adapter(List.of(coordinatorCandidate()),
                new ArrayDeque<>(), null);
        adapter.initialize();

        assertThat(adapter.resolvePort().systemPath()).isEqualTo("/dev/ttyUSB7");
    }

    @Test
    @DisplayName("nothing configured, nothing enumerated: PIE naming BOTH resolution paths")
    void resolvePort_unbound_permanent() throws Exception {
        ZigbeeIntegrationAdapter adapter = adapter(List.of(),
                new ArrayDeque<>(), null);
        adapter.initialize();

        assertThatThrownBy(adapter::resolvePort)
                .isInstanceOf(PermanentIntegrationException.class)
                .hasMessageContaining("serial_port")
                .hasMessageContaining("10c4");
    }

    // ── §7.6 second-boot resume (form → persist → resume; zero re-form) ─────

    @Test
    @DisplayName("§7.6: the first boot FORMS (0x1B84 byte-asserted) and persists; the second "
            + "boot over the SAME data dir RESUMES with zero form frames (+ INV-SE-03)")
    void secondBoot_resumesNeverReforms() throws Exception {
        // Boot 1: empty store → form (energy scan + formNetwork + NETWORK_UP).
        FakeNcp formingNcp = new FakeNcp();
        formingNcp.onEzspCommand(command -> formationHandler(formingNcp, command));
        boot(formingNcp, null);

        byte[] security = lastCommandWithFrameId(formingNcp,
                FRAME_SET_INITIAL_SECURITY_STATE);
        assertThat(security).isNotNull();
        // The SD-5 hashed-TCLK election, byte-asserted (M9.4b §5.4): 0x1B84 LE.
        assertThat(security[5]).isEqualTo((byte) 0x84);
        assertThat(security[6]).isEqualTo((byte) 0x1B);

        PersistentNetworkParameterStore custody =
                new PersistentNetworkParameterStore(tempDir, clock);
        NetworkParameters formed = custody.load().orElseThrow();
        byte[] key = custody.loadNetworkKey(formed.networkKeyRef()).orElseThrow();
        // INV-SE-03: the persisted key hex is in NO plaintext content of the
        // non-secret parameters file.
        String parametersJson = Files.readString(tempDir.resolve(
                        PersistentNetworkParameterStore.PARAMETERS_FILE_NAME),
                StandardCharsets.UTF_8);
        assertThat(parametersJson)
                .doesNotContain(HexFormat.of().formatHex(key))
                .doesNotContain(HexFormat.of().formatHex(key)
                        .toUpperCase(java.util.Locale.ROOT));

        // Boot 2: a fresh adapter + fresh NCP over the SAME data dir → RESUME.
        FakeNcp resumingNcp = new FakeNcp();
        resumingNcp.onEzspCommand(command -> resumeHandler(command, formed));
        boot(resumingNcp, null);

        assertThat(lastCommandWithFrameId(resumingNcp, FRAME_SET_INITIAL_SECURITY_STATE))
                .as("resume never re-forms: zero setInitialSecurityState frames")
                .isNull();
        assertThat(lastCommandWithFrameId(resumingNcp, FRAME_FORM_NETWORK))
                .as("resume never re-forms: zero formNetwork frames")
                .isNull();
        assertThat(lastCommandWithFrameId(resumingNcp, FRAME_NETWORK_INIT))
                .as("resume rides networkInit")
                .isNotNull();
    }

    // ── §7.4 reopen honesty (close → reopen → reset → renegotiate → RESUME) ─

    @Test
    @DisplayName("§7.4: attemptReopen closes the old channel, reopens, renegotiates FIRST "
            + "(resetSession — UG100), then RESUMES; zero form frames on the new NCP")
    void attemptReopen_exactOrder_resumesNeverReforms() throws Exception {
        FakeNcp formingNcp = new FakeNcp();
        formingNcp.onEzspCommand(command -> formationHandler(formingNcp, command));
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        FakeNcp reopenedNcp = new FakeNcp();
        FakeSerialByteChannel second = channelOver(reopenedNcp);
        FakeSerialByteChannel first = channelOver(formingNcp);
        channels.push(second);
        channels.push(first);   // pop order: first, then second
        ZigbeeIntegrationAdapter adapter = adapter(
                List.of(coordinatorCandidate()), channels, null);
        adapter.initialize();
        PortCandidate port = adapter.resolvePort();
        adapter.bindTransport(port);
        adapter.coordinatorProtocol().startSession();
        adapter.resumeOrForm();
        adapter.coordinatorProtocol().awaitNetworkUp();
        NetworkParameters formed = new PersistentNetworkParameterStore(tempDir, clock)
                .load().orElseThrow();
        reopenedNcp.onEzspCommand(command -> resumeHandler(command, formed));

        boolean reopened = adapter.attemptReopen();

        assertThat(reopened).isTrue();
        assertThat(first.isOpen()).as("close precedes reopen").isFalse();
        assertThat(opened).containsExactly(first, second);
        List<byte[]> commands = reopenedNcp.receivedEzspCommands();
        assertThat(commands).isNotEmpty();
        // resetSession → startSession: the FIRST command on the reopened session
        // is the LEGACY version frame (renegotiation) — without resetSession,
        // startSession would early-return and networkInit would arrive first.
        byte[] renegotiation = commands.get(0);
        assertThat(renegotiation).hasSize(4);
        assertThat(renegotiation[1]).isEqualTo((byte) 0x00);
        assertThat(renegotiation[2]).isEqualTo((byte) FRAME_VERSION_LEGACY);
        assertThat(lastCommandWithFrameId(reopenedNcp, FRAME_NETWORK_INIT))
                .as("the reopen RESUMES the stored network")
                .isNotNull();
        assertThat(lastCommandWithFrameId(reopenedNcp, FRAME_SET_INITIAL_SECURITY_STATE))
                .as("a reopen that re-formed would orphan the fleet")
                .isNull();
        assertThat(lastCommandWithFrameId(reopenedNcp, FRAME_FORM_NETWORK)).isNull();
    }

    @Test
    @DisplayName("§5.6: a port that did not re-enumerate reports a failed attempt (backoff domain)")
    void attemptReopen_noTarget_false() throws Exception {
        FakeNcp formingNcp = new FakeNcp();
        formingNcp.onEzspCommand(command -> formationHandler(formingNcp, command));
        List<PortCandidate> enumerated = new ArrayList<>(List.of(coordinatorCandidate()));
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(formingNcp));
        ZigbeeIntegrationAdapter adapter = adapter(enumerated, channels, null);
        adapter.initialize();
        PortCandidate port = adapter.resolvePort();
        adapter.bindTransport(port);
        adapter.coordinatorProtocol().startSession();
        adapter.resumeOrForm();
        adapter.coordinatorProtocol().awaitNetworkUp();
        enumerated.clear();   // the stick vanished — nothing to reopen against

        assertThat(adapter.attemptReopen()).isFalse();
    }

    // ── scripted NCP handlers (v13 dialect) ─────────────────────────────────

    /** Formation-capable handler: scan + form + the NETWORK_UP callback. */
    private List<byte[]> formationHandler(FakeNcp ncp, byte[] command) {
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
            default -> defaultResponses(seq, command);
        };
    }

    /** Resume-capable handler: networkInit restored + a MATCHING live network. */
    private List<byte[]> resumeHandler(byte[] command, NetworkParameters stored) {
        if (isLegacyVersion(command)) {
            return List.of(new byte[] {
                command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74
            });
        }
        int seq = command[0] & 0xFF;
        return switch (frameIdOf(command)) {
            case FRAME_NETWORK_INIT -> List.of(
                    extendedResponse(seq, FRAME_NETWORK_INIT, new byte[] {0x00}),
                    new byte[] {0x00, (byte) 0x90, 0x01, 0x19, 0x00, (byte) 0x90});
            case 0x0028 -> List.of(extendedResponse(seq, 0x0028,
                    networkParametersStruct(stored.channel(), stored.panId(),
                            stored.extendedPanId())));
            default -> defaultResponses(seq, command);
        };
    }

    private List<byte[]> defaultResponses(int seq, byte[] command) {
        return switch (frameIdOf(command)) {
            case 0x0005 -> List.of(extendedResponse(seq, 0x0005, new byte[0]));
            case FRAME_NETWORK_INIT, FRAME_FORM_NETWORK, 0x0022,
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

    private static byte[] networkParametersStruct(int channel, int panId,
            long extendedPanId) {
        byte[] parameters = new byte[1 + 1 + 20];
        parameters[0] = 0x00;   // status SUCCESS (v13: 1 byte)
        parameters[1] = 0x01;   // nodeType: coordinator
        int offset = 2;
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

    private static byte[] lastCommandWithFrameId(FakeNcp ncp, int frameId) {
        byte[] match = null;
        for (byte[] command : ncp.receivedEzspCommands()) {
            if (!isLegacyVersion(command) && frameIdOf(command) == frameId) {
                match = command;
            }
        }
        return match;
    }

    // ── inert context stubs (the adapter never touches these paths here) ────

    private static ConfigurationAccess configAccess(String serialPort) {
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return Map.of();
            }

            @Override
            public Optional<String> getString(String key) {
                return ZigbeeIntegrationAdapter.SERIAL_PORT_KEY.equals(key)
                        ? Optional.ofNullable(serialPort)
                        : Optional.empty();
            }

            @Override
            public Optional<Integer> getInt(String key) {
                return Optional.empty();
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

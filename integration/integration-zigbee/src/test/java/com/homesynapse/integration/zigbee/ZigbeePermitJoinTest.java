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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9.4-PJ — permit-join config wiring: the headless/bench operator path from the
 * already-schema'd {@code integrations.zigbee.permit_join_duration} key to
 * {@link CoordinatorProtocol#permitJoin(int)}.
 *
 * <p>The north star is never-false-ALIVE extended to the join window: the window
 * opens ONLY in production mode, ONLY when the operator set the key, ONCE per boot,
 * and {@link ZigbeeIntegrationAdapter#isPermitJoinActive()} reflects the real
 * clock-based window — never open when closed. The scripted NCP already answers
 * frame {@code 0x0022} (permitJoining) through the shared default handler, so these
 * tests drive the production ladder over the {@link FakeNcp} and byte-assert the
 * emitted frame (the {@link ZigbeeProductionTransportTest} idiom).
 */
@DisplayName("ZigbeeIntegrationAdapter — permit-join config wiring (M9.4-PJ)")
class ZigbeePermitJoinTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        logCapture = new ListAppender<>();
        logCapture.start();
        adapterLogger().addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        adapterLogger().detachAppender(logCapture);
    }

    // ── §4.1 key present ⇒ ONE frame AFTER network-up, none before ──────────

    @Test
    @DisplayName("§4.1: a present key opens the window ONCE after network-up — exactly one "
            + "0x0022 frame carrying the configured duration, none emitted during boot")
    void keyPresent_opensWindowOnce_afterNetworkUp() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null, 200);

        assertThat(countFrames(ncp, FRAME_PERMIT_JOINING))
                .as("no permit-join frame is emitted during boot, before the window opens")
                .isZero();

        adapter.openPermitJoinWindow();

        List<byte[]> joins = framesWithId(ncp, FRAME_PERMIT_JOINING);
        assertThat(joins).as("exactly one permit-join frame after network-up").hasSize(1);
        assertThat(joins.get(0)[5]).as("the duration byte rides the frame")
                .isEqualTo((byte) 200);
        assertThat(adapter.isPermitJoinActive()).as("the window is open").isTrue();
    }

    // ── §4.2 key absent ⇒ zero frames across the whole boot ─────────────────

    @Test
    @DisplayName("§4.2: an absent key opens nothing — zero 0x0022 frames across the whole "
            + "production boot (conservative default is LAW)")
    void keyAbsent_neverOpens() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null, null);

        adapter.openPermitJoinWindow();

        assertThat(countFrames(ncp, FRAME_PERMIT_JOINING))
                .as("no key ⇒ no permit-join frame ever")
                .isZero();
        assertThat(adapter.isPermitJoinActive()).as("the window stays closed").isFalse();
    }

    // ── §4.3 out-of-range ⇒ clamp to 254 + ONE WARN ────────────────────────

    @Test
    @DisplayName("§4.3: an out-of-range key (999) is clamped to 254 with ONE WARN, and the "
            + "clamped duration rides the frame (never the protocol's own range throw)")
    void keyOutOfRange_clampsToMax_withWarn() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null, 999);

        adapter.openPermitJoinWindow();

        List<byte[]> joins = framesWithId(ncp, FRAME_PERMIT_JOINING);
        assertThat(joins).as("exactly one permit-join frame").hasSize(1);
        assertThat(joins.get(0)[5]).as("the clamped duration byte")
                .isEqualTo((byte) 254);
        assertThat(clampWarns())
                .as("exactly one clamp WARN naming configured + clamped")
                .containsExactly("zigbee.permit_join_clamped: configured=999 clamped=254");
        assertThat(adapter.isPermitJoinActive()).isTrue();
    }

    // ── §4.4 honest window state (clock-based) ─────────────────────────────

    @Test
    @DisplayName("§4.4: isPermitJoinActive reflects the real clock window — false before open, "
            + "true inside, false once the clock passes the deadline")
    void isPermitJoinActive_reflectsClockWindow() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null, 200);

        assertThat(adapter.isPermitJoinActive()).as("closed before open").isFalse();

        adapter.openPermitJoinWindow();
        assertThat(adapter.isPermitJoinActive()).as("open at t0").isTrue();

        clock.advance(Duration.ofSeconds(199));
        assertThat(adapter.isPermitJoinActive())
                .as("still open one second before the deadline").isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(adapter.isPermitJoinActive())
                .as("closed once the clock reaches the deadline").isFalse();
    }

    // ── §4.5 driven mode is untouched (the M9.4a hero substrate) ────────────

    @Test
    @DisplayName("§4.5: the M9.4a driven cadence never opens the window even with the key set "
            + "— runCycleOnce() is untouched, so the window never opens")
    void drivenMode_neverOpensWindow() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        FakeSerialByteChannel channel = channelOver(ncp);
        // Driven mode: an injected channel opener selects the M9.4a rig path; the
        // key is set to prove it is production-only — the driven cadence ignores it.
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(null, 200)), new InMemoryDeviceRegistry(),
                new RegistryProjection(new InMemoryDeviceRegistry(),
                        new InMemoryEntityRegistry()),
                tempDir, clock, ignored -> channel);
        adapter.initialize();

        assertThat(adapter.isPermitJoinActive()).isFalse();
        adapter.runCycleOnce();
        adapter.runCycleOnce();

        assertThat(adapter.isPermitJoinActive())
                .as("the driven cadence never opens permit-join — the M9.4a substrate is untouched")
                .isFalse();
        assertThat(countFrames(ncp, FRAME_PERMIT_JOINING))
                .as("zero 0x0022 frames across the driven cadence")
                .isZero();
    }

    // ── M9.5-DURb §4 (DP-B5): a successful reopen clears the window ─────────

    @Test
    @DisplayName("DP-B5: a successful watchdog reopen clears permitJoinDeadline — the "
            + "reset NCP holds no window, so isPermitJoinActive never reads stale-true "
            + "(the M9.4-TCJ recorded limitation, closed)")
    void reopenClearsThePermitJoinDeadline() throws Exception {
        FakeNcp formingNcp = new FakeNcp();
        formingNcp.onEzspCommand(command -> formationHandler(formingNcp, command));
        FakeNcp reopenedNcp = new FakeNcp();
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(reopenedNcp));
        channels.push(channelOver(formingNcp));   // pop order: forming, then reopened
        ZigbeeIntegrationAdapter adapter = bootProduction(channels, null, 200);
        adapter.openPermitJoinWindow();
        assertThat(adapter.isPermitJoinActive()).as("the window opened").isTrue();
        NetworkParameters formed = new PersistentNetworkParameterStore(tempDir, clock)
                .load().orElseThrow();
        reopenedNcp.onEzspCommand(command -> resumeHandler(command, formed));

        assertThat(adapter.attemptReopen()).isTrue();

        assertThat(adapter.isPermitJoinActive())
                .as("the deadline is cleared inside the un-elapsed window — a reopen "
                        + "resets NCP-side policy/key/MAC-window state, and the "
                        + "adapter no longer claims a window the NCP does not hold")
                .isFalse();
    }

    // ── harness ─────────────────────────────────────────────────────────────

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

    /** Boots a production adapter through the full §5.1 ladder to a formed network. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp, String serialPort,
            Integer permitJoinDuration) throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        return bootProduction(channels, serialPort, permitJoinDuration);
    }

    /** The multi-channel variant (the reopen leg pops a second channel). */
    private ZigbeeIntegrationAdapter bootProduction(
            Deque<FakeSerialByteChannel> channels, String serialPort,
            Integer permitJoinDuration) throws Exception {
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(serialPort, permitJoinDuration)),
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

    // ── scripted NCP (v13 dialect — the form path only) ─────────────────────

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

    /** Resume-capable handler for the reopened NCP (the transport-test mirror). */
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

    private List<byte[]> defaultResponses(int seq, byte[] command) {
        int frameId = frameIdOf(command);
        // M9.4-TCJ §A: window-open now runs setPolicy ×2 + importTransientKey
        // BEFORE the 0x0022 — answer them so the ladder reaches the join frame
        // (ZigbeeTrustCenterJoinTest owns the enablement assertions).
        if (frameId == EzspCoordinatorProtocol.FRAME_SET_POLICY) {
            return List.of(extendedResponse(seq, frameId, new byte[] {0x00}));
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY) {
            return List.of(extendedResponse(seq, frameId,
                    new byte[] {0x00, 0x00, 0x00, 0x00}));
        }
        return switch (frameId) {
            case 0x0005 -> List.of(extendedResponse(seq, 0x0005, new byte[0]));
            case FRAME_NETWORK_INIT, FRAME_FORM_NETWORK, FRAME_PERMIT_JOINING,
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

    private List<String> clampWarns() {
        return logCapture.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("zigbee.permit_join_clamped"))
                .toList();
    }

    private static Logger adapterLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);
    }

    // ── inert context stubs (the adapter never touches these paths here) ────

    private static ConfigurationAccess configAccess(String serialPort,
            Integer permitJoinDuration) {
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

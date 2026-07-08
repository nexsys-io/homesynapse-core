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
 * M9.4-TCJ §B — channel-pin config wiring: the already-schema'd
 * {@code integrations.zigbee.channel} key (11–26) into the FIRST-formation path.
 *
 * <p>A present, in-range key forms directly on that channel — the energy scan never
 * runs and the pin is logged ({@code zigbee.channel_pinned}); an absent key leaves
 * the §3.13 energy-scan two-tier selection unchanged; an out-of-range value logs one
 * WARN and falls back to the scan (the schema validates upstream — the guard is the
 * defensive floor). The resume path is untouched: a formed network resumes on its
 * STORED channel regardless of the configured pin (RESUME-never-re-form is LAW).
 * Same scripted-NCP production-ladder idiom as {@link ZigbeePermitJoinTest}.
 */
@DisplayName("ZigbeeIntegrationAdapter — channel-pin config wiring (M9.4-TCJ §B)")
class ZigbeeChannelPinTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_NETWORK_STATE = 0x0018;
    private static final int FRAME_GET_NETWORK_PARAMETERS = 0x0028;
    private static final int FRAME_PERMIT_JOINING = 0x0022;

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private ListAppender<ILoggingEvent> adapterLogCapture;
    private ListAppender<ILoggingEvent> formationLogCapture;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        adapterLogCapture = new ListAppender<>();
        adapterLogCapture.start();
        adapterLogger().addAppender(adapterLogCapture);
        formationLogCapture = new ListAppender<>();
        formationLogCapture.start();
        formationLogger().addAppender(formationLogCapture);
    }

    @AfterEach
    void tearDown() {
        adapterLogger().detachAppender(adapterLogCapture);
        formationLogger().detachAppender(formationLogCapture);
    }

    // ── §B.1 key present ⇒ pinned formation, zero energy scans ──────────────

    @Test
    @DisplayName("§B.1: a present in-range key forms directly on that channel — zero "
            + "0x001A energy-scan frames, the 0x001E frame carries the pinned channel, "
            + "and the pin is logged")
    void channelPresent_formsPinned_noEnergyScan() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, 20);

        assertThat(countFrames(ncp, FRAME_START_SCAN))
                .as("the energy scan never runs when the channel is pinned")
                .isZero();
        List<byte[]> forms = framesWithId(ncp, FRAME_FORM_NETWORK);
        assertThat(forms).as("exactly one formation").hasSize(1);
        assertThat(forms.get(0)[16])
                .as("the pinned channel rides the formNetwork struct (offset 11)")
                .isEqualTo((byte) 20);
        assertThat(adapter.networkParameters().channel()).isEqualTo(20);
        assertThat(pinnedInfos())
                .as("the pin is logged once")
                .containsExactly("zigbee.channel_pinned: channel=20");
    }

    // ── §B.2 key absent ⇒ the energy-scan selection is unchanged ────────────

    @Test
    @DisplayName("§B.2: an absent key runs the §3.13 energy scan exactly as today — "
            + "one 0x001A frame, the scan-selected channel forms, no pin log")
    void channelAbsent_energyScanSelects() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        assertThat(countFrames(ncp, FRAME_START_SCAN))
                .as("the energy scan runs on the unpinned form path")
                .isEqualTo(1);
        // The scripted scan reports channel 20 quietest among the primaries.
        assertThat(adapter.networkParameters().channel()).isEqualTo(20);
        assertThat(pinnedInfos()).as("no pin was configured").isEmpty();
    }

    // ── §B.3 out-of-range ⇒ one WARN, the energy scan still selects ─────────

    @Test
    @DisplayName("§B.3: an out-of-range key (27) is ignored with ONE WARN and the "
            + "energy scan selects the channel (never the NetworkParameters range throw)")
    void channelOutOfRange_ignoredWithWarn_energyScanRuns() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, 27);

        assertThat(countFrames(ncp, FRAME_START_SCAN)).isEqualTo(1);
        assertThat(adapter.networkParameters().channel()).isEqualTo(20);
        assertThat(pinIgnoredWarns())
                .as("exactly one WARN naming the ignored value and the valid range")
                .containsExactly("zigbee.channel_pin_ignored: configured=27 "
                        + "range=11-26; the energy scan selects the channel");
        assertThat(pinnedInfos()).isEmpty();
    }

    // ── §B.4 resume untouched ⇒ the stored channel wins over the pin ────────

    @Test
    @DisplayName("§B.4: the resume path never reads the pin — a network formed on "
            + "channel 20 resumes on 20 with a channel=25 key configured, zero scans, "
            + "zero re-formations")
    void resumeUntouched_storedChannelWins() throws Exception {
        FakeNcp formingNcp = new FakeNcp();
        formingNcp.onEzspCommand(command -> formationHandler(formingNcp, command));
        ZigbeeIntegrationAdapter first = bootProduction(formingNcp, 20);
        NetworkParameters formed = first.networkParameters();
        assertThat(formed.channel()).isEqualTo(20);

        FakeNcp resumingNcp = new FakeNcp();
        resumingNcp.onEzspCommand(
                command -> resumeHandler(resumingNcp, command, formed));
        ZigbeeIntegrationAdapter second = bootProduction(resumingNcp, 25);

        assertThat(countFrames(resumingNcp, FRAME_START_SCAN))
                .as("resume never scans").isZero();
        assertThat(countFrames(resumingNcp, FRAME_FORM_NETWORK))
                .as("resume never re-forms").isZero();
        assertThat(second.networkParameters().channel())
                .as("the stored channel outranks the configured pin")
                .isEqualTo(20);
        assertThat(pinnedInfos())
                .as("the pin log belongs to the FIRST formation only")
                .containsExactly("zigbee.channel_pinned: channel=20");
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

    /** Boots a production adapter through the full §5.1 ladder over {@code tempDir}. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp, Integer channelPin)
            throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(channelPin)),
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

    // ── scripted NCP (v13 dialect) ──────────────────────────────────────────

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

    /** Resume-capable handler: networkInit restores; parameters echo the stored net. */
    private List<byte[]> resumeHandler(FakeNcp ncp, byte[] command,
            NetworkParameters stored) {
        if (isLegacyVersion(command)) {
            return List.of(new byte[] {
                command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74
            });
        }
        int seq = command[0] & 0xFF;
        return switch (frameIdOf(command)) {
            case FRAME_NETWORK_INIT -> List.of(
                    extendedResponse(seq, FRAME_NETWORK_INIT, new byte[] {0x00}),
                    // NETWORK_UP rides the restore — the §5.3 await consumes it.
                    new byte[] {0x00, (byte) 0x90, 0x01, 0x19, 0x00, (byte) 0x90});
            case FRAME_NETWORK_STATE -> List.of(
                    extendedResponse(seq, FRAME_NETWORK_STATE, new byte[] {0x02}));
            case FRAME_GET_NETWORK_PARAMETERS -> List.of(extendedResponse(seq,
                    FRAME_GET_NETWORK_PARAMETERS, networkParametersResponse(stored)));
            default -> defaultResponses(seq, command);
        };
    }

    /** The v13 getNetworkParameters response: status + nodeType + the 20-byte struct. */
    private static byte[] networkParametersResponse(NetworkParameters stored) {
        byte[] p = new byte[22];
        p[0] = 0x00;   // EmberStatus SUCCESS
        p[1] = 0x01;   // nodeType: coordinator
        for (int i = 0; i < 8; i++) {
            p[2 + i] = (byte) ((stored.extendedPanId() >> (8 * i)) & 0xFF);
        }
        p[10] = (byte) (stored.panId() & 0xFF);
        p[11] = (byte) ((stored.panId() >> 8) & 0xFF);
        p[12] = 8;     // radioTxPower
        p[13] = (byte) stored.channel();
        // joinMethod, nwkManagerId, nwkUpdateId, channel mask: zeros.
        return p;
    }

    private List<byte[]> defaultResponses(int seq, byte[] command) {
        return switch (frameIdOf(command)) {
            case 0x0005 -> List.of(extendedResponse(seq, 0x0005, new byte[0]));
            case FRAME_NETWORK_INIT, FRAME_FORM_NETWORK, FRAME_PERMIT_JOINING,
                    FRAME_SET_INITIAL_SECURITY_STATE ->
                    List.of(extendedResponse(seq, frameIdOf(command),
                            new byte[] {0x00}));
            case FRAME_NETWORK_STATE -> List.of(
                    extendedResponse(seq, FRAME_NETWORK_STATE, new byte[] {0x02}));
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

    private List<String> pinnedInfos() {
        return formationLogCapture.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("zigbee.channel_pinned"))
                .toList();
    }

    private List<String> pinIgnoredWarns() {
        return adapterLogCapture.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("zigbee.channel_pin_ignored"))
                .toList();
    }

    private static Logger adapterLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);
    }

    private static Logger formationLogger() {
        return (Logger) LoggerFactory.getLogger(NetworkFormation.class);
    }

    // ── inert context stubs (the adapter never touches these paths here) ────

    private static ConfigurationAccess configAccess(Integer channelPin) {
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
                return ZigbeeIntegrationAdapter.CHANNEL_KEY.equals(key)
                        ? Optional.ofNullable(channelPin)
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

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
 * M9.4-RPT §B — key-establishment observability: the ingestion drain consumes
 * EZSP {@code zigbeeKeyEstablishmentHandler} (0x009B) LOG-ONLY, instrumenting
 * the OBS-2 discriminator (if the SNZB's post-join leave is the TCLK-update
 * class, iteration 4's log SHOWS it).
 *
 * <p><strong>THE PIN (test-enforced):</strong> the handler never creates a
 * device, never schedules an interview, never publishes an event, never alters
 * adoption/availability — pure observability, the M9.4-TCJ 0x0024/0x0023
 * precedent.
 */
@DisplayName("ZclIngestionUnit — key-establishment observability (M9.4-RPT §B)")
class ZigbeeKeyEstablishmentTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;
    private static final int FRAME_SEND_UNICAST = 0x0034;
    private static final int FRAME_NOP = 0x0005;

    private static final long PARTNER_IEEE = 0x00124B0012345678L;
    private static final String PARTNER_HEX = "0x00124B0012345678";

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private ListAppender<ILoggingEvent> ingestionLogCapture;

    /** Callback frames delivered on the NEXT nop keepalive (the rig pump idiom). */
    private final Deque<byte[]> riders = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        ingestionLogCapture = new ListAppender<>();
        ingestionLogCapture.start();
        ingestionLogger().addAppender(ingestionLogCapture);
    }

    @AfterEach
    void tearDown() {
        ingestionLogger().detachAppender(ingestionLogCapture);
    }

    @Test
    @DisplayName("a success-class status logs ONE INFO naming device + status — "
            + "and nothing else happens (THE PIN)")
    void successStatus_logsInfo_neverSynthesizes() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::formationHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp);

        deliver(adapter, keyEstablishmentCallback(PARTNER_IEEE,
                EzspCoordinatorProtocol
                        .KEY_STATUS_TRUST_CENTER_LINK_KEY_ESTABLISHED));

        assertThat(ingestionMessages(Level.INFO, "zigbee.key_established"))
                .containsExactly("zigbee.key_established: device=" + PARTNER_HEX
                        + " status=TRUST_CENTER_LINK_KEY_ESTABLISHED");
        assertThat(ingestionMessages(Level.WARN, "zigbee.key_establishment_failed"))
                .isEmpty();
        assertPin(adapter, ncp);
    }

    @Test
    @DisplayName("a failure-class status logs ONE WARN naming device + status — "
            + "and nothing else happens (THE PIN)")
    void failureStatus_logsWarn_neverSynthesizes() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::formationHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp);

        deliver(adapter, keyEstablishmentCallback(PARTNER_IEEE,
                EzspCoordinatorProtocol
                        .KEY_STATUS_TC_REQUESTER_VERIFY_KEY_FAILURE));

        assertThat(ingestionMessages(Level.WARN, "zigbee.key_establishment_failed"))
                .containsExactly("zigbee.key_establishment_failed: device="
                        + PARTNER_HEX + " status=TC_REQUESTER_VERIFY_KEY_FAILURE");
        assertThat(ingestionMessages(Level.INFO, "zigbee.key_established")).isEmpty();
        assertPin(adapter, ncp);
    }

    @Test
    @DisplayName("an unknown status is WARNed with its hex — never assumed benign")
    void unknownStatus_logsWarnWithHex() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::formationHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp);

        deliver(adapter, keyEstablishmentCallback(PARTNER_IEEE, 0x77));

        assertThat(ingestionMessages(Level.WARN, "zigbee.key_establishment_failed"))
                .containsExactly("zigbee.key_establishment_failed: device="
                        + PARTNER_HEX + " status=0x77");
        assertPin(adapter, ncp);
    }

    @Test
    @DisplayName("a malformed (short) payload is dropped without INFO or WARN")
    void malformedPayload_droppedSilently() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::formationHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp);

        deliver(adapter, callbackFrame(
                EzspCoordinatorProtocol.FRAME_ZIGBEE_KEY_ESTABLISHMENT_HANDLER,
                new byte[] {0x01, 0x02, 0x03}));

        assertThat(ingestionMessages(Level.INFO, "zigbee.key_established")).isEmpty();
        assertThat(ingestionMessages(Level.WARN, "zigbee.key_establishment_failed"))
                .isEmpty();
        assertPin(adapter, ncp);
    }

    /**
     * THE PIN: zero devices, zero interviews, zero events, zero adoption or
     * availability changes from the 0x009B handler — pure observability.
     */
    private void assertPin(ZigbeeIntegrationAdapter adapter, FakeNcp ncp) {
        assertThat(publisher.published())
                .as("the handler never publishes an event").isEmpty();
        assertThat(adapter.device(new IEEEAddress(PARTNER_IEEE)))
                .as("the handler never creates a device").isEmpty();
        assertThat(interviewUnicasts(ncp))
                .as("the handler never schedules an interview").isZero();
        assertThat(deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(PARTNER_IEEE).toHexString()))
                .as("adoption state untouched").isEmpty();
        // A further cycle stays inert — nothing was scheduled.
        adapter.runCycleOnce();
        assertThat(interviewUnicasts(ncp)).isZero();
    }

    /** sendUnicast (0x0034) count — frame-id-scoped, never a total. */
    private static long interviewUnicasts(FakeNcp ncp) {
        return ncp.receivedEzspCommands().stream()
                .filter(command -> !isLegacyVersion(command)
                        && frameIdOf(command) == FRAME_SEND_UNICAST)
                .count();
    }

    // ── harness (the production-ladder idiom, no interview scripting) ───────

    private static PortCandidate coordinatorCandidate() {
        return new PortCandidate("/dev/ttyUSB7",
                "/dev/serial/by-id/usb-ITEAD_SONOFF_20240001-if00-port0",
                PortLocator.VENDOR_SILICON_LABS_CP210X,
                PortLocator.PRODUCT_CP210X_UART_BRIDGE, null);
    }

    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp) throws Exception {
        FakeSerialByteChannel channel = new FakeSerialByteChannel(clock);
        channel.onWrite(ncp);
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channel);
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(), deviceRegistry, tempDir, clock, null,
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

    /** Rides one callback on the nop keepalive, then runs one §G cycle. */
    private void deliver(ZigbeeIntegrationAdapter adapter, byte[] callback) {
        riders.add(callback);
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();
    }

    private IntegrationContext context() {
        return new IntegrationContext(
                new IntegrationId(UlidFactory.generate(clock)), "zigbee", publisher,
                entityRegistry, unusedQueryService(),
                unusedHealthReporter(), emptyConfigAccess(),
                null, null, null, null, null);
    }

    // ── scripted NCP (formation-only; riders pump on the nop keepalive) ─────

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
                    new byte[] {0x00, (byte) 0x90, 0x01, 0x19, 0x00, (byte) 0x90});
            case FRAME_NOP -> {
                List<byte[]> frames = new ArrayList<>();
                while (!riders.isEmpty()) {
                    frames.add(riders.poll());
                }
                frames.add(extendedResponse(seq, FRAME_NOP, new byte[0]));
                yield frames;
            }
            case FRAME_NETWORK_INIT, FRAME_PERMIT_JOINING,
                    FRAME_SET_INITIAL_SECURITY_STATE ->
                    List.of(extendedResponse(seq, frameIdOf(command),
                            new byte[] {0x00}));
            case 0x0018 -> List.of(extendedResponse(seq, 0x0018, new byte[] {0x02}));
            default -> List.of();
        };
    }

    // ── callback fixtures ────────────────────────────────────────────────────

    /** The 0x009B payload: partner EUI64 LE + status u8 (BENCH-VERIFY layout). */
    private static byte[] keyEstablishmentCallback(long partner, int status) {
        byte[] parameters = new byte[9];
        for (int i = 0; i < 8; i++) {
            parameters[i] = (byte) (partner >> (8 * i));
        }
        parameters[8] = (byte) status;
        return callbackFrame(
                EzspCoordinatorProtocol.FRAME_ZIGBEE_KEY_ESTABLISHMENT_HANDLER,
                parameters);
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

    // ── v13 frame helpers ────────────────────────────────────────────────────

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

    // ── log capture ──────────────────────────────────────────────────────────

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

    // ── inert context stubs ──────────────────────────────────────────────────

    private static ConfigurationAccess emptyConfigAccess() {
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

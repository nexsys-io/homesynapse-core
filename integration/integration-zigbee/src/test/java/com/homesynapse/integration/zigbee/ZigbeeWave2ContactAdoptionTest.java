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
import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
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
import java.nio.file.Files;
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
 * M9.7-W2 §4 — the learned-zoneType wiring proven END-TO-END over the scripted
 * NCP (the {@code ZigbeeConfigAcceptedAdoptionTest} production-ladder idiom):
 * an SNZB-04P-shaped device announces, its wire ZoneType (CONTACT, 0x0015)
 * arrives in the SAME ingestion drain — the joins-night choreography guard:
 * {@code ias_zone_type_learned … CONTACT} is observed BEFORE the adopt step —
 * and the accepted adoption lands a BINARY_SENSOR whose installed capability
 * set carries {@code contact} (DP-6 learned-first). Without the learn the same
 * join classifies {@code motion} — byte-for-byte the pre-W2 fallback.
 *
 * <p>W2-LEARN adds the enroll-driven twin: the CONTACT truth arrives ONLY via
 * a ZoneEnrollRequest in the drain (ZCL8 §8.2.2.3 — zoneType rides the request
 * payload; no device volunteers the attribute on real silicon, the joins-night
 * record) — the ACTUAL choreography the long-press re-pair runs.
 */
@DisplayName("ZigbeeIntegrationAdapter — Wave-2 contact adoption (M9.7-W2 §4)")
class ZigbeeWave2ContactAdoptionTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;
    private static final int FRAME_SEND_UNICAST = 0x0034;
    private static final int FRAME_NOP = 0x0005;

    /** The scripted joiner — the SNZB-04P dossier shape (zha machine capture). */
    private static final long CONTACT_IEEE = 0x00124B00AA0004B4L;
    private static final int CONTACT_NWK = 0x7C21;
    private static final int CONTACT_ENDPOINT = 1;
    private static final String CONTACT_LISTED = "0x00124b00aa0004b4";

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private IntegrationId integrationId;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private ListAppender<ILoggingEvent> ingestionLogCapture;

    /** Callback frames delivered on the NEXT nop keepalive (the rig pump idiom). */
    private final Deque<byte[]> riders = new ArrayDeque<>();

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ZigbeeWave2ContactAdoptionTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        integrationId = new IntegrationId(UlidFactory.generate(clock));
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
    @DisplayName("a wire-learned CONTACT before the adopt step lands a "
            + "BINARY_SENSOR carrying contact — the §4 chain (ingestion learn → "
            + "adapter seam → slice → classifier) end-to-end")
    void learnedContactBeforeAdoption_landsContactEntity() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp);

        // The choreography guard: the announce indexes the device, then the
        // ZoneType report (CONTACT) learns — BOTH in the drain the SAME cycle
        // processes before its interview step runs the adoption.
        riders.add(deviceAnnounceCallback(CONTACT_IEEE, CONTACT_NWK));
        riders.add(zoneTypeReportCallback());
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();

        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("the learn is observed BEFORE the adopt step (the go-package "
                        + "guard's log surface)")
                .containsExactly("zigbee.ias_zone_type_learned: "
                        + "device=0x00124B00AA0004B4 zoneType=CONTACT (was MOTION)");
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("the listed COMPLETE proposal adopted").isEqualTo(1);
        Optional<Device> device = deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(CONTACT_IEEE).toHexString());
        assertThat(device).isPresent();
        List<Entity> entities =
                entityRegistry.listEntitiesByDevice(device.get().deviceId());
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).entityType())
                .isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(entities.get(0).capabilities())
                .extracting(c -> c.capabilityId())
                .as("the learned CONTACT selects contact — never the motion "
                        + "fallback")
                .containsExactlyInAnyOrder("contact", "battery", "identify");
        assertThat(adapter.adoptionSlice().matchedProfileIdFor(
                new IEEEAddress(CONTACT_IEEE)))
                .as("the Wave-2 exact_model profile matched through the REAL "
                        + "registry")
                .contains("sonoff_snzb_04p");
    }

    @Test
    @DisplayName("the same join WITHOUT a wire learn classifies motion — the "
            + "DP-6 unlearned fallback through the real wiring")
    void unlearnedJoin_fallsBackToMotionEntity() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp);

        riders.add(deviceAnnounceCallback(CONTACT_IEEE, CONTACT_NWK));
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .isEqualTo(1);
        Optional<Device> device = deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(CONTACT_IEEE).toHexString());
        List<Entity> entities =
                entityRegistry.listEntitiesByDevice(device.orElseThrow().deviceId());
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).capabilities())
                .extracting(c -> c.capabilityId())
                .containsExactlyInAnyOrder("motion", "battery", "identify");
    }

    @Test
    @DisplayName("W2-LEARN: the enroll-driven twin — the CONTACT truth arrives "
            + "ONLY via a ZoneEnrollRequest in the drain, the learn is observed "
            + "BEFORE the adopt step, and the adopted entity carries contact "
            + "(the silicon choreography: long-press re-pair → enroll → learn "
            + "→ adopt)")
    void enrollDrivenLearnBeforeAdoption_landsContactEntity() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp);

        // The joins-night choreography: the announce indexes the device, then
        // the device's OWN ZoneEnrollRequest (zoneType 0x0015 in the request
        // payload) learns — NO ZoneType attribute report anywhere in this walk.
        riders.add(deviceAnnounceCallback(CONTACT_IEEE, CONTACT_NWK));
        riders.add(zoneEnrollRequestCallback());
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();

        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("the enroll-payload learn is observed BEFORE the adopt "
                        + "step (the go-package guard's log surface)")
                .containsExactly("zigbee.ias_zone_type_learned: "
                        + "device=0x00124B00AA0004B4 zoneType=CONTACT "
                        + "(was MOTION)");
        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_enrolled"))
                .as("enrollment still granted through the production send path "
                        + "— the learn never gates the response")
                .hasSize(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("the listed COMPLETE proposal adopted").isEqualTo(1);
        Optional<Device> device = deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(CONTACT_IEEE).toHexString());
        List<Entity> entities =
                entityRegistry.listEntitiesByDevice(device.orElseThrow().deviceId());
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).entityType())
                .isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(entities.get(0).capabilities())
                .extracting(c -> c.capabilityId())
                .as("the enroll-learned CONTACT selects contact — never the "
                        + "motion fallback")
                .containsExactlyInAnyOrder("contact", "battery", "identify");
    }

    @Test
    @DisplayName("LEARN-PERSIST: the wire learn PERSISTS across a restart — the "
            + "next session's fast propose adopts CONTACT with ZERO enroll or "
            + "attribute frames processed (the 2026-07-19 bench race, "
            + "hardware-free)")
    void persistedLearnSurvivesRestart_fastProposeReadsContact()
            throws Exception {
        // Session A — window #1: the device joins + enrolls while UNLISTED;
        // the learn lands and persists, nothing adopts (the bench mechanism:
        // listing the device requires the restart that wipes the in-memory
        // learned map).
        FakeNcp ncpA = new FakeNcp();
        ncpA.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter sessionA =
                bootProduction(ncpA, configAccess(List.of()));
        riders.add(deviceAnnounceCallback(CONTACT_IEEE, CONTACT_NWK));
        riders.add(zoneEnrollRequestCallback());
        sessionA.coordinatorProtocol().ping();
        sessionA.runCycleOnce();
        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("window #1: the enroll-payload learn landed")
                .hasSize(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("unlisted — the proposal sits, nothing adopts in session A")
                .isZero();
        sessionA.deviceCache().flush();

        // The restart: every in-memory surface rebuilds fresh (registries,
        // publisher, adapter, NCP) — only the data directory survives. The
        // NCP wrapper answers networkInit NOT_JOINED (0x93): a fresh fake
        // coordinator holds no NVRAM network, so resume takes the
        // restore-from-parameters arm over the already-scripted form frames.
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        publisher = new RecordingEventPublisher(clock);
        ingestionLogCapture.list.clear();
        FakeNcp ncpB = new FakeNcp();
        ncpB.onEzspCommand(command -> {
            if (!isLegacyVersion(command)
                    && frameIdOf(command) == FRAME_NETWORK_INIT) {
                return List.of(extendedResponse(command[0] & 0xFF,
                        FRAME_NETWORK_INIT, new byte[] {(byte) 0x93}));
            }
            return adoptionHandler(command);
        });
        ListAppender<ILoggingEvent> adapterLogCapture = new ListAppender<>();
        adapterLogCapture.start();
        adapterLogger().addAppender(adapterLogCapture);
        ZigbeeIntegrationAdapter sessionB;
        try {
            sessionB = bootProduction(ncpB,
                    configAccess(List.of(CONTACT_LISTED)));
        } finally {
            adapterLogger().detachAppender(adapterLogCapture);
        }
        assertThat(adapterMessages(adapterLogCapture, Level.INFO,
                "zigbee.learned_zonetypes_rehydrated"))
                .as("the DP-LP-6 glance-point: persistence confirmed at boot, "
                        + "BEFORE any window opens")
                .containsExactly("zigbee.learned_zonetypes_rehydrated: count=1");

        // Session B — window #2: the fast propose. ONLY the announce rides —
        // no enroll, no ZoneType attribute, nothing IAS at all.
        riders.add(deviceAnnounceCallback(CONTACT_IEEE, CONTACT_NWK));
        sessionB.coordinatorProtocol().ping();
        sessionB.runCycleOnce();

        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("session B never learns on the wire — the truth rehydrated")
                .isEmpty();
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("the listed COMPLETE proposal adopted")
                .isEqualTo(1);
        Optional<Device> device = deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(CONTACT_IEEE).toHexString());
        List<Entity> entities = entityRegistry
                .listEntitiesByDevice(device.orElseThrow().deviceId());
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).entityType())
                .isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(entities.get(0).capabilities())
                .extracting(c -> c.capabilityId())
                .as("adopt-time classification reads the PERSISTED learn — "
                        + "never the MOTION default the bench race would have "
                        + "durably shipped")
                .containsExactlyInAnyOrder("contact", "battery", "identify");
    }

    @Test
    @DisplayName("LEARN-PERSIST DP-LP-6: the boot INFO fires with the APPLIED "
            + "count — tolerance-skipped ids excluded, and a learn needs no "
            + "device record to rehydrate")
    void rehydratedCountFiresAndExcludesUnknownIds() throws Exception {
        Files.writeString(tempDir.resolve("zigbee-devices.json"), """
                {
                  "version" : 1,
                  "devices" : [ ],
                  "learnedZoneTypes" : {
                    "0x00124B00AA0004B4" : 21,
                    "0x00124B00FFFF0001" : 30583
                  }
                }
                """, StandardCharsets.UTF_8);
        ListAppender<ILoggingEvent> adapterLogCapture = new ListAppender<>();
        adapterLogCapture.start();
        adapterLogger().addAppender(adapterLogCapture);
        try {
            FakeNcp ncp = new FakeNcp();
            ncp.onEzspCommand(this::adoptionHandler);
            bootProduction(ncp);
        } finally {
            adapterLogger().detachAppender(adapterLogCapture);
        }

        assertThat(adapterMessages(adapterLogCapture, Level.INFO,
                "zigbee.learned_zonetypes_rehydrated"))
                .as("count = APPLIED entries: the unknown id 0x7777 is "
                        + "tolerance-skipped; the CONTACT learn applies with "
                        + "no devices[] node at all")
                .containsExactly("zigbee.learned_zonetypes_rehydrated: count=1");
    }

    // ── harness (the ZigbeeConfigAcceptedAdoptionTest production-ladder idiom) ─

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

    private IntegrationContext context() {
        return new IntegrationContext(
                integrationId, "zigbee", publisher,
                entityRegistry, unusedQueryService(),
                unusedHealthReporter(), configAccess(),
                null, null, null, null, null);
    }

    /** Boots a production adapter through the full §5.1 ladder to a formed network. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp) throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(), deviceRegistry,
                new RegistryProjection(deviceRegistry, entityRegistry),
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

    // ── LEARN-PERSIST harness additions (the existing helpers above stay
    // byte-untouched; the restart leg needs per-session accept lists) ────────

    /** {@link #configAccess()} with an explicit accept list (LEARN-PERSIST). */
    private static ConfigurationAccess configAccess(List<String> adoptDevices) {
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return Map.of(ZigbeeIntegrationAdapter.ADOPT_DEVICES_KEY,
                        adoptDevices);
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

    private IntegrationContext context(ConfigurationAccess config) {
        return new IntegrationContext(
                integrationId, "zigbee", publisher,
                entityRegistry, unusedQueryService(),
                unusedHealthReporter(), config,
                null, null, null, null, null);
    }

    /** {@link #bootProduction(FakeNcp)} with an explicit accept-list config. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp,
            ConfigurationAccess config) throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(config), deviceRegistry,
                new RegistryProjection(deviceRegistry, entityRegistry),
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

    private static Logger adapterLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);
    }

    private static List<String> adapterMessages(
            ListAppender<ILoggingEvent> capture, Level level, String prefix) {
        return capture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
    }

    // ── scripted NCP (v13 dialect; the SNZB-04P interview walk) ─────────────

    private List<byte[]> adoptionHandler(byte[] command) {
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
                // Riders BEFORE the nop response (the rig idiom): the receive
                // loop returns at the matching response, so callbacks must
                // precede it to park in the protocol's queue.
                List<byte[]> frames = new ArrayList<>();
                while (!riders.isEmpty()) {
                    frames.add(riders.poll());
                }
                frames.add(extendedResponse(seq, FRAME_NOP, new byte[0]));
                yield frames;
            }
            case FRAME_SEND_UNICAST ->
                    handleUnicast(seq, extendedParameters(command));
            default -> defaultResponses(seq, command);
        };
    }

    private List<byte[]> defaultResponses(int seq, byte[] command) {
        int frameId = frameIdOf(command);
        if (frameId == EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64) {
            return List.of(extendedResponse(seq, frameId, new byte[] {
                (byte) (CONTACT_NWK & 0xFF), (byte) ((CONTACT_NWK >> 8) & 0xFF)}));
        }
        return switch (frameId) {
            case FRAME_NETWORK_INIT, FRAME_FORM_NETWORK, FRAME_PERMIT_JOINING,
                    FRAME_SET_INITIAL_SECURITY_STATE ->
                    List.of(extendedResponse(seq, frameId, new byte[] {0x00}));
            case 0x0018 -> List.of(extendedResponse(seq, 0x0018, new byte[] {0x02}));
            default -> List.of();
        };
    }

    /** The SNZB-04P interview walk (the dossier §3.2 machine-capture shape). */
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
                    0x0000, CONTACT_ENDPOINT, basicReply(tsn)));
        }
        return frames;
    }

    private static byte[] zdoReply(int cluster, int tsn) {
        int nwkLo = CONTACT_NWK & 0xFF;
        int nwkHi = (CONTACT_NWK >> 8) & 0xFF;
        return switch (cluster) {
            case ZdoCodec.CLUSTER_NODE_DESC_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi,
                    0x02, 0x40, (byte) 0x80,
                    (byte) 0x86, 0x12,      // manufacturer code LE
                    82, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            case ZdoCodec.CLUSTER_ACTIVE_EP_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi, 0x01,
                    (byte) CONTACT_ENDPOINT};
            case ZdoCodec.CLUSTER_SIMPLE_DESC_REQ -> simpleDescriptor(tsn);
            default -> null;
        };
    }

    /** EP1 profile 0x0104, deviceType 0x0402 (IAS Zone) — the zha capture map. */
    private static byte[] simpleDescriptor(int tsn) {
        int[] inClusters = {0x0000, 0x0001, 0x0003, 0x0020, 0x0500, 0xFC11, 0xFC57};
        int[] outClusters = {0x0003, 0x0006, 0x0019};
        int length = 1 + 2 + 2 + 1 + 1 + inClusters.length * 2
                + 1 + outClusters.length * 2;
        byte[] reply = new byte[5 + length];
        int i = 0;
        reply[i++] = (byte) tsn;
        reply[i++] = 0x00;
        reply[i++] = (byte) (CONTACT_NWK & 0xFF);
        reply[i++] = (byte) ((CONTACT_NWK >> 8) & 0xFF);
        reply[i++] = (byte) length;
        reply[i++] = (byte) CONTACT_ENDPOINT;
        reply[i++] = 0x04;                              // HA profile 0x0104 LE
        reply[i++] = 0x01;
        reply[i++] = 0x02;                              // device type 0x0402 LE
        reply[i++] = 0x04;
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
        String model = "SNZB-04P";
        String build = "0x00002200";
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

    // ── callback fixtures (the bench-proven v13 layouts) ────────────────────

    /** A ZDP Device_annce riding the 0x0045 incomingMessageHandler. */
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

    /**
     * An IAS Report-Attributes carrying ZoneType (attr 0x0001, enum16 0x31) =
     * 0x0015 CONTACT — the wire-learn stimulus (F-7a; the zha capture's cached
     * {@code zoneType = 21}).
     */
    private static byte[] zoneTypeReportCallback() {
        return incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                IasZoneHandler.CLUSTER_ID, CONTACT_ENDPOINT,
                new byte[] {0x18, 0x2C, 0x0A, 0x01, 0x00, 0x31, 0x15, 0x00});
    }

    /**
     * An IAS ZoneEnrollRequest (cluster-specific 0x01) carrying zoneType
     * 0x0015 CONTACT LE + manufacturerCode 0x0000 LE — the W2-LEARN wire-learn
     * stimulus (ZCL8 §8.2.2.3; the joins-night record: enrollments arrive,
     * ZoneType attribute reports never do).
     */
    private static byte[] zoneEnrollRequestCallback() {
        return incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                IasZoneHandler.CLUSTER_ID, CONTACT_ENDPOINT,
                new byte[] {0x19, 0x2C, 0x01, 0x15, 0x00, 0x00, 0x00});
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
        parameters[14] = (byte) (CONTACT_NWK & 0xFF);
        parameters[15] = (byte) ((CONTACT_NWK >> 8) & 0xFF);
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

    // ── log capture ─────────────────────────────────────────────────────────

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

    private static ConfigurationAccess configAccess() {
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return Map.of(ZigbeeIntegrationAdapter.ADOPT_DEVICES_KEY,
                        List.of(CONTACT_LISTED));
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

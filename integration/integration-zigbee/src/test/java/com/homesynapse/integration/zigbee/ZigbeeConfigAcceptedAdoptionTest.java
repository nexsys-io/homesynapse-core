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
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.EventTypes;
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.platform.identity.DeviceId;
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
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9.4-ADP — config-accepted adoption (Doc 02 §3.12 Stage 3, the Tier-1
 * user-acceptance surface): the {@code integrations.zigbee.adopt_devices}
 * accept list turns a FRESH, interview-COMPLETE proposal for a listed IEEE
 * into an immediate adoption on the ingestion cycle.
 *
 * <p><strong>The north star:</strong> adoption is CONSENT. An absent or empty
 * list adopts NOTHING (proposals sit, honestly logged — T2/T3); a Stage-2
 * re-link of an already-adopted device stays automatic and NEVER re-enters
 * {@code adopt()} (consent is first-adoption-only — T4/T6); a listed device
 * stalled PARTIAL is loud, never blindly adopted (pin-1 — T7); a malformed
 * list entry warns and is skipped, never failing the boot (T5).
 *
 * <p>Every scenario drives the REAL chain over the scripted NCP (the
 * {@code ZigbeeTrustCenterJoinTest} §A-5 idiom): Device_annce riding the nop
 * keepalive → ingestion drain → interview walk → proposal → the adapter's
 * acceptance gate. Log assertions bind to the production message formats
 * verbatim; the accept-list entries deliberately vary hex/prefix casing to
 * pin the normalize-before-compare contract.
 */
@DisplayName("ZigbeeIntegrationAdapter — config-accepted adoption (M9.4-ADP)")
class ZigbeeConfigAcceptedAdoptionTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;
    private static final int FRAME_SEND_UNICAST = 0x0034;
    private static final int FRAME_NOP = 0x0005;

    /** The scripted joiner — the bench-corpus SNZB-03P identity (rig provenance). */
    private static final long SNZB_IEEE = 0x00124B0012345678L;
    private static final int SNZB_NWK = 0x6B9A;
    private static final int SNZB_ENDPOINT = 1;

    /** The SNZB accept-list entry, lowercase hex (parse is case-insensitive). */
    private static final String SNZB_LISTED_LOWERCASE = "0x00124b0012345678";
    /** The SNZB accept-list entry, uppercase {@code 0X} prefix (both casings). */
    private static final String SNZB_LISTED_UPPER_PREFIX = "0X00124B0012345678";
    /** A listed identity that never announces (T2's "someone else is listed"). */
    private static final String OTHER_LISTED_DEVICE = "0x00178801101A09BB";

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private IntegrationId integrationId;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private ListAppender<ILoggingEvent> adapterLogCapture;
    private ListAppender<ILoggingEvent> sliceLogCapture;

    /** Callback frames delivered on the NEXT nop keepalive (the rig pump idiom). */
    private final Deque<byte[]> riders = new ArrayDeque<>();
    /** When set, the scripted NCP never answers the interview Basic read (PARTIAL). */
    private boolean silentBasicRead;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        integrationId = new IntegrationId(UlidFactory.generate(clock));
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        adapterLogCapture = new ListAppender<>();
        adapterLogCapture.start();
        adapterLogger().addAppender(adapterLogCapture);
        sliceLogCapture = new ListAppender<>();
        sliceLogCapture.start();
        sliceLogger().addAppender(sliceLogCapture);
    }

    @AfterEach
    void tearDown() {
        adapterLogger().detachAppender(adapterLogCapture);
        sliceLogger().detachAppender(sliceLogCapture);
    }

    // ── T1: listed + fresh COMPLETE proposal ⇒ exactly ONE adopt ────────────

    @Test
    @DisplayName("T1: a listed device's fresh COMPLETE proposal adopts exactly once — "
            + "device + entities registered, ONE device_adopted published, the "
            + "proposal_accepted INFO logged (lowercase-hex entry normalizes)")
    void listedCompleteProposal_adoptsExactlyOnce() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(SNZB_LISTED_LOWERCASE));

        announce(adapter, SNZB_IEEE, SNZB_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count())
                .as("the proposal published once").isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("exactly ONE adoption").isEqualTo(1);
        Optional<Device> device = deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(SNZB_IEEE).toHexString());
        assertThat(device).as("the device registered").isPresent();
        assertThat(entityRegistry.listEntitiesByDevice(device.get().deviceId()))
                .as("the classified entity registered").hasSize(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.proposal_accepted"))
                .as("the acceptance is attributed to the config surface")
                .containsExactly("zigbee.proposal_accepted: "
                        + "device=0x00124B0012345678 source=config");
        assertThat(adapterMessages(Level.WARN, "zigbee.interview_failed"))
                .as("the adopt call never threw into the cycle handler").isEmpty();
    }

    // ── T2: unlisted device ⇒ the proposal sits ─────────────────────────────

    @Test
    @DisplayName("T2: an unlisted device's proposal sits — zero adoptions, zero new "
            + "logs beyond the existing device_proposed")
    void unlistedDevice_proposalSits_zeroAdoptions() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(OTHER_LISTED_DEVICE));

        announce(adapter, SNZB_IEEE, SNZB_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count())
                .as("the proposal still publishes").isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("an unlisted device NEVER adopts").isZero();
        assertThat(sliceMessages(Level.INFO, "zigbee.device_proposed"))
                .as("the existing proposal log is untouched").hasSize(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.proposal_accepted")).isEmpty();
        assertThat(adapterMessages(Level.WARN,
                "zigbee.proposal_incomplete_not_adopted")).isEmpty();
        assertThat(deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(SNZB_IEEE).toHexString()))
                .as("no device registered").isEmpty();
    }

    // ── T3: key absent ⇒ nothing adopts (conservative default is LAW) ───────

    @Test
    @DisplayName("T3: an absent adopt_devices key adopts NOTHING — the conservative "
            + "default (the permit-join §4.2 sibling)")
    void keyAbsent_nothingAdopts() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        announce(adapter, SNZB_IEEE, SNZB_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count())
                .isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("no key ⇒ no consent ⇒ no adoption — ever").isZero();
        assertThat(adapterMessages(Level.INFO, "zigbee.proposal_accepted")).isEmpty();
        assertThat(adapterMessages(Level.WARN, "zigbee.adopt_list_entry_invalid"))
                .as("an absent key is silent, never a warning").isEmpty();
    }

    // ── T4: re-link of an adopted listed device ⇒ adopt() NOT called ────────

    @Test
    @DisplayName("T4: a re-link of an already-adopted listed device never re-enters "
            + "adopt() — no ISE, the availability path untouched (consent is "
            + "first-adoption-only)")
    void relinkOfAdoptedListedDevice_neverCallsAdopt() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(SNZB_LISTED_LOWERCASE));

        announce(adapter, SNZB_IEEE, SNZB_NWK);   // first adoption (consented)
        announce(adapter, SNZB_IEEE, SNZB_NWK);   // re-pair: Stage-2 re-link

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("the re-link never re-adopts").isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.AVAILABILITY_CHANGED).count())
                .as("the re-link's availability path is untouched").isEqualTo(1);
        assertThat(sliceMessages(Level.INFO, "zigbee.device_relinked"))
                .as("the Stage-2 re-link ran").hasSize(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.proposal_accepted"))
                .as("acceptance fired for the first adoption only").hasSize(1);
        assertThat(adapterMessages(Level.WARN, "zigbee.interview_failed"))
                .as("no ISE ever surfaced — the guard is outcome-driven, never "
                        + "exception-driven").isEmpty();
    }

    // ── T5: malformed entries warn and are skipped; valid siblings work ─────

    @Test
    @DisplayName("T5: each malformed accept-list entry logs ONE WARN and is skipped — "
            + "the valid sibling still adopts (the list is user input, never a boot "
            + "failure)")
    void malformedEntries_warnedAndSkipped_validSiblingAdopts() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, Arrays.asList(
                "garbage", "0x1234", 42, null, SNZB_LISTED_UPPER_PREFIX));

        assertThat(adapterMessages(Level.WARN, "zigbee.adopt_list_entry_invalid"))
                .as("one WARN per bad entry, in list order — non-hex, wrong length, "
                        + "non-string, null")
                .containsExactly(
                        "zigbee.adopt_list_entry_invalid: value=garbage",
                        "zigbee.adopt_list_entry_invalid: value=0x1234",
                        "zigbee.adopt_list_entry_invalid: value=42",
                        "zigbee.adopt_list_entry_invalid: value=null");

        announce(adapter, SNZB_IEEE, SNZB_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("the valid sibling (uppercase-0X entry) still adopts")
                .isEqualTo(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.proposal_accepted"))
                .hasSize(1);
    }

    // ── T6: repeat announce of an adopted device ⇒ no double-adopt ──────────

    @Test
    @DisplayName("T6: repeat announces of an adopted device never double-adopt — ONE "
            + "device_adopted total, the registry state stable")
    void repeatAnnounceOfAdoptedDevice_noDoubleAdopt() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(SNZB_LISTED_LOWERCASE));

        announce(adapter, SNZB_IEEE, SNZB_NWK);
        announce(adapter, SNZB_IEEE, SNZB_NWK);
        announce(adapter, SNZB_IEEE, SNZB_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("ONE device_adopted total across every announce").isEqualTo(1);
        Optional<Device> device = deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(SNZB_IEEE).toHexString());
        assertThat(device).isPresent();
        assertThat(entityRegistry.listEntitiesByDevice(device.get().deviceId()))
                .as("the entity set never duplicates").hasSize(1);
    }

    // ── T7 (pin-1): PARTIAL never adopts; the COMPLETE re-proposal does ─────

    @Test
    @DisplayName("T7 (pin-1): a listed device's PARTIAL fresh proposal is NOT adopted "
            + "— ONE loud WARN, the proposal sits — and the subsequent COMPLETE "
            + "re-proposal of the same IEEE adopts normally")
    void partialInterview_notAdopted_thenCompleteReproposalAdopts() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(SNZB_LISTED_LOWERCASE));

        silentBasicRead = true;                   // the Basic read stalls ⇒ PARTIAL
        announce(adapter, SNZB_IEEE, SNZB_NWK);

        assertThat(adapterMessages(Level.WARN,
                "zigbee.proposal_incomplete_not_adopted"))
                .as("the operator listed it expecting adoption — the stall is loud")
                .containsExactly("zigbee.proposal_incomplete_not_adopted: "
                        + "device=0x00124B0012345678 status=PARTIAL");
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("a PARTIAL interview is never blindly adopted").isZero();
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count())
                .as("the proposal sits (published, unadopted)").isEqualTo(1);

        silentBasicRead = false;                  // the re-announce re-interviews
        announce(adapter, SNZB_IEEE, SNZB_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("the COMPLETE re-proposal adopts normally").isEqualTo(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.proposal_accepted"))
                .containsExactly("zigbee.proposal_accepted: "
                        + "device=0x00124B0012345678 source=config");
        assertThat(adapterMessages(Level.WARN,
                "zigbee.proposal_incomplete_not_adopted"))
                .as("the WARN fired for the PARTIAL round only").hasSize(1);
    }

    // ── M9.5-DUR (AMD-99) — DP-6 startup rehydration ────────────────────────

    @Test
    @DisplayName("DP-6: adapter start over pre-populated registries rehydrates the "
            + "maps — entityFor/deviceIdFor/bindingFor resolve WITHOUT an announce")
    void rehydrationRebuildsAdapterMapsFromTheRegistries() throws Exception {
        // Pre-populate the registries the way the Phase-3 projection rebuild
        // leaves them (test fixture: direct writes are test-tree-only).
        IEEEAddress ieee = new IEEEAddress(SNZB_IEEE);
        DeviceId deviceId = new DeviceId(UlidFactory.generate(clock));
        EntityId entityId = EntityId.of(UlidFactory.generate(clock));
        deviceRegistry.createDevice(new Device(
                deviceId, "zigbee-00124b0012345678", "eWeLink SNZB-03P",
                "eWeLink", "SNZB-03P", null, null, null, integrationId,
                null, null, List.of(),
                Set.of(new HardwareIdentifier("zigbee", ieee.toHexString())),
                clock.instant()));
        entityRegistry.createEntity(new Entity(
                entityId, "zigbee-00124b0012345678-ep1", EntityType.BINARY_SENSOR,
                "eWeLink SNZB-03P", deviceId, 1, null, true, List.of(), List.of(),
                clock.instant()));

        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::adoptionHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, List.of());

        assertThat(adapter.adoptionSlice().deviceIdFor(ieee))
                .as("the IEEE->deviceId map rehydrated from the registry view")
                .contains(deviceId);
        assertThat(adapter.adoptionSlice().entityFor(ieee, 1))
                .as("ingestion resolution works with NO announce delivered")
                .contains(entityId);
        assertThat(adapter.adoptionSlice().bindingFor(entityId))
                .contains(new ZigbeeAdoptionSlice.EntityBinding(ieee, 1));
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("rehydration re-links; it never adopts")
                .isZero();
    }

    // ── harness (the ZigbeeTrustCenterJoinTest production-ladder idiom) ─────

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
                integrationId, "zigbee", publisher,
                entityRegistry, unusedQueryService(),
                unusedHealthReporter(), configAccess,
                null, null, null, null, null);
    }

    /** Boots a production adapter through the full §5.1 ladder to a formed network. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp,
            List<Object> adoptDevices) throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(adoptDevices)),
                deviceRegistry,
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

    /**
     * Delivers a Device_annce and runs one §G cycle: the announce rides the next
     * nop keepalive's response (callbacks sit unread until the protocol next
     * reads — the rig pump idiom), the drain schedules the interview, and the
     * SAME cycle pass runs the scripted interview walk through to the adapter's
     * acceptance gate.
     */
    private void announce(ZigbeeIntegrationAdapter adapter, long ieee, int nwk) {
        riders.add(deviceAnnounceCallback(ieee, nwk));
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();
    }

    // ── scripted NCP (v13 dialect; interview-capable — the §A-5 script) ─────

    /**
     * Formation + interview-capable handler. Pending {@link #riders} are drained
     * onto the nop keepalive's response — the established pump idiom for
     * unsolicited callbacks (they park in the protocol's callback queue and reach
     * the ingestion drain on the next cycle).
     */
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
                // Riders BEFORE the nop response (the rig idiom): the protocol's
                // receive loop returns at the matching response, so a frame
                // behind it stays unread — callbacks must precede it to park.
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
                (byte) (SNZB_NWK & 0xFF), (byte) ((SNZB_NWK >> 8) & 0xFF)}));
        }
        return switch (frameId) {
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
            if (silentBasicRead) {
                return frames;   // no reply: the read times out ⇒ PARTIAL interview
            }
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

    // ── callback fixtures (the bench-proven v13 layouts) ────────────────────

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

    private List<String> adapterMessages(Level level, String prefix) {
        return messages(adapterLogCapture, level, prefix);
    }

    private List<String> sliceMessages(Level level, String prefix) {
        return messages(sliceLogCapture, level, prefix);
    }

    private static List<String> messages(ListAppender<ILoggingEvent> capture,
            Level level, String prefix) {
        return capture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
    }

    private static Logger adapterLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);
    }

    private static Logger sliceLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeAdoptionSlice.class);
    }

    // ── inert context stubs (the adapter never touches these paths here) ────

    private static ConfigurationAccess configAccess(List<Object> adoptDevices) {
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return adoptDevices == null ? Map.of()
                        : Map.of(ZigbeeIntegrationAdapter.ADOPT_DEVICES_KEY,
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

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-R4-1 — interview-on-rejoin (R-10 Row 10, ruled (a)): the silent-rejoiner
 * adoption gap. A mains router that holds the network key rejoins on its own
 * authority, sends no Device_annce the service sees, and every frame it sends
 * lands as {@code zigbee.ingestion_unknown_sender} — forever (R-3a: the mains
 * fleet silent after the outage; R-4 D-g: the Hue-class device spoke ONLY as
 * {@code ingestion_unknown_sender nwk=0xf87d} inside a 254 s window). These
 * scenarios pin the two admission TRIGGERS into the ONE existing interview
 * path — never a bypass:
 *
 * <ul>
 *   <li><strong>H-ii (primary, the evidenced case):</strong> an unknown-sender
 *       frame while the permit-join window is open → resolve the sender's
 *       IEEE (the cache's NWK→IEEE view, then the coordinator's
 *       {@code lookupEui64ByNodeId}) → the SAME {@code recordAnnounce} +
 *       {@code schedule} an announce takes.</li>
 *   <li><strong>H-i (secondary, cheap, same path):</strong> an ACCEPTED 0x0024
 *       rejoin ({@code SECURED_REJOIN}/{@code UNSECURED_REJOIN}, not denied)
 *       for a device NOT in the adoption maps while the window is open.</li>
 * </ul>
 *
 * <p>The doctrine every scenario holds: relink ≠ adopt. Adoption starts ONLY
 * via interview → proposal → adopted; outside a window a candidate is logged
 * ONCE per (invocation, nwk) and ignored; a device already in the adoption
 * maps never re-enters; denied joins stay observability-only (the M9.4-TCJ
 * §A.2 pin's surviving half). The 0x0061 lookup is THE ONE NEW SILICON
 * SURFACE — bellows-derived, unmeasured until R-4b's first ⏺; assertions bind
 * to the production constant so a silicon correction is a one-constant edit
 * (the 0x0019/0x90 model).
 *
 * <p>Harness: the {@code ZigbeeTrustCenterJoinTest} production-ladder idiom
 * (boot → window-open via the package-private seams) + the
 * {@code ZigbeeConfigAcceptedAdoptionTest} rider pump (callback frames ride the
 * nop keepalive's response — riders FIRST, response LAST) + the scripted SNZB
 * interview walk, so the rejoin-admitted interview runs through proposal and
 * — with the device on the {@code adopt_devices} list — through the REAL
 * {@code device_adopted} path.
 *
 * <p><strong>F-R4-1b (the T3 family):</strong> R-4b measured 0x0061 HITTING the
 * mains router and MISSING the router-parented sleepy SNZB-02P
 * ({@code lookup_eui64_failed nwk=0x15ac status=0x1} — the coordinator's own
 * table never holds a grandchild). The third resolver asks the DEVICE itself
 * with a ZDP {@code IEEE_addr_req} (0x0001) over the locked ZDO exchange after
 * a clean table miss, once per nwk per window epoch on the SAME set, and admits
 * on the response through the SAME announce path. The scripted air answers via
 * {@link #zdoReply}: a scriptable status, a scriptable response nwk, or silence
 * (a {@code null} reply — the fake channel advances the clock, so the 10 s bound
 * elapses deterministically). Sleepy-device physics is not modelled here: the
 * synchronous reply pins the FRAME SHAPE; the wire at R-4c decides the timing.
 */
@DisplayName("ZigbeeIntegrationAdapter — interview-on-rejoin (F-R4-1, R-10 Row 10 (a))")
class ZigbeeInterviewOnRejoinTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;
    private static final int FRAME_SEND_UNICAST = 0x0034;
    private static final int FRAME_NOP = 0x0005;

    /** The scripted rejoiner — the bench-corpus SNZB-03P identity (rig provenance). */
    private static final long SNZB_IEEE = 0x00124B0012345678L;
    private static final IEEEAddress SNZB = new IEEEAddress(SNZB_IEEE);
    private static final int SNZB_NWK = 0x6B9A;
    private static final int SNZB_ENDPOINT = 1;
    private static final String SNZB_ACCEPT_ENTRY = "0x00124B0012345678";

    /** Network addresses the coordinator holds NO address-table entry for. */
    private static final int UNKNOWN_NWK = 0x9999;
    private static final int OTHER_UNKNOWN_NWK = 0x8888;
    private static final int OCCUPANCY_CLUSTER = 0x0406;

    /** The M9.4-PJ bench window (seconds); every open in these scenarios uses it. */
    private static final int WINDOW_SECONDS = 200;

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private IntegrationId integrationId;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private ListAppender<ILoggingEvent> adapterLogCapture;
    private ListAppender<ILoggingEvent> ingestionLogCapture;
    private ListAppender<ILoggingEvent> sliceLogCapture;
    private ListAppender<ILoggingEvent> protocolLogCapture;
    private Level adapterLevelBefore;

    /** Callback frames delivered on the NEXT nop keepalive (the rig pump idiom). */
    private final Deque<byte[]> riders = new ArrayDeque<>();
    /** The scripted coordinator address table: nwk → EUI64 (the 0x0061 answers). */
    private final Map<Integer, Long> addressTable = new HashMap<>();
    /** Every 0x0061 request's nodeId, in send order. */
    private final List<Integer> lookupRequests = new ArrayList<>();
    /** When non-zero, every 0x0061 answers this EmberStatus (the miss script). */
    private int lookupStatus;
    /** Every ZDO unicast REQUEST body, captured per request cluster in send order (F-R4-1b). */
    private final Map<Integer, List<byte[]>> zdoRequests = new HashMap<>();
    /** When non-zero, the scripted IEEE_addr_rsp carries this ZDP status (the air-miss script). */
    private int ieeeAddrStatus;
    /** When set, the air NEVER answers IEEE_addr_req — silence; the fake channel advances the clock. */
    private boolean ieeeAddrSilent;
    /** When non-null, the scripted IEEE_addr_rsp carries this nwk instead of echoing the request's. */
    private Integer ieeeAddrResponseNwk;
    private int reportTsn = 0x40;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        integrationId = new IntegrationId(UlidFactory.generate(clock));
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        adapterLogCapture = attach(adapterLogger());
        ingestionLogCapture = attach(ingestionLogger());
        sliceLogCapture = attach(sliceLogger());
        protocolLogCapture = attach(protocolLogger());
        // The ignored-candidate lines are DEBUG by design (the operator-facing
        // INFO/WARN set is the instruction's three tokens): capture them here.
        adapterLevelBefore = adapterLogger().getLevel();
        adapterLogger().setLevel(Level.DEBUG);
        addressTable.put(SNZB_NWK, SNZB_IEEE);
    }

    @AfterEach
    void tearDown() {
        adapterLogger().setLevel(adapterLevelBefore);
        adapterLogger().detachAppender(adapterLogCapture);
        ingestionLogger().detachAppender(ingestionLogCapture);
        sliceLogger().detachAppender(sliceLogCapture);
        protocolLogger().detachAppender(protocolLogCapture);
    }

    // ── T1: H-ii, window OPEN, lookup HIT ⇒ the whole loop, once ────────────

    @Test
    @DisplayName("T1: an unknown-sender frame inside an open window resolves the IEEE "
            + "over 0x0061 exactly once, enters the SAME announce path (cache + "
            + "queue), interviews once, proposes with source=rejoin, and — listed — "
            + "adopts through the real device_adopted path; the admitting frame "
            + "itself is still skipped, and the device's next frame routes normally")
    void unknownSenderInsideWindow_resolvesAndAdoptsThroughTheAnnouncePath()
            throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS,
                List.of(SNZB_ACCEPT_ENTRY));
        adapter.openPermitJoinWindow();

        riders.add(occupancyReport(SNZB_NWK));
        deliverAndCycle(adapter);

        assertThat(lookupRequests)
                .as("exactly ONE lookupEui64ByNodeId, carrying the sender's nwk")
                .containsExactly(SNZB_NWK);
        assertThat(parametersOf(framesWithId(ncp,
                EzspCoordinatorProtocol.FRAME_LOOKUP_EUI64_BY_NODE_ID).get(0)))
                .as("the 0x0061 request is the nodeId u16 LE (bellows-derived)")
                .containsExactly(0x9A, 0x6B);
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:"))
                .containsExactly("zigbee.rejoin_candidate: device=0x00124B0012345678 "
                        + "nwk=0x6b9a source=unknown_sender");
        assertThat(ingestionMessages(Level.WARN, "zigbee.ingestion_unknown_sender"))
                .as("the admitting frame is still skipped — the WARN continues as today")
                .containsExactly("zigbee.ingestion_unknown_sender: nwk=0x6b9a "
                        + "cluster=0x406; frame skipped");
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("exactly ONE interview walk started — the announce path, reused")
                .isEqualTo(1);
        assertThat(sliceMessages(Level.INFO, "zigbee.device_proposed"))
                .as("the proposal carries its provenance on the LOG LINE, not the payload")
                .containsExactly("zigbee.device_proposed: device=0x00124B0012345678 "
                        + "manufacturer=eWeLink model=SNZB-03P profile="
                        + MeasuredCorpusValues.SNZB_PROFILE_ID
                        + " status=COMPLETE source=rejoin");
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count()).isEqualTo(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.proposal_accepted"))
                .containsExactly("zigbee.proposal_accepted: device=0x00124B0012345678 "
                        + "source=config");
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("adopted by the SAME path — device_proposed → device_adopted")
                .isEqualTo(1);
        assertThat(deviceRegistry.findByHardwareIdentifier("zigbee", SNZB.toHexString()))
                .isPresent();
        assertThat(adapter.device(SNZB))
                .as("the cache carries the announce-shaped record")
                .hasValueSatisfying(record -> {
                    assertThat(record.networkAddress()).isEqualTo(SNZB_NWK);
                    assertThat(record.interviewStatus()).isEqualTo(InterviewStatus.COMPLETE);
                });

        // The device's NEXT frame is a known sender: no second lookup, no WARN,
        // and the report reaches the adopted entity as state_reported.
        riders.add(occupancyReport(SNZB_NWK));
        deliverAndCycle(adapter);

        assertThat(lookupRequests).as("the lookup ran once per nwk").hasSize(1);
        assertThat(ingestionMessages(Level.WARN, "zigbee.ingestion_unknown_sender"))
                .hasSize(1);
        assertThat(publisher.ofType(EventTypes.STATE_REPORTED).count())
                .as("frames route after admission — the R-4b C4 loop, hardware-free")
                .isEqualTo(1);
    }

    // ── T2: window CLOSED ⇒ no schedule; one INFO per (invocation, nwk) ─────

    @Test
    @DisplayName("T2: with no window open, unknown-sender frames never schedule and "
            + "never touch the coordinator — zigbee.rejoin_ignored_window_closed logs "
            + "ONCE per nwk per invocation while the unknown-sender WARN continues per frame")
    void unknownSenderOutsideWindow_logsOncePerNwkAndSchedulesNothing()
            throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null, List.of());

        riders.add(occupancyReport(UNKNOWN_NWK));
        riders.add(occupancyReport(UNKNOWN_NWK));
        riders.add(occupancyReport(OTHER_UNKNOWN_NWK));
        deliverAndCycle(adapter);

        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_ignored_window_closed"))
                .as("once per nwk — the second 0x9999 frame logs nothing new")
                .containsExactly(
                        "zigbee.rejoin_ignored_window_closed: nwk=0x9999 cluster=0x406",
                        "zigbee.rejoin_ignored_window_closed: nwk=0x8888 cluster=0x406");
        assertThat(ingestionMessages(Level.WARN, "zigbee.ingestion_unknown_sender"))
                .as("no behavior change outside the window beyond the one INFO")
                .hasSize(3);
        assertThat(lookupRequests).as("the coordinator is never asked").isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("no interview").isZero();
        assertThat(adapter.allDevices()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("T2b: an accepted SECURED_REJOIN with no window open is observed "
            + "(device_join) and ignored for adoption — DEBUG-noted, never scheduled")
    void acceptedRejoinOutsideWindow_isIgnored() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null, List.of());

        riders.add(trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_SECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_NO_ACTION));
        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.INFO, "zigbee.device_join:"))
                .containsExactly("zigbee.device_join: device=0x00124B0012345678 "
                        + "nwk=0x6b9a status=SECURED_REJOIN decision=NO_ACTION");
        assertThat(adapterMessages(Level.DEBUG, "zigbee.rejoin_candidate_ignored"))
                .containsExactly("zigbee.rejoin_candidate_ignored: "
                        + "device=0x00124B0012345678 nwk=0x6b9a source=tc_join "
                        + "reason=window_closed");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:")).isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .isZero();
        assertThat(adapter.allDevices()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    // ── T3: window OPEN, table MISS ⇒ the air (F-R4-1b); both miss ⇒ one WARN ─

    @Test
    @DisplayName("T3: inside an open window a table MISS (0x0061 status ≠ 0) asks the "
            + "DEVICE itself with ONE ZDP IEEE_addr_req; an air MISS (status 0x81) schedules "
            + "nothing, WARNs zigbee.rejoin_candidate_unresolved reason=zdo_miss ONCE per nwk, "
            + "and a second frame asks NEITHER surface again")
    void unknownSenderInsideWindow_tableAndAirMiss_warnsOnceAndSchedulesNothing()
            throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();
        lookupStatus = 0x01;     // EMBER_ERR_FATAL: not in the address table
        ieeeAddrStatus = 0x81;   // ZDP DEVICE_NOT_FOUND from the air

        riders.add(occupancyReport(UNKNOWN_NWK));
        riders.add(occupancyReport(UNKNOWN_NWK));
        deliverAndCycle(adapter);

        assertThat(lookupRequests).as("asked ONCE per nwk").containsExactly(UNKNOWN_NWK);
        assertThat(zdoRequests.get(ZdoCodec.CLUSTER_IEEE_ADDR_REQ))
                .as("exactly ONE IEEE_addr_req, [tsn][nwk LE][Single 0x00][StartIndex 0x00]")
                .singleElement()
                .satisfies(body -> {
                    assertThat(body).hasSize(5);
                    assertThat(body[1]).isEqualTo((byte) 0x99);
                    assertThat(body[2]).isEqualTo((byte) 0x99);
                    assertThat(body[3]).isEqualTo((byte) 0x00);
                    assertThat(body[4]).isEqualTo((byte) 0x00);
                });
        assertThat(adapterMessages(Level.WARN, "zigbee.rejoin_candidate_unresolved"))
                .containsExactly("zigbee.rejoin_candidate_unresolved: nwk=0x9999 "
                        + "cluster=0x406 reason=zdo_miss");
        assertThat(protocolMessages(Level.WARN, "zigbee.lookup_eui64_failed"))
                .as("the protocol names the status byte — the R-4b criterion-0 instrument")
                .containsExactly("zigbee.lookup_eui64_failed: nwk=0x9999 status=0x1");
        assertThat(protocolMessages(Level.WARN, "zigbee.ieee_addr_rsp_failed"))
                .as("the air's status byte — the R-4c instrument, the lookup mirror")
                .containsExactly("zigbee.ieee_addr_rsp_failed: nwk=0x9999 status=0x81");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:")).isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .isZero();
        assertThat(adapter.allDevices()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("T3b: a table MISS then an air HIT (IEEE_addr_rsp status 0x00) admits "
            + "through the SAME announce path — rejoin_candidate source=unknown_sender, the "
            + "interview runs, device_proposed source=rejoin, and — listed — the device "
            + "adopts; exactly ONE 0x0061 and ONE 0x0001 request")
    void unknownSenderInsideWindow_tableMiss_airHit_adoptsThroughTheAnnouncePath()
            throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS,
                List.of(SNZB_ACCEPT_ENTRY));
        adapter.openPermitJoinWindow();
        lookupStatus = 0x01;     // the coordinator's own table misses (the SNZB-02P shape)

        riders.add(occupancyReport(UNKNOWN_NWK));
        deliverAndCycle(adapter);

        assertThat(lookupRequests).containsExactly(UNKNOWN_NWK);
        assertThat(zdoRequests.get(ZdoCodec.CLUSTER_IEEE_ADDR_REQ))
                .as("exactly ONE IEEE_addr_req").hasSize(1);
        assertThat(protocolMessages(Level.INFO, "zigbee.ieee_addr_req:"))
                .containsExactly("zigbee.ieee_addr_req: nwk=0x9999");
        assertThat(protocolMessages(Level.INFO, "zigbee.ieee_addr_rsp:"))
                .containsExactly("zigbee.ieee_addr_rsp: nwk=0x9999 "
                        + "device=0x00124B0012345678");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:"))
                .as("the T1 line, byte-identical in shape — the surface reads from the "
                        + "two protocol lines that precede it")
                .containsExactly("zigbee.rejoin_candidate: device=0x00124B0012345678 "
                        + "nwk=0x9999 source=unknown_sender");
        assertThat(adapterMessages(Level.WARN, "zigbee.rejoin_candidate_unresolved"))
                .isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("exactly ONE interview walk started — the announce path, reused")
                .isEqualTo(1);
        assertThat(sliceMessages(Level.INFO, "zigbee.device_proposed"))
                .containsExactly("zigbee.device_proposed: device=0x00124B0012345678 "
                        + "manufacturer=eWeLink model=SNZB-03P profile="
                        + MeasuredCorpusValues.SNZB_PROFILE_ID
                        + " status=COMPLETE source=rejoin");
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count()).isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("adopted by the SAME path — device_proposed → device_adopted")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("T3c: a table MISS then a SILENT air times the IEEE_addr_req out ONCE "
            + "at the interview's own 10 s step bound (the clock advances — no real wait), "
            + "WARNs zigbee.ieee_addr_req_unanswered, resolves nothing, and a second frame "
            + "asks nothing")
    void unknownSenderInsideWindow_tableMiss_airSilent_timesOutOnceAndSchedulesNothing()
            throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();
        lookupStatus = 0x01;
        ieeeAddrSilent = true;
        Instant before = clock.instant();

        riders.add(occupancyReport(UNKNOWN_NWK));
        riders.add(occupancyReport(UNKNOWN_NWK));
        deliverAndCycle(adapter);

        assertThat(Duration.between(before, clock.instant()).toMillis())
                .as("the exchange ran to its deadline — the fake channel advanced the clock")
                .isGreaterThanOrEqualTo(InterviewStateMachine.STEP_TIMEOUT_MILLIS);
        assertThat(lookupRequests).containsExactly(UNKNOWN_NWK);
        assertThat(zdoRequests.get(ZdoCodec.CLUSTER_IEEE_ADDR_REQ))
                .as("the air is asked ONCE per nwk per epoch").hasSize(1);
        assertThat(protocolMessages(Level.WARN, "zigbee.ieee_addr_req_unanswered"))
                .containsExactly("zigbee.ieee_addr_req_unanswered: nwk=0x9999 timeout_ms="
                        + InterviewStateMachine.STEP_TIMEOUT_MILLIS);
        assertThat(adapterMessages(Level.WARN, "zigbee.rejoin_candidate_unresolved"))
                .containsExactly("zigbee.rejoin_candidate_unresolved: nwk=0x9999 "
                        + "cluster=0x406 reason=zdo_miss");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:")).isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .isZero();
        assertThat(adapter.allDevices()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("T3d: when the coordinator's table HITS, the air is never asked — ZERO "
            + "IEEE_addr_req unicasts (the resolution ORDER; green-by-construction at HEAD, "
            + "disclosed)")
    void unknownSenderInsideWindow_tableHit_neverAsksTheAir() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();

        riders.add(occupancyReport(SNZB_NWK));
        deliverAndCycle(adapter);

        assertThat(lookupRequests).containsExactly(SNZB_NWK);
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:"))
                .containsExactly("zigbee.rejoin_candidate: device=0x00124B0012345678 "
                        + "nwk=0x6b9a source=unknown_sender");
        assertThat(zdoRequests.getOrDefault(ZdoCodec.CLUSTER_IEEE_ADDR_REQ, List.of()))
                .as("the table answered — the air is never asked").isEmpty();
        assertThat(protocolMessages(Level.INFO, "zigbee.ieee_addr_req:")).isEmpty();
    }

    @Test
    @DisplayName("T3e: an IEEE_addr_rsp whose nwk differs from the request's (a re-addressed "
            + "device still answers) admits on the RESPONSE's pair and renders both nwk "
            + "values on the ieee_addr_rsp line — the tsn-only matcher's guard")
    void unknownSenderInsideWindow_airAnswersWithAnotherNwk_admitsOnTheResponsePair()
            throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();
        lookupStatus = 0x01;
        ieeeAddrResponseNwk = SNZB_NWK;   // the device answers from its live address

        riders.add(occupancyReport(UNKNOWN_NWK));
        deliverAndCycle(adapter);

        assertThat(zdoRequests.get(ZdoCodec.CLUSTER_IEEE_ADDR_REQ)).hasSize(1);
        assertThat(protocolMessages(Level.INFO, "zigbee.ieee_addr_rsp:"))
                .containsExactly("zigbee.ieee_addr_rsp: nwk=0x9999 "
                        + "device=0x00124B0012345678 response_nwk=0x6b9a");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:"))
                .as("admitted on the RESPONSE's pair (DP-6): recordAnnounce takes the response nwk")
                .containsExactly("zigbee.rejoin_candidate: device=0x00124B0012345678 "
                        + "nwk=0x6b9a source=unknown_sender");
        assertThat(adapter.device(SNZB))
                .hasValueSatisfying(record ->
                        assertThat(record.networkAddress()).isEqualTo(SNZB_NWK));
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("exactly ONE interview walk started").isEqualTo(1);
    }

    // ── T4: H-i, accepted rejoin for an UNKNOWN device, window OPEN ─────────

    @Test
    @DisplayName("T4: an accepted 0x0024 SECURED_REJOIN for an unknown device inside an "
            + "open window schedules exactly one interview through the announce path "
            + "(source=tc_join; no 0x0061 — the callback carried the EUI64) and proposes "
            + "with source=rejoin; unlisted, the proposal sits honestly")
    void acceptedSecuredRejoinInsideWindow_schedulesOnce() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();

        riders.add(trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_SECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_NO_ACTION));
        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.INFO, "zigbee.device_join:"))
                .as("the join observation logs beside the admission")
                .containsExactly("zigbee.device_join: device=0x00124B0012345678 "
                        + "nwk=0x6b9a status=SECURED_REJOIN decision=NO_ACTION");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:"))
                .containsExactly("zigbee.rejoin_candidate: device=0x00124B0012345678 "
                        + "nwk=0x6b9a source=tc_join");
        assertThat(lookupRequests).as("the callback carried the EUI64").isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("exactly ONE interview walk started").isEqualTo(1);
        assertThat(sliceMessages(Level.INFO, "zigbee.device_proposed"))
                .containsExactly("zigbee.device_proposed: device=0x00124B0012345678 "
                        + "manufacturer=eWeLink model=SNZB-03P profile="
                        + MeasuredCorpusValues.SNZB_PROFILE_ID
                        + " status=COMPLETE source=rejoin");
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count()).isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("unlisted: consent is still the operator's — no adoption")
                .isZero();
        assertThat(adapter.device(SNZB)).isPresent();
    }

    @Test
    @DisplayName("T4b: an accepted UNSECURED_REJOIN admits the same way")
    void acceptedUnsecuredRejoinInsideWindow_schedulesOnce() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();

        riders.add(trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_UNSECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_USE_PRECONFIGURED_KEY));
        deliverAndCycle(adapter);

        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:"))
                .containsExactly("zigbee.rejoin_candidate: device=0x00124B0012345678 "
                        + "nwk=0x6b9a source=tc_join");
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("T4c: an accepted rejoin THEN a Device_annce in the same drain drives "
            + "the interview exactly once — the queue is keyed by IEEE (put-replace), so "
            + "the hook never double-drives the announce path; the later announce owns "
            + "the provenance (source=announce)")
    void rejoinThenAnnounce_interviewsOnce_announceOwnsProvenance() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();

        riders.add(trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_SECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_NO_ACTION));
        riders.add(deviceAnnounceCallback(SNZB_IEEE, SNZB_NWK));
        deliverAndCycle(adapter);

        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:")).hasSize(1);
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("ONE interview — never a double drive").isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count()).isEqualTo(1);
        assertThat(sliceMessages(Level.INFO, "zigbee.device_proposed"))
                .singleElement().asString().endsWith("source=announce");
    }

    // ── T5: a KNOWN (adopted) device never re-enters via either hook ────────

    @Test
    @DisplayName("T5: an accepted SECURED_REJOIN for a device already in the adoption "
            + "maps (rehydrated from the registry) schedules NOTHING — today's relink "
            + "path is untouched; the candidate is DEBUG-noted as already adopted")
    void acceptedRejoinForAdoptedDevice_neverSchedules() throws Exception {
        DeviceId adopted = seedAdoptedSnzb();
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();
        assertThat(adapter.adoptionSlice().deviceIdFor(SNZB)).contains(adopted);

        riders.add(trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_SECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_NO_ACTION));
        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.INFO, "zigbee.device_join:")).hasSize(1);
        assertThat(adapterMessages(Level.DEBUG, "zigbee.rejoin_candidate_ignored"))
                .containsExactly("zigbee.rejoin_candidate_ignored: "
                        + "device=0x00124B0012345678 nwk=0x6b9a source=tc_join "
                        + "reason=already_adopted");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:")).isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("no interview for an adopted device").isZero();
        assertThat(lookupRequests).isEmpty();
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count()).isZero();
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count()).isZero();
    }

    @Test
    @DisplayName("T5b (the cache-unindexed corner): an unknown-sender frame whose "
            + "0x0061 resolves to an ADOPTED device schedules nothing and writes nothing "
            + "— the lookup still runs only once per nwk; the frames stay skipped "
            + "(documented; the re-index is a follow-on, not this WU)")
    void unknownSenderResolvingToAdoptedDevice_neverSchedules() throws Exception {
        seedAdoptedSnzb();
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();
        assertThat(adapter.device(SNZB)).as("a fresh cache: no NWK index").isEmpty();

        riders.add(occupancyReport(SNZB_NWK));
        riders.add(occupancyReport(SNZB_NWK));
        deliverAndCycle(adapter);

        assertThat(lookupRequests).as("asked once per nwk, hit or miss")
                .containsExactly(SNZB_NWK);
        assertThat(adapterMessages(Level.DEBUG, "zigbee.rejoin_candidate_ignored"))
                .containsExactly("zigbee.rejoin_candidate_ignored: "
                        + "device=0x00124B0012345678 nwk=0x6b9a source=unknown_sender "
                        + "reason=already_adopted");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:")).isEmpty();
        assertThat(ingestionMessages(Level.WARN, "zigbee.ingestion_unknown_sender"))
                .hasSize(2);
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .isZero();
        assertThat(adapter.device(SNZB)).as("no cache write for an adopted device").isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    // ── T6: denied / left ⇒ observability only (the pin's surviving half) ───

    @Test
    @DisplayName("T6: a DENIED 0x0024 rejoin inside an open window stays observability-only "
            + "— one WARN zigbee.device_join_failed, never a schedule (the M9.4-TCJ pin's "
            + "surviving half; GREEN-by-construction, disclosed)")
    void deniedRejoinInsideWindow_neverSchedules() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();

        riders.add(trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_SECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_DENY_JOIN));
        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.WARN, "zigbee.device_join_failed"))
                .containsExactly("zigbee.device_join_failed: device=0x00124B0012345678 "
                        + "status=SECURED_REJOIN decision=DENY_JOIN");
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:")).isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .isZero();
        assertThat(lookupRequests).isEmpty();
        assertThat(adapter.allDevices()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("T6b: a DEVICE_LEFT 0x0024 inside an open window stays observability-only")
    void deviceLeftInsideWindow_neverSchedules() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();

        riders.add(trustCenterJoinCallback(SNZB_IEEE, SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_DEVICE_LEFT,
                EzspCoordinatorProtocol.JOIN_DECISION_NO_ACTION));
        deliverAndCycle(adapter);

        assertThat(ingestionMessages(Level.INFO, "zigbee.device_left")).hasSize(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_candidate:")).isEmpty();
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .isZero();
        assertThat(adapter.allDevices()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    // ── T7: a window reopen clears BOTH once-per-invocation sets ────────────

    @Test
    @DisplayName("T7: the once-per-(invocation, nwk) sets — the window-closed note AND "
            + "the lookup-attempted set — clear on openPermitJoinWindow(): after a reopen "
            + "a previously-noted nwk logs the closed INFO again and is looked up again; "
            + "F-R4-1b: the ONE set bounds the PAIR, so the air is re-asked with it")
    void windowReopen_clearsTheOncePerInvocationSets() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::rejoinHandler);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, WINDOW_SECONDS, List.of());
        adapter.openPermitJoinWindow();
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS + 1));
        assertThat(adapter.isPermitJoinActive()).as("closed by time").isFalse();

        riders.add(occupancyReport(UNKNOWN_NWK));
        riders.add(occupancyReport(UNKNOWN_NWK));
        riders.add(occupancyReport(OTHER_UNKNOWN_NWK));
        deliverAndCycle(adapter);
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_ignored_window_closed"))
                .containsExactly(
                        "zigbee.rejoin_ignored_window_closed: nwk=0x9999 cluster=0x406",
                        "zigbee.rejoin_ignored_window_closed: nwk=0x8888 cluster=0x406");

        // Reopen (the operator's restart semantic) with a coordinator that
        // holds no entry AND — F-R4-1b — an air that misses too (T7 pins the
        // SETS, not the surfaces): the lookup runs ONCE, the air is asked
        // ONCE, ONE WARN, then quiet.
        adapter.openPermitJoinWindow();
        lookupStatus = 0x01;
        ieeeAddrStatus = 0x81;
        riders.add(occupancyReport(UNKNOWN_NWK));
        riders.add(occupancyReport(UNKNOWN_NWK));
        deliverAndCycle(adapter);
        assertThat(lookupRequests).containsExactly(UNKNOWN_NWK);
        assertThat(zdoRequests.get(ZdoCodec.CLUSTER_IEEE_ADDR_REQ))
                .as("the air is asked once per nwk per epoch").hasSize(1);
        assertThat(adapterMessages(Level.WARN, "zigbee.rejoin_candidate_unresolved"))
                .hasSize(1);

        // Closed again by time: the closed-set was cleared at the reopen, so
        // the SAME nwk is noted again — exactly once more.
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS + 1));
        riders.add(occupancyReport(UNKNOWN_NWK));
        riders.add(occupancyReport(UNKNOWN_NWK));
        deliverAndCycle(adapter);
        assertThat(adapterMessages(Level.INFO, "zigbee.rejoin_ignored_window_closed"))
                .containsExactly(
                        "zigbee.rejoin_ignored_window_closed: nwk=0x9999 cluster=0x406",
                        "zigbee.rejoin_ignored_window_closed: nwk=0x8888 cluster=0x406",
                        "zigbee.rejoin_ignored_window_closed: nwk=0x9999 cluster=0x406");

        // A second reopen clears the attempted-set: the nwk is looked up again
        // — and the air asked again (the ONE set bounds the pair, DP-3).
        adapter.openPermitJoinWindow();
        riders.add(occupancyReport(UNKNOWN_NWK));
        deliverAndCycle(adapter);
        assertThat(lookupRequests).containsExactly(UNKNOWN_NWK, UNKNOWN_NWK);
        assertThat(zdoRequests.get(ZdoCodec.CLUSTER_IEEE_ADDR_REQ))
                .as("the ONE set bounds the pair and clears with it (F-R4-1b DP-3)")
                .hasSize(2);
        assertThat(adapterMessages(Level.WARN, "zigbee.rejoin_candidate_unresolved"))
                .hasSize(2);
        assertThat(countFrames(ncp, EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64))
                .as("nothing was ever scheduled").isZero();
        assertThat(adapter.allDevices()).isEmpty();
    }

    // ── harness (the TCJ production-ladder idiom + the ADP rider pump) ──────

    /** Pre-populates the registries the way the Phase-3 projection rebuild leaves them. */
    private DeviceId seedAdoptedSnzb() {
        DeviceId deviceId = new DeviceId(UlidFactory.generate(clock));
        EntityId entityId = EntityId.of(UlidFactory.generate(clock));
        deviceRegistry.createDevice(new Device(
                deviceId, "zigbee-00124b0012345678", "eWeLink SNZB-03P",
                "eWeLink", "SNZB-03P", null, null, null, integrationId,
                null, null, List.of(),
                Set.of(new HardwareIdentifier("zigbee", SNZB.toHexString())),
                clock.instant()));
        entityRegistry.createEntity(new Entity(
                entityId, "zigbee-00124b0012345678-ep1", EntityType.BINARY_SENSOR,
                "eWeLink SNZB-03P", deviceId, 1, null, true, List.of(), List.of(),
                clock.instant()));
        return deviceId;
    }

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

    /**
     * The rig's deliver idiom: riders ride the nop keepalive's response and park
     * in the protocol's callback queue; the §G cycle drains them, and a
     * scheduled interview runs in the SAME cycle (the queue's entry is due
     * immediately).
     */
    private static void deliverAndCycle(ZigbeeIntegrationAdapter adapter) {
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();
    }

    /** Boots a production adapter through the full §5.1 ladder to a formed network. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp,
            Integer permitJoinDuration, List<Object> adoptDevices) throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(permitJoinDuration, adoptDevices)),
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

    // ── scripted NCP (v13 dialect; interview-capable; 0x0061-capable) ───────

    /**
     * Formation + enablement + interview handler with the F-R4-1 address table.
     * Pending {@link #riders} drain onto the nop keepalive's response — riders
     * FIRST, response LAST (the receive loop returns at the match).
     */
    private List<byte[]> rejoinHandler(byte[] command) {
        if (isLegacyVersion(command)) {
            return List.of(new byte[] {
                command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74
            });
        }
        int seq = command[0] & 0xFF;
        int frameId = frameIdOf(command);
        if (frameId == EzspCoordinatorProtocol.FRAME_LOOKUP_EUI64_BY_NODE_ID) {
            return List.of(lookupEui64Response(seq, extendedParameters(command)));
        }
        return switch (frameId) {
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
            case FRAME_SEND_UNICAST ->
                    handleUnicast(seq, extendedParameters(command));
            default -> defaultResponses(seq, command);
        };
    }

    /**
     * The scripted {@code lookupEui64ByNodeId} answer (bellows-derived layout,
     * unmeasured until R-4b): [status u8][EUI64 u64 LE]; a miss carries a
     * non-zero EmberStatus and a zeroed EUI64.
     */
    private byte[] lookupEui64Response(int seq, byte[] parameters) {
        int nwk = (parameters[0] & 0xFF) | ((parameters[1] & 0xFF) << 8);
        lookupRequests.add(nwk);
        Long ieee = addressTable.get(nwk);
        byte[] response = new byte[9];
        if (lookupStatus != 0 || ieee == null) {
            response[0] = (byte) (lookupStatus != 0 ? lookupStatus : 0x01);
        } else {
            for (int i = 0; i < 8; i++) {
                response[1 + i] = (byte) (ieee >> (8 * i));
            }
        }
        return extendedResponse(seq,
                EzspCoordinatorProtocol.FRAME_LOOKUP_EUI64_BY_NODE_ID, response);
    }

    private List<byte[]> defaultResponses(int seq, byte[] command) {
        int frameId = frameIdOf(command);
        if (frameId == EzspCoordinatorProtocol.FRAME_SET_POLICY) {
            return List.of(extendedResponse(seq, frameId, new byte[] {0x00}));
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY) {
            return List.of(extendedResponse(seq, frameId,
                    new byte[] {0x00, 0x00, 0x00, 0x00}));
        }
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
            zdoRequests.computeIfAbsent(cluster, key -> new ArrayList<>()).add(message);
            byte[] reply = zdoReply(cluster, tsn, message);
            if (reply != null) {
                frames.add(incomingMessage(EzspCoordinatorProtocol.ZDO_PROFILE_ID,
                        cluster | 0x8000, 0, SNZB_NWK, reply));
            }
            return frames;
        }
        if (cluster == 0x0000 && (message[0] & 0x03) == 0x00) {  // interview Basic read
            int tsn = message[1] & 0xFF;
            frames.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                    0x0000, SNZB_ENDPOINT, SNZB_NWK, basicReply(tsn)));
        }
        return frames;
    }

    /**
     * The scripted ZDO replies. A {@code null} = silence (the fake channel
     * advances the clock to the exchange's deadline). The F-R4-1b
     * {@code IEEE_addr_req} case answers for the SNZB with a scriptable status
     * ({@link #ieeeAddrStatus}), a scriptable response nwk
     * ({@link #ieeeAddrResponseNwk}; default: the REQUEST's nwk echoed), or
     * silence ({@link #ieeeAddrSilent}).
     */
    private byte[] zdoReply(int cluster, int tsn, byte[] request) {
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
            case ZdoCodec.CLUSTER_IEEE_ADDR_REQ -> ieeeAddressResponse(tsn, request);
            default -> null;
        };
    }

    /**
     * The scripted IEEE_addr_rsp: {@code [tsn][status][ieee LE 8][nwk LE 2]}
     * (ZDP §2.4.4.1.2, the Single Device Response shape); a non-success status
     * carries a zeroed EUI64 (the {@link #lookupEui64Response} idiom).
     */
    private byte[] ieeeAddressResponse(int tsn, byte[] request) {
        if (ieeeAddrSilent) {
            return null;
        }
        int requestNwk = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
        int nwk = ieeeAddrResponseNwk != null ? ieeeAddrResponseNwk : requestNwk;
        byte[] reply = new byte[12];
        reply[0] = (byte) tsn;
        reply[1] = (byte) ieeeAddrStatus;
        if (ieeeAddrStatus == 0) {
            for (int i = 0; i < 8; i++) {
                reply[2 + i] = (byte) (SNZB_IEEE >> (8 * i));
            }
        }
        reply[10] = (byte) (nwk & 0xFF);
        reply[11] = (byte) ((nwk >> 8) & 0xFF);
        return reply;
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

    private int nextTsn() {
        reportTsn = (reportTsn + 1) & 0xFF;
        return reportTsn;
    }

    /**
     * An SNZB-shaped occupancy report ({@code map8} bit 0, the measured shape)
     * from ANY sender nwk — the frame class the silent rejoiner sends. Distinct
     * TSNs per frame (the dedup discipline).
     */
    private byte[] occupancyReport(int senderNwk) {
        byte[] zcl = {0x18, (byte) nextTsn(), 0x0A, 0x00, 0x00, 0x18, 0x01};
        return incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                OCCUPANCY_CLUSTER, SNZB_ENDPOINT, senderNwk, zcl);
    }

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
                ZdoCodec.CLUSTER_DEVICE_ANNOUNCE, 0, nwk, message);
    }

    /** The 0x0045 incomingMessageHandler callback layout (v13 — bench-proven). */
    private static byte[] incomingMessage(int profile, int cluster,
            int sourceEndpoint, int senderNwk, byte[] message) {
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
        parameters[14] = (byte) (senderNwk & 0xFF);
        parameters[15] = (byte) ((senderNwk >> 8) & 0xFF);
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

    // ── log capture (the ZigbeeConfigAcceptedAdoptionTest idiom) ────────────

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        return capture;
    }

    private List<String> adapterMessages(Level level, String prefix) {
        return messages(adapterLogCapture, level, prefix);
    }

    private List<String> ingestionMessages(Level level, String prefix) {
        return messages(ingestionLogCapture, level, prefix);
    }

    private List<String> sliceMessages(Level level, String prefix) {
        return messages(sliceLogCapture, level, prefix);
    }

    private List<String> protocolMessages(Level level, String prefix) {
        return messages(protocolLogCapture, level, prefix);
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

    private static Logger ingestionLogger() {
        return (Logger) LoggerFactory.getLogger(ZclIngestionUnit.class);
    }

    private static Logger sliceLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeAdoptionSlice.class);
    }

    private static Logger protocolLogger() {
        return (Logger) LoggerFactory.getLogger(EzspCoordinatorProtocol.class);
    }

    // ── inert context stubs (the adapter never touches these paths here) ────

    private static ConfigurationAccess configAccess(Integer permitJoinDuration,
            List<Object> adoptDevices) {
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return Map.of(ZigbeeIntegrationAdapter.ADOPT_DEVICES_KEY, adoptDevices);
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

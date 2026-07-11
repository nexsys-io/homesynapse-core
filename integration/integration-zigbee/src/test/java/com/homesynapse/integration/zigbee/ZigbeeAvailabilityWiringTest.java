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
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectType;
import com.homesynapse.integration.CommandEnvelope;
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

import java.nio.file.Files;
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
 * M9.6-AVAIL — the availability-ALIVE wiring: the previously UNWIRED
 * {@code StandardAvailabilityTracker} (mechanism-without-driver #6) goes live.
 * Device-originated RX evidence honestly moves entity availability to ALIVE;
 * battery silence / mains ping-timeout honestly move it to NOT-ALIVE — the
 * never-false-ALIVE doctrine in BOTH directions.
 *
 * <p><strong>The publish contract under test (DP-5):</strong> one root
 * {@code availability_changed} PER ADOPTED ENTITY of the transitioning device,
 * ENTITY-grain {@code SubjectRef} (the state projection applies availability
 * only at entity grain — device-grain events are a structural no-op), canonical
 * {@code "online"}/{@code "offline"} vocabulary, CRITICAL priority toward
 * offline / NORMAL toward online.
 *
 * <p>Every scenario drives the REAL adapter over the scripted NCP (the
 * {@code ZigbeeConfigAcceptedAdoptionTest} production-ladder idiom). Report
 * frames ride the nop keepalive into the ingestion drain; the timeout legs
 * live entirely on the injected {@link TestClock}.
 */
@DisplayName("ZigbeeIntegrationAdapter — availability-ALIVE wiring (M9.6-AVAIL)")
class ZigbeeAvailabilityWiringTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;
    private static final int FRAME_SEND_UNICAST = 0x0034;
    private static final int FRAME_NOP = 0x0005;

    /** The battery reporter (SNZB-03P shape, PowerSource 0x03). */
    private static final long REPORTER_IEEE = 0x00124B0012345678L;
    private static final int REPORTER_NWK = 0x6B9A;
    /** The mains actuator (dimmable-light shape, PowerSource 0x01). */
    private static final long MAINS_IEEE = 0x00178801101A09BBL;
    private static final int MAINS_NWK = 0x22FE;
    /** The uninterviewed-class identity (PowerSource 0x00 — the N-5 posture). */
    private static final long ZERO_PS_IEEE = 0x00124B00AAAAAAAAL;
    private static final int ZERO_PS_NWK = 0x77AA;

    private static final String REPORTER_LISTED = "0x00124b0012345678";

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private IntegrationId integrationId;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private ListAppender<ILoggingEvent> trackerLogCapture;

    /** Callback frames delivered on the NEXT nop keepalive (the rig pump idiom). */
    private final Deque<byte[]> riders = new ArrayDeque<>();
    /** When set, the scripted NCP accepts the availability ping but never replies. */
    private boolean pingSilent;
    /** Every ZCL tsn the scripted NCP consumed (kept distinct per reply). */
    private int scriptTsnSalt;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        integrationId = new IntegrationId(UlidFactory.generate(clock));
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        trackerLogCapture = new ListAppender<>();
        trackerLogCapture.start();
        trackerLogger().addAppender(trackerLogCapture);
    }

    @AfterEach
    void tearDown() {
        trackerLogger().detachAppender(trackerLogCapture);
    }

    // ── the wiring leg (RED pre-M9.6-AVAIL): the bench flow end-to-end ──────

    @Test
    @DisplayName("wiring: announce → interview → config-accepted adoption leaves the "
            + "adopted entity ONLINE in the event stream — entity grain, canonical "
            + "vocabulary, NORMAL priority (the view finally leaves UNKNOWN)")
    void configAcceptedAdoption_publishesEntityGrainOnline() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(REPORTER_LISTED));

        // The REAL bench ordering: the announce (device RX — the tracker's first
        // contact) precedes adoption, so the online edge fires while the device
        // has NO adopted entities. The adoption-time view seed closes that gap.
        announce(adapter, REPORTER_IEEE, REPORTER_NWK);

        List<EventEnvelope> online = entityAvailability();
        assertThat(online)
                .as("exactly one publish for the one adopted entity")
                .hasSize(1);
        EventEnvelope envelope = online.get(0);
        assertThat(envelope.subjectRef().type())
                .as("ENTITY grain — the projection applies availability only there")
                .isEqualTo(SubjectType.ENTITY);
        assertThat(adoptedEntityIds(REPORTER_IEEE))
                .contains(new EntityId(envelope.subjectRef().id()));
        AvailabilityChangedEvent payload =
                (AvailabilityChangedEvent) envelope.payload();
        assertThat(payload.previousStatus()).isEqualTo("unknown");
        assertThat(payload.newStatus())
                .as("the record's canonical vocabulary — never relink's 'available'")
                .isEqualTo("online");
        assertThat(envelope.priority()).isEqualTo(EventPriority.NORMAL);

        // A report after adoption is the same device talking — edge-triggered,
        // no second online publish.
        deliverReport(adapter, REPORTER_NWK, 1, 0x0406, occupancyReport(1, 1));
        assertThat(entityAvailability()).hasSize(1);
    }

    // ── per-entity fanout ────────────────────────────────────────────────────

    @Test
    @DisplayName("fanout: a device with TWO adopted entities publishes exactly two "
            + "online events, one per entityId")
    void twoAdoptedEntities_twoPublishes_onePerEntity() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        ZigbeeAdoptionSlice.AdoptedDevice adopted =
                adoptDirect(adapter, twoEndpointReporterInterview());

        deliverReport(adapter, REPORTER_NWK, 1, 0x0406, occupancyReport(1, 1));

        List<EventEnvelope> online = entityAvailability();
        assertThat(online).hasSize(2);
        assertThat(online.stream()
                .map(e -> new EntityId(e.subjectRef().id())))
                .containsExactlyInAnyOrderElementsOf(adopted.entityIds().values());
        assertThat(online).allSatisfy(e -> {
            AvailabilityChangedEvent payload = (AvailabilityChangedEvent) e.payload();
            assertThat(payload.previousStatus()).isEqualTo("unknown");
            assertThat(payload.newStatus()).isEqualTo("online");
            assertThat(e.priority()).isEqualTo(EventPriority.NORMAL);
        });
    }

    @Test
    @DisplayName("fanout: a transitioning device with NO adopted entities publishes "
            + "NOTHING — the sidecar still records the state")
    void unadoptedDeviceTransition_publishesNothing_sidecarWritten() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        // Known to the cache (NWK resolution), never adopted.
        adapter.deviceCache().recordInterview(reporterInterview(), null);

        deliverReport(adapter, REPORTER_NWK, 1, 0x0406, occupancyReport(1, 1));

        assertThat(publisher.ofType(EventTypes.AVAILABILITY_CHANGED).count())
                .as("no adopted entities — nothing to publish").isZero();
        assertThat(adapter.deviceCache()
                .lastKnownAvailability(new IEEEAddress(REPORTER_IEEE)))
                .as("DP-5: the sidecar write-through happens on EVERY transition")
                .contains(true);
    }

    // ── edge-triggering ──────────────────────────────────────────────────────

    @Test
    @DisplayName("edge-triggering: ten frames from the same device produce exactly "
            + "ONE online publish per adopted entity")
    void tenFrames_exactlyOneOnlinePublish() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, reporterInterview());

        for (int i = 0; i < 10; i++) {
            deliverReport(adapter, REPORTER_NWK, 1, 0x0406,
                    occupancyReport(0x10 + i, i % 2));
        }

        assertThat(entityAvailability())
                .as("one entity, one transition — ten frames are still one edge")
                .hasSize(1);
    }

    // ── construction silence (the M-1 pin) + the DP-1 restart pin ───────────

    @Test
    @DisplayName("M-1: initialize() with a registry-carried device publishes ZERO "
            + "entity-grain availability and logs ZERO tracker transitions")
    void initializeAlone_noTrackerActivity() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, reporterInterview());
        deliverReport(adapter, REPORTER_NWK, 1, 0x0406, occupancyReport(1, 1));
        adapter.close();

        // The restart: the registries carry the device (projection-rebuilt
        // shape); the sidecar file carries lastKnownAvailability=true.
        publisher = new RecordingEventPublisher(clock);
        trackerLogCapture.list.clear();
        Files.deleteIfExists(tempDir.resolve("zigbee-network.json"));
        FakeNcp restartNcp = new FakeNcp();
        restartNcp.onEzspCommand(this::scriptedNcp);
        bootProduction(restartNcp, null);

        assertThat(entityAvailability())
                .as("no frames yet — the boot itself emits no entity availability")
                .isEmpty();
        assertThat(trackerMessages())
                .as("M-1: construction performs no transitions")
                .isEmpty();
    }

    @Test
    @DisplayName("DP-1: after a restart with a PERSISTED sidecar (last state online), "
            + "the first frame still emits the online edge — the empty persisted map "
            + "is deliberate (sidecar-init + edge-triggering would starve the view)")
    void restartWithPersistedSidecar_firstFrameStillEdges() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, reporterInterview());
        deliverReport(adapter, REPORTER_NWK, 1, 0x0406, occupancyReport(1, 1));
        assertThat(adapter.deviceCache()
                .lastKnownAvailability(new IEEEAddress(REPORTER_IEEE)))
                .contains(true);
        adapter.close();   // flushes zigbee-devices.json — the sidecar persists

        publisher = new RecordingEventPublisher(clock);
        Files.deleteIfExists(tempDir.resolve("zigbee-network.json"));
        FakeNcp restartNcp = new FakeNcp();
        restartNcp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter restarted = bootProduction(restartNcp, null);

        // Had the tracker been "helpfully" seeded from availabilitySnapshot(),
        // this frame would be a no-edge and the rebuilt view would starve at
        // UNKNOWN forever — Nick's ruled trap #1.
        deliverReport(restarted, REPORTER_NWK, 1, 0x0406, occupancyReport(2, 1));

        List<EventEnvelope> online = entityAvailability();
        assertThat(online)
                .as("the first post-boot frame emits the edge the view waits for")
                .hasSize(1);
        AvailabilityChangedEvent payload =
                (AvailabilityChangedEvent) online.get(0).payload();
        assertThat(payload.previousStatus()).isEqualTo("unknown");
        assertThat(payload.newStatus()).isEqualTo("online");
    }

    // ── battery timeout ──────────────────────────────────────────────────────

    @Test
    @DisplayName("battery: 25 h of silence transitions the device offline — CRITICAL "
            + "priority, canonical payload, sidecar false, and no ping was ever sent "
            + "(the passive arm)")
    void batterySilence_offlineCritical() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, reporterInterview());
        deliverReport(adapter, REPORTER_NWK, 1, 0x0406, occupancyReport(1, 1));
        assertThat(entityAvailability()).hasSize(1);

        clock.advance(Duration.ofHours(25).plusMinutes(1));
        adapter.runCycleOnce();

        List<EventEnvelope> events = entityAvailability();
        assertThat(events).hasSize(2);
        EventEnvelope offline = events.get(1);
        AvailabilityChangedEvent payload =
                (AvailabilityChangedEvent) offline.payload();
        assertThat(payload.previousStatus()).isEqualTo("online");
        assertThat(payload.newStatus()).isEqualTo("offline");
        assertThat(offline.priority())
                .as("the payload record's doctrine: CRITICAL toward offline")
                .isEqualTo(EventPriority.CRITICAL);
        assertThat(adapter.deviceCache()
                .lastKnownAvailability(new IEEEAddress(REPORTER_IEEE)))
                .contains(false);
        assertThat(pingUnicasts(ncp, REPORTER_NWK))
                .as("battery devices take the SILENCE_TIMEOUT arm — never pinged")
                .isZero();
    }

    // ── the mains read-ping arm (DP-4 — load-bearing) ───────────────────────

    @Test
    @DisplayName("mains ping-timeout: 10 min of silence sends ONE Basic read; no "
            + "response ⇒ PING_TIMEOUT ⇒ offline CRITICAL — a dead mains device no "
            + "longer stays ALIVE forever; the next report honestly recovers it")
    void mainsPingTimeout_offlineCritical_thenRecovers() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, mainsInterview());
        deliverReport(adapter, MAINS_NWK, 11, 0x0006, onOffReport(1, true));
        assertThat(entityAvailability()).hasSize(1);

        pingSilent = true;
        clock.advance(Duration.ofMinutes(11));
        adapter.runCycleOnce();

        assertThat(pingUnicasts(ncp, MAINS_NWK))
                .as("exactly one availability ping went out")
                .isEqualTo(1);
        List<EventEnvelope> events = entityAvailability();
        assertThat(events).hasSize(2);
        AvailabilityChangedEvent offline =
                (AvailabilityChangedEvent) events.get(1).payload();
        assertThat(offline.previousStatus()).isEqualTo("online");
        assertThat(offline.newStatus()).isEqualTo("offline");
        assertThat(events.get(1).priority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(adapter.deviceCache()
                .lastKnownAvailability(new IEEEAddress(MAINS_IEEE)))
                .contains(false);

        // An offline device is no longer a ping candidate — no ping spam.
        adapter.runCycleOnce();
        assertThat(pingUnicasts(ncp, MAINS_NWK)).isEqualTo(1);

        // Recovery honesty: the device talking again is the online edge.
        pingSilent = false;
        deliverReport(adapter, MAINS_NWK, 11, 0x0006, onOffReport(2, true));
        List<EventEnvelope> recovered = entityAvailability();
        assertThat(recovered).hasSize(3);
        AvailabilityChangedEvent online =
                (AvailabilityChangedEvent) recovered.get(2).payload();
        assertThat(online.previousStatus()).isEqualTo("offline");
        assertThat(online.newStatus()).isEqualTo("online");
        assertThat(recovered.get(2).priority()).isEqualTo(EventPriority.NORMAL);
    }

    @Test
    @DisplayName("mains ping-success: the device answers the Basic read — stays "
            + "online, zero spurious publishes, and the response refreshes the "
            + "silence clock (no immediate re-ping)")
    void mainsPingSuccess_staysOnline_refreshesSilence() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, mainsInterview());
        deliverReport(adapter, MAINS_NWK, 11, 0x0006, onOffReport(1, true));
        assertThat(entityAvailability()).hasSize(1);

        clock.advance(Duration.ofMinutes(11));
        adapter.runCycleOnce();

        assertThat(pingUnicasts(ncp, MAINS_NWK)).isEqualTo(1);
        assertThat(entityAvailability())
                .as("a successful ping is not a transition — edge-triggered")
                .hasSize(1);

        // The ping response was consumed by the exchange itself (it never rides
        // the ingestion drain), so the refreshed silence clock is the
        // recordCommandResult(true) wiring — observable as no re-ping inside a
        // fresh 10 min window...
        clock.advance(Duration.ofMinutes(9));
        adapter.runCycleOnce();
        assertThat(pingUnicasts(ncp, MAINS_NWK)).isEqualTo(1);

        // ...and a second ping once that window lapses.
        clock.advance(Duration.ofMinutes(2));
        adapter.runCycleOnce();
        assertThat(pingUnicasts(ncp, MAINS_NWK)).isEqualTo(2);
        assertThat(entityAvailability()).hasSize(1);
    }

    // ── never-false-ALIVE: what must NOT feed the tracker ───────────────────

    @Test
    @DisplayName("TX never feeds: a dispatched command the NCP accepts is not device "
            + "evidence — the tracker state stays UNKNOWN (DP-7)")
    void dispatchAccepted_neverFeedsAvailability() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        ZigbeeAdoptionSlice.AdoptedDevice adopted =
                adoptDirect(adapter, mainsInterview());
        EntityId entity = adopted.entityIds().values().iterator().next();

        adapter.commandHandler().handle(new CommandEnvelope(
                entity, "turn_on", Map.of(),
                UlidFactory.generate(clock), UlidFactory.generate(clock),
                integrationId));

        assertThat(publisher.ofType(EventTypes.AVAILABILITY_CHANGED).count())
                .as("NCP acceptance fabricates no liveness").isZero();
        assertThat(trackerMessages())
                .as("the tracker never transitioned").isEmpty();
        assertThat(adapter.deviceCache()
                .lastKnownAvailability(new IEEEAddress(MAINS_IEEE)))
                .as("the sidecar was never written").isEmpty();
    }

    @Test
    @DisplayName("keepalives and join callbacks never feed: NCP nops, a "
            + "trustCenterJoin and a childJoin leave the tracker silent (the "
            + "handler pins re-asserted at the tracker)")
    void keepalivesAndJoinCallbacks_neverFeed() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        riders.add(trustCenterJoinCallback(REPORTER_IEEE, REPORTER_NWK));
        riders.add(childJoinCallback(REPORTER_IEEE, REPORTER_NWK));
        for (int i = 0; i < 3; i++) {
            adapter.coordinatorProtocol().ping();   // the keepalive-class exchange
            adapter.runCycleOnce();
        }

        assertThat(publisher.ofType(EventTypes.AVAILABILITY_CHANGED).count())
                .isZero();
        assertThat(trackerMessages()).isEmpty();
    }

    // ── DP-2 powerSource routing through the wiring ──────────────────────────

    @Test
    @DisplayName("powerSource routing: a mains record takes the 10 min ping path; a "
            + "powerSource-0 record takes the conservative 25 h battery window (N-5)")
    void powerSourceRouting_mainsPinged_zeroPowerSourceWaits() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, mainsInterview());
        adoptDirect(adapter, zeroPowerSourceInterview());
        deliverReport(adapter, MAINS_NWK, 11, 0x0006, onOffReport(1, true));
        deliverReport(adapter, ZERO_PS_NWK, 1, 0x0406, occupancyReport(1, 1));
        assertThat(entityAvailability()).hasSize(2);

        clock.advance(Duration.ofMinutes(11));
        adapter.runCycleOnce();

        assertThat(pingUnicasts(ncp, MAINS_NWK))
                .as("the mains record reached the ping arm").isEqualTo(1);
        assertThat(pingUnicasts(ncp, ZERO_PS_NWK))
                .as("PowerSource 0 is battery-conservative — never pinged")
                .isZero();
        assertThat(entityAvailability())
                .as("the answered ping and the waiting battery window move nothing")
                .hasSize(2);

        clock.advance(Duration.ofHours(25));
        adapter.runCycleOnce();

        assertThat(pingUnicasts(ncp, ZERO_PS_NWK)).isZero();
        List<EventEnvelope> events = entityAvailability();
        List<EventEnvelope> offline = events.stream()
                .filter(e -> "offline".equals(
                        ((AvailabilityChangedEvent) e.payload()).newStatus()))
                .toList();
        assertThat(offline)
                .as("the 25 h window finally offlines the powerSource-0 device")
                .hasSize(1);
        assertThat(new EntityId(offline.get(0).subjectRef().id()))
                .isIn(adoptedEntityIds(ZERO_PS_IEEE));
    }

    // ── derivation regression ────────────────────────────────────────────────

    @Test
    @DisplayName("derivation regression: availability publishes mint ONLY "
            + "availability_changed — zero state_changed drafts appear")
    void availabilityPublishes_deriveNothing() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, reporterInterview());

        deliverReport(adapter, REPORTER_NWK, 1, 0x0406, occupancyReport(1, 1));

        assertThat(entityAvailability()).hasSize(1);
        assertThat(publisher.ofType(EventTypes.STATE_CHANGED).count())
                .as("state_changed derives from state_reported only — the "
                        + "ProductionDerivationRule gate; availability events "
                        + "produce zero drafts")
                .isZero();
    }

    // ── the DP-8 frozen token ────────────────────────────────────────────────

    @Test
    @DisplayName("DP-8: the tracker's transition INFO goes live with the wiring — "
            + "verbatim, both directions (frozen from this WU forward)")
    void transitionInfoToken_verbatimBothDirections() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::scriptedNcp);
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adoptDirect(adapter, reporterInterview());

        deliverReport(adapter, REPORTER_NWK, 1, 0x0406, occupancyReport(1, 1));
        clock.advance(Duration.ofHours(25).plusMinutes(1));
        adapter.runCycleOnce();

        IEEEAddress reporter = new IEEEAddress(REPORTER_IEEE);
        assertThat(trackerMessages())
                .containsExactly(
                        "zigbee.availability_changed: device=" + reporter
                                + " available=true",
                        "zigbee.availability_changed: device=" + reporter
                                + " available=false");
    }

    // ── fixtures: interviews (recordInterview + slice adoption inputs) ──────

    private static InterviewResult reporterInterview() {
        return new InterviewResult(new IEEEAddress(REPORTER_IEEE), REPORTER_NWK,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500),
                        List.of(0x0003, 0x0019))),
                "eWeLink", "SNZB-03P", 3, InterviewStatus.COMPLETE);
    }

    private static InterviewResult twoEndpointReporterInterview() {
        EndpointDescriptor occupancy1 = new EndpointDescriptor(1, 0x0104, 0x0107,
                List.of(0x0000, 0x0001, 0x0406), List.of());
        EndpointDescriptor occupancy2 = new EndpointDescriptor(2, 0x0104, 0x0107,
                List.of(0x0000, 0x0001, 0x0406), List.of());
        return new InterviewResult(new IEEEAddress(REPORTER_IEEE), REPORTER_NWK,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(occupancy1, occupancy2),
                "eWeLink", "SNZB-03P", 3, InterviewStatus.COMPLETE);
    }

    private static InterviewResult mainsInterview() {
        return new InterviewResult(new IEEEAddress(MAINS_IEEE), MAINS_NWK,
                new NodeDescriptor(1, 0x100B, 82, 142),
                List.of(new EndpointDescriptor(11, 0x0104, 0x0101,
                        List.of(0x0000, 0x0003, 0x0006, 0x0008), List.of(0x0019))),
                "Signify Netherlands B.V.", "LWA004", 1,
                InterviewStatus.COMPLETE);
    }

    private static InterviewResult zeroPowerSourceInterview() {
        return new InterviewResult(new IEEEAddress(ZERO_PS_IEEE), ZERO_PS_NWK,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0406), List.of())),
                "unknown", "unknown", 0, InterviewStatus.COMPLETE);
    }

    /**
     * Adoption without the announce/interview walk: the cache learns the record
     * (NWK index + powerSource — the DP-2 lookup source), the slice proposes and
     * adopts. No frame has fed the tracker yet — the device sits UNKNOWN.
     */
    private ZigbeeAdoptionSlice.AdoptedDevice adoptDirect(
            ZigbeeIntegrationAdapter adapter, InterviewResult interview) {
        adapter.deviceCache().recordInterview(interview, null);
        adapter.adoptionSlice().onDeviceDiscovered(interview, null);
        return adapter.adoptionSlice().adopt(interview.ieeeAddress());
    }

    private Set<EntityId> adoptedEntityIds(long ieee) {
        return deviceRegistry.findByHardwareIdentifier(
                        ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                        new IEEEAddress(ieee).toHexString())
                .map(device -> entityRegistry.listEntitiesByDevice(device.deviceId())
                        .stream().map(com.homesynapse.device.Entity::entityId)
                        .collect(java.util.stream.Collectors.toSet()))
                .orElse(Set.of());
    }

    // ── the pump + report fixtures ───────────────────────────────────────────

    /** Delivers one Device_annce and runs one §G cycle (the rig pump idiom). */
    private void announce(ZigbeeIntegrationAdapter adapter, long ieee, int nwk) {
        riders.add(deviceAnnounceCallback(ieee, nwk));
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();
    }

    /** Delivers one incoming ZCL frame from a device and runs one §G cycle. */
    private void deliverReport(ZigbeeIntegrationAdapter adapter, int senderNwk,
            int sourceEndpoint, int clusterId, byte[] zcl) {
        riders.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                clusterId, sourceEndpoint, senderNwk, zcl));
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();
    }

    /** An unsolicited occupancy report (cluster 0x0406, attr 0x0000, map8). */
    private static byte[] occupancyReport(int tsn, int occupied) {
        return new byte[] {0x18, (byte) tsn, 0x0A, 0x00, 0x00, 0x18,
                (byte) occupied};
    }

    /** An unsolicited on/off report (cluster 0x0006, attr 0x0000, bool). */
    private static byte[] onOffReport(int tsn, boolean on) {
        return new byte[] {0x18, (byte) tsn, 0x0A, 0x00, 0x00, 0x10,
                (byte) (on ? 0x01 : 0x00)};
    }

    // ── availability stream helpers ──────────────────────────────────────────

    /** The ENTITY-grain availability envelopes, in publish order. */
    private List<EventEnvelope> entityAvailability() {
        return publisher.ofType(EventTypes.AVAILABILITY_CHANGED)
                .filter(e -> e.subjectRef().type() == SubjectType.ENTITY)
                .toList();
    }

    /** The DP-8 tracker INFO lines, in order. */
    private List<String> trackerMessages() {
        return trackerLogCapture.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("zigbee.availability_changed"))
                .toList();
    }

    private static Logger trackerLogger() {
        return (Logger) LoggerFactory.getLogger(StandardAvailabilityTracker.class);
    }

    /**
     * Counts the availability pings (Basic-cluster ZCL global reads) the NCP
     * received for one destination — frame-id-scoped, never a total (the
     * session-start arithmetic lesson).
     */
    private static long pingUnicasts(FakeNcp ncp, int destinationNwk) {
        return ncp.receivedEzspCommands().stream()
                .filter(command -> command.length >= 5
                        && frameIdOf(command) == FRAME_SEND_UNICAST)
                .map(ZigbeeAvailabilityWiringTest::extendedParameters)
                .filter(p -> p.length > 16
                        && ((p[1] & 0xFF) | ((p[2] & 0xFF) << 8)) == destinationNwk
                        && ((p[3] & 0xFF) | ((p[4] & 0xFF) << 8))
                                == EzspCoordinatorProtocol.HA_PROFILE_ID
                        && ((p[5] & 0xFF) | ((p[6] & 0xFF) << 8)) == 0x0000)
                .count();
    }

    // ── the production boot ladder (the ConfigAcceptedAdoption idiom) ───────

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

    // ── the scripted NCP (v13; formation + interview + ping capable) ────────

    private List<byte[]> scriptedNcp(byte[] command) {
        if (isLegacyVersion(command)) {
            return List.of(new byte[] {
                command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74
            });
        }
        int seq = command[0] & 0xFF;
        return switch (frameIdOf(command)) {
            case FRAME_START_SCAN -> {
                List<byte[]> frames = new ArrayList<>();
                frames.add(extendedResponse(seq, FRAME_START_SCAN,
                        new byte[] {0x00}));
                for (int channel = 11; channel <= 26; channel++) {
                    int rssi = channel == 20 ? -95 : -60;
                    frames.add(new byte[] {
                        0x00, (byte) 0x90, 0x01, 0x48, 0x00, (byte) channel,
                        (byte) rssi
                    });
                }
                frames.add(new byte[] {
                    0x00, (byte) 0x90, 0x01, 0x1C, 0x00, 0x00, 0x00});
                yield frames;
            }
            case FRAME_FORM_NETWORK -> List.of(
                    extendedResponse(seq, FRAME_FORM_NETWORK, new byte[] {0x00}),
                    new byte[] {0x00, (byte) 0x90, 0x01, 0x19, 0x00, (byte) 0x90});
            case FRAME_NOP -> {
                // Riders BEFORE the nop response (the rig idiom): the receive
                // loop returns at the matching response — callbacks must precede
                // it to park in the drain.
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
                (byte) (REPORTER_NWK & 0xFF), (byte) ((REPORTER_NWK >> 8) & 0xFF)}));
        }
        return switch (frameId) {
            case FRAME_NETWORK_INIT, FRAME_FORM_NETWORK, FRAME_PERMIT_JOINING,
                    FRAME_SET_INITIAL_SECURITY_STATE ->
                    List.of(extendedResponse(seq, frameId, new byte[] {0x00}));
            case 0x0018 -> List.of(extendedResponse(seq, 0x0018, new byte[] {0x02}));
            default -> List.of();
        };
    }

    /**
     * Unicast handling: the SRSP acceptance always leads; the ZDO interview walk
     * and the interview Basic read serve the wiring leg; a Basic-cluster global
     * read for the SINGLE attribute 0x0000 is the availability ping — answered
     * unless {@link #pingSilent} (the exchange then times out on the channel's
     * clock advance).
     */
    private List<byte[]> handleUnicast(int seq, byte[] parameters) {
        int destination = (parameters[1] & 0xFF) | ((parameters[2] & 0xFF) << 8);
        int profile = (parameters[3] & 0xFF) | ((parameters[4] & 0xFF) << 8);
        int cluster = (parameters[5] & 0xFF) | ((parameters[6] & 0xFF) << 8);
        int destEndpoint = parameters[8] & 0xFF;
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
                        cluster | 0x8000, 0, REPORTER_NWK, reply));
            }
            return frames;
        }
        if (cluster == 0x0000 && (message[0] & 0x03) == 0x00
                && (message[2] & 0xFF) == 0x00) {
            int tsn = message[1] & 0xFF;
            if (message.length == 5) {
                // The availability ping: one attribute id (0x0000).
                if (!pingSilent) {
                    frames.add(incomingMessage(
                            EzspCoordinatorProtocol.HA_PROFILE_ID, 0x0000,
                            destEndpoint, destination, pingReply(tsn)));
                }
                return frames;
            }
            // The interview identity read.
            frames.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                    0x0000, 1, REPORTER_NWK, basicReply(tsn)));
        }
        return frames;
    }

    /** A minimal Read Attributes Response (attr 0x0000, u8, SUCCESS). */
    private static byte[] pingReply(int tsn) {
        return new byte[] {0x18, (byte) tsn, 0x01, 0x00, 0x00, 0x00, 0x20, 0x03};
    }

    private static byte[] zdoReply(int cluster, int tsn) {
        int nwkLo = REPORTER_NWK & 0xFF;
        int nwkHi = (REPORTER_NWK >> 8) & 0xFF;
        return switch (cluster) {
            case ZdoCodec.CLUSTER_NODE_DESC_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi,
                    0x02, 0x40, (byte) 0x80,
                    (byte) 0x86, 0x12,
                    82, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            case ZdoCodec.CLUSTER_ACTIVE_EP_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi, 0x01, 0x01};
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
        reply[i++] = (byte) (REPORTER_NWK & 0xFF);
        reply[i++] = (byte) ((REPORTER_NWK >> 8) & 0xFF);
        reply[i++] = (byte) length;
        reply[i++] = 0x01;                              // endpoint 1
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
        reply[i++] = 0x18;
        reply[i++] = (byte) tsn;
        reply[i++] = 0x01;
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
        buffer[offset++] = 0x00;
        buffer[offset++] = 0x42;
        buffer[offset++] = (byte) value.length();
        for (char c : value.toCharArray()) {
            buffer[offset++] = (byte) c;
        }
        return offset;
    }

    // ── callback fixtures (the bench-proven v13 layouts) ────────────────────

    private static byte[] deviceAnnounceCallback(long ieee, int nwk) {
        byte[] message = new byte[12];
        message[0] = 0x41;
        message[1] = (byte) (nwk & 0xFF);
        message[2] = (byte) ((nwk >> 8) & 0xFF);
        for (int i = 0; i < 8; i++) {
            message[3 + i] = (byte) (ieee >> (8 * i));
        }
        message[11] = (byte) 0x80;
        return incomingMessage(EzspCoordinatorProtocol.ZDO_PROFILE_ID,
                ZdoCodec.CLUSTER_DEVICE_ANNOUNCE, 0, nwk, message);
    }

    /** A 0x0024 trustCenterJoinHandler callback (secured join started). */
    private static byte[] trustCenterJoinCallback(long ieee, int nwk) {
        byte[] parameters = new byte[14];
        parameters[0] = (byte) (nwk & 0xFF);
        parameters[1] = (byte) ((nwk >> 8) & 0xFF);
        for (int i = 0; i < 8; i++) {
            parameters[2 + i] = (byte) (ieee >> (8 * i));
        }
        parameters[10] = 0x01;   // DEVICE_UPDATE: standard security, secured join
        parameters[11] = 0x00;   // JOIN_DECISION: use preconfigured key
        parameters[12] = 0x00;   // parent LE
        parameters[13] = 0x00;
        return callbackFrame(
                EzspCoordinatorProtocol.FRAME_TRUST_CENTER_JOIN_HANDLER,
                parameters);
    }

    /** A 0x0023 childJoinHandler callback (joining). */
    private static byte[] childJoinCallback(long ieee, int nwk) {
        byte[] parameters = new byte[13];
        parameters[0] = 0x00;    // index
        parameters[1] = 0x01;    // joining
        parameters[2] = (byte) (nwk & 0xFF);
        parameters[3] = (byte) ((nwk >> 8) & 0xFF);
        for (int i = 0; i < 8; i++) {
            parameters[4 + i] = (byte) (ieee >> (8 * i));
        }
        parameters[12] = 0x04;   // sleepy end device
        return callbackFrame(
                EzspCoordinatorProtocol.FRAME_CHILD_JOIN_HANDLER, parameters);
    }

    /** The 0x0045 incomingMessageHandler callback layout, sender-parameterized. */
    private static byte[] incomingMessage(int profile, int cluster,
            int sourceEndpoint, int senderNwk, byte[] message) {
        byte[] parameters = new byte[19 + message.length];
        parameters[0] = 0x00;
        parameters[1] = (byte) (profile & 0xFF);
        parameters[2] = (byte) ((profile >> 8) & 0xFF);
        parameters[3] = (byte) (cluster & 0xFF);
        parameters[4] = (byte) ((cluster >> 8) & 0xFF);
        parameters[5] = (byte) sourceEndpoint;
        parameters[6] = (byte) (sourceEndpoint == 0 ? 0 : 1);
        parameters[12] = (byte) 176;
        parameters[13] = (byte) -56;
        parameters[14] = (byte) (senderNwk & 0xFF);
        parameters[15] = (byte) ((senderNwk >> 8) & 0xFF);
        parameters[16] = (byte) 0xFF;
        parameters[17] = (byte) 0xFF;
        parameters[18] = (byte) message.length;
        System.arraycopy(message, 0, parameters, 19, message.length);
        return callbackFrame(
                EzspCoordinatorProtocol.FRAME_INCOMING_MESSAGE_HANDLER, parameters);
    }

    private static byte[] callbackFrame(int frameId, byte[] parameters) {
        byte[] frame = new byte[5 + parameters.length];
        frame[0] = 0x00;
        frame[1] = (byte) 0x90;
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

    // ── inert context stubs ──────────────────────────────────────────────────

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

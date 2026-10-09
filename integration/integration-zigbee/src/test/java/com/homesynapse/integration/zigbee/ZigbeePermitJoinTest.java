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
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.PairingWindow;
import com.homesynapse.integration.PairingWindowRequest;
import com.homesynapse.integration.PermitJoinClosed;
import com.homesynapse.integration.PermitJoinOpened;
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
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PJ-2 — the pairing window as a DECLARED, TIME-BOXED, RECORDED act: the window's
 * tests of record. It opens ONLY by {@link ZigbeeIntegrationAdapter#openPairingWindow}
 * (the endpoint's path, run on the adapter's command executor — the fixture calls it
 * directly), never at start: the start path reads no join key at all —
 * {@code permit_join_duration} left the schema with AMD-102 (CONFIG-ERROR-1), and a
 * configuration carrying it fails the boot (M9.4-PJ's start-path window is retired).
 * Every open publishes {@code permit_join_opened}; every close publishes ONE
 * {@code permit_join_closed} naming its cause ({@code elapsed} from the cycle,
 * {@code superseded} by a later open, {@code transport_reopened}, {@code shutdown}).
 *
 * <p>The north star is never-false-ALIVE extended to the join window:
 * {@link ZigbeeIntegrationAdapter#isPermitJoinActive()} reflects the real clock-based
 * window — never open when closed. The scripted NCP answers frame {@code 0x0022}
 * (permitJoining) through the shared default handler, so these tests drive the
 * production ladder over the {@link FakeNcp} and byte-assert the emitted frame (the
 * {@link ZigbeeProductionTransportTest} idiom); the fake NCP's {@code 0x0022} count is
 * THE instrument for "the protocol sent permit-join".
 */
@DisplayName("ZigbeeIntegrationAdapter — the pairing window as a declared act (PJ-2)")
class ZigbeePermitJoinTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;

    private static final int WINDOW_SECONDS = 120;
    private static final PairingWindowRequest REQUEST =
            new PairingWindowRequest(WINDOW_SECONDS, "pair the hallway sensor", "key-01", null);
    private static final PairingWindowRequest SECOND_REQUEST =
            new PairingWindowRequest(60, "pair the second sensor", "key-02", null);
    /** J2b: the device-scoped open — the SNZB's IEEE as an operator writes it. */
    private static final String SCOPE_LOWER = "0x00124b0012345678";
    private static final String SCOPE_CANONICAL = "0x00124B0012345678";
    private static final PairingWindowRequest SCOPED_REQUEST =
            new PairingWindowRequest(WINDOW_SECONDS, "recover the hallway sensor", "key-01",
                    SCOPE_LOWER);

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private ListAppender<ILoggingEvent> logCapture;
    private IntegrationId integrationId;

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

    // ── T1: the open — enablement, ONE 0x0022, the clock window, ONE event ──

    @Test
    @DisplayName("T1: openPairingWindow enables joins then sends ONE 0x0022 carrying the "
            + "request's duration, opens the clock window, and publishes ONE "
            + "permit_join_opened on the integration subject whose payload IS the returned "
            + "window")
    void openPairingWindow_opensProtocolWindow_publishesOpened() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        assertThat(countFrames(ncp, FRAME_PERMIT_JOINING))
                .as("no permit-join frame is emitted during boot").isZero();

        PairingWindow window = adapter.openPairingWindow(REQUEST);

        List<byte[]> joins = framesWithId(ncp, FRAME_PERMIT_JOINING);
        assertThat(joins).as("exactly one permit-join frame").hasSize(1);
        assertThat(joins.get(0)[5]).as("the request's duration byte rides the frame")
                .isEqualTo((byte) WINDOW_SECONDS);
        assertThat(enablementFrameIds(ncp))
                .as("policy → policy → transient key → permitJoin (M9.4-TCJ §A.1)")
                .containsExactly(
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY,
                        FRAME_PERMIT_JOINING);
        assertThat(adapter.isPermitJoinActive()).as("the window is open").isTrue();
        assertThat(adapter.currentPairingWindow()).contains(window);

        Instant opensAt = window.opensAt();
        assertThat(opensAt).as("opensAt is the fixture clock's instant").isEqualTo(clock.instant());
        assertThat(window).isEqualTo(new PairingWindow(integrationId, opensAt,
                opensAt.plusSeconds(WINDOW_SECONDS), WINDOW_SECONDS, REQUEST.reason(),
                REQUEST.actor(), null));

        List<EventEnvelope> opened = publisher.ofType(EventTypes.PERMIT_JOIN_OPENED).toList();
        assertThat(opened).as("ONE permit_join_opened").hasSize(1);
        EventEnvelope envelope = opened.get(0);
        assertThat(envelope.subjectRef()).isEqualTo(SubjectRef.integration(integrationId));
        assertThat(envelope.origin()).isEqualTo(EventOrigin.INTEGRATION);
        assertThat(envelope.priority()).isEqualTo(EventPriority.NORMAL);
        assertThat(envelope.eventTime()).isEqualTo(opensAt);
        assertThat(envelope.schemaVersion())
                .as("J2: permit_join_opened publishes at schema 2 (+ nullable scope)")
                .isEqualTo(2);
        assertThat(envelope.payload()).isEqualTo(new PermitJoinOpened(integrationId, "zigbee",
                WINDOW_SECONDS, REQUEST.reason(), REQUEST.actor(), opensAt,
                opensAt.plusSeconds(WINDOW_SECONDS), null));
        assertThat(publisher.published()).as("nothing but the open").hasSize(1);
        assertThat(messages(Level.INFO, "zigbee.permit_join_opened"))
                .containsExactly("zigbee.permit_join_opened: duration=120s "
                        + "reason=pair the hallway sensor actor=key-01");
        assertThat(messages(Level.WARN, "zigbee.permit_join")).isEmpty();
    }

    // ── J2b: the scoped open, the un-scoped bytes, the three-act close ───────

    @Test
    @DisplayName("T1s (J2b): a SCOPED open writes TC policy 0x0013 and installs the transient "
            + "key with the partner's EUI64 (wire order) in its first 8 bytes; the window, the "
            + "event and the INFO carry the canonical scope; the opened event is schema 2")
    void scopedOpen_writesPolicy0x0013_andThePartnerBytes() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        PairingWindow window = adapter.openPairingWindow(SCOPED_REQUEST);

        List<byte[]> policies = framesWithId(ncp, EzspCoordinatorProtocol.FRAME_SET_POLICY);
        assertThat(policies).hasSize(2);
        assertThat(parametersOf(policies.get(0)))
                .as("ALLOW_JOINS | ALLOW_UNSECURED_REJOINS | JOINS_USE_INSTALL_CODE_KEY, u16 LE")
                .containsExactly(0x00, 0x13, 0x00);
        assertThat(parametersOf(policies.get(1)))
                .as("TC_KEY_REQUEST_POLICY unchanged").containsExactly(0x05, 0x51, 0x00);
        byte[] transientKey = parametersOf(
                framesWithId(ncp, EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY).get(0));
        assertThat(transientKey).hasSize(25);
        assertThat(Arrays.copyOfRange(transientKey, 0, 8))
                .as("the partner EUI64 little-endian — the inverse of KeyEstablishment.parse")
                .containsExactly(0x78, 0x56, 0x34, 0x12, 0x00, 0x4B, 0x12, 0x00);
        assertThat(enablementFrameIds(ncp)).containsExactly(
                EzspCoordinatorProtocol.FRAME_SET_POLICY,
                EzspCoordinatorProtocol.FRAME_SET_POLICY,
                EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY,
                FRAME_PERMIT_JOINING);
        assertThat(window.scope()).as("canonical: 0x + upper-case").isEqualTo(SCOPE_CANONICAL);
        EventEnvelope opened = publisher.ofType(EventTypes.PERMIT_JOIN_OPENED).findFirst()
                .orElseThrow();
        assertThat(opened.schemaVersion()).isEqualTo(2);
        assertThat(opened.payload()).isEqualTo(new PermitJoinOpened(integrationId, "zigbee",
                WINDOW_SECONDS, SCOPED_REQUEST.reason(), SCOPED_REQUEST.actor(),
                window.opensAt(), window.closesAt(), SCOPE_CANONICAL));
        assertThat(messages(Level.INFO, "zigbee.permit_join_"))
                .as("the frozen opened line first, byte-exact; the scope line AFTER it")
                .containsExactly(
                        "zigbee.permit_join_opened: duration=120s "
                                + "reason=recover the hallway sensor actor=key-01",
                        "zigbee.permit_join_scoped: scope=" + SCOPE_CANONICAL);
    }

    @Test
    @DisplayName("T2u (J2b): the un-scoped open is byte-for-byte today's — policy 0x0003, the "
            + "wildcard partner, no scope line; scope null on the window and the event")
    void unscopedOpen_todaysBytesExactly() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        PairingWindow window = adapter.openPairingWindow(REQUEST);

        List<byte[]> policies = framesWithId(ncp, EzspCoordinatorProtocol.FRAME_SET_POLICY);
        assertThat(parametersOf(policies.get(0))).containsExactly(0x00, 0x03, 0x00);
        assertThat(parametersOf(policies.get(1))).containsExactly(0x05, 0x51, 0x00);
        byte[] transientKey = parametersOf(
                framesWithId(ncp, EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY).get(0));
        byte[] wildcard = new byte[8];
        Arrays.fill(wildcard, (byte) 0xFF);
        assertThat(Arrays.copyOfRange(transientKey, 0, 8)).containsExactly(wildcard);
        assertThat(window.scope()).isNull();
        assertThat(((PermitJoinOpened) publisher.published().get(0).payload()).scope()).isNull();
        assertThat(messages(Level.INFO, "zigbee.permit_join_scoped")).isEmpty();
    }

    @Test
    @DisplayName("T3 (J2b): the elapsed close runs the NCP's three-act close — 0x006B, then "
            + "setPolicy(TC, 0x0002), then permitJoin(0) — BEFORE permit_join_closed(elapsed) "
            + "is published; the event-sequence pin holds")
    void elapsedClose_runsTheThreeActNcpClose_beforePublishing() throws Exception {
        FakeNcp ncp = new FakeNcp();
        List<Long> closedEventsAtClear = new ArrayList<>();
        ncp.onEzspCommand(command -> {
            if (!isLegacyVersion(command) && frameIdOf(command)
                    == EzspCoordinatorProtocol.FRAME_CLEAR_TRANSIENT_LINK_KEYS) {
                closedEventsAtClear.add(
                        publisher.ofType(EventTypes.PERMIT_JOIN_CLOSED).count());
            }
            return formationHandler(ncp, command);
        });
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adapter.openPairingWindow(REQUEST);
        int framesAtOpen = ncp.receivedEzspCommands().size();
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS + 1));

        adapter.runCycleOnce();

        List<byte[]> afterOpen = ncp.receivedEzspCommands()
                .subList(framesAtOpen, ncp.receivedEzspCommands().size());
        assertThat(windowFrameIdsOf(afterOpen))
                .as("clearTransientLinkKeys → setPolicy → permitJoin, nothing else window-shaped")
                .containsExactly(
                        EzspCoordinatorProtocol.FRAME_CLEAR_TRANSIENT_LINK_KEYS,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        FRAME_PERMIT_JOINING);
        List<byte[]> closeFrames = afterOpen.stream()
                .filter(c -> !isLegacyVersion(c)).toList();
        assertThat(parametersOf(frameOf(closeFrames,
                EzspCoordinatorProtocol.FRAME_CLEAR_TRANSIENT_LINK_KEYS)))
                .as("0x006B carries no parameters").isEmpty();
        assertThat(parametersOf(frameOf(closeFrames, EzspCoordinatorProtocol.FRAME_SET_POLICY)))
                .as("ALLOW_UNSECURED_REJOINS only — the standing posture between windows")
                .containsExactly(0x00, 0x02, 0x00);
        assertThat(parametersOf(frameOf(closeFrames, FRAME_PERMIT_JOINING)))
                .as("permitJoin(0) closes the MAC window").containsExactly(0x00);
        assertThat(closedEventsAtClear)
                .as("the NCP close ran BEFORE the record's close was published")
                .containsExactly(0L);
        assertThat(publisher.published()).extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.PERMIT_JOIN_OPENED, EventTypes.PERMIT_JOIN_CLOSED);
        assertThat(messages(Level.WARN, "zigbee.permit_join_ncp_close_failed")).isEmpty();
    }

    // ── T2: the cycle closes an elapsed window ONCE ──────────────────────────

    @Test
    @DisplayName("T2: one cycle past the deadline publishes ONE permit_join_closed(elapsed) "
            + "whose closedAt is the window's own end (not the cycle's clock) and openedAt "
            + "the open; the window reads closed; a second cycle publishes nothing")
    void runCycleOnce_pastDeadline_publishesElapsedOnce() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        PairingWindow window = adapter.openPairingWindow(REQUEST);
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS + 1));

        adapter.runCycleOnce();

        List<EventEnvelope> closed = publisher.ofType(EventTypes.PERMIT_JOIN_CLOSED).toList();
        assertThat(closed).as("ONE permit_join_closed").hasSize(1);
        assertThat(closed.get(0).subjectRef()).isEqualTo(SubjectRef.integration(integrationId));
        assertThat(closed.get(0).origin()).isEqualTo(EventOrigin.INTEGRATION);
        assertThat(closed.get(0).payload()).isEqualTo(new PermitJoinClosed(integrationId,
                "zigbee", PermitJoinClosed.CAUSE_ELAPSED, window.opensAt(), window.closesAt()));
        assertThat(adapter.isPermitJoinActive()).as("closed by time").isFalse();
        assertThat(adapter.currentPairingWindow()).isEmpty();

        adapter.runCycleOnce();

        assertThat(publisher.published())
                .as("the open, the one close — nothing from the second cycle")
                .extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.PERMIT_JOIN_OPENED, EventTypes.PERMIT_JOIN_CLOSED);
    }

    // ── T3 (the key-ignored pair) RETIRED by AMD-102 / CONFIG-ERROR-1: the key left the
    //    schema and the adapter reads none; a configuration carrying it fails the boot
    //    (HomeSynapseCoreSchemaAdmissionTest T3). The start path's "zero enablement
    //    frames" pin lives in ZigbeeTrustCenterJoinTest §A-2. ────────────────────────

    // ── the record rejects out-of-range durations before any frame ──────────

    @Test
    @DisplayName("an out-of-range duration (0, 255) is rejected by the request record itself "
            + "— IllegalArgumentException naming the bound; no frame is ever sent")
    void requestOutOfRange_rejectedByTheRecord() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        assertThatThrownBy(() -> new PairingWindowRequest(0, "pair", "key-01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("254");
        assertThatThrownBy(() -> new PairingWindowRequest(255, "pair", "key-01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("254");

        assertThat(countFrames(ncp, FRAME_PERMIT_JOINING))
                .as("no request ⇒ no frame").isZero();
        assertThat(adapter.isPermitJoinActive()).isFalse();
    }

    // ── honest window state (clock-based) ───────────────────────────────────

    @Test
    @DisplayName("isPermitJoinActive reflects the real clock window — false before open, "
            + "true inside, false once the clock reaches the request's deadline")
    void isPermitJoinActive_reflectsClockWindow() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);

        assertThat(adapter.isPermitJoinActive()).as("closed before open").isFalse();

        adapter.openPairingWindow(new PairingWindowRequest(200, "pair", "key-01", null));
        assertThat(adapter.isPermitJoinActive()).as("open at t0").isTrue();

        clock.advance(Duration.ofSeconds(199));
        assertThat(adapter.isPermitJoinActive())
                .as("still open one second before the deadline").isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(adapter.isPermitJoinActive())
                .as("closed once the clock reaches the deadline").isFalse();
        assertThat(adapter.currentPairingWindow())
                .as("never-false-ALIVE: no current window past its end").isEmpty();
    }

    // ── driven mode is untouched (the M9.4a hero substrate) ─────────────────

    @Test
    @DisplayName("the M9.4a driven cadence never opens the window — runCycleOnce() opens "
            + "nothing, so the window never opens")
    void drivenMode_neverOpensWindow() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        FakeSerialByteChannel channel = channelOver(ncp);
        // Driven mode: an injected channel opener selects the M9.4a rig path (no join
        // key exists for the adapter to read since AMD-102).
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(null)), new InMemoryDeviceRegistry(),
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
        assertThat(publisher.published()).isEmpty();
    }

    // ── DP-B5: a successful reopen closes the window (transport_reopened) ───

    @Test
    @DisplayName("DP-B5: a successful watchdog reopen clears permitJoinDeadline AND publishes "
            + "ONE permit_join_closed(transport_reopened) — the reset NCP holds no window, "
            + "so isPermitJoinActive never reads stale-true and the record says why")
    void reopenClearsThePermitJoinDeadline() throws Exception {
        FakeNcp formingNcp = new FakeNcp();
        formingNcp.onEzspCommand(command -> formationHandler(formingNcp, command));
        FakeNcp reopenedNcp = new FakeNcp();
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(reopenedNcp));
        channels.push(channelOver(formingNcp));   // pop order: forming, then reopened
        ZigbeeIntegrationAdapter adapter = bootProduction(channels, null);
        PairingWindow window = adapter.openPairingWindow(REQUEST);
        assertThat(adapter.isPermitJoinActive()).as("the window opened").isTrue();
        NetworkParameters formed = new PersistentNetworkParameterStore(tempDir, clock)
                .load().orElseThrow();
        reopenedNcp.onEzspCommand(command -> resumeHandler(command, formed));
        clock.advance(Duration.ofSeconds(10));

        assertThat(adapter.attemptReopen()).isTrue();

        assertThat(adapter.isPermitJoinActive())
                .as("the deadline is cleared inside the un-elapsed window — a reopen "
                        + "resets NCP-side policy/key/MAC-window state, and the "
                        + "adapter no longer claims a window the NCP does not hold")
                .isFalse();
        assertThat(adapter.currentPairingWindow()).isEmpty();
        List<EventEnvelope> closed = publisher.ofType(EventTypes.PERMIT_JOIN_CLOSED).toList();
        assertThat(closed).as("ONE permit_join_closed for the reopen").hasSize(1);
        assertThat(closed.get(0).payload()).isEqualTo(new PermitJoinClosed(integrationId,
                "zigbee", PermitJoinClosed.CAUSE_TRANSPORT_REOPENED, window.opensAt(),
                clock.instant()));
        List<byte[]> reopenedJoins = framesWithId(reopenedNcp, FRAME_PERMIT_JOINING);
        assertThat(reopenedJoins)
                .as("Reopen ≠ boot: the window is NOT renewed — the ONE 0x0022 on the reopened "
                        + "NCP is J2's close (permitJoin(0)), never an open")
                .hasSize(1);
        assertThat(reopenedJoins.get(0)[5]).isEqualTo((byte) 0);
        assertThat(windowFrameIds(reopenedNcp))
                .as("J2: the three-act close on the reopened NCP, in order; no enablement")
                .containsExactly(
                        EzspCoordinatorProtocol.FRAME_CLEAR_TRANSIENT_LINK_KEYS,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        FRAME_PERMIT_JOINING);
    }

    // ── T4: shutdown and superseding opens ───────────────────────────────────

    @Test
    @DisplayName("T4a: close() with an open window publishes ONE permit_join_closed(shutdown) "
            + "before the transport closes; the close proceeds")
    void close_withOpenWindow_publishesShutdown() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        PairingWindow window = adapter.openPairingWindow(REQUEST);
        clock.advance(Duration.ofSeconds(7));

        adapter.close();

        List<EventEnvelope> closed = publisher.ofType(EventTypes.PERMIT_JOIN_CLOSED).toList();
        assertThat(closed).as("ONE permit_join_closed(shutdown)").hasSize(1);
        assertThat(closed.get(0).payload()).isEqualTo(new PermitJoinClosed(integrationId,
                "zigbee", PermitJoinClosed.CAUSE_SHUTDOWN, window.opensAt(), clock.instant()));
        assertThat(adapter.isPermitJoinActive()).isFalse();
        assertThat(adapter.currentPairingWindow()).isEmpty();
        assertThat(publisher.published()).extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.PERMIT_JOIN_OPENED, EventTypes.PERMIT_JOIN_CLOSED);
    }

    @Test
    @DisplayName("T4b: a second open while a window is open closes the prior FIRST with ONE "
            + "permit_join_closed(superseded, closedAt = now), then opens the new one — two "
            + "0x0022 frames, the enablement order repeated per open")
    void supersedingOpen_closesPriorFirst() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        PairingWindow first = adapter.openPairingWindow(REQUEST);
        clock.advance(Duration.ofSeconds(30));

        PairingWindow renewed = adapter.openPairingWindow(SECOND_REQUEST);

        assertThat(publisher.published()).extracting(EventEnvelope::eventType)
                .as("closed(superseded) THEN opened, in that order")
                .containsExactly(EventTypes.PERMIT_JOIN_OPENED, EventTypes.PERMIT_JOIN_CLOSED,
                        EventTypes.PERMIT_JOIN_OPENED);
        assertThat(publisher.published().get(1).payload()).isEqualTo(new PermitJoinClosed(
                integrationId, "zigbee", PermitJoinClosed.CAUSE_SUPERSEDED, first.opensAt(),
                renewed.opensAt()));
        assertThat(publisher.published().get(2).payload()).isEqualTo(new PermitJoinOpened(
                integrationId, "zigbee", 60, SECOND_REQUEST.reason(), SECOND_REQUEST.actor(),
                renewed.opensAt(), renewed.opensAt().plusSeconds(60), null));
        assertThat(renewed.opensAt()).isEqualTo(first.opensAt().plusSeconds(30));
        List<byte[]> joins = framesWithId(ncp, FRAME_PERMIT_JOINING);
        assertThat(joins).as("open(120) → the prior's close permitJoin(0) → open(60)")
                .hasSize(3);
        assertThat(joins.get(0)[5]).isEqualTo((byte) WINDOW_SECONDS);
        assertThat(joins.get(1)[5]).as("J2: the superseded window's MAC close")
                .isEqualTo((byte) 0);
        assertThat(joins.get(2)[5]).isEqualTo((byte) 60);
        assertThat(windowFrameIds(ncp))
                .as("J2: the prior's three-act close COMPLETES before the new enablement — "
                        + "never between a policy write and a key import")
                .containsExactly(
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY,
                        FRAME_PERMIT_JOINING,
                        EzspCoordinatorProtocol.FRAME_CLEAR_TRANSIENT_LINK_KEYS,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        FRAME_PERMIT_JOINING,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY,
                        FRAME_PERMIT_JOINING);
        assertThat(enablementFrameIds(ncp))
                .as("the enablement surface: the close's policy revert + permitJoin(0) sit "
                        + "between the two enablements")
                .containsExactly(
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY,
                        FRAME_PERMIT_JOINING,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        FRAME_PERMIT_JOINING,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY,
                        FRAME_PERMIT_JOINING);
        assertThat(adapter.currentPairingWindow()).contains(renewed);
        assertThat(adapter.isPermitJoinActive()).isTrue();
        clock.advance(Duration.ofSeconds(60));
        assertThat(adapter.isPermitJoinActive())
                .as("the renewed window's own end governs").isFalse();
    }

    @Test
    @DisplayName("T4c: a second open after the clock passed the first window's end with NO "
            + "cycle run closes the prior as elapsed (closedAt = its closesAt), then opens")
    void supersedingOpen_afterElapsed_closesPriorAsElapsed() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        PairingWindow first = adapter.openPairingWindow(REQUEST);
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS + 5));
        assertThat(adapter.isPermitJoinActive()).as("elapsed, unobserved by a cycle").isFalse();

        PairingWindow renewed = adapter.openPairingWindow(SECOND_REQUEST);

        assertThat(publisher.published()).extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.PERMIT_JOIN_OPENED, EventTypes.PERMIT_JOIN_CLOSED,
                        EventTypes.PERMIT_JOIN_OPENED);
        assertThat(publisher.published().get(1).payload()).isEqualTo(new PermitJoinClosed(
                integrationId, "zigbee", PermitJoinClosed.CAUSE_ELAPSED, first.opensAt(),
                first.closesAt()));
        assertThat(adapter.currentPairingWindow()).contains(renewed);
        assertThat(adapter.isPermitJoinActive()).isTrue();

        adapter.runCycleOnce();

        assertThat(publisher.published())
                .as("the cycle sees the renewed window still open — nothing more")
                .hasSize(3);
    }

    @Test
    @DisplayName("T4a-J2: close() with an open window runs the three-act NCP close (0x006B, "
            + "policy 0x0002, permitJoin(0)) before the transport closes; the record closes once")
    void close_withOpenWindow_runsTheNcpCloseFirst() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(command -> formationHandler(ncp, command));
        ZigbeeIntegrationAdapter adapter = bootProduction(ncp, null);
        adapter.openPairingWindow(REQUEST);
        int framesAtOpen = ncp.receivedEzspCommands().size();

        adapter.close();

        assertThat(windowFrameIdsOf(ncp.receivedEzspCommands()
                .subList(framesAtOpen, ncp.receivedEzspCommands().size())))
                .containsExactly(
                        EzspCoordinatorProtocol.FRAME_CLEAR_TRANSIENT_LINK_KEYS,
                        EzspCoordinatorProtocol.FRAME_SET_POLICY,
                        FRAME_PERMIT_JOINING);
        assertThat(publisher.ofType(EventTypes.PERMIT_JOIN_CLOSED).count()).isEqualTo(1);
        assertThat(messages(Level.WARN, "zigbee.permit_join")).isEmpty();
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
        integrationId = new IntegrationId(UlidFactory.generate(clock));
        return new IntegrationContext(
                integrationId, "zigbee", publisher,
                new InMemoryEntityRegistry(), unusedQueryService(),
                unusedHealthReporter(), configAccess,
                null, null, null, null, null);
    }

    /** Boots a production adapter through the full §5.1 ladder to a formed network. */
    private ZigbeeIntegrationAdapter bootProduction(FakeNcp ncp, String serialPort)
            throws Exception {
        Deque<FakeSerialByteChannel> channels = new ArrayDeque<>();
        channels.push(channelOver(ncp));
        return bootProduction(channels, serialPort);
    }

    /** The multi-channel variant (the reopen leg pops a second channel). */
    private ZigbeeIntegrationAdapter bootProduction(
            Deque<FakeSerialByteChannel> channels, String serialPort) throws Exception {
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(serialPort)),
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
        if (frameId == EzspCoordinatorProtocol.FRAME_CLEAR_TRANSIENT_LINK_KEYS) {
            // J2: clearTransientLinkKeys (0x006B) answers with NO status byte (bellows
            // `(0x006B, (), ())`) — arrival is success. Unanswered, the close would
            // never return on the TestClock: every closer here needs this arm.
            return List.of(extendedResponse(seq, frameId, new byte[0]));
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

    /** The adapter's captured log lines at {@code level} starting with {@code prefix}. */
    private List<String> messages(Level level, String prefix) {
        return logCapture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
    }

    /** The enablement + join frame ids in send order (the ZigbeeTrustCenterJoinTest form). */
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

    /** J2: the window-shaped frame ids (enablement + close) in send order. */
    private static List<Integer> windowFrameIds(FakeNcp ncp) {
        return windowFrameIdsOf(ncp.receivedEzspCommands());
    }

    private static List<Integer> windowFrameIdsOf(List<byte[]> commands) {
        List<Integer> ids = new ArrayList<>();
        for (byte[] command : commands) {
            if (isLegacyVersion(command)) {
                continue;
            }
            int frameId = frameIdOf(command);
            if (frameId == EzspCoordinatorProtocol.FRAME_SET_POLICY
                    || frameId == EzspCoordinatorProtocol.FRAME_IMPORT_TRANSIENT_KEY
                    || frameId == EzspCoordinatorProtocol.FRAME_CLEAR_TRANSIENT_LINK_KEYS
                    || frameId == FRAME_PERMIT_JOINING) {
                ids.add(frameId);
            }
        }
        return ids;
    }

    /** The first non-legacy command with {@code frameId} in {@code commands}. */
    private static byte[] frameOf(List<byte[]> commands, int frameId) {
        for (byte[] command : commands) {
            if (!isLegacyVersion(command) && frameIdOf(command) == frameId) {
                return command;
            }
        }
        throw new AssertionError("no frame 0x" + Integer.toHexString(frameId));
    }

    /** The parameters of an extended command (seq, 0x00, 0x01, id lo, id hi, params…). */
    private static byte[] parametersOf(byte[] extendedCommand) {
        return Arrays.copyOfRange(extendedCommand, 5, extendedCommand.length);
    }

    private static Logger adapterLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);
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
                return Optional.empty();   // AMD-102: no join key exists to answer
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

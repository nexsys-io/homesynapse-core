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
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
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

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9.4-RPT — the reporting binding + adoption-path drive + posture routing:
 * adoption/re-link now DRIVES the M9.3 {@code ReportingConfigurator} through the
 * real EZSP {@code ReportingOps} binding (bind → Configure Reporting → read-back
 * verify → posture facts), and the returned facts route into the installed
 * per-device confirmation surface (the AMD-97 confirmability consumption).
 *
 * <p><strong>The north star:</strong> never-false-CONFIRMED is enforced at
 * INSTALL time — a capability whose confirmation rides authoritative reports
 * stays fully report-confirmable only where the covering cluster's posture is
 * read-back VERIFIED; a NONE-class posture (the attribute is absent) disables
 * confirmation with the reason recorded; every other non-verified posture
 * re-rates to best-effort, loudly. Reporting outcomes NEVER gate or fail the
 * adopt/re-link — a fully-degraded posture list is a recorded outcome
 * (T4/T6), and the {@code zigbee.reporting_configured} INFO fires on EVERY
 * drive completion so runbook step-11 pairs WARN-absence with positive
 * evidence, never absence-of-failure.
 *
 * <p>Every scenario drives the REAL chain over the scripted NCP (the
 * {@code ZigbeeConfigAcceptedAdoptionTest} idiom): Device_annce riding the nop
 * keepalive → ingestion drain → interview walk → proposal → acceptance gate →
 * the reporting drive. Wire payloads are byte-asserted against the production
 * BENCH-VERIFY constants; log assertions bind to the production message
 * formats verbatim.
 *
 * <p>ENERGY-READ T4: the same chain with the joiner re-scripted to the owned
 * Gen4's recorded signature — the two formatting reads precede every
 * configure, the change fields are byte-asserted at their full wire width
 * (the uint48 field is SIX bytes; the harness's device-side store is
 * long-typed so a mis-encoded field can never read back as correct), a
 * re-link re-applies from the cache with ZERO read frames, and the read
 * formatting scales the very next report.
 */
@DisplayName("ZigbeeIntegrationAdapter — reporting drive + posture routing (M9.4-RPT)")
class ZigbeeReportingDriveTest {

    private static final int FRAME_START_SCAN = 0x001A;
    private static final int FRAME_FORM_NETWORK = 0x001E;
    private static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;
    private static final int FRAME_NETWORK_INIT = 0x0017;
    private static final int FRAME_PERMIT_JOINING = 0x0022;
    private static final int FRAME_SEND_UNICAST = 0x0034;
    private static final int FRAME_NOP = 0x0005;

    /** The scripted joiner — an UNMATCHED dimmable light (no bundled profile). */
    private static final long LIGHT_IEEE = 0x00178801AABBCCDDL;
    private static final int LIGHT_NWK = 0x4E21;
    private static final int LIGHT_ENDPOINT = 1;
    private static final String LIGHT_LISTED = "0x00178801aabbccdd";
    private static final String LIGHT_HEX = "0x00178801AABBCCDD";

    /** OnOff / LevelControl — the two §3.7 DEFAULTS rows the light exposes. */
    private static final int CLUSTER_ON_OFF = 0x0006;
    private static final int CLUSTER_LEVEL = 0x0008;

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private ListAppender<ILoggingEvent> adapterLogCapture;
    private ListAppender<ILoggingEvent> configuratorLogCapture;
    private ListAppender<ILoggingEvent> installerLogCapture;

    /** Callback frames delivered on the NEXT nop keepalive (the rig pump idiom). */
    private final Deque<byte[]> riders = new ArrayDeque<>();

    // ── per-scenario reporting-exchange scripting ────────────────────────────
    /** ZDO Bind_req outcomes by BOUND cluster; absent = success. */
    private final Map<Integer, Integer> bindStatusByCluster = new HashMap<>();
    /** Clusters whose Bind_rsp never arrives (the await times out). */
    private final Set<Integer> bindSilent = new HashSet<>();
    /** Configure Reporting Response status by cluster; absent = SUCCESS. */
    private final Map<Integer, Integer> configureStatusByCluster = new HashMap<>();
    /** Clusters whose Configure Reporting Response never arrives (sleepy). */
    private final Set<Integer> configureSilent = new HashSet<>();
    /** Clusters whose read-back reports {@code maxInterval 0xFFFF} (ACK-lies). */
    private final Set<Integer> readbackReportingOff = new HashSet<>();
    /** The device-side reporting store: (cluster,attr) → {type,min,max,change}. */
    private final Map<Long, long[]> reportingStore = new HashMap<>();

    // ── ENERGY-READ: the scripted joiner's signature + its formatting answers ─
    /** The joiner's device type — the dimmable light unless a test re-scripts it. */
    private int scriptedDeviceType = 0x0101;
    /** The joiner's input clusters — the light's unless a test re-scripts them. */
    private int[] scriptedInClusters = {0x0000, 0x0003, 0x0006, 0x0008};
    /**
     * Formatting answers by cluster: attribute id → value. An attribute absent
     * from a scripted cluster answers 0x86 UNSUPPORTED_ATTRIBUTE; an unscripted
     * cluster never answers (the read times out).
     */
    private final Map<Integer, Map<Integer, Long>> formattingByCluster =
            new HashMap<>();

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        adapterLogCapture = new ListAppender<>();
        adapterLogCapture.start();
        adapterLogger().addAppender(adapterLogCapture);
        configuratorLogCapture = new ListAppender<>();
        configuratorLogCapture.start();
        configuratorLogger().addAppender(configuratorLogCapture);
        installerLogCapture = new ListAppender<>();
        installerLogCapture.start();
        installerLogger().addAppender(installerLogCapture);
    }

    @AfterEach
    void tearDown() {
        adapterLogger().detachAppender(adapterLogCapture);
        configuratorLogger().detachAppender(configuratorLogCapture);
        installerLogger().detachAppender(installerLogCapture);
    }

    // ── T1 (scenario 1): the fresh-adopt drive, healthy — byte-asserted ─────

    @Test
    @DisplayName("T1: a fresh adoption drives bind + Configure Reporting + read-back "
            + "per the §3.7 spec — payloads byte-asserted, VERIFIED facts counted, "
            + "the reporting_configured INFO verbatim, policies untouched")
    void freshAdoption_drivesBindConfigureReadback_verifiedFactsRecorded()
            throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("the drive rides a successful adoption").isEqualTo(1);

        // The ZDO Bind_req per configured cluster (0x0006, 0x0008 — the two
        // DEFAULTS rows), byte-asserted after the tsn: src IEEE LE + src
        // endpoint + cluster LE + dstAddrMode 0x03 + coordinator EUI64 LE +
        // coordinator endpoint 1.
        List<byte[]> binds = zdoMessages(ncp, EzspReportingOps.ZDO_CLUSTER_BIND_REQ);
        assertThat(binds).as("one bind per §3.7 cluster row").hasSize(2);
        assertThat(tail(binds.get(0)))
                .containsExactly(bindRequestTail(CLUSTER_ON_OFF));
        assertThat(tail(binds.get(1)))
                .containsExactly(bindRequestTail(CLUSTER_LEVEL));

        // Configure Reporting (ZCL global 0x06): the §3.7 rows verbatim —
        // OnOff attr 0x0000 bool(0x10) 0/3600, discrete (no change field);
        // Level attr 0x0000 uint8(0x20) 5/3600 change 1 (one analog byte).
        List<byte[]> onOffConfigure = zclGlobalMessages(ncp, CLUSTER_ON_OFF,
                EzspReportingOps.COMMAND_CONFIGURE_REPORTING);
        assertThat(onOffConfigure).hasSize(1);
        assertThat(zclPayload(onOffConfigure.get(0))).containsExactly(
                0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x10, 0x0E);
        List<byte[]> levelConfigure = zclGlobalMessages(ncp, CLUSTER_LEVEL,
                EzspReportingOps.COMMAND_CONFIGURE_REPORTING);
        assertThat(levelConfigure).hasSize(1);
        assertThat(zclPayload(levelConfigure.get(0))).containsExactly(
                0x00, 0x00, 0x00, 0x20, 0x05, 0x00, 0x10, 0x0E, 0x01);

        // The read-back verify (ZCL global 0x08) per cluster.
        assertThat(zclGlobalMessages(ncp, CLUSTER_ON_OFF,
                EzspReportingOps.COMMAND_READ_REPORTING_CONFIGURATION)).hasSize(1);
        List<byte[]> levelReadback = zclGlobalMessages(ncp, CLUSTER_LEVEL,
                EzspReportingOps.COMMAND_READ_REPORTING_CONFIGURATION);
        assertThat(levelReadback).hasSize(1);
        assertThat(zclPayload(levelReadback.get(0)))
                .containsExactly(0x00, 0x00, 0x00);

        // The anti-vacuous INFO, verbatim — measurement, not absence-of-failure.
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .containsExactly("zigbee.reporting_configured: device="
                        + LIGHT_HEX + " clusters=2 verified=2 degraded=0");

        // VERIFIED postures leave the installed confirmation untouched (§3).
        CapabilityInstance onOff = capability(adoptedEntity(), "on_off");
        assertThat(onOff.confirmation().mode())
                .isEqualTo(ConfirmationMode.EXACT_MATCH);
        assertThat(onOff.confirmation().authoritativeAttributes())
                .containsExactly("on");
        assertThat(installerMessages(Level.WARN, "zigbee.confirmation_downgraded"))
                .as("verified postures never downgrade").isEmpty();
        assertThat(configuratorMessages(Level.WARN, "zigbee.")).isEmpty();
    }

    // ── T2 (scenario 2): the re-link drive — onRejoin, no re-adoption ───────

    @Test
    @DisplayName("T2: an already-adopted device's re-announce re-links and re-runs "
            + "the SAME configuration through onRejoin — no adopt, no proposal, "
            + "no double-publish (the iteration-4 Hue shape)")
    void relink_reappliesReportingConfiguration_withoutReadoption() throws Exception {
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);   // first adoption + drive
        announce(adapter, LIGHT_IEEE, LIGHT_NWK);   // power-cycle: re-link + drive

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("the re-link never re-adopts").isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.DEVICE_DISCOVERED).count())
                .as("the re-link mints no proposal").isEqualTo(1);
        assertThat(configuratorMessages(Level.INFO, "zigbee.reporting_reapply"))
                .as("the re-link arm runs onRejoin, not configureDevice")
                .hasSize(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .as("one drive per arc: adoption, then rejoin").hasSize(2);
        assertThat(zdoMessages(ncp, EzspReportingOps.ZDO_CLUSTER_BIND_REQ))
                .as("both drives bind both cluster rows").hasSize(4);
        assertThat(adapterMessages(Level.WARN, "zigbee.interview_failed"))
                .as("the drive never threw into the cycle handler").isEmpty();
    }

    // ── T3 (scenario 3): ACK-lies — read-back shows reporting OFF ───────────

    @Test
    @DisplayName("T3: configure SUCCESS but read-back shows reporting OFF — the "
            + "existing reporting_ack_lies WARN fires, no VERIFIED fact is minted "
            + "for the lying cluster, the capability re-rates best-effort")
    void ackLies_readbackReportingOff_downgradesHonestly() throws Exception {
        readbackReportingOff.add(CLUSTER_LEVEL);
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        assertThat(configuratorMessages(Level.WARN, "zigbee.reporting_ack_lies"))
                .as("the M9.3 ACK-lies WARN reaches the log from the real binding")
                .hasSize(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .as("no VERIFIED fact minted for the lying cluster")
                .containsExactly("zigbee.reporting_configured: device="
                        + LIGHT_HEX + " clusters=2 verified=1 degraded=1");
        assertThat(installerMessages(Level.WARN, "zigbee.confirmation_downgraded"))
                .containsExactly("zigbee.confirmation_downgraded: device="
                        + LIGHT_HEX + " capability=brightness cluster=0x8 "
                        + "posture=READBACK_ONLY/NONE outcome=best_effort");
        // Best-effort keeps the tracking machinery (CONFIRMED only ever renders
        // on genuine report evidence — Register §51); the re-rating is loud.
        assertThat(capability(adoptedEntity(), "brightness").confirmation().mode())
                .isEqualTo(ConfirmationMode.TOLERANCE);
        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count()).isEqualTo(1);
    }

    // ── T4 (scenario 4): the degrade ladder never gates the adoption ────────

    @Test
    @DisplayName("T4: 0x86 UNSUPPORTED and a sleepy configure timeout produce their "
            + "posture facts, adoption is UNAFFECTED, and the success INFO still "
            + "fires with degraded= counting them")
    void degradeLadder_unsupportedAndTimeout_neverGateAdoption() throws Exception {
        configureStatusByCluster.put(CLUSTER_ON_OFF,
                EzspReportingOps.ZCL_STATUS_UNSUPPORTED_ATTRIBUTE);
        configureSilent.add(CLUSTER_LEVEL);
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("reporting outcomes never gate the adopt").isEqualTo(1);
        assertThat(entityRegistry.listEntitiesByDevice(adoptedDeviceId()))
                .as("the classified entity stands").hasSize(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .as("the INFO fires on EVERY drive completion, all-degraded included")
                .containsExactly("zigbee.reporting_configured: device="
                        + LIGHT_HEX + " clusters=2 verified=0 degraded=2");
        assertThat(installerMessages(Level.WARN, "zigbee.confirmation_downgraded"))
                .containsExactly(
                        "zigbee.confirmation_downgraded: device=" + LIGHT_HEX
                                + " capability=on_off cluster=0x6 "
                                + "posture=NONE/NONE outcome=disabled",
                        "zigbee.confirmation_downgraded: device=" + LIGHT_HEX
                                + " capability=brightness cluster=0x8 "
                                + "posture=VERIFIED_REPORTS/SLEEPY "
                                + "outcome=best_effort");
        assertThat(adapterMessages(Level.WARN, "zigbee.interview_failed")).isEmpty();
    }

    // ── T4b (scenario 4): the 0x8C UNREPORTABLE rung ─────────────────────────

    @Test
    @DisplayName("T4b: 0x8C UNREPORTABLE_ATTRIBUTE records the READBACK_ONLY/NONE "
            + "posture — the capability re-rates best-effort (tracking kept), "
            + "adoption unaffected, the INFO counts it degraded")
    void degradeLadder_unreportableAttribute_recordsReadbackOnly() throws Exception {
        configureStatusByCluster.put(CLUSTER_ON_OFF,
                EzspReportingOps.ZCL_STATUS_UNREPORTABLE_ATTRIBUTE);
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count()).isEqualTo(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .containsExactly("zigbee.reporting_configured: device="
                        + LIGHT_HEX + " clusters=2 verified=1 degraded=1");
        assertThat(installerMessages(Level.WARN, "zigbee.confirmation_downgraded"))
                .containsExactly("zigbee.confirmation_downgraded: device="
                        + LIGHT_HEX + " capability=on_off cluster=0x6 "
                        + "posture=READBACK_ONLY/NONE outcome=best_effort");
        // Best-effort keeps the tracking machinery — an attribute that exists
        // but never reports renders honest UNCONFIRMED on timeout, never a
        // false CONFIRMED (Register §51).
        assertThat(capability(adoptedEntity(), "on_off").confirmation().mode())
                .isEqualTo(ConfirmationMode.EXACT_MATCH);
    }

    // ── T5 (scenario 5): the install-time never-false-CONFIRMED fence ───────

    @Test
    @DisplayName("T5 (§3): a NONE-class posture on a report-confirmable capability "
            + "DISABLES its confirmation with the reason recorded (ONE WARN naming "
            + "device + cluster + posture); a VERIFIED posture leaves its sibling "
            + "untouched")
    void postureRouting_nonePosture_disablesConfirmation_verifiedUntouched()
            throws Exception {
        configureStatusByCluster.put(CLUSTER_ON_OFF,
                EzspReportingOps.ZCL_STATUS_UNSUPPORTED_ATTRIBUTE);
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        Entity entity = adoptedEntity();
        CapabilityInstance onOff = capability(entity, "on_off");
        assertThat(onOff.confirmation().mode())
                .as("never-false-CONFIRMED at install: the attribute is absent — "
                        + "confirmation tracking is disabled, never optimistic")
                .isEqualTo(ConfirmationMode.DISABLED);
        assertThat(onOff.confirmation().authoritativeAttributes()).isEmpty();
        assertThat(onOff.confirmation().defaultTimeoutMs())
                .as("the capability default timeout is kept, mirroring the "
                        + "installer's UNCONFIRMABLE shape").isEqualTo(5000L);

        CapabilityInstance brightness = capability(entity, "brightness");
        assertThat(brightness.confirmation().mode())
                .as("the VERIFIED sibling stays report-confirmable")
                .isEqualTo(ConfirmationMode.TOLERANCE);
        assertThat(brightness.confirmation().authoritativeAttributes())
                .containsExactly("brightness");

        assertThat(installerMessages(Level.WARN, "zigbee.confirmation_downgraded"))
                .as("exactly ONE WARN, naming device + cluster + posture")
                .containsExactly("zigbee.confirmation_downgraded: device="
                        + LIGHT_HEX + " capability=on_off cluster=0x6 "
                        + "posture=NONE/NONE outcome=disabled");

        // The durable half of DP-4 (AMD-99 F1 / REG-INV-1 write-ahead): the
        // downgrade rides an entity_registered RE-EMIT — adoption emitted one,
        // the routing re-emit makes two — and the LAST payload reconstructs
        // EXACTLY the routed registry entity, so the measured posture survives
        // the Phase-3 replay after a process restart. An apply-without-publish
        // regression keeps every registry-read assertion above green; it must
        // fail HERE.
        List<EventEnvelope> registrations =
                publisher.ofType(EventTypes.ENTITY_REGISTERED).toList();
        assertThat(registrations)
                .as("adoption emit + the posture-routing re-emit")
                .hasSize(2);
        assertThat(RegistryEventMapper.toEntity(
                (EntityRegisteredEvent) registrations.get(1).payload()))
                .as("the re-emit payload alone rebuilds the routed entity")
                .isEqualTo(entity);
    }

    // ── T6 (scenario 6): every ops call failing — the loop survives ─────────

    @Test
    @DisplayName("T6: every reporting exchange failing (silent NCP) still adopts, "
            + "entities intact, the INFO honest at verified=0, and the ingestion "
            + "loop survives to serve the next announce")
    void reportingFailures_neverGateAdoption_loopSurvives() throws Exception {
        bindSilent.add(CLUSTER_ON_OFF);
        bindSilent.add(CLUSTER_LEVEL);
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count()).isEqualTo(1);
        assertThat(entityRegistry.listEntitiesByDevice(adoptedDeviceId())).hasSize(1);
        assertThat(configuratorMessages(Level.WARN, "zigbee.bind_failed"))
                .as("the M9.3 bind_failed WARN fires per failed cluster").hasSize(2);
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .containsExactly("zigbee.reporting_configured: device="
                        + LIGHT_HEX + " clusters=2 verified=0 degraded=2");
        assertThat(adapterMessages(Level.WARN, "zigbee.interview_failed")).isEmpty();

        // The loop survives: the next announce re-links and re-drives.
        announce(adapter, LIGHT_IEEE, LIGHT_NWK);
        assertThat(configuratorMessages(Level.INFO, "zigbee.reporting_reapply"))
                .hasSize(1);
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .hasSize(2);
    }

    // ── ENERGY-READ T4: the formatting read rides the adoption drive ────────

    private static final int CLUSTER_METERING = 0x0702;
    private static final int CLUSTER_ELECTRICAL = 0x0B04;

    /**
     * Re-scripts the joiner with the owned Gen4's RECORDED signature
     * (PLUG-DOSSIER row 16 / DEVICE-SET §1: EP1, device type 0x010A, the eight
     * input clusters). The formatting values a test scripts beside it are
     * FIXTURE values — the owned unit's divisors are UNREAD (the measurement
     * record's G4-1/G4-2 rows), and nothing here predicts them.
     */
    private void scriptGen4Signature() {
        scriptedDeviceType = 0x010A;
        scriptedInClusters = new int[] {0x0000, 0x0003, 0x0004, 0x0005, 0x0006,
            CLUSTER_METERING, CLUSTER_ELECTRICAL, 0xFC21};
    }

    /** The fixture formatting: ACPower 1/100 (+V 1/10, I 1/1000); summation 1/1e6 kWh. */
    private void scriptFixtureFormatting() {
        Map<Integer, Long> electrical = new HashMap<>();
        electrical.put(0x0604, 1L);
        electrical.put(0x0605, 100L);
        electrical.put(0x0600, 1L);
        electrical.put(0x0601, 10L);
        electrical.put(0x0602, 1L);
        electrical.put(0x0603, 1000L);
        formattingByCluster.put(CLUSTER_ELECTRICAL, electrical);
        Map<Integer, Long> metering = new HashMap<>();
        metering.put(0x0300, 0x00L);
        metering.put(0x0301, 1L);
        metering.put(0x0302, 1_000_000L);
        metering.put(0x0303, 0x00L);
        formattingByCluster.put(CLUSTER_METERING, metering);
    }

    @Test
    @DisplayName("T4 (P4): a fresh adoption READS each metering cluster's "
            + "formatting — ONE ReadAttributes per cluster — BEFORE any Configure "
            + "Reporting on it, and the configure's change field is the "
            + "engineering threshold scaled by the read: 100 (1 W ÷100) and 5,000 "
            + "(5 Wh at 1e6/kWh), little-endian at the field's full width")
    void freshAdoption_readsFormattingBeforeConfigure() throws Exception {
        scriptGen4Signature();
        scriptFixtureFormatting();
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count()).isEqualTo(1);
        Entity entity = adoptedEntity();
        assertThat(entity.entityType())
                .isEqualTo(EntityType.SWITCH);
        assertThat(entity.capabilities()).extracting(CapabilityInstance::capabilityId)
                .containsExactlyInAnyOrder("on_off", "power_meter",
                        "energy_meter", "identify");

        // ONE read frame per metering cluster, the attribute ids LE in the
        // production order (the power pair first).
        List<byte[]> electricalReads = zclGlobalMessages(ncp, CLUSTER_ELECTRICAL,
                ZclCodec.COMMAND_READ_ATTRIBUTES);
        assertThat(electricalReads).hasSize(1);
        assertThat(zclPayload(electricalReads.get(0))).containsExactly(
                0x04, 0x06, 0x05, 0x06, 0x00, 0x06, 0x01, 0x06, 0x02, 0x06,
                0x03, 0x06);
        List<byte[]> meteringReads = zclGlobalMessages(ncp, CLUSTER_METERING,
                ZclCodec.COMMAND_READ_ATTRIBUTES);
        assertThat(meteringReads).hasSize(1);
        assertThat(zclPayload(meteringReads.get(0))).containsExactly(
                0x00, 0x03, 0x01, 0x03, 0x02, 0x03, 0x03, 0x03);

        // Frame ORDER: both reads precede every configure (and the device's
        // other clusters configure as ever, in descriptor order).
        assertThat(globalSendOrder(ncp)).containsExactly(
                "b04:0", "702:0",
                "6:6", "6:8", "702:6", "702:8", "b04:6", "b04:8");

        // ActivePower 0x050B int16(0x29) 5/600 s, change 100 = 64 00.
        List<byte[]> electricalConfigure = zclGlobalMessages(ncp,
                CLUSTER_ELECTRICAL, EzspReportingOps.COMMAND_CONFIGURE_REPORTING);
        assertThat(electricalConfigure).hasSize(1);
        assertThat(zclPayload(electricalConfigure.get(0))).containsExactly(
                0x00, 0x0B, 0x05, 0x29, 0x05, 0x00, 0x58, 0x02, 0x64, 0x00);
        // CurrentSummationDelivered 0x0000 uint48(0x25) 5/3600 s, change 5,000
        // = 88 13 00 00 00 00 — SIX bytes, the upper four ZERO.
        List<byte[]> meteringConfigure = zclGlobalMessages(ncp, CLUSTER_METERING,
                EzspReportingOps.COMMAND_CONFIGURE_REPORTING);
        assertThat(meteringConfigure).hasSize(1);
        assertThat(zclPayload(meteringConfigure.get(0))).containsExactly(
                0x00, 0x00, 0x00, 0x25, 0x05, 0x00, 0x10, 0x0E,
                0x88, 0x13, 0x00, 0x00, 0x00, 0x00);

        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .as("a healthy meter counts VERIFIED — never a false degraded")
                .containsExactly("zigbee.reporting_configured: device="
                        + LIGHT_HEX + " clusters=3 verified=3 degraded=0");
        assertThat(configuratorMessages(Level.INFO,
                "zigbee.metering_formatting_read")).containsExactly(
                "zigbee.metering_formatting_read: device=" + LIGHT_HEX
                        + " endpoint=1 cluster=0xb04 source=device mult=1 "
                        + "div=100 voltage=1/10 current=1/1000",
                "zigbee.metering_formatting_read: device=" + LIGHT_HEX
                        + " endpoint=1 cluster=0x702 source=device mult=1 "
                        + "div=1000000 unit=0x0");
        assertThat(configuratorMessages(Level.WARN, "zigbee.")).isEmpty();
        assertThat(adapterMessages(Level.INFO,
                "zigbee.learned_metering_formatting_rehydrated"))
                .as("the boot glance-point: nothing persisted before the first read")
                .containsExactly(
                        "zigbee.learned_metering_formatting_rehydrated: count=0");
    }

    @Test
    @DisplayName("T4 (P7): a re-link does NOT re-read — ZERO read frames on the "
            + "rejoin arm; onRejoin re-applies the SAME scaled configure from the "
            + "cached formatting")
    void relink_usesCachedFormatting_noRead() throws Exception {
        scriptGen4Signature();
        scriptFixtureFormatting();
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);   // adoption: reads + configures
        announce(adapter, LIGHT_IEEE, LIGHT_NWK);   // power-cycle: re-link

        assertThat(configuratorMessages(Level.INFO, "zigbee.reporting_reapply"))
                .hasSize(1);
        assertThat(zclGlobalMessages(ncp, CLUSTER_ELECTRICAL,
                ZclCodec.COMMAND_READ_ATTRIBUTES))
                .as("the adoption's one read — the rejoin adds none").hasSize(1);
        assertThat(zclGlobalMessages(ncp, CLUSTER_METERING,
                ZclCodec.COMMAND_READ_ATTRIBUTES)).hasSize(1);
        List<byte[]> electricalConfigure = zclGlobalMessages(ncp,
                CLUSTER_ELECTRICAL, EzspReportingOps.COMMAND_CONFIGURE_REPORTING);
        assertThat(electricalConfigure).as("both drives configure").hasSize(2);
        assertThat(zclPayload(electricalConfigure.get(1)))
                .containsExactly(zclPayload(electricalConfigure.get(0)));
        List<byte[]> meteringConfigure = zclGlobalMessages(ncp, CLUSTER_METERING,
                EzspReportingOps.COMMAND_CONFIGURE_REPORTING);
        assertThat(meteringConfigure).hasSize(2);
        assertThat(zclPayload(meteringConfigure.get(1))).containsExactly(
                0x00, 0x00, 0x00, 0x25, 0x05, 0x00, 0x10, 0x0E,
                0x88, 0x13, 0x00, 0x00, 0x00, 0x00);
        assertThat(configuratorMessages(Level.INFO,
                "zigbee.metering_formatting_read"))
                .as("the rejoin names its source: the cache")
                .hasSize(4)
                .last().asString().contains("cluster=0x702 source=cache");
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .containsExactly(
                        "zigbee.reporting_configured: device=" + LIGHT_HEX
                                + " clusters=3 verified=3 degraded=0",
                        "zigbee.reporting_configured: device=" + LIGHT_HEX
                                + " clusters=3 verified=3 degraded=0");
    }

    @Test
    @DisplayName("T4 (R3→R4, the vertical): the formatting the adoption drive read "
            + "is in the handler table for the very next frame — an ActivePower "
            + "report publishes state_reported power_w scaled, raw beside it")
    void freshAdoption_thenActivePowerReport_publishesScaledPowerW()
            throws Exception {
        scriptGen4Signature();
        scriptFixtureFormatting();
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));
        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        riders.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                CLUSTER_ELECTRICAL, LIGHT_ENDPOINT, new byte[] {
                    0x18, 0x51, 0x0A, 0x0B, 0x05, 0x29, 0x40, 0x1F}));   // 8000 raw
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();

        List<StateReportedEvent> reported =
                publisher.ofType(EventTypes.STATE_REPORTED)
                        .map(envelope ->
                                (StateReportedEvent) envelope.payload())
                        .toList();
        assertThat(reported).hasSize(1);
        assertThat(reported.get(0).attributeKey()).isEqualTo("power_w");
        assertThat(reported.get(0).value()).isEqualTo("80.0");
        assertThat(reported.get(0).rawProtocolValue()).isEqualTo("8000");
        assertThat(reported.get(0).rawProtocolUnit()).isEqualTo("mult=1 div=100");
    }

    @Test
    @DisplayName("T4/T7 (P5, the vertical): formatting the device never answers "
            + "configures NOTHING on the metering clusters (no bind, no configure "
            + "— no raw-threshold flood) and emits NOTHING; adoption is unaffected "
            + "and the INFO counts them degraded")
    void unreadableFormatting_configuresNothing_emitsNothing() throws Exception {
        scriptGen4Signature();                       // no formatting scripted
        FakeNcp ncp = new FakeNcp();
        ncp.onEzspCommand(this::reportingHandler);
        ZigbeeIntegrationAdapter adapter =
                bootProduction(ncp, List.<Object>of(LIGHT_LISTED));

        announce(adapter, LIGHT_IEEE, LIGHT_NWK);

        assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                .as("reporting outcomes never gate the adopt").isEqualTo(1);
        assertThat(globalSendOrder(ncp)).containsExactly(
                "b04:0", "702:0", "6:6", "6:8");
        assertThat(zdoMessages(ncp, EzspReportingOps.ZDO_CLUSTER_BIND_REQ))
                .as("only the OnOff row binds").hasSize(1);
        assertThat(configuratorMessages(Level.WARN,
                "zigbee.metering_formatting_unreadable")).hasSize(2);
        assertThat(adapterMessages(Level.INFO, "zigbee.reporting_configured"))
                .containsExactly("zigbee.reporting_configured: device="
                        + LIGHT_HEX + " clusters=3 verified=1 degraded=2");

        riders.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                CLUSTER_ELECTRICAL, LIGHT_ENDPOINT, new byte[] {
                    0x18, 0x51, 0x0A, 0x0B, 0x05, 0x29, 0x40, 0x1F}));
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();

        assertThat(publisher.ofType(EventTypes.STATE_REPORTED).count())
                .as("an unknown scale emits nothing").isZero();
    }

    /**
     * Every ZCL GLOBAL unicast the NCP received outside the Basic cluster, in
     * send order, as {@code cluster:command} hex — the drive's frame order
     * (the interview's Basic read and any availability ping are 0x0000 and
     * stay out of scope).
     */
    private static List<String> globalSendOrder(FakeNcp ncp) {
        List<String> order = new ArrayList<>();
        for (byte[] command : ncp.receivedEzspCommands()) {
            if (command.length < 5 || isLegacyVersion(command)
                    || frameIdOf(command) != FRAME_SEND_UNICAST) {
                continue;
            }
            byte[] parameters = extendedParameters(command);
            int profile = (parameters[3] & 0xFF) | ((parameters[4] & 0xFF) << 8);
            int cluster = (parameters[5] & 0xFF) | ((parameters[6] & 0xFF) << 8);
            if (profile != EzspCoordinatorProtocol.HA_PROFILE_ID
                    || cluster == 0x0000) {
                continue;
            }
            int messageOffset = 16;
            if ((parameters[messageOffset] & 0x03) != 0x00) {
                continue;
            }
            order.add(Integer.toHexString(cluster) + ":"
                    + Integer.toHexString(parameters[messageOffset + 2] & 0xFF));
        }
        return order;
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

    private IntegrationContext context(ConfigurationAccess configAccess) {
        return new IntegrationContext(
                new IntegrationId(UlidFactory.generate(clock)), "zigbee", publisher,
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
     * nop keepalive's response, the drain schedules the interview, and the SAME
     * cycle pass runs interview → proposal → acceptance gate → reporting drive.
     */
    private void announce(ZigbeeIntegrationAdapter adapter, long ieee, int nwk) {
        riders.add(deviceAnnounceCallback(ieee, nwk));
        adapter.coordinatorProtocol().ping();
        adapter.runCycleOnce();
    }

    private Device adoptedDevice() {
        Optional<Device> device = deviceRegistry.findByHardwareIdentifier(
                ZigbeeAdoptionSlice.HARDWARE_NAMESPACE,
                new IEEEAddress(LIGHT_IEEE).toHexString());
        assertThat(device).as("the light adopted").isPresent();
        return device.get();
    }

    private DeviceId adoptedDeviceId() {
        return adoptedDevice().deviceId();
    }

    private Entity adoptedEntity() {
        List<Entity> entities = entityRegistry.listEntitiesByDevice(adoptedDeviceId());
        assertThat(entities).hasSize(1);
        return entities.get(0);
    }

    private static CapabilityInstance capability(Entity entity, String capabilityId) {
        Optional<CapabilityInstance> instance = entity.capabilities().stream()
                .filter(c -> c.capabilityId().equals(capabilityId))
                .findFirst();
        assertThat(instance).as("capability %s classified", capabilityId).isPresent();
        return instance.get();
    }

    // ── scripted NCP (v13 dialect; interview + reporting-exchange capable) ──

    /**
     * Formation + interview + reporting-exchange handler. Pending {@link #riders}
     * ride the nop keepalive's response (riders FIRST, response LAST — the
     * established pump idiom); the reporting exchanges respond per the
     * per-scenario mode flags, with read-backs echoing the device-side store the
     * Configure Reporting writes populated (the healthy path is VERIFIED by
     * measurement, not by default).
     */
    private List<byte[]> reportingHandler(byte[] command) {
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
                (byte) (LIGHT_NWK & 0xFF), (byte) ((LIGHT_NWK >> 8) & 0xFF)}));
        }
        return switch (frameId) {
            case FRAME_NETWORK_INIT, FRAME_FORM_NETWORK, FRAME_PERMIT_JOINING,
                    FRAME_SET_INITIAL_SECURITY_STATE ->
                    List.of(extendedResponse(seq, frameId, new byte[] {0x00}));
            case 0x0018 -> List.of(extendedResponse(seq, 0x0018, new byte[] {0x02}));
            // Unscripted frames (getEui64) fall through to the FakeNcp built-in.
            default -> List.of();
        };
    }

    /** The light's interview walk + the M9.4-RPT reporting exchanges. */
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
            if (cluster == EzspReportingOps.ZDO_CLUSTER_BIND_REQ) {
                int boundCluster = (message[10] & 0xFF) | ((message[11] & 0xFF) << 8);
                if (bindSilent.contains(boundCluster)) {
                    return frames;   // no Bind_rsp: the await times out
                }
                int status = bindStatusByCluster.getOrDefault(boundCluster, 0x00);
                frames.add(incomingMessage(EzspCoordinatorProtocol.ZDO_PROFILE_ID,
                        EzspReportingOps.ZDO_CLUSTER_BIND_RSP, 0,
                        new byte[] {(byte) tsn, (byte) status}));
                return frames;
            }
            byte[] reply = zdoReply(cluster, tsn);
            if (reply != null) {
                frames.add(incomingMessage(EzspCoordinatorProtocol.ZDO_PROFILE_ID,
                        cluster | 0x8000, 0, reply));
            }
            return frames;
        }
        if ((message[0] & 0x03) != 0x00) {
            return frames;   // cluster-specific ZCL: none expected in these tests
        }
        int tsn = message[1] & 0xFF;
        int commandId = message[2] & 0xFF;
        if (cluster == 0x0000 && commandId == 0x00) {   // interview Basic read
            frames.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID,
                    0x0000, LIGHT_ENDPOINT, basicReply(tsn)));
            return frames;
        }
        if (commandId == ZclCodec.COMMAND_READ_ATTRIBUTES) {
            respondToFormattingRead(frames, cluster, tsn, message);
            return frames;
        }
        if (commandId == EzspReportingOps.COMMAND_CONFIGURE_REPORTING) {
            respondToConfigure(frames, cluster, tsn, message);
            return frames;
        }
        if (commandId == EzspReportingOps.COMMAND_READ_REPORTING_CONFIGURATION) {
            respondToReadback(frames, cluster, tsn, message);
            return frames;
        }
        return frames;
    }

    /**
     * Answers a metering cluster's formatting read (ENERGY-READ R3) from the
     * per-scenario script: a Read Attributes Response carrying one record per
     * requested id — SUCCESS + the ZCL-typed value, or 0x86 when the scripted
     * cluster lacks the attribute. An unscripted cluster never answers.
     */
    private void respondToFormattingRead(List<byte[]> frames, int cluster,
            int tsn, byte[] message) {
        Map<Integer, Long> scripted = formattingByCluster.get(cluster);
        if (scripted == null) {
            return;   // the read times out: formatting unreadable
        }
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(0x18);                           // global, server-to-client
        response.write(tsn);
        response.write(ZclCodec.COMMAND_READ_ATTRIBUTES_RESPONSE);
        for (int i = 3; i + 1 < message.length; i += 2) {
            int attribute = (message[i] & 0xFF) | ((message[i + 1] & 0xFF) << 8);
            response.write(message[i]);
            response.write(message[i + 1]);
            Long value = scripted.get(attribute);
            if (value == null) {
                response.write(EzspReportingOps.ZCL_STATUS_UNSUPPORTED_ATTRIBUTE);
                continue;
            }
            response.write(0x00);                       // record status SUCCESS
            int dataType = formattingDataType(cluster, attribute);
            response.write(dataType);
            int width = switch (dataType) {
                case 0x18, 0x30 -> 1;                   // map8, enum8
                case 0x21 -> 2;                         // uint16
                default -> 3;                           // uint24
            };
            for (int b = 0; b < width; b++) {
                response.write((int) ((value >> (8 * b)) & 0xFF));
            }
        }
        frames.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID, cluster,
                LIGHT_ENDPOINT, response.toByteArray()));
    }

    /** The ZCL wire type of a formatting attribute (the spec's, per cluster). */
    private static int formattingDataType(int cluster, int attribute) {
        if (cluster == 0x0B04) {
            return 0x21;                                // the six AC pairs: uint16
        }
        return switch (attribute) {
            case 0x0300 -> 0x30;                        // UnitOfMeasure: enum8
            case 0x0303 -> 0x18;                        // SummationFormatting: map8
            default -> 0x22;                            // Multiplier/Divisor: uint24
        };
    }

    /** Parses one Configure Reporting record, stores it, answers per the mode. */
    private void respondToConfigure(List<byte[]> frames, int cluster, int tsn,
            byte[] message) {
        int attribute = (message[4] & 0xFF) | ((message[5] & 0xFF) << 8);
        int dataType = message[6] & 0xFF;
        int minInterval = (message[7] & 0xFF) | ((message[8] & 0xFF) << 8);
        int maxInterval = (message[9] & 0xFF) | ((message[10] & 0xFF) << 8);
        // Long arithmetic: the uint48 change field is SIX bytes, and an int
        // shift distance wraps at 32 (JLS §15.19) — bytes 4–5 would fold onto
        // bytes 0–1 and a mis-encoded field would read back as correct.
        long change = 0;
        for (int i = 11; i < message.length; i++) {
            change |= (long) (message[i] & 0xFF) << (8 * (i - 11));
        }
        reportingStore.put(storeKey(cluster, attribute),
                new long[] {dataType, minInterval, maxInterval, change});
        if (configureSilent.contains(cluster)) {
            return;   // the sleepy write timeout
        }
        int status = configureStatusByCluster.getOrDefault(cluster, 0x00);
        byte[] response = status == 0x00
                ? new byte[] {0x08, (byte) tsn,
                        (byte) EzspReportingOps.COMMAND_CONFIGURE_REPORTING_RESPONSE,
                        0x00}
                : new byte[] {0x08, (byte) tsn,
                        (byte) EzspReportingOps.COMMAND_CONFIGURE_REPORTING_RESPONSE,
                        (byte) status, 0x00,
                        message[4], message[5]};
        frames.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID, cluster,
                LIGHT_ENDPOINT, response));
    }

    /** Answers a Read Reporting Configuration from the device-side store. */
    private void respondToReadback(List<byte[]> frames, int cluster, int tsn,
            byte[] message) {
        int attribute = (message[4] & 0xFF) | ((message[5] & 0xFF) << 8);
        long[] stored = reportingStore.getOrDefault(storeKey(cluster, attribute),
                new long[] {0x10, 0, 0, 0});
        int maxInterval = readbackReportingOff.contains(cluster)
                ? 0xFFFF : (int) stored[2];
        int changeWidth = switch ((int) stored[0]) {
            case 0x20 -> 1;
            case 0x21, 0x29 -> 2;
            case 0x25 -> 6;
            default -> 0;
        };
        byte[] response = new byte[12 + changeWidth];
        response[0] = 0x08;
        response[1] = (byte) tsn;
        response[2] = (byte)
                EzspReportingOps.COMMAND_READ_REPORTING_CONFIGURATION_RESPONSE;
        response[3] = 0x00;                             // record status SUCCESS
        response[4] = 0x00;                             // direction: reported
        response[5] = message[4];
        response[6] = message[5];
        response[7] = (byte) stored[0];
        response[8] = (byte) (stored[1] & 0xFF);
        response[9] = (byte) ((stored[1] >> 8) & 0xFF);
        response[10] = (byte) (maxInterval & 0xFF);
        response[11] = (byte) ((maxInterval >> 8) & 0xFF);
        for (int i = 0; i < changeWidth; i++) {
            // stored[3] is a long: the shift distance does not wrap at 32.
            response[12 + i] = (byte) ((stored[3] >> (8 * i)) & 0xFF);
        }
        frames.add(incomingMessage(EzspCoordinatorProtocol.HA_PROFILE_ID, cluster,
                LIGHT_ENDPOINT, response));
    }

    private static long storeKey(int cluster, int attribute) {
        return ((long) cluster << 16) | attribute;
    }

    private byte[] zdoReply(int cluster, int tsn) {
        int nwkLo = LIGHT_NWK & 0xFF;
        int nwkHi = (LIGHT_NWK >> 8) & 0xFF;
        return switch (cluster) {
            case ZdoCodec.CLUSTER_NODE_DESC_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi,
                    0x01, 0x40, (byte) 0x8E,            // router, rx-on-idle
                    0x5F, 0x11,                         // manufacturer code LE
                    82, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            case ZdoCodec.CLUSTER_ACTIVE_EP_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi, 0x01,
                    (byte) LIGHT_ENDPOINT};
            case ZdoCodec.CLUSTER_SIMPLE_DESC_REQ -> simpleDescriptor(tsn);
            default -> null;
        };
    }

    private byte[] simpleDescriptor(int tsn) {
        int[] inClusters = scriptedInClusters;
        int[] outClusters = {0x0019};
        int length = 1 + 2 + 2 + 1 + 1 + inClusters.length * 2
                + 1 + outClusters.length * 2;
        byte[] reply = new byte[5 + length];
        int i = 0;
        reply[i++] = (byte) tsn;
        reply[i++] = 0x00;
        reply[i++] = (byte) (LIGHT_NWK & 0xFF);
        reply[i++] = (byte) ((LIGHT_NWK >> 8) & 0xFF);
        reply[i++] = (byte) length;
        reply[i++] = (byte) LIGHT_ENDPOINT;
        reply[i++] = 0x04;                              // HA profile 0x0104 LE
        reply[i++] = 0x01;
        reply[i++] = (byte) (scriptedDeviceType & 0xFF);  // 0x0101 unless re-scripted
        reply[i++] = (byte) ((scriptedDeviceType >> 8) & 0xFF);
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
        String manufacturer = "Acme";
        String model = "DimTest";
        String build = "1.0.0";
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
        reply[i++] = 0x01;                              // mains
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
        message[11] = (byte) 0x8E;                      // MAC capability: router
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
        parameters[14] = (byte) (LIGHT_NWK & 0xFF);
        parameters[15] = (byte) ((LIGHT_NWK >> 8) & 0xFF);
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

    // ── sent-frame inspection (frame-id-scoped, never totals) ───────────────

    /** ZDO messages the NCP received for one ZDO cluster, in send order. */
    private static List<byte[]> zdoMessages(FakeNcp ncp, int zdoCluster) {
        List<byte[]> messages = new ArrayList<>();
        for (byte[] command : ncp.receivedEzspCommands()) {
            byte[] message = unicastMessage(command,
                    EzspCoordinatorProtocol.ZDO_PROFILE_ID, zdoCluster);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    /** ZCL GLOBAL messages for one cluster + global command id, in send order. */
    private static List<byte[]> zclGlobalMessages(FakeNcp ncp, int cluster,
            int globalCommandId) {
        List<byte[]> messages = new ArrayList<>();
        for (byte[] command : ncp.receivedEzspCommands()) {
            byte[] message = unicastMessage(command,
                    EzspCoordinatorProtocol.HA_PROFILE_ID, cluster);
            if (message != null && (message[0] & 0x03) == 0x00
                    && (message[2] & 0xFF) == globalCommandId) {
                messages.add(message);
            }
        }
        return messages;
    }

    /** The APS message of one sendUnicast command, or null when not a match. */
    private static byte[] unicastMessage(byte[] command, int profile, int cluster) {
        if (command.length < 5 || isLegacyVersion(command)
                || frameIdOf(command) != FRAME_SEND_UNICAST) {
            return null;
        }
        byte[] parameters = extendedParameters(command);
        int actualProfile = (parameters[3] & 0xFF) | ((parameters[4] & 0xFF) << 8);
        int actualCluster = (parameters[5] & 0xFF) | ((parameters[6] & 0xFF) << 8);
        if (actualProfile != profile || actualCluster != cluster) {
            return null;
        }
        byte[] message = new byte[parameters[15] & 0xFF];
        System.arraycopy(parameters, 16, message, 0, message.length);
        return message;
    }

    /** The message bytes after the tsn (ZDO) — the tsn is counter-dependent. */
    private static byte[] tail(byte[] message) {
        return Arrays.copyOfRange(message, 1, message.length);
    }

    /** The ZCL command payload after the 3-byte global header. */
    private static byte[] zclPayload(byte[] message) {
        return Arrays.copyOfRange(message, 3, message.length);
    }

    /** The expected Bind_req body after the tsn, per the BENCH-VERIFY layout. */
    private static byte[] bindRequestTail(int clusterId) {
        byte[] tail = new byte[21];
        int i = 0;
        for (int b = 0; b < 8; b++) {
            tail[i++] = (byte) (LIGHT_IEEE >> (8 * b));
        }
        tail[i++] = (byte) LIGHT_ENDPOINT;
        tail[i++] = (byte) (clusterId & 0xFF);
        tail[i++] = (byte) ((clusterId >> 8) & 0xFF);
        tail[i++] = (byte) EzspReportingOps.BIND_DST_ADDRESS_MODE_UNICAST;
        for (int b = 0; b < 8; b++) {
            tail[i++] = (byte) (FakeNcp.COORDINATOR_EUI64 >> (8 * b));
        }
        tail[i] = (byte) EzspReportingOps.COORDINATOR_ENDPOINT;
        return tail;
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

    private List<String> configuratorMessages(Level level, String prefix) {
        return messages(configuratorLogCapture, level, prefix);
    }

    private List<String> installerMessages(Level level, String prefix) {
        return messages(installerLogCapture, level, prefix);
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

    private static Logger configuratorLogger() {
        return (Logger) LoggerFactory.getLogger(ReportingConfigurator.class);
    }

    private static Logger installerLogger() {
        return (Logger) LoggerFactory.getLogger(ConfirmationOverrideInstaller.class);
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

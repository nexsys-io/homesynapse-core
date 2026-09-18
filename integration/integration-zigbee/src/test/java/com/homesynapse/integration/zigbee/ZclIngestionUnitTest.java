/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectType;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ObjLongConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZclIngestionUnit} tests: the drain → dedup → handler dispatch →
 * {@code state_reported} publish pipeline (§G bound-and-drain; the bound lives
 * at the protocol queue and is pinned in {@code EzspInterviewTest}), the
 * device-announce path, graceful unknown-cluster/unknown-sender handling, the
 * IAS dual-path tolerate rule, the F-4 dedup scope (0x0A only — the readback
 * channel bypasses), the F-7a enroll/zone-type-learn paths, and the F-8
 * handler-table invalidation.
 */
class ZclIngestionUnitTest {

    private static final IEEEAddress SNZB = new IEEEAddress(0x00124B0012345678L);
    private static final int SNZB_NWK = 0x6B9A;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private List<EzspFrame> pendingFrames;
    private int drainCalls;
    private ReportDeduplicator dedup;
    private Map<Long, EntityId> entities;
    private List<ZdoCodec.DeviceAnnounce> announces;
    private List<IEEEAddress> framesSeen;
    private List<NwkHook> nwkHooks;
    private List<IeeeHook> ieeeHooks;
    private List<ZclFrame> sentFrames;
    private List<Integer> sentTargets;
    private boolean sendAccepted;
    private ZoneType resolverZoneType;
    private ZclIngestionUnit.DeviceResolver resolver;
    private ZclIngestionUnit.IngestionListener listener;
    private List<SinkCall> sinkCalls;
    private ZclIngestionUnit ingestion;
    private ListAppender<ILoggingEvent> logCapture;

    /** One write-through sink invocation (LEARN-PERSIST DP-LP-3). */
    private record SinkCall(IEEEAddress device, long zclId) { }

    /** One F-R4-1 H-ii signal: an unknown-sender frame's (nwk, cluster). */
    private record NwkHook(int networkAddress, int clusterId) { }

    /** One F-R4-1 H-i signal: an accepted rejoin's (EUI64, nwk). */
    private record IeeeHook(IEEEAddress device, int networkAddress) { }

    @BeforeEach
    void setUp() {
        logCapture = new ListAppender<>();
        logCapture.start();
        ingestionLogger().addAppender(logCapture);
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        pendingFrames = new ArrayList<>();
        dedup = new ReportDeduplicator(clock);
        entities = new HashMap<>();
        entities.put(entityKey(SNZB, 1),
                EntityId.of(UlidFactory.generate(clock)));
        announces = new ArrayList<>();
        framesSeen = new ArrayList<>();
        nwkHooks = new ArrayList<>();
        ieeeHooks = new ArrayList<>();
        sentFrames = new ArrayList<>();
        sentTargets = new ArrayList<>();
        sendAccepted = true;
        resolverZoneType = ZoneType.MOTION;
        sinkCalls = new ArrayList<>();

        resolver =
                new ZclIngestionUnit.DeviceResolver() {
                    @Override
                    public Optional<IEEEAddress> deviceForNetworkAddress(
                            int networkAddress) {
                        return networkAddress == SNZB_NWK
                                ? Optional.of(SNZB) : Optional.empty();
                    }

                    @Override
                    public Optional<EntityId> entityFor(IEEEAddress device,
                            int endpoint) {
                        return Optional.ofNullable(
                                entities.get(entityKey(device, endpoint)));
                    }

                    @Override
                    public ZoneType zoneTypeFor(IEEEAddress device) {
                        return resolverZoneType;
                    }
                };
        listener =
                new ZclIngestionUnit.IngestionListener() {
                    @Override
                    public void onDeviceAnnounce(ZdoCodec.DeviceAnnounce announce) {
                        announces.add(announce);
                    }

                    @Override
                    public void onFrame(IEEEAddress device) {
                        framesSeen.add(device);
                    }

                    @Override
                    public void onRejoinCandidate(int networkAddress, int clusterId) {
                        nwkHooks.add(new NwkHook(networkAddress, clusterId));
                    }

                    @Override
                    public void onRejoinCandidate(IEEEAddress device,
                            int networkAddress) {
                        ieeeHooks.add(new IeeeHook(device, networkAddress));
                    }
                };
        ingestion = new ZclIngestionUnit(() -> {
            drainCalls++;
            List<EzspFrame> drained = List.copyOf(pendingFrames);
            pendingFrames.clear();
            return drained;
        }, resolver, listener, dedup, publisher, clock, (frame, networkAddress) -> {
            sentFrames.add(frame);
            sentTargets.add(networkAddress);
            return sendAccepted;
        });
    }

    @AfterEach
    void tearDown() {
        ingestionLogger().detachAppender(logCapture);
    }

    private static long entityKey(IEEEAddress device, int endpoint) {
        return device.value() ^ endpoint;
    }

    private void enqueueReport(int nwk, int endpoint, int cluster, byte[] zcl) {
        pendingFrames.add(incomingFrame(0x0104, cluster, endpoint, nwk, zcl));
    }

    @Test
    @DisplayName("drain → dispatch → state_reported: the occupancy edge publishes with entity subject")
    void occupancyEdgePublishes() {
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(drainCalls).isEqualTo(1);
        List<EventEnvelope> published = publisher.published();
        assertThat(published).hasSize(1);
        EventEnvelope envelope = published.get(0);
        assertThat(envelope.eventType()).isEqualTo(EventTypes.STATE_REPORTED);
        assertThat(envelope.subjectRef().type()).isEqualTo(SubjectType.ENTITY);
        assertThat(envelope.origin()).isEqualTo(EventOrigin.PHYSICAL);
        assertThat(envelope.eventTime()).isEqualTo(clock.instant());
        StateReportedEvent payload = (StateReportedEvent) envelope.payload();
        assertThat(payload.attributeKey()).isEqualTo("occupied");
        assertThat(payload.value()).isEqualTo("true");
    }

    @Test
    @DisplayName("the measured ×2 duplicate publishes ONCE")
    void duplicatePublishesOnce() {
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01});
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2B, 0x0A, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("battery reports carry DEVICE_AUTONOMOUS origin and the raw protocol value")
    void batteryReportOrigin() {
        enqueueReport(SNZB_NWK, 1, 0x0001,
                new byte[] {0x18, 0x30, 0x0A, 0x21, 0x00, 0x20, (byte) 0xC8});

        ingestion.processCycle();

        EventEnvelope envelope = publisher.published().get(0);
        assertThat(envelope.origin()).isEqualTo(EventOrigin.DEVICE_AUTONOMOUS);
        StateReportedEvent payload = (StateReportedEvent) envelope.payload();
        assertThat(payload.attributeKey()).isEqualTo("battery_pct");
        assertThat(payload.value()).isEqualTo("100");
        assertThat(payload.rawProtocolValue()).isEqualTo("200");
    }

    @Test
    @DisplayName("a device announce clears the dedup scope and reaches the listener")
    void announceClearsDedupAndNotifies() {
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01});
        ingestion.processCycle();

        pendingFrames.add(announceFrame());
        // Post-power-cycle the TSN restarts: the same-looking frame is genuine.
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2B, 0x0A, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(announces).hasSize(1);
        assertThat(announces.get(0).ieeeAddress()).isEqualTo(SNZB);
        assertThat(publisher.published()).hasSize(2);
    }

    @Test
    @DisplayName("unknown manufacturer clusters are skipped gracefully — never failures")
    void unknownClusterSkipped() {
        enqueueReport(SNZB_NWK, 1, 0xFC57,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x20, 0x05});

        ingestion.processCycle();

        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("an unknown sender network address is skipped with no publish")
    void unknownSenderSkipped() {
        enqueueReport(0x9999, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(publisher.published()).isEmpty();
    }

    // ── F-R4-1 (R-10 Row 10 (a)): the two admission hooks, DETECTION only ───

    @Test
    @DisplayName("F-R4-1 H-ii: an unknown-sender HA frame raises the rejoin-candidate "
            + "hook with its (nwk, cluster) BEFORE the unknown-sender WARN, which "
            + "continues as today — the frame is still skipped, nothing is published")
    void unknownSenderRaisesRejoinHookThenSkips() {
        enqueueReport(0x9999, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(nwkHooks).containsExactly(new NwkHook(0x9999, 0x0406));
        assertThat(ieeeHooks).isEmpty();
        assertThat(ingestionMessages(Level.WARN, "zigbee.ingestion_unknown_sender"))
                .containsExactly("zigbee.ingestion_unknown_sender: nwk=0x9999 "
                        + "cluster=0x406; frame skipped");
        assertThat(framesSeen).as("an unknown sender is never liveness").isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("F-R4-1 H-ii: a KNOWN sender never raises the hook (the resolver hit "
            + "is the gate), and a non-HA-profile frame is dropped before the resolver")
    void knownSenderAndNonHaFrameNeverRaiseRejoinHook() {
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01});
        pendingFrames.add(incomingFrame(0xA1E0, 0x0021, 242, 0x9999,
                new byte[] {0x11, 0x00, 0x00}));

        ingestion.processCycle();

        assertThat(nwkHooks).isEmpty();
        assertThat(ieeeHooks).isEmpty();
        assertThat(framesSeen).contains(SNZB);
    }

    @Test
    @DisplayName("F-R4-1 H-i: an accepted 0x0024 SECURED_REJOIN raises the EUI64 hook "
            + "beside the device_join INFO; an accepted UNSECURED_REJOIN does too")
    void acceptedRejoinRaisesIeeeHook() {
        pendingFrames.add(trustCenterJoinFrame(SNZB.value(), SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_SECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_NO_ACTION));
        pendingFrames.add(trustCenterJoinFrame(SNZB.value(), SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_UNSECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_USE_PRECONFIGURED_KEY));

        ingestion.processCycle();

        assertThat(ieeeHooks).containsExactly(
                new IeeeHook(SNZB, SNZB_NWK), new IeeeHook(SNZB, SNZB_NWK));
        assertThat(nwkHooks).isEmpty();
        assertThat(ingestionMessages(Level.INFO, "zigbee.device_join:"))
                .containsExactly(
                        "zigbee.device_join: device=0x00124B0012345678 nwk=0x6b9a "
                                + "status=SECURED_REJOIN decision=NO_ACTION",
                        "zigbee.device_join: device=0x00124B0012345678 nwk=0x6b9a "
                                + "status=UNSECURED_REJOIN decision=USE_PRECONFIGURED_KEY");
        assertThat(publisher.published()).isEmpty();
        assertThat(framesSeen).as("a join callback is never availability").isEmpty();
    }

    @Test
    @DisplayName("F-R4-1 H-i: a fresh UNSECURED_JOIN (the announce follows), a DENIED "
            + "rejoin, a DEVICE_LEFT, and a malformed 0x0024 never raise the hook — "
            + "the M9.4-TCJ pin's surviving half")
    void freshJoinDeniedLeftAndMalformedNeverRaiseIeeeHook() {
        pendingFrames.add(trustCenterJoinFrame(SNZB.value(), SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_UNSECURED_JOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_USE_PRECONFIGURED_KEY));
        pendingFrames.add(trustCenterJoinFrame(SNZB.value(), SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_SECURED_REJOIN,
                EzspCoordinatorProtocol.JOIN_DECISION_DENY_JOIN));
        pendingFrames.add(trustCenterJoinFrame(SNZB.value(), SNZB_NWK,
                EzspCoordinatorProtocol.DEVICE_UPDATE_DEVICE_LEFT,
                EzspCoordinatorProtocol.JOIN_DECISION_NO_ACTION));
        pendingFrames.add(new EzspFrame(
                EzspCoordinatorProtocol.FRAME_TRUST_CENTER_JOIN_HANDLER, true,
                new byte[] {0x01, 0x02, 0x03}));

        ingestion.processCycle();

        assertThat(ieeeHooks).isEmpty();
        assertThat(nwkHooks).isEmpty();
        assertThat(ingestionMessages(Level.INFO, "zigbee.device_join:")).hasSize(1);
        assertThat(ingestionMessages(Level.WARN, "zigbee.device_join_failed")).hasSize(1);
        assertThat(ingestionMessages(Level.INFO, "zigbee.device_left")).hasSize(1);
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("an unadopted endpoint records the frame but publishes nothing")
    void unadoptedEndpointPublishesNothing() {
        enqueueReport(SNZB_NWK, 3, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(publisher.published()).isEmpty();
        assertThat(framesSeen).contains(SNZB);
    }

    @Test
    @DisplayName("IAS ZoneStatusChangeNotification ingests regardless of enrollment (§3.12 tolerate)")
    void iasNotificationIngests() {
        // Cluster-specific frame: fc 0x19, tsn, cmd 0x00, zoneStatus 0x0021 LE,
        // extendedStatus, zoneId, delay LE.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});

        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(1);
        StateReportedEvent payload =
                (StateReportedEvent) publisher.published().get(0).payload();
        assertThat(payload.attributeKey()).isEqualTo("detected");
        assertThat(payload.value()).isEqualTo("true");
    }

    @Test
    @DisplayName("a Read Attributes Response ingests like a report (the readback path)")
    void readAttributesResponseIngests() {
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x01, 0x00, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("F-4: a same-payload consecutive-TSN repeat outside the twin window publishes")
    void periodicRepeatOutsideWindowPublishes() {
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01});
        ingestion.processCycle();

        // The periodic-reporting scale — far past DEDUP_WINDOW_MS.
        clock.advance(Duration.ofMinutes(5));
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2B, 0x0A, 0x00, 0x00, 0x18, 0x01});
        ingestion.processCycle();

        assertThat(publisher.published())
                .as("an unchanged periodic report is a genuine observation, "
                        + "never the F-4 false-drop")
                .hasSize(2);
    }

    @Test
    @DisplayName("F-4: a readback byte-identical to a prior report is never eaten "
            + "(the VERIFY channel bypasses dedup)")
    void readbackByteIdenticalToReportNeverEaten() {
        // The report's command payload [00 00 00 18 01] decodes to NO 0x0A
        // records (data type 0x00 is unknown) but IS recorded by dedup.
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x00, 0x18, 0x01});
        // The BYTE-IDENTICAL payload on the consecutive TSN, as an 0x01
        // record stream, is a valid readback: attr 0x0000, status SUCCESS,
        // type 0x18, value 0x01 — the pre-F-4 dedup would have dropped it.
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2B, 0x01, 0x00, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(1);
        StateReportedEvent payload =
                (StateReportedEvent) publisher.published().get(0).payload();
        assertThat(payload.attributeKey()).isEqualTo("occupied");
        assertThat(payload.value()).isEqualTo("true");
    }

    @Test
    @DisplayName("F-4: identical consecutive-TSN readbacks BOTH publish")
    void readbackTwinsBothPublish() {
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2A, 0x01, 0x00, 0x00, 0x00, 0x18, 0x01});
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2B, 0x01, 0x00, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(2);
    }

    @Test
    @DisplayName("F-7a: a ZoneEnrollRequest is answered with ZoneEnrollResponse "
            + "[success, zoneId 0] at the requester's endpoint/address")
    void zoneEnrollRequestAnswered() {
        // Cluster-specific frame: fc 0x19, tsn, cmd 0x01 (ZoneEnrollRequest),
        // zoneType 0x0015 LE, manufacturerCode 0x0000 LE.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x01, 0x15, 0x00, 0x00, 0x00});

        ingestion.processCycle();

        assertThat(sentFrames).hasSize(1);
        ZclFrame response = sentFrames.get(0);
        assertThat(response.clusterId()).isEqualTo(0x0500);
        assertThat(response.commandId())
                .isEqualTo(IasZoneHandler.COMMAND_ZONE_ENROLL_RESPONSE);
        assertThat(response.isClusterSpecific()).isTrue();
        assertThat(response.destinationEndpoint()).isEqualTo(1);
        assertThat(response.payload())
                .as("ZCL8 §8.2.2.3: [enrollResponseCode=Success, zoneId=0]")
                .containsExactly(0x00, 0x00);
        assertThat(sentTargets).containsExactly(SNZB_NWK);
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("F-7a: a rejected enroll-response send is logged and the cycle continues")
    void zoneEnrollSendRejectionContinues() {
        sendAccepted = false;
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x01, 0x15, 0x00, 0x00, 0x00});
        enqueueReport(SNZB_NWK, 1, 0x0406,
                new byte[] {0x18, 0x2B, 0x0A, 0x00, 0x00, 0x18, 0x01});

        ingestion.processCycle();

        assertThat(sentFrames).hasSize(1);
        assertThat(publisher.published())
                .as("the occupancy report behind the failed send still lands")
                .hasSize(1);
    }

    @Test
    @DisplayName("F-7a/F-8: a learned ZoneType outranks the resolver default and "
            + "rebuilds the handler table")
    void zoneTypeLearnRebuildsHandlers() {
        // The table builds under the resolver's MOTION default.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();
        assertThat(lastReportedKey()).isEqualTo("detected");

        // ZoneType 0x0015 (CONTACT) observed via Report-Attributes (enum16 0x31).
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x18, 0x2C, 0x0A, 0x01, 0x00, 0x31, 0x15, 0x00});
        // The next notification normalizes under the LEARNED type.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2D, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();

        assertThat(lastReportedKey()).isEqualTo("open");
    }

    @Test
    @DisplayName("F-7a: ZoneType learns from the readback channel too")
    void zoneTypeLearnsFromReadAttributesResponse() {
        // Read-response record: attr 0x0001, status SUCCESS, enum16, 0x0015.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x18, 0x2A, 0x01, 0x01, 0x00, 0x00, 0x31, 0x15, 0x00});
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2B, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});

        ingestion.processCycle();

        assertThat(lastReportedKey()).isEqualTo("open");
    }

    @Test
    @DisplayName("F-7a: an unknown ZoneType value is ignored — the resolver default stands")
    void unknownZoneTypeIgnored() {
        enqueueReport(SNZB_NWK, 1, 0x0500, new byte[] {0x18, 0x2A, 0x0A, 0x01,
                0x00, 0x31, (byte) 0x99, (byte) 0x99});
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2B, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});

        ingestion.processCycle();

        assertThat(lastReportedKey()).isEqualTo("detected");
    }

    @Test
    @DisplayName("F-8: a device announce invalidates the handler table — the rebuild "
            + "sees current zone-type truth")
    void announceInvalidatesHandlerTable() {
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();
        assertThat(lastReportedKey()).isEqualTo("detected");

        // The adoption layer's truth changes; the cached table must not
        // outlive the next invalidation event.
        resolverZoneType = ZoneType.CONTACT;
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2B, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();
        assertThat(lastReportedKey())
                .as("the table is cached until an invalidation event")
                .isEqualTo("detected");

        pendingFrames.add(announceFrame());
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2C, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();

        assertThat(lastReportedKey()).isEqualTo("open");
    }

    @Test
    @DisplayName("F-8: invalidateHandlers drops the table for the adapter's "
            + "adoption-completion hook")
    void invalidateHandlersRebuildsOnNextFrame() {
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();
        assertThat(lastReportedKey()).isEqualTo("detected");

        resolverZoneType = ZoneType.CONTACT;
        ingestion.invalidateHandlers(SNZB);

        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2B, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();

        assertThat(lastReportedKey()).isEqualTo("open");
    }

    // ── M9.7-W2 §2 — the temperature/humidity ingestion wiring ──────────────

    @Test
    @DisplayName("a 0x0402 temperature report publishes the canonical "
            + "temperature_c: wire 2350 (0.01 °C) → 23.5 °C, raw retained")
    void temperatureReportPublishesCanonicalCelsius() {
        // Report Attributes: attr 0x0000, type 0x29 (int16), 2350 = 0x092E LE.
        enqueueReport(SNZB_NWK, 1, 0x0402,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x29, 0x2E, 0x09});

        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(1);
        EventEnvelope envelope = publisher.published().get(0);
        assertThat(envelope.eventType()).isEqualTo(EventTypes.STATE_REPORTED);
        assertThat(envelope.origin()).isEqualTo(EventOrigin.PHYSICAL);
        StateReportedEvent payload = (StateReportedEvent) envelope.payload();
        assertThat(payload.attributeKey()).isEqualTo("temperature_c");
        assertThat(payload.value()).isEqualTo("23.5");
        assertThat(payload.unit()).isEqualTo("°C");
        assertThat(payload.rawProtocolValue()).isEqualTo("2350");
    }

    @Test
    @DisplayName("the embedded negative vector: wire 0xF830 sign-extends to "
            + "−2000 → −20.00 °C (the DP-9 int16 contract)")
    void temperatureNegativeWireVectorSignExtends() {
        enqueueReport(SNZB_NWK, 1, 0x0402,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x29, 0x30, (byte) 0xF8});

        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(1);
        StateReportedEvent payload =
                (StateReportedEvent) publisher.published().get(0).payload();
        assertThat(payload.value()).isEqualTo("-20.0");
        assertThat(payload.rawProtocolValue()).isEqualTo("-2000");
    }

    @Test
    @DisplayName("the temperature invalid sentinel (wire 0x8000) publishes "
            + "NOTHING — honest absence")
    void temperatureInvalidSentinelPublishesNothing() {
        enqueueReport(SNZB_NWK, 1, 0x0402,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x29, 0x00, (byte) 0x80});

        ingestion.processCycle();

        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("a 0x0405 humidity report publishes the canonical humidity_pct: "
            + "wire 4523 (0.01 %) → 45.23 %, raw retained")
    void humidityReportPublishesCanonicalPercent() {
        // Report Attributes: attr 0x0000, type 0x21 (uint16), 4523 = 0x11AB LE.
        enqueueReport(SNZB_NWK, 1, 0x0405,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x21, (byte) 0xAB, 0x11});

        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(1);
        StateReportedEvent payload =
                (StateReportedEvent) publisher.published().get(0).payload();
        assertThat(payload.attributeKey()).isEqualTo("humidity_pct");
        assertThat(payload.value()).isEqualTo("45.23");
        assertThat(payload.unit()).isEqualTo("%");
        assertThat(payload.rawProtocolValue()).isEqualTo("4523");
    }

    @Test
    @DisplayName("the humidity invalid sentinel (0xFFFF) publishes NOTHING — "
            + "honest absence")
    void humidityInvalidSentinelPublishesNothing() {
        enqueueReport(SNZB_NWK, 1, 0x0405,
                new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x21,
                        (byte) 0xFF, (byte) 0xFF});

        ingestion.processCycle();

        assertThat(publisher.published()).isEmpty();
    }

    // ── M9.7-W2 §4 — the learned-zoneType accessor ──────────────────────────

    @Test
    @DisplayName("learnedZoneType exposes LEARNED state only — empty before a "
            + "wire learn even while the resolver default is MOTION, the learned "
            + "type after (the classification seam's unlearned/learned split)")
    void learnedZoneTypeExposesWireLearnedStateOnly() {
        assertThat(ingestion.learnedZoneType(SNZB))
                .as("unlearned reads EMPTY — never the resolver default")
                .isEmpty();

        // ZoneType 0x0015 (CONTACT) observed via Report-Attributes (enum16 0x31).
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x18, 0x2C, 0x0A, 0x01, 0x00, 0x31, 0x15, 0x00});
        ingestion.processCycle();

        assertThat(ingestion.learnedZoneType(SNZB)).contains(ZoneType.CONTACT);
        assertThat(ingestion.learnedZoneType(new IEEEAddress(0x00124B00FFFF0001L)))
                .as("a different device stays unlearned")
                .isEmpty();
    }

    // ── W2-LEARN §1 — the enroll-payload learn (the joins-night gap) ────────

    @Test
    @DisplayName("F-7a: a ZoneEnrollRequest's zoneType payload LEARNS — no real "
            + "device volunteers the ZoneType attribute; the enroll request is "
            + "where zoneType arrives (ZCL8 §8.2.2.3)")
    void enrollRequestLearnsZoneType() {
        // The zoneEnrollRequestAnswered fixture shape: fc 0x19, tsn, cmd 0x01,
        // zoneType 0x0015 LE, manufacturerCode 0x0000 LE. Mutant bound:
        // deleting the payload parse flips the learn assert to empty.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x01, 0x15, 0x00, 0x00, 0x00});

        ingestion.processCycle();

        assertThat(ingestion.learnedZoneType(SNZB))
                .as("the request payload's zoneType is wire truth — learned")
                .contains(ZoneType.CONTACT);
        assertThat(sentFrames)
                .as("the ZoneEnrollResponse still sent — the learn never gates "
                        + "enrollment")
                .hasSize(1);
    }

    @Test
    @DisplayName("F-7a/F-8: an enroll-driven CONTACT learn on an effective-MOTION "
            + "device rebuilds the handler table and logs the learn INFO")
    void enrollLearnRebuildsHandlers() {
        // The table builds under the resolver's MOTION default.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();
        assertThat(lastReportedKey()).isEqualTo("detected");

        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2B, 0x01, 0x15, 0x00, 0x00, 0x00});
        // The next notification normalizes under the LEARNED type. Mutant
        // bound: removing the shared core's invalidate leaves the MOTION
        // table cached and this stays "detected".
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2C, 0x00, 0x21, 0x00, 0x00, 0x01, 0x00, 0x00});
        ingestion.processCycle();

        assertThat(lastReportedKey()).isEqualTo("open");
        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("the FROZEN-shape learn INFO, fed by effectiveZoneType")
                .containsExactly("zigbee.ias_zone_type_learned: "
                        + "device=0x00124B0012345678 zoneType=CONTACT "
                        + "(was MOTION)");
    }

    @Test
    @DisplayName("F-7a: the learn precedes the send outcome — a rejected "
            + "enroll-response send still records the learn")
    void enrollLearnPrecedesSendOutcome() {
        // Mutant bound: gating the learn on the send outcome (or reordering
        // respond-before-learn and returning on rejection) loses the learn.
        sendAccepted = false;
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x01, 0x15, 0x00, 0x00, 0x00});

        ingestion.processCycle();

        assertThat(sentFrames)
                .as("the send was attempted and rejected")
                .hasSize(1);
        assertThat(ingestion.learnedZoneType(SNZB))
                .as("the learn never depends on the send outcome")
                .contains(ZoneType.CONTACT);
    }

    @Test
    @DisplayName("F-7a: an unknown zoneType in the enroll payload is ignored — "
            + "the existing unknown DEBUG, no store, the response still sent")
    void enrollUnknownZoneTypeIgnored() {
        // logback filters below the logger's effective level (root INFO in the
        // test tree) BEFORE appenders run — the explicit DEBUG keeps the
        // presence assertion from passing vacuously on an empty capture.
        Level previous = ingestionLogger().getLevel();
        ingestionLogger().setLevel(Level.DEBUG);
        try {
            enqueueReport(SNZB_NWK, 1, 0x0500,
                    new byte[] {0x19, 0x2A, 0x01, 0x77, 0x77, 0x00, 0x00});
            ingestion.processCycle();
        } finally {
            ingestionLogger().setLevel(previous);
        }

        assertThat(ingestion.learnedZoneType(SNZB))
                .as("an unknown zclId never stores")
                .isEmpty();
        assertThat(ingestionMessages(Level.DEBUG, "zigbee.ias_zone_type_unknown"))
                .containsExactly("zigbee.ias_zone_type_unknown: "
                        + "device=0x00124B0012345678 zclId=0x7777; ignored");
        assertThat(sentFrames).hasSize(1);
    }

    @Test
    @DisplayName("§3.12: an enroll payload shorter than 2 bytes learns nothing — "
            + "the response still sent (a malformed request never blocks "
            + "enrollment)")
    void enrollShortPayloadLearnsNothing() {
        // A 1-byte payload — the mutant bound: dropping the length guard makes
        // the LE uint16 read throw past the frame end and fail this test.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x01, 0x15});

        ingestion.processCycle();

        assertThat(ingestion.learnedZoneType(SNZB)).isEmpty();
        assertThat(sentFrames).hasSize(1);
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("F-7a: a second enroll with the same type stores silently — "
            + "no second invalidate, no second learn INFO (the same-as-effective "
            + "rule)")
    void enrollSameTypeTwiceInvalidatesOnce() {
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x01, 0x15, 0x00, 0x00, 0x00});
        ingestion.processCycle();
        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("the first learn changes the effective type — ONE INFO")
                .hasSize(1);

        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2B, 0x01, 0x15, 0x00, 0x00, 0x00});
        ingestion.processCycle();

        assertThat(ingestion.learnedZoneType(SNZB)).contains(ZoneType.CONTACT);
        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("the second same-type learn is a silent store — still ONE")
                .hasSize(1);
        assertThat(sentFrames)
                .as("both requests answered")
                .hasSize(2);
    }

    // ── W2-LEARN §3 — the route() non-HA diagnostic ─────────────────────────

    @Test
    @DisplayName("route(): a non-HA-profile frame is dropped exactly as before, "
            + "now with ONE DEBUG diagnostic; HA frames produce no such line")
    void nonHaProfileFrameSkippedWithDiagnostic() {
        Level previous = ingestionLogger().getLevel();
        ingestionLogger().setLevel(Level.DEBUG);
        try {
            pendingFrames.add(incomingFrame(0xC05E, 0x0006, 1, SNZB_NWK,
                    new byte[] {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01}));
            enqueueReport(SNZB_NWK, 1, 0x0406,
                    new byte[] {0x18, 0x2B, 0x0A, 0x00, 0x00, 0x18, 0x01});
            ingestion.processCycle();
        } finally {
            ingestionLogger().setLevel(previous);
        }

        assertThat(publisher.published())
                .as("the non-HA frame never dispatches; the HA frame does")
                .hasSize(1);
        assertThat(ingestionMessages(Level.DEBUG,
                "zigbee.ingestion_profile_skipped"))
                .as("ONE diagnostic for the 0xC05E frame, none for the HA frame")
                .containsExactly("zigbee.ingestion_profile_skipped: nwk=0x6b9a "
                        + "profile=0xc05e cluster=0x6; non-HA frame skipped");
    }

    // ── LEARN-PERSIST — the construction seed + the write-through sink ──────

    /** A unit over the setUp collaborators with an explicit seed and sink. */
    private ZclIngestionUnit seededUnit(Map<Long, Long> seed,
            ObjLongConsumer<IEEEAddress> sink) {
        return new ZclIngestionUnit(() -> {
            drainCalls++;
            List<EzspFrame> drained = List.copyOf(pendingFrames);
            pendingFrames.clear();
            return drained;
        }, resolver, listener, dedup, publisher, clock, (frame, networkAddress) -> {
            sentFrames.add(frame);
            sentTargets.add(networkAddress);
            return sendAccepted;
        }, seed, sink);
    }

    @Test
    @DisplayName("LEARN-PERSIST DP-LP-4: a seeded zone type answers WITHOUT any "
            + "frame processed, and seeding is SILENT — no ias_zone_type_learned "
            + "INFO at construction (the wire-learn INFO means exactly one thing)")
    void seededZoneTypeAnswersWithoutFramesAndStaysSilent() {
        ZclIngestionUnit seeded = seededUnit(
                Map.of(SNZB.value(), (long) ZoneType.CONTACT.zclId()),
                (device, zclId) -> { });

        assertThat(seeded.learnedZoneType(SNZB))
                .as("the persisted learn answers pre-cycle — the fast-propose "
                        + "race read")
                .contains(ZoneType.CONTACT);
        assertThat(seeded.learnedZoneTypeCount()).isEqualTo(1);
        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("seeding is NOT a wire learn — the INFO absence pin "
                        + "(mutant M3's kill)")
                .isEmpty();
    }

    @Test
    @DisplayName("LEARN-PERSIST DP-LP-2: an unknown persisted zclId is skipped "
            + "with a DEBUG — never a crash, never a learn; the known sibling "
            + "still applies and the applied count excludes the skip")
    void seedUnknownZclIdSkippedWithDebug() {
        IEEEAddress other = new IEEEAddress(0x00124B00FFFF0001L);
        Level previous = ingestionLogger().getLevel();
        ingestionLogger().setLevel(Level.DEBUG);
        ZclIngestionUnit seeded;
        try {
            seeded = seededUnit(Map.of(
                    SNZB.value(), 0x7777L,
                    other.value(), (long) ZoneType.CONTACT.zclId()),
                    (device, zclId) -> { });
        } finally {
            ingestionLogger().setLevel(previous);
        }

        assertThat(seeded.learnedZoneType(SNZB))
                .as("an unknown persisted id never stores")
                .isEmpty();
        assertThat(seeded.learnedZoneType(other)).contains(ZoneType.CONTACT);
        assertThat(seeded.learnedZoneTypeCount())
                .as("the applied count excludes tolerance-skipped entries")
                .isEqualTo(1);
        assertThat(ingestionMessages(Level.DEBUG,
                "zigbee.ias_zone_type_seed_unknown"))
                .containsExactly("zigbee.ias_zone_type_seed_unknown: "
                        + "device=0x00124B0012345678 zclId=0x7777; skipped");
    }

    @Test
    @DisplayName("LEARN-PERSIST DP-LP-3: EVERY successful wire learn reaches "
            + "the sink — the change case AND the same-as-effective silent case")
    void wireLearnWritesThroughSinkBothCases() {
        ZclIngestionUnit unit = seededUnit(Map.of(),
                (device, zclId) -> sinkCalls.add(new SinkCall(device, zclId)));

        // The change case: effective MOTION (resolver default) → CONTACT.
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x01, 0x15, 0x00, 0x00, 0x00});
        unit.processCycle();
        assertThat(sinkCalls).containsExactly(
                new SinkCall(SNZB, ZoneType.CONTACT.zclId()));

        // The same-as-effective case: the INFO-silent path STILL persists —
        // a re-learn equal to the effective type is a learned fact (DP-LP-3;
        // mutant M2's kill is the sink staying empty, a sink-inside-the-
        // changed-branch mutant dies on this second invocation).
        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2B, 0x01, 0x15, 0x00, 0x00, 0x00});
        unit.processCycle();

        assertThat(sinkCalls).hasSize(2);
        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("the second learn stays INFO-silent — persistence is "
                        + "never log noise")
                .hasSize(1);
    }

    @Test
    @DisplayName("LEARN-PERSIST DP-LP-7: after rehydration a live re-learn of "
            + "the SAME type is INFO-silent (no re-learn noise across sessions) "
            + "and still writes through")
    void rehydratedRelearnStaysSilentAndWritesThrough() {
        ZclIngestionUnit seeded = seededUnit(
                Map.of(SNZB.value(), (long) ZoneType.CONTACT.zclId()),
                (device, zclId) -> sinkCalls.add(new SinkCall(device, zclId)));

        enqueueReport(SNZB_NWK, 1, 0x0500,
                new byte[] {0x19, 0x2A, 0x01, 0x15, 0x00, 0x00, 0x00});
        seeded.processCycle();

        assertThat(seeded.learnedZoneType(SNZB)).contains(ZoneType.CONTACT);
        assertThat(ingestionMessages(Level.INFO, "zigbee.ias_zone_type_learned"))
                .as("the seeded type IS the effective type — the wire re-learn "
                        + "takes the silent path")
                .isEmpty();
        assertThat(sinkCalls)
                .as("the silent path still persists")
                .containsExactly(new SinkCall(SNZB, ZoneType.CONTACT.zclId()));
    }

    // ── ENERGY-READ T7 / R4 — metering reports scale by LEARNED formatting
    //    only; unknown formatting is silence plus one DEBUG ─────────────────

    private static final MeteringFormatting GEN4_FIXTURE_FORMATTING =
            new MeteringFormatting(1, 100, 1, 10, 1, 1000, 1, 1_000_000, 0x00);

    /** A 0x0B04 Report Attributes frame: ActivePower (0x050B, int16 LE). */
    private static byte[] activePowerReport(int tsn, int rawWatts) {
        return new byte[] {0x18, (byte) tsn, 0x0A, 0x0B, 0x05, 0x29,
            (byte) (rawWatts & 0xFF), (byte) ((rawWatts >> 8) & 0xFF)};
    }

    /** A 0x0702 Report Attributes frame: CurrentSummationDelivered (uint48 LE). */
    private static byte[] summationReport(int tsn, long raw) {
        byte[] frame = new byte[12];
        frame[0] = 0x18;
        frame[1] = (byte) tsn;
        frame[2] = 0x0A;
        frame[5] = 0x25;
        for (int i = 0; i < 6; i++) {
            frame[6 + i] = (byte) ((raw >> (8 * i)) & 0xFF);
        }
        return frame;
    }

    /** A unit over the setUp collaborators with a metering-formatting seed. */
    private ZclIngestionUnit meteringUnit(
            Map<Long, Map<Integer, MeteringFormatting>> formattingSeed) {
        return new ZclIngestionUnit(() -> {
            drainCalls++;
            List<EzspFrame> drained = List.copyOf(pendingFrames);
            pendingFrames.clear();
            return drained;
        }, resolver, listener, dedup, publisher, clock, (frame, networkAddress) -> {
            sentFrames.add(frame);
            sentTargets.add(networkAddress);
            return sendAccepted;
        }, Map.of(), (device, zclId) -> { }, formattingSeed);
    }

    @Test
    @DisplayName("T7: a metering report for a device with NO learned formatting "
            + "publishes NOTHING — one DEBUG zigbee.metering_unscaled per "
            + "(device, cluster) and nothing else (honest silence over a guessed "
            + "scale)")
    void meteringReportWithoutFormatting_isSilent() {
        Level previous = ingestionLogger().getLevel();
        ingestionLogger().setLevel(Level.DEBUG);
        try {
            enqueueReport(SNZB_NWK, 1, 0x0B04, activePowerReport(0x2A, 8000));
            enqueueReport(SNZB_NWK, 1, 0x0B04, activePowerReport(0x2C, 8010));
            enqueueReport(SNZB_NWK, 1, 0x0702, summationReport(0x2E, 12_345L));
            ingestion.processCycle();
        } finally {
            ingestionLogger().setLevel(previous);
        }

        assertThat(publisher.published())
                .as("no state_reported — a value with an unknown scale is never "
                        + "presented").isEmpty();
        assertThat(ingestionMessages(Level.DEBUG, "zigbee.metering_unscaled"))
                .containsExactly(
                        "zigbee.metering_unscaled: device=0x00124B0012345678 "
                                + "cluster=0xb04; no formatting learned — the "
                                + "report is not scaled, nothing emitted",
                        "zigbee.metering_unscaled: device=0x00124B0012345678 "
                                + "cluster=0x702; no formatting learned — the "
                                + "report is not scaled, nothing emitted");
        assertThat(ingestionMessages(Level.DEBUG,
                "zigbee.ingestion_unhandled_cluster"))
                .as("the metering line REPLACES the generic one").isEmpty();
    }

    @Test
    @DisplayName("T7/R4: WITH learned formatting the same report publishes "
            + "state_reported power_w — scaled, the raw value and the formatting "
            + "beside it")
    void meteringReportWithFormatting_publishesScaledPowerW() {
        ZclIngestionUnit metering = meteringUnit(
                Map.of(SNZB.value(), Map.of(1, GEN4_FIXTURE_FORMATTING)));
        enqueueReport(SNZB_NWK, 1, 0x0B04, activePowerReport(0x2A, 8000));
        enqueueReport(SNZB_NWK, 1, 0x0702, summationReport(0x2B, 12_345_678L));

        metering.processCycle();

        assertThat(metering.learnedMeteringFormattingCount()).isEqualTo(1);
        List<EventEnvelope> published = publisher.published();
        assertThat(published).hasSize(2);
        assertThat(published.get(0).eventType())
                .isEqualTo(EventTypes.STATE_REPORTED);
        assertThat(published.get(0).origin()).isEqualTo(EventOrigin.PHYSICAL);
        StateReportedEvent power = (StateReportedEvent) published.get(0).payload();
        assertThat(power.attributeKey()).isEqualTo("power_w");
        assertThat(power.value()).isEqualTo("80.0");
        assertThat(power.unit()).isEqualTo("W");
        assertThat(power.rawProtocolValue()).isEqualTo("8000");
        assertThat(power.rawProtocolUnit()).isEqualTo("mult=1 div=100");
        StateReportedEvent energy =
                (StateReportedEvent) published.get(1).payload();
        assertThat(energy.attributeKey()).isEqualTo("energy_wh");
        assertThat(energy.value()).isEqualTo("12345.678");
        assertThat(energy.unit()).isEqualTo("Wh");
        assertThat(energy.rawProtocolValue()).isEqualTo("12345678");
    }

    @Test
    @DisplayName("R3/R4: recording a formatting AFTER the handler table was built "
            + "rebuilds it — the adoption drive's hand-off takes effect on the "
            + "very next frame")
    void recordLearnedMeteringFormatting_rebuildsTheHandlerTable() {
        enqueueReport(SNZB_NWK, 1, 0x0B04, activePowerReport(0x2A, 8000));
        ingestion.processCycle();
        assertThat(publisher.published()).isEmpty();

        ingestion.recordLearnedMeteringFormatting(SNZB, 1,
                GEN4_FIXTURE_FORMATTING);
        enqueueReport(SNZB_NWK, 1, 0x0B04, activePowerReport(0x2C, 8010));
        ingestion.processCycle();

        assertThat(publisher.published()).hasSize(1);
        assertThat(((StateReportedEvent) publisher.published().get(0).payload())
                .value()).isEqualTo("80.1");
    }

    @Test
    @DisplayName("R4: endpoints that DISAGREE on a cluster's formatting leave that "
            + "cluster unscaled (the handler table is per device — a scale is "
            + "never borrowed across endpoints); a cluster they agree on scales")
    void endpointsDisagreeingOnFormatting_stayUnscaled() {
        MeteringFormatting other = new MeteringFormatting(1, 10, 1, 10, 1, 1000,
                1, 1_000_000, 0x00);
        ZclIngestionUnit metering = meteringUnit(Map.of(SNZB.value(),
                Map.of(1, GEN4_FIXTURE_FORMATTING, 2, other)));
        enqueueReport(SNZB_NWK, 1, 0x0B04, activePowerReport(0x2A, 8000));
        enqueueReport(SNZB_NWK, 1, 0x0702, summationReport(0x2B, 5_000L));

        metering.processCycle();

        assertThat(publisher.published()).hasSize(1);
        assertThat(((StateReportedEvent) publisher.published().get(0).payload())
                .attributeKey()).isEqualTo("energy_wh");
    }

    @Test
    @DisplayName("R4: the agreement is judged across ALL endpoints at once — a "
            + "third endpoint never re-fills a pair two others hold differently")
    void thirdEndpointNeverRefillsADisputedPair() {
        MeteringFormatting dissent = new MeteringFormatting(1, 10, 1, 10, 1, 1000,
                1, 1_000_000, 0x00);
        ZclIngestionUnit metering = meteringUnit(Map.of(SNZB.value(), Map.of(
                1, GEN4_FIXTURE_FORMATTING, 2, dissent,
                3, GEN4_FIXTURE_FORMATTING)));
        enqueueReport(SNZB_NWK, 1, 0x0B04, activePowerReport(0x2A, 8000));

        metering.processCycle();

        assertThat(metering.learnedMeteringFormattingCount()).isEqualTo(3);
        assertThat(publisher.published()).isEmpty();
    }

    private String lastReportedKey() {
        List<EventEnvelope> published = publisher.published();
        return ((StateReportedEvent) published.get(published.size() - 1)
                .payload()).attributeKey();
    }

    private static EzspFrame announceFrame() {
        byte[] announce = new byte[12];
        announce[0] = 0x07;
        announce[1] = (byte) (SNZB_NWK & 0xFF);
        announce[2] = (byte) (SNZB_NWK >> 8);
        long ieee = SNZB.value();
        for (int i = 0; i < 8; i++) {
            announce[3 + i] = (byte) (ieee >> (8 * i));
        }
        announce[11] = (byte) 0x80;
        return incomingFrame(0x0000, ZdoCodec.CLUSTER_DEVICE_ANNOUNCE, 0,
                SNZB_NWK, announce);
    }

    /** A 0x0024 trustCenterJoinHandler callback: nodeId, EUI64, status, decision, parent. */
    private static EzspFrame trustCenterJoinFrame(long ieee, int nwk, int status,
            int decision) {
        byte[] parameters = new byte[14];
        parameters[0] = (byte) (nwk & 0xFF);
        parameters[1] = (byte) ((nwk >> 8) & 0xFF);
        for (int i = 0; i < 8; i++) {
            parameters[2 + i] = (byte) (ieee >> (8 * i));
        }
        parameters[10] = (byte) status;
        parameters[11] = (byte) decision;
        return new EzspFrame(
                EzspCoordinatorProtocol.FRAME_TRUST_CENTER_JOIN_HANDLER, true,
                parameters);
    }

    private static EzspFrame incomingFrame(int profile, int cluster, int srcEp,
            int sender, byte[] message) {
        byte[] parameters = new byte[19 + message.length];
        parameters[0] = 0x00;
        parameters[1] = (byte) (profile & 0xFF);
        parameters[2] = (byte) ((profile >> 8) & 0xFF);
        parameters[3] = (byte) (cluster & 0xFF);
        parameters[4] = (byte) ((cluster >> 8) & 0xFF);
        parameters[5] = (byte) srcEp;
        parameters[6] = 0x01;
        parameters[12] = (byte) 164;
        parameters[13] = (byte) -59;
        parameters[14] = (byte) (sender & 0xFF);
        parameters[15] = (byte) ((sender >> 8) & 0xFF);
        parameters[16] = (byte) 0xFF;
        parameters[17] = (byte) 0xFF;
        parameters[18] = (byte) message.length;
        System.arraycopy(message, 0, parameters, 19, message.length);
        return new EzspFrame(
                EzspCoordinatorProtocol.FRAME_INCOMING_MESSAGE_HANDLER, true,
                parameters);
    }

    // ── log capture (the ZigbeeWave2ContactAdoptionTest idiom) ──────────────

    private List<String> ingestionMessages(Level level, String prefix) {
        return logCapture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();
    }

    private static Logger ingestionLogger() {
        return (Logger) LoggerFactory.getLogger(ZclIngestionUnit.class);
    }
}

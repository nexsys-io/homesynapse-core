/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectType;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private List<ZclFrame> sentFrames;
    private List<Integer> sentTargets;
    private boolean sendAccepted;
    private ZoneType resolverZoneType;
    private ZclIngestionUnit ingestion;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        pendingFrames = new ArrayList<>();
        dedup = new ReportDeduplicator(clock);
        entities = new HashMap<>();
        entities.put(entityKey(SNZB, 1),
                EntityId.of(UlidFactory.generate(clock)));
        announces = new ArrayList<>();
        framesSeen = new ArrayList<>();
        sentFrames = new ArrayList<>();
        sentTargets = new ArrayList<>();
        sendAccepted = true;
        resolverZoneType = ZoneType.MOTION;

        ZclIngestionUnit.DeviceResolver resolver =
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
        ZclIngestionUnit.IngestionListener listener =
                new ZclIngestionUnit.IngestionListener() {
                    @Override
                    public void onDeviceAnnounce(ZdoCodec.DeviceAnnounce announce) {
                        announces.add(announce);
                    }

                    @Override
                    public void onFrame(IEEEAddress device) {
                        framesSeen.add(device);
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
}

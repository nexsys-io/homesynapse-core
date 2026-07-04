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
 * device-announce path, graceful unknown-cluster/unknown-sender handling, and
 * the IAS dual-path tolerate rule.
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
    private ZclIngestionUnit ingestion;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        pendingFrames = new ArrayList<>();
        dedup = new ReportDeduplicator();
        entities = new HashMap<>();
        entities.put(entityKey(SNZB, 1),
                EntityId.of(UlidFactory.generate(clock)));
        announces = new ArrayList<>();
        framesSeen = new ArrayList<>();

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
                        return ZoneType.MOTION;
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
        }, resolver, listener, dedup, publisher, clock);
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

        byte[] announce = new byte[12];
        announce[0] = 0x07;
        announce[1] = (byte) (SNZB_NWK & 0xFF);
        announce[2] = (byte) (SNZB_NWK >> 8);
        long ieee = SNZB.value();
        for (int i = 0; i < 8; i++) {
            announce[3 + i] = (byte) (ieee >> (8 * i));
        }
        announce[11] = (byte) 0x80;
        pendingFrames.add(incomingFrame(0x0000, ZdoCodec.CLUSTER_DEVICE_ANNOUNCE,
                0, SNZB_NWK, announce));
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

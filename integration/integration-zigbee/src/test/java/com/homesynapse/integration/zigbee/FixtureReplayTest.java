/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE §J FIXTURE-REPLAY GATE — the M9.3 done-when: both bench-captured fixtures
 * (nexsys-bench@5ceff3b, copied with provenance headers) replay through the
 * REAL ingestion path — loader → registry → adoption slice → deduplicator →
 * cluster handlers → ingestion unit → {@code state_reported} publishes — and
 * the profile-matched confirmation characterizations render the RECORDED
 * confirmability per capability.
 *
 * <p>SNZB-03P walk-test: the measured ×2 duplicate stream dedups to the
 * fixture's derived {@code occupancy.occupied} EDGE stream (18 edges,
 * alternating, wire-silent re-triggers invisible). Hue confirmation windows:
 * the CT reports Kelvin-convert at ingestion including the ±1-mired drift rows
 * (the rounding regression), raw mireds retained.
 */
class FixtureReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final IEEEAddress SNZB = new IEEEAddress(0x00124B0012345678L);
    private static final int SNZB_NWK = 0x6B9A;
    private static final IEEEAddress HUE = new IEEEAddress(0x0017880109AB12CDL);
    private static final int HUE_NWK = 0x260F;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private StandardDeviceProfileRegistry registry;
    private ZigbeeAdoptionSlice adoption;
    private ReportDeduplicator deduplicator;
    private List<EzspFrame> pendingFrames;
    private ZclIngestionUnit ingestion;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        registry = new StandardDeviceProfileRegistry();
        registry.register(new ZigbeeProfileLoader().loadBundled());
        InMemoryDeviceRegistry deviceRegistry = new InMemoryDeviceRegistry();
        InMemoryEntityRegistry entityRegistry = new InMemoryEntityRegistry();
        adoption = new ZigbeeAdoptionSlice(
                new IntegrationId(UlidFactory.generate(clock)),
                deviceRegistry, entityRegistry,
                new RegistryProjection(deviceRegistry, entityRegistry),
                registry, publisher, clock);
        deduplicator = new ReportDeduplicator(clock);
        pendingFrames = new ArrayList<>();
        ingestion = new ZclIngestionUnit(() -> {
            List<EzspFrame> drained = List.copyOf(pendingFrames);
            pendingFrames.clear();
            return drained;
        }, new ZclIngestionUnit.DeviceResolver() {
            @Override
            public Optional<IEEEAddress> deviceForNetworkAddress(
                    int networkAddress) {
                if (networkAddress == SNZB_NWK) {
                    return Optional.of(SNZB);
                }
                if (networkAddress == HUE_NWK) {
                    return Optional.of(HUE);
                }
                return Optional.empty();
            }

            @Override
            public Optional<EntityId> entityFor(IEEEAddress device,
                    int endpoint) {
                return adoption.entityFor(device, endpoint);
            }

            @Override
            public ZoneType zoneTypeFor(IEEEAddress device) {
                return ZoneType.MOTION;
            }
        }, new ZclIngestionUnit.IngestionListener() {
            @Override
            public void onDeviceAnnounce(ZdoCodec.DeviceAnnounce announce) {
            }

            @Override
            public void onFrame(IEEEAddress device) {
            }

            @Override
            public void onRejoinCandidate(int networkAddress, int clusterId) {
                // Fixture replay opens no window: never an admission here.
            }

            @Override
            public void onRejoinCandidate(IEEEAddress device, int networkAddress) {
            }
        }, deduplicator, publisher, clock, (frame, networkAddress) -> true);
    }

    private static InterviewResult snzbInterview() {
        return new InterviewResult(SNZB, SNZB_NWK,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500,
                                0xFC57),
                        List.of(0x0003, 0x0019))),
                "eWeLink", "SNZB-03P", 3, InterviewStatus.COMPLETE);
    }

    private static InterviewResult hueInterview() {
        return new InterviewResult(HUE, HUE_NWK,
                new NodeDescriptor(1, 0x100B, 82, 142),
                List.of(new EndpointDescriptor(11, 0x0104, 0x010D,
                        List.of(0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0008,
                                0x0300, 0x1000, 0xFC01, 0xFC04),
                        List.of(0x0019))),
                "Signify Netherlands B.V.", "LCA017", 1,
                InterviewStatus.COMPLETE);
    }

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream in =
                FixtureReplayTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture resource %s", name).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    private List<StateReportedEvent> reportsOf(String attributeKey) {
        return publisher.ofType(EventTypes.STATE_REPORTED)
                .map(e -> (StateReportedEvent) e.payload())
                .filter(p -> p.attributeKey().equals(attributeKey))
                .toList();
    }

    @Test
    @DisplayName("§J/SNZB: the walk-test replays to the deduped occupancy.occupied edge stream")
    void snzbWalkTestReplaysToEdgeStream() throws Exception {
        DeviceProfile profile = registry.findProfile(snzbInterview()).orElseThrow();
        adoption.onDeviceDiscovered(snzbInterview(), profile.profileId());
        adoption.adopt(SNZB);
        publisher.published(); // adoption events precede the replay
        int adoptionEvents = publisher.published().size();

        JsonNode fixture =
                fixture("2026-07-01_snzb-03p_motion-walktest_event-stream.json");
        List<Instant> frameTimes = new ArrayList<>();
        for (JsonNode event : fixture.get("events")) {
            Instant ts = OffsetDateTime.parse(event.get("ts").asText()).toInstant();
            clock.setFixed(ts);
            frameTimes.add(ts);
            int tsn = event.get("tsn").asInt();
            long value = event.get("attr").get("value").asLong();
            pendingFrames.add(incomingFrame(SNZB_NWK, 1, 0x0406, new byte[] {
                    0x18, (byte) tsn, 0x0A, 0x00, 0x00, 0x18, (byte) value}));
            ingestion.processCycle();
        }

        List<EventEnvelope> reported = publisher.published()
                .subList(adoptionEvents, publisher.published().size());
        // The fixture's derived truth: 35 raw frames, every edge transmitted
        // twice (consecutive TSNs) except the opening clear — 18 edges,
        // alternating from false; the event log sees edges only, never
        // continued presence (silent re-triggers are wire-invisible).
        assertThat(reported).hasSize(18);
        for (int i = 0; i < reported.size(); i++) {
            StateReportedEvent payload =
                    (StateReportedEvent) reported.get(i).payload();
            assertThat(payload.attributeKey()).isEqualTo("occupied");
            assertThat(payload.value())
                    .as("edge %d alternates starting false", i)
                    .isEqualTo(i % 2 == 0 ? "false" : "true");
        }
        // Edge event times are the FIRST twin's timestamps.
        assertThat(reported.get(0).eventTime())
                .isEqualTo(OffsetDateTime.parse("2026-07-01T21:45:02.066-04:00")
                        .toInstant());
        assertThat(reported.get(1).eventTime())
                .isEqualTo(OffsetDateTime.parse("2026-07-01T21:45:32.820-04:00")
                        .toInstant());
        assertThat(reported.get(17).eventTime())
                .isEqualTo(OffsetDateTime.parse("2026-07-01T22:01:00.288-04:00")
                        .toInstant());
        // The silent-retrigger window (21:54:25 → 21:57:01): no publishes.
        Instant windowStart =
                OffsetDateTime.parse("2026-07-01T21:54:25.082-04:00").toInstant();
        Instant windowEnd =
                OffsetDateTime.parse("2026-07-01T21:57:01.357-04:00").toInstant();
        assertThat(reported.stream()
                .filter(e -> e.eventTime().isAfter(windowStart)
                        && e.eventTime().isBefore(windowEnd)))
                .isEmpty();
        assertThat(frameTimes).hasSize(35);
    }

    @Test
    @DisplayName("§J/SNZB: the profile-matched characterization renders the recorded EMPTY block")
    void snzbRendersRecordedConfirmability() {
        DeviceProfile profile = registry.findProfile(snzbInterview()).orElseThrow();

        assertThat(profile.profileId())
                .isEqualTo(MeasuredCorpusValues.SNZB_PROFILE_ID);
        assertThat(profile.confirmation())
                .as("the read-only sensor's recorded confirmability is the "
                        + "EMPTY block; this fixture IS the device's corpus value")
                .isEmpty();
    }

    @Test
    @DisplayName("§J/Hue: the confirmation windows replay to the Kelvin CT stream incl. the drift rows")
    void hueWindowsReplayToKelvinStream() throws Exception {
        DeviceProfile profile = registry.findProfile(hueInterview()).orElseThrow();
        adoption.onDeviceDiscovered(hueInterview(), profile.profileId());
        adoption.adopt(HUE);

        JsonNode fixture = fixture(
                "2026-07-01_hue-lca017_confirmation-windows_event-stream.json");
        int tsn = 1;
        for (JsonNode sequence : fixture.get("sequences")) {
            for (JsonNode event : sequence.get("events")) {
                if (!"in".equals(event.path("dir").asText())
                        || !"Report_Attributes".equals(
                                event.path("frame").asText())) {
                    continue;
                }
                // Non-consecutive TSNs: fixture reports are distinct
                // observations, never the ×2 twin pattern.
                tsn += 2;
                pendingFrames.add(reportFrame(event, tsn));
                ingestion.processCycle();
                clock.advance(java.time.Duration.ofSeconds(1));
            }
        }

        List<StateReportedEvent> ct = reportsOf("color_temp_kelvin");
        assertThat(ct).extracting(StateReportedEvent::value)
                .as("K = 1,000,000 / mireds at ingestion, rounded to nearest — "
                        + "the ±1-mired drift rows (154/154/153) pin the rounding")
                .containsExactly("2237", "4525", "6494", "6494", "6536");
        assertThat(ct).extracting(StateReportedEvent::rawProtocolValue)
                .as("raw mireds retained (Doc 02 §3.7 auditability)")
                .containsExactly("447", "221", "154", "154", "153");
        assertThat(ct).allSatisfy(payload -> {
            assertThat(payload.unit()).isEqualTo("K");
            assertThat(payload.rawProtocolUnit()).isEqualTo("mired");
        });
        assertThat(reportsOf("on")).extracting(StateReportedEvent::value)
                .containsExactly("false", "true");
        assertThat(reportsOf("brightness")).extracting(StateReportedEvent::value)
                .containsExactly("22", "36", "13", "36", "128");
    }

    @Test
    @DisplayName("§J/Hue: the profile-matched characterizations render the recorded confirmability per capability")
    void hueRendersRecordedConfirmability() {
        DeviceProfile profile = registry.findProfile(hueInterview()).orElseThrow();

        assertThat(profile.profileId())
                .isEqualTo(MeasuredCorpusValues.HUE_PROFILE_ID);
        assertThat(profile.confirmation())
                .containsExactlyElementsOf(
                        MeasuredCorpusValues.HUE_CONFIRMATION_BLOCK);
        Map<String, Confirmability> byCapability = profile.confirmation().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConfirmationCharacterization::capability,
                        ConfirmationCharacterization::confirmability));
        assertThat(byCapability).containsExactlyInAnyOrderEntriesOf(Map.of(
                "on_off", Confirmability.CONFIRMABLE,
                "brightness", Confirmability.CONFIRMABLE,
                "color_temperature", Confirmability.CONFIRMABLE,
                "effect", Confirmability.UNCONFIRMABLE,
                "identify", Confirmability.UNCONFIRMABLE));
    }

    // ── fixture-event → ZCL frame translation ──────────────────────────────

    private static EzspFrame reportFrame(JsonNode event, int tsn) {
        List<byte[]> records = new ArrayList<>();
        if (event.has("attr")) {
            JsonNode attr = event.get("attr");
            records.add(attributeRecord(attr.get("name").asText(),
                    attr.get("value")));
        } else {
            event.get("attrs").fields().forEachRemaining(entry ->
                    records.add(attributeRecord(entry.getKey(),
                            entry.getValue())));
        }
        int length = 3;
        for (byte[] record : records) {
            length += record.length;
        }
        byte[] zcl = new byte[length];
        zcl[0] = 0x18;
        zcl[1] = (byte) tsn;
        zcl[2] = 0x0A;
        int offset = 3;
        for (byte[] record : records) {
            System.arraycopy(record, 0, zcl, offset, record.length);
            offset += record.length;
        }
        int cluster = clusterFor(firstName(event));
        return incomingFrame(HUE_NWK, 11, cluster, zcl);
    }

    private static String firstName(JsonNode event) {
        return event.has("attr") ? event.get("attr").get("name").asText()
                : event.get("attrs").fieldNames().next();
    }

    private static int clusterFor(String name) {
        return switch (name) {
            case "on_off" -> 0x0006;
            case "current_level" -> 0x0008;
            default -> 0x0300;
        };
    }

    private static byte[] attributeRecord(String name, JsonNode value) {
        return switch (name) {
            case "on_off" -> new byte[] {0x00, 0x00, 0x10,
                    (byte) (value.asBoolean() ? 1 : 0)};
            case "current_level" -> new byte[] {0x00, 0x00, 0x20,
                    (byte) value.asInt()};
            case "color_temperature" -> uint16Record(0x0007, value.asInt());
            case "current_x" -> uint16Record(0x0003, value.asInt());
            case "current_y" -> uint16Record(0x0004, value.asInt());
            case "current_hue" -> new byte[] {0x00, 0x00, 0x20,
                    (byte) value.asInt()};
            case "current_saturation" -> new byte[] {0x01, 0x00, 0x20,
                    (byte) value.asInt()};
            default -> throw new IllegalArgumentException(
                    "Fixture attribute '" + name + "' has no frame mapping");
        };
    }

    private static byte[] uint16Record(int attributeId, int value) {
        return new byte[] {
                (byte) (attributeId & 0xFF), (byte) ((attributeId >> 8) & 0xFF),
                0x21, (byte) (value & 0xFF), (byte) ((value >> 8) & 0xFF)};
    }

    private static EzspFrame incomingFrame(int sender, int srcEp, int cluster,
            byte[] message) {
        byte[] parameters = new byte[19 + message.length];
        parameters[0] = 0x00;
        parameters[1] = 0x04;
        parameters[2] = 0x01;
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

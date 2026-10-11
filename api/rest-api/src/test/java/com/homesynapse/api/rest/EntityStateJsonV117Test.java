/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.event.ProbeAnsweredEvent;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityLink;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.RecoveryCardQueryService;
import com.homesynapse.state.RecoveryCardRecord;
import com.homesynapse.state.StaleRecordBound;
import com.homesynapse.value.AttributeValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AVAIL-API-1 V-6 (and V-4's null state at the read surface) — the v1.1.7 keys on the three
 * reads: eleven camelCase keys beside {@code availability} (after the frozen v1.1.6 keys, never
 * under it) for the entity's owning device, every one {@code null} while the recovery-card
 * projection is not LIVE; the {@code probe*} keys null with no record, the contract keys null
 * with no contract, the two {@code record*} keys from {@link StaleRecordBound} (Q10);
 * {@code meta.recoveryViewPosition} after {@code viewPosition}, null while not LIVE. The golden
 * test: the existing keys byte-identical to the v1.1.6 response for the same entity.
 */
@DisplayName("EntityStateJson v1.1.7 — the recovery-card keys on the three reads (AVAIL-API-1 V-6)")
final class EntityStateJsonV117Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ENTITY_ULID = "01H8000000000000000000000A";
    private static final String DEVICE_ULID = "01H8000000000000000000000D";
    private static final EntityId ENTITY_ID = EntityId.of(Ulid.parse(ENTITY_ULID));
    private static final DeviceId DEVICE_ID = DeviceId.parse(DEVICE_ULID);
    private static final Instant REPORTED = Instant.parse("2026-10-11T11:00:00Z");
    private static final Instant PROBE_AT = Instant.parse("2026-10-11T11:30:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-10-11T10:00:00Z");
    private static final Instant CONTRACT_AT = Instant.parse("2026-10-11T09:00:00Z");

    static final List<String> V116_KEYS = List.of("entityId", "attributes", "availability",
            "stateVersion", "lastChanged", "lastUpdated", "lastReported", "staleAfter", "stale",
            "availabilityReason", "lastSeenAt", "link");
    static final List<String> V117_KEYS = List.of("probeAnsweredAt", "probeWindowStart",
            "probesAnswered", "probesMissed", "availabilityClass", "reportIntervalSeconds",
            "silenceLimitSeconds", "missesToDark", "recordIntervalSeconds", "recordDueBy",
            "recordOverdueSince");

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    EntityStateJsonV117Test() {
    }

    private static EntityState state() {
        return new EntityState(ENTITY_ID, Map.<String, AttributeValue>of(),
                Availability.AVAILABLE, 4L, Instant.parse("2026-10-11T10:59:00Z"),
                Instant.parse("2026-10-11T11:00:00Z"), REPORTED, null, false, "first_contact",
                REPORTED, new EntityLink(200, -45, REPORTED));
    }

    private static Entity entity() {
        return new Entity(ENTITY_ID, "entity-" + ENTITY_ULID, EntityType.LIGHT, "Entity",
                DEVICE_ID, 0, null, true, List.of(), List.of(), EntityRole.PRIMARY, Instant.EPOCH);
    }

    private static RecoveryCardRecord fullRecord() {
        return RecoveryCardRecord.empty(DEVICE_ID)
                .withProbe(new ProbeAnsweredEvent(PROBE_AT, PROBE_AT.minusSeconds(3600), 55, 1,
                        200, -45, PROBE_AT.minusSeconds(30)))
                .withContract(new AvailabilityContractDeclaredEvent("mains-metered", 600, 660, 2,
                        3600), CONTRACT_AT)
                .withAvailabilityChangedAt(CHANGED_AT);
    }

    @Test
    @DisplayName("V-6: the eleven keys follow the twelve v1.1.6 keys in order, rendered from the "
            + "record — instants ISO-8601 UTC, the class token, the bound from StaleRecordBound")
    void elevenKeysInOrder_renderedFromTheRecord() {
        FakeRecoveryCardQueryService cards = new FakeRecoveryCardQueryService().live(7L)
                .put(fullRecord()).asked(1);

        Map<String, Object> json = EntityStateJson.render(state(), DEVICE_ID, cards, NOW);

        List<String> expected = new ArrayList<>(V116_KEYS);
        expected.addAll(V117_KEYS);
        assertThat(json.keySet()).containsExactlyElementsOf(expected);
        assertThat(json)
                .containsEntry("probeAnsweredAt", "2026-10-11T11:30:00Z")
                .containsEntry("probeWindowStart", "2026-10-11T10:30:00Z")
                .containsEntry("probesAnswered", 55)
                .containsEntry("probesMissed", 1)
                .containsEntry("availabilityClass", "mains-metered")
                .containsEntry("reportIntervalSeconds", 600)
                .containsEntry("silenceLimitSeconds", 660)
                .containsEntry("missesToDark", 2)
                .containsEntry("recordIntervalSeconds", 3600);
        // The evidence: the newest of lastReported 11:00, probeAt 11:30, changed 10:00, contract
        // 09:00 → 11:30; N = 1: + 3600 + 660 + 10 + 0.05 s.
        assertThat(json).containsEntry("recordDueBy", "2026-10-11T12:41:10.050Z");
        assertThat(json).containsEntry("recordOverdueSince", null);
        assertThat(json).doesNotContainKey("probeLink");
    }

    @Test
    @DisplayName("V-6 / V-4: not LIVE → every v1.1.7 key present and null (the tri-state law); "
            + "recoveryViewPosition null")
    void notLive_everyKeyNull() {
        FakeRecoveryCardQueryService cards = new FakeRecoveryCardQueryService()
                .put(fullRecord()).asked(1);

        Map<String, Object> json = EntityStateJson.render(state(), DEVICE_ID, cards, NOW);

        assertThat(json.keySet()).containsAll(V117_KEYS);
        for (String key : V117_KEYS) {
            assertThat(json.get(key)).as(key).isNull();
        }
        assertThat(EntityStateJson.recoveryViewPosition(cards)).isNull();
    }

    @Test
    @DisplayName("V-6: LIVE with no record → the probe, contract and record keys null; with a probe "
            + "but no contract → the contract and the two record keys null")
    void partialRecords_nullWhereAbsent() {
        FakeRecoveryCardQueryService none = new FakeRecoveryCardQueryService().live(3L);
        Map<String, Object> noRecord = EntityStateJson.render(state(), DEVICE_ID, none, NOW);
        for (String key : V117_KEYS) {
            assertThat(noRecord.get(key)).as(key).isNull();
        }

        FakeRecoveryCardQueryService probeOnly = new FakeRecoveryCardQueryService().live(3L)
                .put(RecoveryCardRecord.empty(DEVICE_ID).withProbe(
                        new ProbeAnsweredEvent(PROBE_AT, PROBE_AT.minusSeconds(60), 1, 0, null,
                                null, null)));
        Map<String, Object> json = EntityStateJson.render(state(), DEVICE_ID, probeOnly, NOW);
        assertThat(json)
                .containsEntry("probeAnsweredAt", "2026-10-11T11:30:00Z")
                .containsEntry("probeWindowStart", "2026-10-11T11:29:00Z")
                .containsEntry("probesAnswered", 1)
                .containsEntry("probesMissed", 0)
                .containsEntry("availabilityClass", null)
                .containsEntry("recordIntervalSeconds", null)
                .containsEntry("recordDueBy", null)
                .containsEntry("recordOverdueSince", null);
        assertThat(EntityStateJson.recoveryViewPosition(probeOnly)).isEqualTo(3L);

        Map<String, Object> noDevice = EntityStateJson.render(state(), null, probeOnly, NOW);
        for (String key : V117_KEYS) {
            assertThat(noDevice.get(key)).as("an entity with no owning device: " + key).isNull();
        }
    }

    @Test
    @DisplayName("V-6 (Q10): recordOverdueSince is the evidence instant once now is past dueBy")
    void overdueSince_whenNowIsPastDueBy() {
        FakeRecoveryCardQueryService cards = new FakeRecoveryCardQueryService().live(7L)
                .put(fullRecord()).asked(1);
        Instant later = Instant.parse("2026-10-11T12:41:10.051Z");

        Map<String, Object> json = EntityStateJson.render(state(), DEVICE_ID, cards, later);

        assertThat(json).containsEntry("recordOverdueSince", "2026-10-11T11:30:00Z");
    }

    @Test
    @DisplayName("V-6: the three reads carry the keys beside availability and "
            + "meta.recoveryViewPosition after viewPosition and timestamp — LIVE: the position; "
            + "not LIVE: null")
    void threeReads_keysAndMeta() {
        FakeStateQueryService qs = new FakeStateQueryService().withViewPosition(12L)
                .withSnapshotTime(NOW).put(state());
        FakeEntityRegistry registry = new FakeEntityRegistry().put(entity());
        FakeRecoveryCardQueryService live = new FakeRecoveryCardQueryService().live(9L)
                .put(fullRecord()).asked(1);
        FakeRecoveryCardQueryService dark = new FakeRecoveryCardQueryService().put(fullRecord());

        for (FakeRecoveryCardQueryService cards : List.of(live, dark)) {
            Object expectedPosition = cards.isLive() ? Long.valueOf(9L) : null;

            RecordingEndpointContext list = new RecordingEndpointContext();
            new ListEntitiesEndpoint(qs, registry, qs::getViewPosition, cards, FIXED_CLOCK)
                    .apply(list);
            Map<String, Object> row = firstRow(list);
            assertThat(row.keySet()).containsSequence("availability", "stale", "deviceId",
                    "lastReported", "availabilityReason", "lastSeenAt", "link",
                    "probeAnsweredAt");
            assertThat(row.keySet()).endsWith(V117_KEYS.toArray(new String[0]));
            assertThat(row.get("availabilityClass"))
                    .isEqualTo(cards.isLive() ? "mains-metered" : null);
            assertMeta(list, expectedPosition);

            RecordingEndpointContext get = new RecordingEndpointContext()
                    .withPathParam("entityId", ENTITY_ULID);
            new GetEntityEndpoint(qs, qs::getViewPosition, registry, cards, FIXED_CLOCK).apply(get);
            assertThat(data(get).keySet()).endsWith(V117_KEYS.toArray(new String[0]));
            assertThat(data(get).get("probesAnswered")).isEqualTo(cards.isLive() ? 55 : null);
            assertMeta(get, expectedPosition);

            RecordingEndpointContext getState = new RecordingEndpointContext()
                    .withPathParam("entityId", ENTITY_ULID);
            new GetEntityStateEndpoint(qs, qs::getViewPosition, registry, cards, FIXED_CLOCK)
                    .apply(getState);
            assertThat(data(getState).keySet()).endsWith(V117_KEYS.toArray(new String[0]));
            assertThat(data(getState).get("recordDueBy"))
                    .isEqualTo(cards.isLive() ? "2026-10-11T12:41:10.050Z" : null);
            assertMeta(getState, expectedPosition);
        }
    }

    /** The v1.1.6 bytes of the list read for the fixture: the golden file (HEAD 9cf0ed9 renders it). */
    static final String GOLDEN_V116_LIST = "{\"data\":[{\"entityId\":\"01H8000000000000000000000A\",\"availability\":\"AVAILABLE\",\"stale\":false,\"deviceId\":\"01H8000000000000000000000D\",\"lastReported\":\"2026-10-11T11:00:00Z\",\"availabilityReason\":\"first_contact\",\"lastSeenAt\":\"2026-10-11T11:00:00Z\",\"link\":{\"lqi\":200,\"rssiDbm\":-45,\"at\":\"2026-10-11T11:00:00Z\"}}],\"meta\":{\"viewPosition\":12,\"timestamp\":\"2026-10-11T12:00:00Z\"}}";

    /** The v1.1.6 bytes of the two Get reads for the fixture: the golden file. */
    static final String GOLDEN_V116_RECORD = "{\"data\":{\"entityId\":\"01H8000000000000000000000A\",\"attributes\":{},\"availability\":\"AVAILABLE\",\"stateVersion\":4,\"lastChanged\":\"2026-10-11T10:59:00Z\",\"lastUpdated\":\"2026-10-11T11:00:00Z\",\"lastReported\":\"2026-10-11T11:00:00Z\",\"staleAfter\":null,\"stale\":false,\"availabilityReason\":\"first_contact\",\"lastSeenAt\":\"2026-10-11T11:00:00Z\",\"link\":{\"lqi\":200,\"rssiDbm\":-45,\"at\":\"2026-10-11T11:00:00Z\"}},\"meta\":{\"viewPosition\":12,\"timestamp\":\"2026-10-11T12:00:00Z\"}}";

    @Test
    @DisplayName("V-6 THE GOLDEN TEST: the v1.1.7 response with the eleven keys and recoveryViewPosition "
            + "removed is byte-identical to the pinned v1.1.6 bytes on all three reads; the "
            + "pre-AVAIL-API-1 constructors render the same frozen bytes with the keys null")
    void goldenTest_existingKeysByteIdentical() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FakeStateQueryService qs = new FakeStateQueryService().withViewPosition(12L)
                .withSnapshotTime(NOW).put(state());
        FakeEntityRegistry registry = new FakeEntityRegistry().put(entity());
        FakeRecoveryCardQueryService cards = new FakeRecoveryCardQueryService().live(9L)
                .put(fullRecord()).asked(1);

        RecordingEndpointContext list116 = new RecordingEndpointContext();
        new ListEntitiesEndpoint(qs, registry, qs::getViewPosition, FIXED_CLOCK).apply(list116);
        RecordingEndpointContext list117 = new RecordingEndpointContext();
        new ListEntitiesEndpoint(qs, registry, qs::getViewPosition, cards, FIXED_CLOCK)
                .apply(list117);
        assertThat(mapper.writeValueAsString(stripped(list117.body))).isEqualTo(GOLDEN_V116_LIST);
        assertThat(mapper.writeValueAsString(stripped(list116.body))).isEqualTo(GOLDEN_V116_LIST);
        assertThat(mapper.writeValueAsString(list116.body))
                .as("the pre-AVAIL-API-1 constructor: the keys present and null (the tri-state law)")
                .contains("\"probeAnsweredAt\":null").contains("\"recoveryViewPosition\":null");

        RecordingEndpointContext get116 = new RecordingEndpointContext()
                .withPathParam("entityId", ENTITY_ULID);
        new GetEntityEndpoint(qs, qs::getViewPosition, FIXED_CLOCK).apply(get116);
        RecordingEndpointContext get117 = new RecordingEndpointContext()
                .withPathParam("entityId", ENTITY_ULID);
        new GetEntityEndpoint(qs, qs::getViewPosition, registry, cards, FIXED_CLOCK).apply(get117);
        assertThat(mapper.writeValueAsString(stripped(get117.body))).isEqualTo(GOLDEN_V116_RECORD);
        assertThat(mapper.writeValueAsString(stripped(get116.body))).isEqualTo(GOLDEN_V116_RECORD);

        RecordingEndpointContext state116 = new RecordingEndpointContext()
                .withPathParam("entityId", ENTITY_ULID);
        new GetEntityStateEndpoint(qs, qs::getViewPosition, FIXED_CLOCK).apply(state116);
        RecordingEndpointContext state117 = new RecordingEndpointContext()
                .withPathParam("entityId", ENTITY_ULID);
        new GetEntityStateEndpoint(qs, qs::getViewPosition, registry, cards, FIXED_CLOCK)
                .apply(state117);
        assertThat(mapper.writeValueAsString(stripped(state117.body))).isEqualTo(GOLDEN_V116_RECORD);
        assertThat(mapper.writeValueAsString(stripped(state116.body))).isEqualTo(GOLDEN_V116_RECORD);
        assertThat(mapper.writeValueAsString(state117.body))
                .contains("\"recoveryViewPosition\":9")
                .contains("\"availabilityClass\":\"mains-metered\"");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRow(RecordingEndpointContext ctx) {
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertThat(data).hasSize(1);
        return data.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(RecordingEndpointContext ctx) {
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private static void assertMeta(RecordingEndpointContext ctx, Object expectedPosition) {
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        Map<String, Object> meta = (Map<String, Object>) body.get("meta");
        assertThat(meta.keySet()).containsExactly("viewPosition", "timestamp",
                "recoveryViewPosition");
        assertThat(meta.get("viewPosition")).isEqualTo(12L);
        assertThat(meta.get("recoveryViewPosition")).isEqualTo(expectedPosition);
    }

    /** The v1.1.7 body with the eleven keys and recoveryViewPosition removed — the v1.1.6 shape. */
    @SuppressWarnings("unchecked")
    private static Object stripped(Object body) {
        Map<String, Object> source = (Map<String, Object>) body;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if ("data".equals(entry.getKey())) {
                if (value instanceof List<?> rows) {
                    List<Object> strippedRows = new ArrayList<>();
                    for (Object row : rows) {
                        strippedRows.add(withoutKeys((Map<String, Object>) row, V117_KEYS));
                    }
                    out.put("data", strippedRows);
                } else {
                    out.put("data", withoutKeys((Map<String, Object>) value, V117_KEYS));
                }
            } else if ("meta".equals(entry.getKey())) {
                out.put("meta", withoutKeys((Map<String, Object>) value,
                        List.of("recoveryViewPosition")));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    private static Map<String, Object> withoutKeys(Map<String, Object> map, List<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>(map);
        keys.forEach(out::remove);
        return out;
    }

    /** The test fake: a LIVE flag, a position, the records, the asked count. */
    static final class FakeRecoveryCardQueryService implements RecoveryCardQueryService {
        private final Map<DeviceId, RecoveryCardRecord> records = new HashMap<>();
        private boolean live;
        private long position;
        private int asked;

        FakeRecoveryCardQueryService() {
        }

        FakeRecoveryCardQueryService live(long position) {
            this.live = true;
            this.position = position;
            return this;
        }

        FakeRecoveryCardQueryService put(RecoveryCardRecord record) {
            records.put(record.deviceId(), record);
            return this;
        }

        FakeRecoveryCardQueryService asked(int count) {
            this.asked = count;
            return this;
        }

        @Override
        public Optional<RecoveryCardRecord> recordFor(DeviceId deviceId) {
            return Optional.ofNullable(records.get(deviceId));
        }

        @Override
        public int askedCount() {
            return asked;
        }

        @Override
        public boolean isLive() {
            return live;
        }

        @Override
        public long position() {
            return position;
        }
    }
}

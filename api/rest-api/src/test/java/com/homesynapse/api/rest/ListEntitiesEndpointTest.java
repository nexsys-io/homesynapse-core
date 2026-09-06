/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.value.AttributeValue;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityState;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ListEntitiesEndpoint}.
 *
 * <p>Drives the endpoint's {@code apply(EndpointContext)} method directly
 * through a {@link RecordingEndpointContext} stub — same pattern as
 * {@link ReadinessFilterTest}. End-to-end HTTP coverage (real Jetty, real
 * client) lives in the lifecycle integration tests.</p>
 */
@DisplayName("ListEntitiesEndpoint")
final class ListEntitiesEndpointTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC);

    /** Three valid ULID strings, lexicographically ordered. */
    private static final String ULID_A = "01H8000000000000000000000A";
    private static final String ULID_B = "01H8000000000000000000000B";
    private static final String ULID_C = "01H8000000000000000000000C";

    /** A valid device ULID for the v1.1.3 deviceId correlation pin. */
    private static final String DEVICE_ULID = "01H8000000000000000000000D";

    ListEntitiesEndpointTest() {
    }

    @Test
    @DisplayName("returns 200 with empty data array when no entities exist")
    void returnsEmptyArrayWhenNoEntities() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(0L)
                .withSnapshotTime(FIXED_CLOCK.instant());
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, new FakeEntityRegistry(), qs::getViewPosition,
                        FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        assertThat(body).containsKey("data").containsKey("meta");
        @SuppressWarnings("unchecked")
        List<Object> data = (List<Object>) body.get("data");
        assertThat(data).isEmpty();
    }

    @Test
    @DisplayName("returns entities sorted ascending by entityId by default")
    void returnsSortedEntitiesAscByDefault() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(99L)
                .withSnapshotTime(FIXED_CLOCK.instant())
                .put(entity(ULID_C))
                .put(entity(ULID_A))
                .put(entity(ULID_B));
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, new FakeEntityRegistry(), qs::getViewPosition,
                        FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertThat(data).extracting(m -> m.get("entityId"))
                .containsExactly(ULID_A, ULID_B, ULID_C);
    }

    @Test
    @DisplayName("respects the limit query parameter")
    void respectsLimitParameter() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .put(entity(ULID_A))
                .put(entity(ULID_B))
                .put(entity(ULID_C));
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, new FakeEntityRegistry(), qs::getViewPosition,
                        FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withQueryParam("limit", "2");

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        List<Object> data = (List<Object>) body.get("data");
        assertThat(data).hasSize(2);
    }

    @Test
    @DisplayName("silently clamps limit to MAX_LIMIT (100)")
    void clampsLimitTo100() {
        FakeStateQueryService qs = new FakeStateQueryService();
        // Seed 105 entities — exceeds the cap on either side. ULID is 26
        // chars: "01H" (3) + 20 zeroes (20) + 3-digit suffix (3) = 26.
        for (int i = 0; i < 105; i++) {
            String suffix = String.format("%03d", i);
            qs.put(entity("01H00000000000000000000" + suffix));
        }
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, new FakeEntityRegistry(), qs::getViewPosition,
                        FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withQueryParam("limit", "999");

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        List<Object> data = (List<Object>) body.get("data");
        assertThat(data).hasSize(ListEntitiesEndpoint.MAX_LIMIT);
    }

    @Test
    @DisplayName("includes X-HomeSynapse-View-Position header")
    void includesViewPositionHeader() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(42L)
                .put(entity(ULID_A));
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, new FakeEntityRegistry(), qs::getViewPosition,
                        FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.headers).containsEntry(
                ListEntitiesEndpoint.VIEW_POSITION_HEADER, "42");
    }

    @Test
    @DisplayName("sort=DESC reverses the order (case-insensitive)")
    void sortDescReversesOrder() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .put(entity(ULID_A))
                .put(entity(ULID_B))
                .put(entity(ULID_C));
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, new FakeEntityRegistry(), qs::getViewPosition,
                        FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withQueryParam("sort", "desc");

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertThat(data).extracting(m -> m.get("entityId"))
                .containsExactly(ULID_C, ULID_B, ULID_A);
    }

    // ──────────────────────────────────────────────────────────────────
    // v1.1.3 (CG-2 / CG-3): deviceId + lastReported on the A1 rows
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("v1.1.3: a row carries exactly entityId, availability, stale, deviceId, lastReported — in order")
    void rows_v113Keys_inOrder() {
        FakeStateQueryService qs = new FakeStateQueryService().put(entity(ULID_A));
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, new FakeEntityRegistry(), qs::getViewPosition,
                        FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        // The LinkedHashMap order IS the wire order: the v1.1 base first, the two v1.1.3 keys appended.
        assertThat(rows(ctx).get(0).keySet())
                .containsExactly("entityId", "availability", "stale", "deviceId", "lastReported");
    }

    @Test
    @DisplayName("v1.1.3: deviceId is the registry's owning device — JSON null when unregistered or a helper")
    void deviceId_fromRegistry_andNullWhenUnregistered() {
        DeviceId device = DeviceId.parse(DEVICE_ULID);
        FakeEntityRegistry registry = new FakeEntityRegistry()
                .put(registered(ULID_A, device))
                .put(registered(ULID_C, null));   // a helper entity owns no device
        FakeStateQueryService qs = new FakeStateQueryService()
                .put(entity(ULID_A))
                .put(entity(ULID_B))                // B is in the projection but NOT in the registry
                .put(entity(ULID_C));
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, registry, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        List<Map<String, Object>> rows = rows(ctx);
        assertThat(rows).extracting(m -> m.get("entityId")).containsExactly(ULID_A, ULID_B, ULID_C);
        assertThat(rows).extracting(m -> m.get("deviceId")).containsExactly(DEVICE_ULID, null, null);
        // Null means PRESENT with JSON null — never an absent key.
        assertThat(rows).allSatisfy(row -> assertThat(row).containsKey("deviceId"));
    }

    @Test
    @DisplayName("v1.1.3: lastReported renders as an ISO-8601 string (never epoch seconds), or JSON null")
    void lastReported_iso8601_orNull() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .put(entityReportedAt(ULID_A, Instant.parse("2026-01-01T00:00:00Z")))
                .put(entityReportedAt(ULID_B, null));
        ListEntitiesEndpoint endpoint =
                new ListEntitiesEndpoint(qs, new FakeEntityRegistry(), qs::getViewPosition,
                        FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        List<Map<String, Object>> rows = rows(ctx);
        // A STRING in the same rendering as meta.timestamp — the dialect pin refuting the F-S8
        // epoch-seconds form the record-direct A2/A3 path still carries until Row 8 lands.
        assertThat(rows.get(0).get("lastReported")).isInstanceOf(String.class)
                .isEqualTo("2026-01-01T00:00:00Z");
        assertThat(rows.get(1)).containsEntry("lastReported", null);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static EntityState entity(String ulid) {
        return entityReportedAt(ulid, Instant.EPOCH);
    }

    /** An {@link EntityState} whose {@code lastReported} is the given instant (nullable). */
    private static EntityState entityReportedAt(String ulid, Instant lastReported) {
        return new EntityState(
                EntityId.of(Ulid.parse(ulid)),
                Map.<String, AttributeValue>of(),
                Availability.UNKNOWN,
                1L,
                Instant.EPOCH,
                Instant.EPOCH,
                lastReported,
                null,
                false);
    }

    /** A registry {@link Entity} for {@code ulid} owned by {@code deviceId} ({@code null} = helper). */
    private static Entity registered(String ulid, DeviceId deviceId) {
        return new Entity(EntityId.of(Ulid.parse(ulid)), "entity-" + ulid, EntityType.LIGHT,
                "Entity " + ulid, deviceId, 0, null, true, List.of(), List.of(),
                EntityRole.PRIMARY, Instant.EPOCH);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(RecordingEndpointContext ctx) {
        return (List<Map<String, Object>>) ((Map<String, Object>) ctx.body).get("data");
    }
}

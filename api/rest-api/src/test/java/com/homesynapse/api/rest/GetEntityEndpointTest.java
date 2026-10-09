/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.value.AttributeValue;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityLink;
import com.homesynapse.state.EntityState;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GetEntityEndpoint}.
 */
@DisplayName("GetEntityEndpoint")
final class GetEntityEndpointTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC);

    private static final String VALID_ULID = "01H8000000000000000000000A";

    GetEntityEndpointTest() {
    }

    @Test
    @DisplayName("returns 200 with the entity record for a known entityId")
    void returns200ForKnownEntity() {
        EntityState state = entity(VALID_ULID);
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(7L)
                .put(state);
        GetEntityEndpoint endpoint =
                new GetEntityEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        // IR-132 (CONFIG-ERROR-1): data is the renderer's map, never the record itself.
        assertThat(body.get("data")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data).containsEntry("entityId", VALID_ULID);
        assertThat(ctx.headers).containsEntry(
                ListEntitiesEndpoint.VIEW_POSITION_HEADER, "7");
    }

    @Test
    @DisplayName("returns 404 RFC 9457 problem when entity is not found")
    void returns404ForUnknownEntity() {
        FakeStateQueryService qs = new FakeStateQueryService();
        GetEntityEndpoint endpoint =
                new GetEntityEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
        // R-C (F-V2): an endpoint-level problem goes out as application/problem+json
        // (Doc 09 §3.8), not the application/json that Javalin's json(...) sets.
        assertThat(ctx.headers).containsEntry("Content-Type", "application/problem+json");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        assertThat(body)
                .containsEntry("type", ProblemType.NOT_FOUND.typeUri())
                .containsEntry("status", 404)
                .containsEntry("title", ProblemType.NOT_FOUND.title());
        assertThat(body.get("detail")).asString().contains(VALID_ULID);
    }

    @Test
    @DisplayName("returns 400 RFC 9457 problem for malformed entityId")
    void returns400ForMalformedEntityId() {
        FakeStateQueryService qs = new FakeStateQueryService();
        GetEntityEndpoint endpoint =
                new GetEntityEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", "not-a-ulid");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        assertThat(body)
                .containsEntry("type", ProblemType.INVALID_PARAMETERS.typeUri())
                .containsEntry("status", 400);
    }

    @Test
    @DisplayName("includes viewPosition in meta")
    void includesViewPositionInMeta() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(12_345L)
                .put(entity(VALID_ULID));
        GetEntityEndpoint endpoint =
                new GetEntityEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) body.get("meta");
        assertThat(meta).containsEntry("viewPosition", 12_345L);
    }

    @Test
    @DisplayName("J1 T9 (as IR-132 renders it): data carries availabilityReason, lastSeenAt and "
            + "link — through EntityStateJson, the instants ISO-8601 UTC, the link as the list "
            + "read's {lqi, rssiDbm, at}")
    void dataCarriesTheAvailabilityDetail() {
        Instant seen = Instant.parse("2026-10-03T12:00:00Z");
        EntityLink link = new EntityLink(200, -45, Instant.parse("2026-10-03T11:59:30Z"));
        EntityState dark = new EntityState(EntityId.of(Ulid.parse(VALID_ULID)),
                Map.<String, AttributeValue>of(), Availability.UNAVAILABLE, 2L,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, false,
                "ping_timeout", seen, link);
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(9L)
                .put(dark);
        GetEntityEndpoint endpoint =
                new GetEntityEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data)
                .containsEntry("availability", "UNAVAILABLE")
                .containsEntry("availabilityReason", "ping_timeout")
                .containsEntry("lastSeenAt", "2026-10-03T12:00:00Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> linkJson = (Map<String, Object>) data.get("link");
        assertThat(linkJson.keySet()).containsExactly("lqi", "rssiDbm", "at");
        assertThat(linkJson)
                .containsEntry("lqi", 200)
                .containsEntry("rssiDbm", -45)
                .containsEntry("at", "2026-10-03T11:59:30Z");
    }

    @Test
    @DisplayName("IR-132 (CONFIG-ERROR-1 R-1): every instant on data renders Instant.toString() "
            + "— lastChanged, lastUpdated, lastReported, staleAfter, lastSeenAt and link.at are "
            + "ISO-8601 UTC strings Instant.parse accepts (never epoch seconds); the record's "
            + "key order is kept; attributes pass through as the object they are")
    void everyInstantRendersIso8601_keyOrderKept() {
        Instant changed = Instant.parse("2026-10-08T02:00:58.784419723Z");
        Instant updated = Instant.parse("2026-10-08T02:01:00Z");
        Instant reported = Instant.parse("2026-10-08T02:01:30.5Z");
        Instant staleAfter = Instant.parse("2026-10-08T02:21:30.5Z");
        Instant seen = Instant.parse("2026-10-08T02:00:58.103646800Z");
        EntityLink link = new EntityLink(255, -40, Instant.parse("2026-10-08T02:00:58.103646800Z"));
        Map<String, AttributeValue> attributes = Map.of();
        EntityState state = new EntityState(EntityId.of(Ulid.parse(VALID_ULID)),
                attributes, Availability.AVAILABLE, 4L,
                changed, updated, reported, staleAfter, false,
                "first_contact", seen, link);
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(11L)
                .put(state);
        GetEntityEndpoint endpoint =
                new GetEntityEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.keySet()).containsExactly("entityId", "attributes", "availability",
                "stateVersion", "lastChanged", "lastUpdated", "lastReported", "staleAfter",
                "stale", "availabilityReason", "lastSeenAt", "link");
        assertThat(data)
                .containsEntry("entityId", VALID_ULID)
                .containsEntry("availability", "AVAILABLE")
                .containsEntry("stateVersion", 4L)
                .containsEntry("stale", false)
                .containsEntry("availabilityReason", "first_contact");
        assertThat(data.get("attributes")).isSameAs(attributes);
        assertThat(data.get("lastChanged")).isInstanceOf(String.class);
        assertThat(data.get("lastUpdated")).isInstanceOf(String.class);
        assertThat(data.get("lastReported")).isInstanceOf(String.class);
        assertThat(data.get("staleAfter")).isInstanceOf(String.class);
        assertThat(data.get("lastSeenAt")).isInstanceOf(String.class);
        assertThat(Instant.parse((String) data.get("lastChanged"))).isEqualTo(changed);
        assertThat(Instant.parse((String) data.get("lastUpdated"))).isEqualTo(updated);
        assertThat(Instant.parse((String) data.get("lastReported"))).isEqualTo(reported);
        assertThat(Instant.parse((String) data.get("staleAfter"))).isEqualTo(staleAfter);
        assertThat(Instant.parse((String) data.get("lastSeenAt"))).isEqualTo(seen);
        @SuppressWarnings("unchecked")
        Map<String, Object> linkJson = (Map<String, Object>) data.get("link");
        assertThat(linkJson.keySet()).containsExactly("lqi", "rssiDbm", "at");
        assertThat(linkJson).containsEntry("lqi", 255).containsEntry("rssiDbm", -40);
        assertThat(linkJson.get("at")).isInstanceOf(String.class);
        assertThat(Instant.parse((String) linkJson.get("at"))).isEqualTo(link.at());
    }

    private static EntityState entity(String ulid) {
        return new EntityState(
                EntityId.of(Ulid.parse(ulid)),
                Map.<String, AttributeValue>of(),
                Availability.AVAILABLE,
                1L,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                false, null, null, null);
    }
}

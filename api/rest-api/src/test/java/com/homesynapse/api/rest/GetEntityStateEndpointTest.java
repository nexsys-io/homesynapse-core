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
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GetEntityStateEndpoint}.
 */
@DisplayName("GetEntityStateEndpoint")
final class GetEntityStateEndpointTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC);

    private static final String VALID_ULID = "01H8000000000000000000000A";

    GetEntityStateEndpointTest() {
    }

    @Test
    @DisplayName("returns 200 with the full EntityState record")
    void returns200WithFullState() {
        // Attribute map intentionally contains a null value to exercise the
        // brief's "Map.copyOf and null attribute values" gotcha — the
        // handler must not call Map.copyOf, which would reject the null.
        Map<String, AttributeValue> attrs = new HashMap<>();
        attrs.put("brightness", null);
        EntityState state = new EntityState(
                EntityId.of(Ulid.parse(VALID_ULID)),
                attrs,
                Availability.AVAILABLE,
                2L,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                false, null, null, null);
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(5L)
                .put(state);
        GetEntityStateEndpoint endpoint =
                new GetEntityStateEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        // IR-132 (CONFIG-ERROR-1): data is the renderer's map; attributes pass through
        // as the object they are — the null-valued entry survives untouched.
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("attributes")).isSameAs(attrs);
        assertThat(attrs).containsKey("brightness");
        assertThat(attrs.get("brightness")).isNull();
    }

    @Test
    @DisplayName("returns 404 for unknown entity")
    void returns404ForUnknownEntity() {
        FakeStateQueryService qs = new FakeStateQueryService();
        GetEntityStateEndpoint endpoint =
                new GetEntityStateEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        assertThat(body).containsEntry("type", ProblemType.NOT_FOUND.typeUri());
    }

    @Test
    @DisplayName("stale field reflects MaterializedStateQueryService recomputation")
    void staleFieldReflectsCurrentTime() {
        // The handler does NOT recompute stale; MaterializedStateQueryService
        // does (and we trust its contract test). This test verifies the
        // handler faithfully returns whatever stale value the query service
        // produced — i.e., it does not overwrite or re-derive on its own.
        EntityState staleByQs = new EntityState(
                EntityId.of(Ulid.parse(VALID_ULID)),
                Map.<String, AttributeValue>of(),
                Availability.AVAILABLE,
                1L,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.parse("2026-05-22T11:00:00Z"), // past — so stale
                true, null, null, null);                                  // already true
        FakeStateQueryService qs = new FakeStateQueryService().put(staleByQs);
        GetEntityStateEndpoint endpoint =
                new GetEntityStateEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data)
                .containsEntry("stale", true)
                .containsEntry("staleAfter", "2026-05-22T11:00:00Z");
    }

    @Test
    @DisplayName("J1 T9 (as IR-132 renders it): data carries availabilityReason, lastSeenAt and "
            + "link exactly as the query service produced them — nulls included, present as "
            + "JSON null keys")
    void dataCarriesTheAvailabilityDetail_nullsIncluded() {
        Instant seen = Instant.parse("2026-10-03T12:00:00Z");
        EntityState dark = new EntityState(EntityId.of(Ulid.parse(VALID_ULID)),
                Map.<String, AttributeValue>of(), Availability.UNAVAILABLE, 3L,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, false,
                "silence_timeout", seen, null);
        FakeStateQueryService qs = new FakeStateQueryService().put(dark);
        GetEntityStateEndpoint endpoint =
                new GetEntityStateEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data)
                .containsEntry("availabilityReason", "silence_timeout")
                .containsEntry("lastSeenAt", "2026-10-03T12:00:00Z")
                .containsEntry("staleAfter", null)
                .containsEntry("link", null);
        assertThat(data.get("link")).as("no reading carried — a value, not an error").isNull();
    }

    @Test
    @DisplayName("IR-132 (CONFIG-ERROR-1 R-1): /entities/{id}/state renders every instant "
            + "Instant.toString() — lastChanged, lastUpdated, lastReported, staleAfter, "
            + "lastSeenAt and link.at are ISO-8601 UTC strings Instant.parse accepts (the BC8 "
            + "float 1791423298.1036468 is gone); the record's key order is kept")
    void everyInstantRendersIso8601_keyOrderKept() {
        Instant changed = Instant.parse("2026-10-08T02:00:58.784419723Z");
        Instant updated = Instant.parse("2026-10-08T02:01:00Z");
        Instant reported = Instant.parse("2026-10-08T02:01:30.5Z");
        Instant staleAfter = Instant.parse("2026-10-08T02:21:30.5Z");
        Instant seen = Instant.parse("2026-10-08T02:00:58.103646800Z");
        EntityLink link = new EntityLink(255, -40, Instant.parse("2026-10-08T02:00:58.103646800Z"));
        EntityState state = new EntityState(EntityId.of(Ulid.parse(VALID_ULID)),
                Map.<String, AttributeValue>of(), Availability.AVAILABLE, 4L,
                changed, updated, reported, staleAfter, false,
                "first_contact", seen, link);
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(11L)
                .put(state);
        GetEntityStateEndpoint endpoint =
                new GetEntityStateEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
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
}

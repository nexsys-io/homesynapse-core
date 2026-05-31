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
        assertThat(body.get("data")).isSameAs(state);
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
                false);
    }
}

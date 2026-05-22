/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.AttributeValue;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;
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
                false);
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
        EntityState returned = (EntityState) body.get("data");
        assertThat(returned.attributes()).containsKey("brightness");
        assertThat(returned.attributes().get("brightness")).isNull();
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
                true);                                  // already true
        FakeStateQueryService qs = new FakeStateQueryService().put(staleByQs);
        GetEntityStateEndpoint endpoint =
                new GetEntityStateEndpoint(qs, qs::getViewPosition, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", VALID_ULID);

        endpoint.apply(ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        EntityState returned = (EntityState) body.get("data");
        assertThat(returned.stale()).isTrue();
    }
}

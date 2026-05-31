/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.value.AttributeValue;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.ReadinessSource;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProjectionStatusEndpoint}.
 */
@DisplayName("ProjectionStatusEndpoint")
final class ProjectionStatusEndpointTest {

    private static final String ULID_A = "01H8000000000000000000000A";
    private static final String ULID_B = "01H8000000000000000000000B";

    ProjectionStatusEndpointTest() {
    }

    @Test
    @DisplayName("returns 200 with mode/viewPosition/entityCount/ready fields")
    void returns200WithProjectionStatus() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(123L)
                .put(entity(ULID_A))
                .put(entity(ULID_B));
        ReadinessSource readiness = () -> SubscriberMode.LIVE;
        ProjectionStatusEndpoint endpoint =
                new ProjectionStatusEndpoint(readiness, qs, qs::getViewPosition);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        assertThat(body)
                .containsEntry("mode", "LIVE")
                .containsEntry("viewPosition", 123L)
                .containsEntry("entityCount", 2)
                .containsEntry("ready", true);
    }

    @Test
    @DisplayName("responds 200 during REPLAY (not 503 — operational endpoint)")
    void respondsDuringReplay() {
        FakeStateQueryService qs = new FakeStateQueryService();
        ReadinessSource readiness = () -> SubscriberMode.REPLAY;
        ProjectionStatusEndpoint endpoint =
                new ProjectionStatusEndpoint(readiness, qs, qs::getViewPosition);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        assertThat(body)
                .containsEntry("mode", "REPLAY")
                .containsEntry("ready", false);
    }

    @Test
    @DisplayName("ready is true only when mode is LIVE")
    void readyTrueWhenLive() {
        FakeStateQueryService qs = new FakeStateQueryService();
        for (SubscriberMode mode : SubscriberMode.values()) {
            ReadinessSource readiness = () -> mode;
            ProjectionStatusEndpoint endpoint =
                    new ProjectionStatusEndpoint(readiness, qs, qs::getViewPosition);
            RecordingEndpointContext ctx = new RecordingEndpointContext();

            endpoint.apply(ctx);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) ctx.body;
            assertThat(body).containsEntry("ready", mode == SubscriberMode.LIVE);
        }
    }

    private static EntityState entity(String ulid) {
        return new EntityState(
                EntityId.of(Ulid.parse(ulid)),
                Map.<String, AttributeValue>of(),
                Availability.UNKNOWN,
                1L,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                false);
    }
}

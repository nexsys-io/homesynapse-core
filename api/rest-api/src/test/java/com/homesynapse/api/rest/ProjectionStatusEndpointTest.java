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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProjectionStatusEndpoint} — the M7.5c-a envelope
 * conformance (frozen v1.1.1 §A4, DRIFT-1 adjudication 2026-07-02).
 *
 * <p>The headline is the <strong>v1.1.1 shape test</strong>: the serialized
 * {@code /internal/projection} body must carry the {@code {data, meta}}
 * envelope with exactly the frozen A4 {@code data} fields
 * ({@code mode}, {@code viewPosition}, {@code lagEvents},
 * {@code projectionVersion}) plus the ruled additive extras
 * ({@code entityCount}, {@code ready}), and {@code meta.viewPosition} — the
 * dashboard's poll-cursor anchor (freeze §0).</p>
 */
@DisplayName("ProjectionStatusEndpoint")
final class ProjectionStatusEndpointTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final int PROJECTION_VERSION = 5;

    private static final String ULID_A = "01H8000000000000000000000A";
    private static final String ULID_B = "01H8000000000000000000000B";

    ProjectionStatusEndpointTest() {
    }

    @Test
    @DisplayName("serializes exactly the frozen v1.1.1 A4 envelope shape "
            + "(frozen fields + ruled additive extras)")
    void projection_v111ShapeTest() {
        FakeStateQueryService qs = new FakeStateQueryService()
                .withViewPosition(123L)
                .put(entity(ULID_A))
                .put(entity(ULID_B));
        ReadinessSource readiness = () -> SubscriberMode.LIVE;
        LongSupplier logHead = () -> 130L;
        ProjectionStatusEndpoint endpoint = new ProjectionStatusEndpoint(
                readiness, qs, qs::getViewPosition, logHead, PROJECTION_VERSION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat(ctx.headers).containsEntry(ListEntitiesEndpoint.VIEW_POSITION_HEADER, "123");
        assertThat(ctx.headers).containsEntry("ETag", "W/\"123\"");

        Map<String, Object> body = asMap(ctx.body);
        assertThat(body).containsOnlyKeys("data", "meta");

        Map<String, Object> data = asMap(body.get("data"));
        assertThat(data).containsOnlyKeys(
                "mode", "viewPosition", "lagEvents", "projectionVersion", "entityCount", "ready");
        assertThat(data).containsEntry("mode", "LIVE");
        assertThat(data).containsEntry("viewPosition", 123L);
        assertThat(data).containsEntry("lagEvents", 7L);
        assertThat(data).containsEntry("projectionVersion", PROJECTION_VERSION);
        assertThat(data).containsEntry("entityCount", 2);
        assertThat(data).containsEntry("ready", true);

        Map<String, Object> meta = asMap(body.get("meta"));
        assertThat(meta).containsOnlyKeys("viewPosition", "timestamp");
        assertThat(meta).containsEntry("viewPosition", 123L);
        assertThat(meta).containsEntry("timestamp", "2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("meta.viewPosition is present — the dashboard poll-cursor anchor (freeze §0)")
    void projection_metaViewPositionPresent() {
        FakeStateQueryService qs = new FakeStateQueryService().withViewPosition(42L);
        ProjectionStatusEndpoint endpoint = new ProjectionStatusEndpoint(
                () -> SubscriberMode.LIVE, qs, qs::getViewPosition,
                () -> 42L, PROJECTION_VERSION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        Map<String, Object> meta = asMap(asMap(ctx.body).get("meta"));
        assertThat(meta).containsEntry("viewPosition", 42L);
    }

    @Test
    @DisplayName("lagEvents is log head minus projection viewPosition")
    void projection_lagEvents_headMinusViewPosition() {
        FakeStateQueryService qs = new FakeStateQueryService().withViewPosition(100L);
        ProjectionStatusEndpoint endpoint = new ProjectionStatusEndpoint(
                () -> SubscriberMode.REPLAY, qs, qs::getViewPosition,
                () -> 250L, PROJECTION_VERSION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(asMap(asMap(ctx.body).get("data"))).containsEntry("lagEvents", 150L);
    }

    @Test
    @DisplayName("lagEvents clamps to 0 when the projection cursor samples ahead of the head")
    void projection_lagEvents_neverNegative() {
        FakeStateQueryService qs = new FakeStateQueryService().withViewPosition(10L);
        ProjectionStatusEndpoint endpoint = new ProjectionStatusEndpoint(
                () -> SubscriberMode.LIVE, qs, qs::getViewPosition,
                () -> 7L, PROJECTION_VERSION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(asMap(asMap(ctx.body).get("data"))).containsEntry("lagEvents", 0L);
    }

    @Test
    @DisplayName("responds 200 during REPLAY (not 503 — operational endpoint)")
    void respondsDuringReplay() {
        FakeStateQueryService qs = new FakeStateQueryService();
        ReadinessSource readiness = () -> SubscriberMode.REPLAY;
        ProjectionStatusEndpoint endpoint = new ProjectionStatusEndpoint(
                readiness, qs, qs::getViewPosition, () -> 0L, PROJECTION_VERSION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        Map<String, Object> data = asMap(asMap(ctx.body).get("data"));
        assertThat(data)
                .containsEntry("mode", "REPLAY")
                .containsEntry("ready", false);
    }

    @Test
    @DisplayName("ready is true only when mode is LIVE")
    void readyTrueWhenLive() {
        FakeStateQueryService qs = new FakeStateQueryService();
        for (SubscriberMode mode : SubscriberMode.values()) {
            ReadinessSource readiness = () -> mode;
            ProjectionStatusEndpoint endpoint = new ProjectionStatusEndpoint(
                    readiness, qs, qs::getViewPosition, () -> 0L, PROJECTION_VERSION, FIXED_CLOCK);
            RecordingEndpointContext ctx = new RecordingEndpointContext();

            endpoint.apply(ctx);

            Map<String, Object> data = asMap(asMap(ctx.body).get("data"));
            assertThat(data).containsEntry("ready", mode == SubscriberMode.LIVE);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
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

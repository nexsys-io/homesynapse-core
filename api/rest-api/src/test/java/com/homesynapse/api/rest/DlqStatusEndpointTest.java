/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.test.MinimalEventBusStub;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DlqStatusEndpoint} — the M7.5c-a envelope conformance
 * (frozen v1.1.1 §A5, DRIFT-1 adjudication 2026-07-02).
 *
 * <p>The headline is the <strong>v1.1.1 shape test</strong>: the serialized
 * {@code /internal/dlq} body must carry the {@code {data, meta}} envelope with
 * exactly the frozen A5 {@code data} fields ({@code depth},
 * {@code parkedSubscribers}) plus the retained additive per-subscriber
 * {@code subscribers} detail, and {@code meta.viewPosition} — the dashboard's
 * poll-cursor anchor (freeze §0).</p>
 *
 * <p>Uses {@link MinimalEventBusStub} from {@code event-bus} testFixtures —
 * the stub overrides {@link com.homesynapse.event.bus.EventBus#subscribers()
 * subscribers()} via its snapshot-list constructor; the other interface
 * methods inherit the stub's no-op defaults, and the endpoint must not
 * touch them.</p>
 */
@DisplayName("DlqStatusEndpoint")
final class DlqStatusEndpointTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LongSupplier VIEW_POSITION = () -> 42L;

    DlqStatusEndpointTest() {
    }

    @Test
    @DisplayName("serializes exactly the frozen v1.1.1 A5 envelope shape "
            + "(depth + parkedSubscribers + additive subscribers detail)")
    void dlq_v111ShapeTest() {
        MinimalEventBusStub bus = new MinimalEventBusStub(List.of(
                new SubscriberSnapshot(
                        "state_projection", SubscriberMode.LIVE, 100L, 0, 0, 0, null),
                new SubscriberSnapshot(
                        "automation", SubscriberMode.LIVE, 99L, 3, 0, 1,
                        Instant.parse("2026-01-01T00:00:05Z"))));
        DlqStatusEndpoint endpoint = new DlqStatusEndpoint(bus, VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat(ctx.headers).containsEntry(ListEntitiesEndpoint.VIEW_POSITION_HEADER, "42");
        assertThat(ctx.headers).containsEntry("ETag", "W/\"42\"");

        Map<String, Object> body = asMap(ctx.body);
        assertThat(body).containsOnlyKeys("data", "meta");

        Map<String, Object> data = asMap(body.get("data"));
        assertThat(data).containsOnlyKeys("depth", "parkedSubscribers", "subscribers");
        assertThat(data).containsEntry("depth", 3);
        @SuppressWarnings("unchecked")
        List<String> parked = (List<String>) data.get("parkedSubscribers");
        assertThat(parked).containsExactly("automation");

        List<?> entries = (List<?>) data.get("subscribers");
        assertThat(entries).hasSize(2);
        Map<String, Object> first = asMap(entries.get(0));
        assertThat(first).containsOnlyKeys(
                "subscriberId", "mode", "dlqDepth", "crashCount", "oldestParkedAt");
        assertThat(first)
                .containsEntry("subscriberId", "state_projection")
                .containsEntry("mode", "LIVE")
                .containsEntry("dlqDepth", 0)
                .containsEntry("crashCount", 0)
                .containsEntry("oldestParkedAt", null);
        Map<String, Object> second = asMap(entries.get(1));
        assertThat(second)
                .containsEntry("subscriberId", "automation")
                .containsEntry("dlqDepth", 3)
                .containsEntry("crashCount", 1)
                .containsEntry("oldestParkedAt", "2026-01-01T00:00:05Z");

        Map<String, Object> meta = asMap(body.get("meta"));
        assertThat(meta).containsOnlyKeys("viewPosition", "timestamp");
        assertThat(meta).containsEntry("viewPosition", 42L);
        assertThat(meta).containsEntry("timestamp", "2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("meta.viewPosition is present — the dashboard poll-cursor anchor (freeze §0)")
    void dlq_metaViewPositionPresent() {
        MinimalEventBusStub bus = new MinimalEventBusStub(List.of());
        DlqStatusEndpoint endpoint = new DlqStatusEndpoint(bus, VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        Map<String, Object> meta = asMap(asMap(ctx.body).get("meta"));
        assertThat(meta).containsEntry("viewPosition", 42L);
    }

    @Test
    @DisplayName("depth sums per-subscriber DLQ depths; parkedSubscribers lists only "
            + "subscribers with parked entries")
    void dlq_depthAndParkedDerivation() {
        MinimalEventBusStub bus = new MinimalEventBusStub(List.of(
                new SubscriberSnapshot(
                        "state_projection", SubscriberMode.LIVE, 100L, 2, 0, 0,
                        Instant.parse("2026-01-01T00:00:01Z")),
                new SubscriberSnapshot(
                        "automation_engine", SubscriberMode.LIVE, 99L, 0, 0, 0, null),
                new SubscriberSnapshot(
                        "pending_command_ledger", SubscriberMode.SUSPENDED, 98L, 5, 0, 2,
                        Instant.parse("2026-01-01T00:00:02Z"))));
        DlqStatusEndpoint endpoint = new DlqStatusEndpoint(bus, VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        Map<String, Object> data = asMap(asMap(ctx.body).get("data"));
        assertThat(data).containsEntry("depth", 7);
        @SuppressWarnings("unchecked")
        List<String> parked = (List<String>) data.get("parkedSubscribers");
        assertThat(parked).containsExactly("state_projection", "pending_command_ledger");
    }

    @Test
    @DisplayName("responds 200 during REPLAY (not 503 — operational endpoint)")
    void respondsDuringReplay() {
        MinimalEventBusStub bus = new MinimalEventBusStub(List.of(
                new SubscriberSnapshot(
                        "state_projection", SubscriberMode.REPLAY, 50L, 0, 0, 0, null)));
        DlqStatusEndpoint endpoint = new DlqStatusEndpoint(bus, VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        List<?> entries = (List<?>) asMap(asMap(ctx.body).get("data")).get("subscribers");
        assertThat(entries).hasSize(1);
        assertThat(asMap(entries.get(0))).containsEntry("mode", "REPLAY");
    }

    @Test
    @DisplayName("empty subscriber set yields depth 0, empty arrays, and the full envelope")
    void emptySubscribersStillEnveloped() {
        MinimalEventBusStub bus = new MinimalEventBusStub(List.of());
        DlqStatusEndpoint endpoint = new DlqStatusEndpoint(bus, VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        Map<String, Object> body = asMap(ctx.body);
        assertThat(body).containsOnlyKeys("data", "meta");
        Map<String, Object> data = asMap(body.get("data"));
        assertThat(data).containsEntry("depth", 0);
        assertThat((List<?>) data.get("parkedSubscribers")).isEmpty();
        assertThat((List<?>) data.get("subscribers")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.test.MinimalEventBusStub;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DlqStatusEndpoint}.
 *
 * <p>Uses {@link MinimalEventBusStub} from {@code event-bus} testFixtures —
 * the stub overrides {@link com.homesynapse.event.bus.EventBus#subscribers()
 * subscribers()} via its snapshot-list constructor; the other interface
 * methods inherit the stub's no-op defaults, and the endpoint must not
 * touch them.</p>
 */
@DisplayName("DlqStatusEndpoint")
final class DlqStatusEndpointTest {

    DlqStatusEndpointTest() {
    }

    @Test
    @DisplayName("returns 200 with one entry per registered subscriber")
    void returns200WithSubscriberDlqStatus() {
        MinimalEventBusStub bus = new MinimalEventBusStub(List.of(
                new SubscriberSnapshot(
                        "state_projection", SubscriberMode.LIVE, 100L, 0, 0, null),
                new SubscriberSnapshot(
                        "automation", SubscriberMode.LIVE, 99L, 3, 1, null)));
        DlqStatusEndpoint endpoint = new DlqStatusEndpoint(bus);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) body.get("subscribers");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0))
                .containsEntry("subscriberId", "state_projection")
                .containsEntry("mode", "LIVE")
                .containsEntry("dlqDepth", 0)
                .containsEntry("crashCount", 0);
        assertThat(entries.get(1))
                .containsEntry("subscriberId", "automation")
                .containsEntry("dlqDepth", 3)
                .containsEntry("crashCount", 1);
    }

    @Test
    @DisplayName("responds 200 during REPLAY (not 503 — operational endpoint)")
    void respondsDuringReplay() {
        MinimalEventBusStub bus = new MinimalEventBusStub(List.of(
                new SubscriberSnapshot(
                        "state_projection", SubscriberMode.REPLAY, 50L, 0, 0, null)));
        DlqStatusEndpoint endpoint = new DlqStatusEndpoint(bus);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) body.get("subscribers");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("mode", "REPLAY");
    }

    @Test
    @DisplayName("returns empty subscribers array when no subscribers registered")
    void emptySubscribersArrayWhenNoneRegistered() {
        MinimalEventBusStub bus = new MinimalEventBusStub(List.of());
        DlqStatusEndpoint endpoint = new DlqStatusEndpoint(bus);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.body;
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) body.get("subscribers");
        assertThat(entries).isEmpty();
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DlqStatusEndpoint}.
 *
 * <p>Uses a stub {@link EventBus} implementation that overrides only
 * {@link EventBus#subscribers()} (the single method the endpoint
 * consumes). The other interface methods inherit their default
 * {@code UnsupportedOperationException} bodies — the endpoint must not
 * touch them, and the test confirms this implicitly.</p>
 */
@DisplayName("DlqStatusEndpoint")
final class DlqStatusEndpointTest {

    DlqStatusEndpointTest() {
    }

    @Test
    @DisplayName("returns 200 with one entry per registered subscriber")
    void returns200WithSubscriberDlqStatus() {
        StubBus bus = new StubBus(List.of(
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
        StubBus bus = new StubBus(List.of(
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
        StubBus bus = new StubBus(List.of());
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

    /**
     * Minimal {@link EventBus} test double — overrides only the methods the
     * endpoint actually calls. Other defaults stay at
     * {@code UnsupportedOperationException}.
     */
    private static final class StubBus implements EventBus {
        private final List<SubscriberSnapshot> snapshots;

        StubBus(List<SubscriberSnapshot> snapshots) {
            this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        }

        @Override
        public List<SubscriberSnapshot> subscribers() {
            return List.copyOf(snapshots);
        }

        @Override
        public void subscribe(SubscriberInfo subscriber) {
            throw new UnsupportedOperationException("subscribe");
        }

        @Override
        public void unsubscribe(String subscriberId) {
            throw new UnsupportedOperationException("unsubscribe");
        }

        @Override
        public void notifyEvent(long globalPosition) {
            throw new UnsupportedOperationException("notifyEvent");
        }

        @Override
        public long subscriberPosition(String subscriberId) {
            throw new UnsupportedOperationException("subscriberPosition");
        }
    }
}

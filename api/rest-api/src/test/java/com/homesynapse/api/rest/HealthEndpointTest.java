/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.state.ReadinessSource;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HealthEndpoint} (R-9 / E3-HEALTH, 2026-08-22) — the
 * unauthenticated loopback readiness bit. Drives the pure {@code apply} logic
 * through a {@link RecordingEndpointContext} over a mode-flipping
 * {@link ReadinessSource}. The 503 matrix names EVERY non-LIVE constant (rider
 * R-9-a) and the first test pins the constant set itself, so a new
 * {@link SubscriberMode} value fails here before it can slip past the probe.
 * The wire proof (real Jetty, a headerless client, HEAD, the guarded
 * neighbours) lives in {@code HomeSynapseCoreTest}.
 */
@DisplayName("HealthEndpoint -- GET/HEAD /health (the readiness bit)")
final class HealthEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The four non-LIVE constants (rider R-9-a): each one is a 503. */
    private static final EnumSet<SubscriberMode> NOT_READY = EnumSet.of(
            SubscriberMode.COLD, SubscriberMode.REPLAY,
            SubscriberMode.TRANSITION, SubscriberMode.SUSPENDED);

    private final AtomicReference<SubscriberMode> mode =
            new AtomicReference<>(SubscriberMode.LIVE);
    private final HealthEndpoint endpoint = new HealthEndpoint(mode::get);

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    HealthEndpointTest() {
    }

    @Test
    @DisplayName("the 503 matrix names EVERY SubscriberMode constant — a new constant fails here first")
    void subscriberModeSetIsPinned() {
        EnumSet<SubscriberMode> expected = EnumSet.copyOf(NOT_READY);
        expected.add(SubscriberMode.LIVE);

        assertThat(SubscriberMode.values()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("LIVE → 200, body exactly {\"status\":\"LIVE\"}, Cache-Control: no-store, no readiness headers")
    void liveIs200WithTheOneKeyBody() throws Exception {
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat(ctx.headers).containsEntry("Cache-Control", "no-store");
        assertThat(ctx.headers).doesNotContainKeys(
                ReadinessFilter.PROJECTION_STATE_HEADER, "Retry-After");
        assertThat(asMap(ctx.body)).containsExactly(Map.entry("status", "LIVE"));
        assertThat(MAPPER.writeValueAsString(ctx.body)).isEqualTo("{\"status\":\"LIVE\"}");
    }

    @Test
    @DisplayName("COLD / REPLAY / TRANSITION / SUSPENDED → 503 + X-HomeSynapse-Projection-State "
            + "+ Retry-After: 5 + no-store + {\"status\":<mode>} — the gate's own vocabulary")
    void everyNonLiveModeIs503WithTheReadinessHeaders() throws Exception {
        for (SubscriberMode notReady : NOT_READY) {
            mode.set(notReady);
            RecordingEndpointContext ctx = new RecordingEndpointContext();

            endpoint.apply(ctx);

            assertThat(ctx.statusSet).as("%s", notReady).isEqualTo(503);
            assertThat(ctx.headers).as("%s", notReady)
                    .containsEntry(ReadinessFilter.PROJECTION_STATE_HEADER, notReady.name())
                    .containsEntry("Retry-After", ReadinessFilter.RETRY_AFTER_SECONDS)
                    .containsEntry("Cache-Control", "no-store");
            assertThat(asMap(ctx.body)).as("%s", notReady)
                    .containsExactly(Map.entry("status", notReady.name()));
            assertThat(MAPPER.writeValueAsString(ctx.body)).as("%s", notReady)
                    .isEqualTo("{\"status\":\"" + notReady.name() + "\"}");
        }
    }

    @Test
    @DisplayName("the source is read on every call — no caching: REPLAY → LIVE → SUSPENDED on one handler")
    void modeIsReadPerCall() {
        mode.set(SubscriberMode.REPLAY);
        RecordingEndpointContext first = new RecordingEndpointContext();
        endpoint.apply(first);
        mode.set(SubscriberMode.LIVE);
        RecordingEndpointContext second = new RecordingEndpointContext();
        endpoint.apply(second);
        mode.set(SubscriberMode.SUSPENDED);
        RecordingEndpointContext third = new RecordingEndpointContext();
        endpoint.apply(third);

        assertThat(first.statusSet).isEqualTo(503);
        assertThat(second.statusSet).isEqualTo(200);
        assertThat(third.statusSet).isEqualTo(503);
        assertThat(third.headers)
                .containsEntry(ReadinessFilter.PROJECTION_STATE_HEADER, "SUSPENDED");
    }

    @Test
    @DisplayName("a null ReadinessSource is rejected at construction")
    void constructorRejectsNull() {
        assertThatThrownBy(() -> new HealthEndpoint(null))
                .isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}

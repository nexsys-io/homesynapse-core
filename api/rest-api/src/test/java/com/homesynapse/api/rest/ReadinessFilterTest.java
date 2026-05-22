/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.bus.SubscriberMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReadinessFilter} — the Javalin {@code before} handler that
 * gates {@code /api/*} traffic until the State Projection reaches
 * {@link SubscriberMode#LIVE}.
 *
 * <p>Javalin's {@code io.javalin.http.Context} is a thick interface with
 * roughly eighty methods. Mocking it requires either a heavy stub
 * implementation or pulling in {@code javalin-testtools} (which spins up a
 * real Jetty server per test). Neither is appropriate for unit-level
 * coverage of this small filter. {@link ReadinessFilter} therefore
 * factors the response side into a narrow {@link ReadinessFilter.Responder}
 * SPI, and the {@code handle(Context)} method wraps the supplied
 * {@code Context} in the production adapter
 * ({@link ReadinessFilter.ContextResponder}) before delegating to the
 * package-private {@link ReadinessFilter#apply} method. Tests below exercise
 * {@link ReadinessFilter#apply} directly through a
 * {@link RecordingResponder} stub, which captures the status, headers, and
 * JSON body the filter writes.</p>
 *
 * <p>This is the alternative test approach invited by the M3.6e.1
 * "Coder Pushback Welcome" section. End-to-end HTTP coverage of the filter
 * (real Jetty, real client) lives in the lifecycle integration test for
 * {@code HomeSynapseCore}, which is M3.6e.2 / M3.7 scope per the brief.</p>
 */
@DisplayName("ReadinessFilter")
final class ReadinessFilterTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ReadinessFilterTest() {
    }

    @Nested
    @DisplayName("LIVE — request passes through")
    final class LiveBehavior {

        /** Explicit no-arg constructor. */
        LiveBehavior() {
        }

        @Test
        @DisplayName("allows the request when readiness source reports LIVE")
        void allowsRequestWhenLive() {
            ReadinessFilter filter = filterFor(SubscriberMode.LIVE);
            RecordingResponder responder = new RecordingResponder();

            boolean rejected = filter.apply(responder);

            assertThat(rejected).isFalse();
            assertThat(responder.statusSet).isNull();
            assertThat(responder.headers).isEmpty();
            assertThat(responder.body).isNull();
        }
    }

    @Nested
    @DisplayName("Non-LIVE — request gets 503")
    final class NonLiveBehavior {

        /** Explicit no-arg constructor. */
        NonLiveBehavior() {
        }

        @Test
        @DisplayName("rejects with 503 when mode is REPLAY (with diagnostic headers)")
        void rejects503WhenReplaying() {
            ReadinessFilter filter = filterFor(SubscriberMode.REPLAY);
            RecordingResponder responder = new RecordingResponder();

            boolean rejected = filter.apply(responder);

            assertThat(rejected).isTrue();
            assertThat(responder.statusSet).isEqualTo(503);
            assertThat(responder.headers)
                    .containsEntry("X-HomeSynapse-Projection-State", "REPLAY")
                    .containsEntry("Retry-After", "5");
        }

        @Test
        @DisplayName("rejects with 503 when mode is COLD")
        void rejects503WhenCold() {
            ReadinessFilter filter = filterFor(SubscriberMode.COLD);
            RecordingResponder responder = new RecordingResponder();

            filter.apply(responder);

            assertThat(responder.statusSet).isEqualTo(503);
            assertThat(responder.headers).containsEntry(
                    "X-HomeSynapse-Projection-State", "COLD");
        }

        @Test
        @DisplayName("rejects with 503 when mode is TRANSITION")
        void rejects503WhenInTransition() {
            ReadinessFilter filter = filterFor(SubscriberMode.TRANSITION);
            RecordingResponder responder = new RecordingResponder();

            filter.apply(responder);

            assertThat(responder.statusSet).isEqualTo(503);
            assertThat(responder.headers).containsEntry(
                    "X-HomeSynapse-Projection-State", "TRANSITION");
        }

        @Test
        @DisplayName("response body contains RFC 9457 problem detail fields")
        void responseBodyContainsProblemDetail() {
            ReadinessFilter filter = filterFor(SubscriberMode.REPLAY);
            RecordingResponder responder = new RecordingResponder();

            filter.apply(responder);

            assertThat(responder.body)
                    .isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) responder.body;
            assertThat(body)
                    .containsEntry("type", ProblemType.STATE_STORE_REPLAYING.typeUri())
                    .containsEntry("status", 503)
                    .containsEntry("title", ProblemType.STATE_STORE_REPLAYING.title());
            assertThat(body.get("detail"))
                    .asString()
                    .contains("REPLAY");
        }
    }

    @Nested
    @DisplayName("Mode is read on every request")
    final class FreshModeRead {

        /** Explicit no-arg constructor. */
        FreshModeRead() {
        }

        @Test
        @DisplayName("re-reads readiness source per invocation — transitions are observed")
        void readsModeOnEveryCall() {
            AtomicReference<SubscriberMode> mode =
                    new AtomicReference<>(SubscriberMode.REPLAY);
            ReadinessFilter filter = new ReadinessFilter(mode::get);

            RecordingResponder r1 = new RecordingResponder();
            assertThat(filter.apply(r1)).isTrue();

            mode.set(SubscriberMode.LIVE);
            RecordingResponder r2 = new RecordingResponder();
            assertThat(filter.apply(r2)).isFalse();
            assertThat(r2.statusSet).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static ReadinessFilter filterFor(SubscriberMode mode) {
        return new ReadinessFilter(() -> mode);
    }

    /**
     * Test stub for {@link ReadinessFilter.Responder} — records every call so
     * tests can make precise structural assertions.
     */
    private static final class RecordingResponder implements ReadinessFilter.Responder {
        Integer statusSet;
        final Map<String, String> headers = new LinkedHashMap<>();
        final List<Object> jsonCalls = new ArrayList<>();
        Object body;

        RecordingResponder() {
        }

        @Override
        public void status(int status) {
            this.statusSet = status;
        }

        @Override
        public void header(String name, String value) {
            this.headers.put(name, value);
        }

        @Override
        public void json(Object body) {
            this.jsonCalls.add(body);
            this.body = body;
        }
    }
}

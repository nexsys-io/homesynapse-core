/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.api.rest.PairingWindowPort.PairingWindowView;
import com.homesynapse.platform.identity.IntegrationId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T6 (PJ-2) — {@link PermitJoinEndpoint}: {@code POST
 * /api/v1/integrations/{integrationId}/permit-join}. Drives the pure
 * {@code apply(EndpointContext, ApiKeyIdentity)} through a {@link RecordingEndpointContext}
 * (the {@link IssueCommandEndpointTest} pattern) over a port stub returning completed,
 * failed, or never-completing futures. The endpoint publishes NOTHING — the adapter owns
 * the events; the port is the only collaborator.
 *
 * <p>The port timeout is injected through the package-private constructor (50 ms here —
 * a test clock cannot expire a blocking {@code get}, so the never-completing case waits a
 * real 50 ms).</p>
 */
@DisplayName("PermitJoinEndpoint")
final class PermitJoinEndpointTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String INTEGRATION_ULID = "01H8000000000000000000000C";
    private static final IntegrationId INTEGRATION_ID = IntegrationId.parse(INTEGRATION_ULID);
    private static final ApiKeyIdentity CALLER =
            new ApiKeyIdentity("key-01", "operator laptop", NOW.minusSeconds(3600));
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);
    private static final String VALID_BODY =
            "{\"durationSeconds\":120,\"reason\":\"pair the hallway sensor\"}";

    PermitJoinEndpointTest() {
    }

    // ── 200 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("200: the six data keys in order, the actor from the identity attribute, "
            + "ISO-8601 instants, meta.timestamp from the clock, Cache-Control: no-store")
    void happyPath_200_sixKeys_actorFromIdentity() {
        RecordingPort port = RecordingPort.completing(NOW, NOW.plusSeconds(120), 120,
                "pair the hallway sensor", "key-01");
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpointOver(port).apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(200);
        Map<String, Object> body = bodyOf(ctx);
        assertThat(body.keySet()).containsExactly("data", "meta");
        Map<String, Object> data = section(body, "data");
        assertThat(data.keySet()).containsExactly(
                "integrationId", "durationSeconds", "reason", "actor", "opensAt", "closesAt");
        assertThat(data.get("integrationId")).isEqualTo(INTEGRATION_ULID);
        assertThat(data.get("durationSeconds")).isEqualTo(120);
        assertThat(data.get("reason")).isEqualTo("pair the hallway sensor");
        assertThat(data.get("actor")).isEqualTo("key-01");
        assertThat(data.get("opensAt")).isEqualTo("2026-09-28T12:00:00Z");
        assertThat(data.get("closesAt")).isEqualTo("2026-09-28T12:02:00Z");
        Map<String, Object> meta = section(body, "meta");
        assertThat(meta.keySet()).containsExactly("timestamp");
        assertThat(meta.get("timestamp")).isEqualTo("2026-09-28T12:00:00Z");
        assertThat(ctx.headers).containsEntry("Cache-Control", "no-store");
        assertThat(port.calls).hasSize(1);
        assertThat(port.calls.get(0).integrationId()).isEqualTo(INTEGRATION_ID);
        assertThat(port.calls.get(0).durationSeconds()).isEqualTo(120);
        assertThat(port.calls.get(0).reason()).isEqualTo("pair the hallway sensor");
        assertThat(port.calls.get(0).actor()).as("the actor is the caller's keyId")
                .isEqualTo("key-01");
    }

    // ── 400: the body ────────────────────────────────────────────────────

    @Test
    @DisplayName("400: durationSeconds 0 (below 1) — the port is never called")
    void durationZero_400() {
        assertBadDuration("{\"durationSeconds\":0,\"reason\":\"pair\"}");
    }

    @Test
    @DisplayName("400: durationSeconds 255 (above 254)")
    void duration255_400() {
        assertBadDuration("{\"durationSeconds\":255,\"reason\":\"pair\"}");
    }

    @Test
    @DisplayName("400: durationSeconds missing")
    void durationMissing_400() {
        assertBadDuration("{\"reason\":\"pair\"}");
    }

    @Test
    @DisplayName("400: durationSeconds as a JSON string (\"120\") is not an integer")
    void durationString_400() {
        assertBadDuration("{\"durationSeconds\":\"120\",\"reason\":\"pair\"}");
    }

    @Test
    @DisplayName("400: reason blank")
    void reasonBlank_400() {
        assertBadReason("{\"durationSeconds\":120,\"reason\":\"   \"}");
    }

    @Test
    @DisplayName("400: reason of 121 characters (above 120)")
    void reason121_400() {
        assertBadReason("{\"durationSeconds\":120,\"reason\":\"" + "r".repeat(121) + "\"}");
    }

    @Test
    @DisplayName("400: a body that is not JSON, and a body that is not an object")
    void bodyNotJsonOrNotObject_400() {
        RecordingPort port = RecordingPort.completing(NOW, NOW.plusSeconds(120), 120, "x", "k");
        RecordingEndpointContext notJson = post("{durationSeconds");
        endpointOver(port).apply(notJson, CALLER);
        assertThat(notJson.statusSet).isEqualTo(400);
        assertThat(bodyOf(notJson).get("title")).isEqualTo("Invalid Parameters");

        RecordingEndpointContext notObject = post("[120]");
        endpointOver(port).apply(notObject, CALLER);
        assertThat(notObject.statusSet).isEqualTo(400);
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("400: a malformed {integrationId} path parameter (never 500)")
    void integrationIdMalformed_400() {
        RecordingPort port = RecordingPort.completing(NOW, NOW.plusSeconds(120), 120, "x", "k");
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("integrationId", "not-a-ulid")
                .withBody(VALID_BODY);

        endpointOver(port).apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Invalid Parameters");
        assertThat(String.valueOf(bodyOf(ctx).get("detail"))).contains("integrationId");
        assertThat(port.calls).isEmpty();
    }

    // ── 401 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("401: a null caller (the auth filter did not run) — nothing reaches the port")
    void nullCaller_401() {
        RecordingPort port = RecordingPort.completing(NOW, NOW.plusSeconds(120), 120, "x", "k");
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpointOver(port).apply(ctx, null);

        assertThat(ctx.statusSet).isEqualTo(401);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Authentication Required");
        assertThat(port.calls).isEmpty();
    }

    // ── the port's failures ──────────────────────────────────────────────

    @Test
    @DisplayName("503 Integration Unhealthy: a future failed with IllegalStateException "
            + "(not running / no supervisor yet), the detail carrying the message")
    void failedIllegalState_503() {
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpointOver(RecordingPort.failing(
                new IllegalStateException("integration not running: " + INTEGRATION_ULID)))
                .apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(503);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Integration Unhealthy");
        assertThat(String.valueOf(bodyOf(ctx).get("detail")))
                .contains("integration not running: " + INTEGRATION_ULID);
    }

    @Test
    @DisplayName("409 Pairing Window Unsupported: a future failed with "
            + "UnsupportedOperationException")
    void failedUnsupported_409() {
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpointOver(RecordingPort.failing(
                new UnsupportedOperationException("plain has no pairing window")))
                .apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(409);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Pairing Window Unsupported");
        assertThat(bodyOf(ctx).get("type"))
                .isEqualTo("https://homesynapse.local/problems/pairing-window-unsupported");
        assertThat(String.valueOf(bodyOf(ctx).get("detail"))).contains("plain has no pairing window");
    }

    @Test
    @DisplayName("400: a future failed with IllegalArgumentException (the record's bound)")
    void failedIllegalArgument_400() {
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpointOver(RecordingPort.failing(
                new IllegalArgumentException("durationSeconds must be between 1 and 254")))
                .apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Invalid Parameters");
    }

    @Test
    @DisplayName("503: any other adapter throw (an NCP NAK) — the class name and message in "
            + "the detail, never a stack, never 500 (the integration is the unhealthy party)")
    void failedOtherThrow_503_classNameAndMessage() {
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpointOver(RecordingPort.failing(
                new RuntimeException("EZSP setPolicy(trustCenterPolicy) failed: status=0x01")))
                .apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(503);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Integration Unhealthy");
        String detail = String.valueOf(bodyOf(ctx).get("detail"));
        assertThat(detail)
                .contains("RuntimeException")
                .contains("EZSP setPolicy(trustCenterPolicy) failed: status=0x01")
                .doesNotContain("\n\tat ");
    }

    @Test
    @DisplayName("503: a future that never completes past the port timeout (50 ms here)")
    void neverCompleting_503_pastTimeout() {
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpointOver(RecordingPort.neverCompleting()).apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(503);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Integration Unhealthy");
        assertThat(String.valueOf(bodyOf(ctx).get("detail")))
                .isEqualTo("the integration did not answer in 50 ms");
    }

    @Test
    @DisplayName("the default port timeout is 5 s and renders as '5 s'")
    void defaultTimeoutIsFiveSeconds() {
        assertThat(PermitJoinEndpoint.PORT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(PermitJoinEndpoint.render(Duration.ofSeconds(5))).isEqualTo("5 s");
        assertThat(PermitJoinEndpoint.render(Duration.ofMillis(50))).isEqualTo("50 ms");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void assertBadDuration(String body) {
        RecordingPort port = RecordingPort.completing(NOW, NOW.plusSeconds(120), 120, "x", "k");
        RecordingEndpointContext ctx = post(body);

        endpointOver(port).apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Invalid Parameters");
        assertThat(String.valueOf(bodyOf(ctx).get("detail"))).contains("durationSeconds");
        assertThat(port.calls).as("the port is never called on a bad body").isEmpty();
    }

    private void assertBadReason(String body) {
        RecordingPort port = RecordingPort.completing(NOW, NOW.plusSeconds(120), 120, "x", "k");
        RecordingEndpointContext ctx = post(body);

        endpointOver(port).apply(ctx, CALLER);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Invalid Parameters");
        assertThat(String.valueOf(bodyOf(ctx).get("detail"))).contains("reason");
        assertThat(port.calls).isEmpty();
    }

    private static PermitJoinEndpoint endpointOver(PairingWindowPort port) {
        return new PermitJoinEndpoint(port, FIXED_CLOCK, SHORT_TIMEOUT);
    }

    private static RecordingEndpointContext post(String body) {
        return new RecordingEndpointContext()
                .withPathParam("integrationId", INTEGRATION_ULID)
                .withBody(body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(RecordingEndpointContext ctx) {
        assertThat(ctx.body).as("response body").isNotNull();
        return (Map<String, Object>) ctx.body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    /** One recorded port call. */
    record Call(IntegrationId integrationId, int durationSeconds, String reason, String actor) {
    }

    /** A port stub: records every call; answers with the configured future. */
    static final class RecordingPort implements PairingWindowPort {

        final List<Call> calls = new ArrayList<>();
        private final CompletableFuture<PairingWindowView> answer;
        private final Instant opensAt;
        private final Instant closesAt;

        private RecordingPort(CompletableFuture<PairingWindowView> answer,
                              Instant opensAt, Instant closesAt) {
            this.answer = answer;
            this.opensAt = opensAt;
            this.closesAt = closesAt;
        }

        static RecordingPort completing(Instant opensAt, Instant closesAt, int durationSeconds,
                                        String reason, String actor) {
            return new RecordingPort(null, opensAt, closesAt);
        }

        static RecordingPort failing(Throwable failure) {
            return new RecordingPort(CompletableFuture.failedFuture(failure), null, null);
        }

        static RecordingPort neverCompleting() {
            return new RecordingPort(new CompletableFuture<>(), null, null);
        }

        @Override
        public CompletableFuture<PairingWindowView> open(IntegrationId integrationId,
                                                         int durationSeconds, String reason,
                                                         String actor) {
            calls.add(new Call(integrationId, durationSeconds, reason, actor));
            if (answer != null) {
                return answer;
            }
            return CompletableFuture.completedFuture(new PairingWindowView(
                    integrationId, opensAt, closesAt, durationSeconds, reason, actor));
        }
    }
}

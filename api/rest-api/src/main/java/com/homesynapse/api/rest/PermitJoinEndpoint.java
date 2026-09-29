/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.api.rest.PairingWindowPort.PairingWindowView;
import com.homesynapse.platform.identity.IntegrationId;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Javalin handler for {@code POST /api/v1/integrations/{integrationId}/permit-join} —
 * the pairing window as a declared act (PJ-2, IR-63, DP-PJ2-6).
 *
 * <p>Body {@code {"durationSeconds": 1–254, "reason": "<1–120 chars>"}}; the actor is
 * the authenticated caller's key id (the identity the auth filter attached). The open
 * runs on the adapter's command executor through the {@link PairingWindowPort}; this
 * handler waits at most {@link #PORT_TIMEOUT} on the port's future (a Javalin thread,
 * bounded — the EZSP round trip is milliseconds; a timeout means the adapter's executor
 * is busy or dead, and 503 is the honest word). {@code 200} carries the sibling's
 * {@code {data, meta}} envelope with the six window fields. This endpoint publishes
 * NOTHING — the adapter owns the events of record ({@code permit_join_opened} /
 * {@code permit_join_closed}); {@code REST_ENDPOINTS_NO_EVENT_PUBLISHING} holds without
 * an allowlist edit.</p>
 *
 * <p>Problems (RFC 9457): a bad body or a malformed {@code {integrationId}} → 400
 * {@code INVALID_PARAMETERS}; a null caller → 401; the port's future failing with
 * {@link IllegalStateException} (not running / no supervisor) → 503
 * {@code INTEGRATION_UNHEALTHY}; {@link UnsupportedOperationException} → 409
 * {@code PAIRING_WINDOW_UNSUPPORTED}; {@link IllegalArgumentException} → 400; the
 * future not done in time → 503; any other adapter throw (an NCP NAK) → 503 naming the
 * exception's class and message — never a stack, never 500: the integration is the
 * unhealthy party. 500 {@code INTERNAL_ERROR} is reserved for the port itself throwing
 * synchronously, which is the API's own fault.</p>
 *
 * <p>Thread safety: stateless beyond the injected collaborators.</p>
 *
 * @see RestFilters#installPermitJoinEndpoint
 */
final class PermitJoinEndpoint implements Handler {

    /** The default bound on the port's future (§4 of the PJ-2 instruction). */
    static final Duration PORT_TIMEOUT = Duration.ofSeconds(5);

    /** The request's bounds — the same words {@code PairingWindowRequest} enforces. */
    static final int MIN_DURATION_SECONDS = 1;
    static final int MAX_DURATION_SECONDS = 254;
    static final int MAX_REASON_LENGTH = 120;

    /** rest-api is the JSON boundary (LTD-08) — the body is parsed here only. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PairingWindowPort port;
    private final Clock clock;
    private final Duration portTimeout;

    /**
     * Production wiring: the default {@link #PORT_TIMEOUT}.
     *
     * @param port  the bridge to the supervisor; never {@code null}
     * @param clock injected clock for {@code meta.timestamp}; never {@code null}
     */
    PermitJoinEndpoint(PairingWindowPort port, Clock clock) {
        this(port, clock, PORT_TIMEOUT);
    }

    /**
     * Test seam: the port timeout is injectable (a test clock cannot expire a blocking
     * {@code get}; the never-completing case waits a real, short bound).
     *
     * @param port        the bridge to the supervisor; never {@code null}
     * @param clock       injected clock; never {@code null}
     * @param portTimeout the bound on the port's future; never {@code null}
     */
    PermitJoinEndpoint(PairingWindowPort port, Clock clock, Duration portTimeout) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.portTimeout = Objects.requireNonNull(portTimeout, "portTimeout");
    }

    @Override
    public void handle(Context ctx) {
        apply(new JavalinEndpointContext(ctx), ctx.attribute(RestFilters.IDENTITY_ATTRIBUTE));
    }

    /**
     * Pure handler logic — package-private so tests drive the endpoint with a recording
     * {@link EndpointContext} stub and an explicit caller (the {@code TokenAdminEndpoints}
     * form).
     *
     * @param ctx    the request/response SPI; never {@code null}
     * @param caller the identity the auth filter attached — {@code null} only if the
     *               filter did not run (answered 401, never trusted)
     */
    void apply(EndpointContext ctx, ApiKeyIdentity caller) {
        if (caller == null) {
            EndpointResponses.problem(ctx, ProblemType.AUTHENTICATION_REQUIRED,
                    "no authenticated caller on the request");
            return;
        }
        IntegrationId integrationId;
        try {
            integrationId = IntegrationId.parse(ctx.pathParam("integrationId"));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "integrationId must be a ULID: " + ctx.pathParam("integrationId"));
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(ctx.body());
        } catch (JsonProcessingException ex) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Request body is not valid JSON");
            return;
        }
        if (root == null || !root.isObject()) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Request body must be a JSON object");
            return;
        }
        JsonNode durationNode = root.get("durationSeconds");
        if (durationNode == null || !durationNode.isIntegralNumber()) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "durationSeconds must be an integer");
            return;
        }
        int durationSeconds = durationNode.asInt();
        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS
                || !durationNode.canConvertToInt()) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "durationSeconds must be between " + MIN_DURATION_SECONDS + " and "
                            + MAX_DURATION_SECONDS);
            return;
        }
        JsonNode reasonNode = root.get("reason");
        if (reasonNode == null || !reasonNode.isTextual() || reasonNode.asText().isBlank()) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "reason must be a non-blank string");
            return;
        }
        String reason = reasonNode.asText();
        if (reason.length() > MAX_REASON_LENGTH) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "reason must be at most " + MAX_REASON_LENGTH + " characters");
            return;
        }

        CompletableFuture<PairingWindowView> future;
        try {
            future = port.open(integrationId, durationSeconds, reason, caller.keyId());
        } catch (RuntimeException portFailure) {
            // The port itself threw before producing a future — the API's own fault.
            EndpointResponses.problem(ctx, ProblemType.INTERNAL_ERROR,
                    "the pairing-window port failed: " + portFailure.getClass().getSimpleName());
            return;
        }
        PairingWindowView window;
        try {
            window = future.get(portTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            respondToFailure(ctx, failed.getCause() != null ? failed.getCause() : failed);
            return;
        } catch (TimeoutException slow) {
            EndpointResponses.problem(ctx, ProblemType.INTEGRATION_UNHEALTHY,
                    "the integration did not answer in " + render(portTimeout));
            return;
        } catch (CancellationException cancelled) {
            EndpointResponses.problem(ctx, ProblemType.INTEGRATION_UNHEALTHY,
                    "the pairing-window open was cancelled");
            return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            EndpointResponses.problem(ctx, ProblemType.INTEGRATION_UNHEALTHY,
                    "interrupted while waiting for the integration");
            return;
        }
        respondOpened(ctx, window);
    }

    /** Maps the port future's cause to the problem the contract names (DP-PJ2-6). */
    private static void respondToFailure(EndpointContext ctx, Throwable cause) {
        String message = cause.getMessage() != null ? cause.getMessage() : "";
        if (cause instanceof IllegalStateException) {
            EndpointResponses.problem(ctx, ProblemType.INTEGRATION_UNHEALTHY, message);
        } else if (cause instanceof UnsupportedOperationException) {
            EndpointResponses.problem(ctx, ProblemType.PAIRING_WINDOW_UNSUPPORTED, message);
        } else if (cause instanceof IllegalArgumentException) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS, message);
        } else {
            // The adapter's own throw (an NCP NAK, a transport failure): the integration
            // is the unhealthy party — its class and message, never a stack, never 500.
            EndpointResponses.problem(ctx, ProblemType.INTEGRATION_UNHEALTHY,
                    "the integration rejected the open: " + cause.getClass().getSimpleName()
                            + ": " + message);
        }
    }

    // ── Response building (the live {data, meta} inline idiom, DP-2) ────

    private void respondOpened(EndpointContext ctx, PairingWindowView window) {
        Map<String, Object> data = new LinkedHashMap<>(6);
        data.put("integrationId", window.integrationId().toString());
        data.put("durationSeconds", window.durationSeconds());
        data.put("reason", window.reason());
        data.put("actor", window.actor());
        data.put("opensAt", window.opensAt().toString());
        data.put("closesAt", window.closesAt().toString());

        Map<String, Object> meta = new LinkedHashMap<>(1);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", data);
        body.put("meta", meta);

        ctx.status(200);
        ctx.header("Cache-Control", "no-store");
        ctx.json(body);
    }

    /** {@code 5 s} for whole seconds, else {@code 50 ms} — the 503 detail's word. */
    static String render(Duration timeout) {
        long millis = timeout.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + " s" : millis + " ms";
    }
}

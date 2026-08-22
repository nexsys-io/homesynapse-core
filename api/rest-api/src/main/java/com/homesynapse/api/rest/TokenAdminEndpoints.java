/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin handlers for the token-admin surface (R-6 TOKEN-OPS, 2026-08-22 — the
 * token-holder half of the rotation operator path; the pairing wizard's future
 * hand-off):
 * <ul>
 *   <li>{@code GET /internal/tokens} → 200 {@code { "data": { "tokens": [ {keyId,
 *       displayName, createdAt, expiresAt|null, scopes, siteId|null, revoked} ] },
 *       "meta": { "timestamp" } }} — hashes and raw tokens never appear;</li>
 *   <li>{@code POST /internal/tokens} with {@code { "displayName": "…",
 *       "scopes"?: ["*"], "siteId"?: "…" }} → 201 {@code { "data": { "keyId",
 *       "token" }, "meta": { "timestamp" } }} — the raw token is returned ONCE and
 *       never again; a blank/missing {@code displayName} → 400; {@code scopes}
 *       defaults to {@code ["*"]};</li>
 *   <li>{@code DELETE /internal/tokens/{keyId}} → 204 when an active token was
 *       revoked, 404 otherwise. <strong>Self-revocation is allowed</strong>: the
 *       caller may revoke its own token; the response still completes (the auth
 *       filter already ran for this request) and every later request with that
 *       token is 403.</li>
 * </ul>
 *
 * <h2>Authorization — two layers (INV-SE-02)</h2>
 *
 * <p>{@code RestFilters.installAuth}'s catch-all {@code before(*)} already gates
 * {@code /internal/*} behind a valid bearer token. Every handler here then resolves
 * the authenticated identity ({@code ctx.attribute(RestFilters.IDENTITY_ATTRIBUTE)})
 * → {@link OpaqueTokenStore#claimsFor(String)} → requires
 * {@link ApiKeyClaims#fullAccess()}, else 403. That second layer is the enterprise
 * scope split the {@link ApiKeyClaims} javadoc anticipates: a scoped token may read
 * entities but may not administer tokens. A missing identity attribute (the filter
 * always sets it; the handler is fenced anyway) is 403, never an NPE.</p>
 *
 * <h2>The audit line</h2>
 *
 * <p>Every mutating act logs ONE INFO line — {@code token admin: actor={keyId}
 * verb={mint|revoke} target={keyId}} — so the journal carries a forensic trail of
 * token administration beside the request-file WARN. Key ids only: display names
 * are operator-controlled strings (a pasted token would be a secret in the log),
 * hashes and raw tokens never ride. The sink is injectable so the line is
 * unit-pinned without a logging binding.</p>
 *
 * <h2>No envelope cursor</h2>
 *
 * <p>No {@code meta.viewPosition}, no {@code X-HomeSynapse-View-Position}, no
 * ETag — these are not projection reads; the {@code /internal/dlq} header idiom
 * is for projection-bearing responses. Every response — 2xx and problem alike —
 * carries {@code Cache-Control: no-store} (a 201 body holds a raw token; the
 * header is set first, before any body write).</p>
 *
 * <p>Thread safety: stateless beyond the injected collaborators; the store
 * serializes its own mutations (LTD-11).</p>
 *
 * @see RestFilters#installTokenAdminEndpoints(Object, OpaqueTokenStore, Clock)
 * @see OpaqueTokenStore
 */
final class TokenAdminEndpoints {

    /** The collection route — {@code GET} lists, {@code POST} mints. */
    static final String COLLECTION_PATH = "/internal/tokens";

    /** The item route — {@code DELETE} revokes. */
    static final String ITEM_PATH = "/internal/tokens/{keyId}";

    private static final Logger LOG = LoggerFactory.getLogger(TokenAdminEndpoints.class);

    /** rest-api is the JSON boundary (LTD-08) — the request body is parsed here only. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpaqueTokenStore store;
    private final Clock clock;
    private final Consumer<String> auditSink;

    /**
     * Production wiring: the audit line goes to this class's SLF4J logger at INFO.
     *
     * @param store the token store; never {@code null}
     * @param clock injected clock for {@code meta.timestamp}; never {@code null}
     */
    TokenAdminEndpoints(OpaqueTokenStore store, Clock clock) {
        this(store, clock, line -> LOG.info(line));
    }

    /**
     * Test seam: the audit sink is injectable so the formatted line is asserted
     * without a logging binding on the test classpath.
     *
     * @param store     the token store; never {@code null}
     * @param clock     injected clock; never {@code null}
     * @param auditSink receives each formatted audit line; never {@code null}
     */
    TokenAdminEndpoints(OpaqueTokenStore store, Clock clock, Consumer<String> auditSink) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }

    /** The {@code GET /internal/tokens} handler. */
    Handler listHandler() {
        return ctx -> list(new JavalinEndpointContext(ctx), identityOf(ctx));
    }

    /** The {@code POST /internal/tokens} handler. */
    Handler mintHandler() {
        return ctx -> mint(new JavalinEndpointContext(ctx), identityOf(ctx));
    }

    /** The {@code DELETE /internal/tokens/{keyId}} handler. */
    Handler revokeHandler() {
        return ctx -> revoke(new JavalinEndpointContext(ctx), identityOf(ctx));
    }

    /** The identity the auth filter attached — {@code null} only if the filter did not run. */
    private static ApiKeyIdentity identityOf(Context ctx) {
        return ctx.attribute(RestFilters.IDENTITY_ATTRIBUTE);
    }

    /**
     * Pure {@code GET} logic — package-private so tests drive it through a
     * recording {@link EndpointContext} with an explicit caller.
     *
     * @param ctx    the request/response SPI; never {@code null}
     * @param caller the authenticated identity, or {@code null} when absent
     */
    void list(EndpointContext ctx, ApiKeyIdentity caller) {
        ctx.header("Cache-Control", "no-store");
        if (!authorize(ctx, caller)) {
            return;
        }
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (OpaqueTokenStore.TokenSummary summary : store.summaries()) {
            Map<String, Object> entry = new LinkedHashMap<>(7);
            entry.put("keyId", summary.keyId());
            entry.put("displayName", summary.displayName());
            entry.put("createdAt", summary.createdAt().toString());
            entry.put("expiresAt",
                    summary.expiresAt() == null ? null : summary.expiresAt().toString());
            entry.put("scopes", summary.scopes());
            entry.put("siteId", summary.siteId());
            entry.put("revoked", summary.revoked());
            tokens.add(entry);
        }
        Map<String, Object> data = new LinkedHashMap<>(1);
        data.put("tokens", tokens);
        respond(ctx, 200, data);
    }

    /**
     * Pure {@code POST} logic: body parse/shape → 400; mint; 201 with the raw
     * token ONCE; the audit line.
     *
     * @param ctx    the request/response SPI; never {@code null}
     * @param caller the authenticated identity, or {@code null} when absent
     */
    void mint(EndpointContext ctx, ApiKeyIdentity caller) {
        ctx.header("Cache-Control", "no-store");
        if (!authorize(ctx, caller)) {
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
        JsonNode nameNode = root.get("displayName");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "displayName is required and must be a non-blank string");
            return;
        }
        String displayName = nameNode.asText().strip();

        List<String> scopes = List.of(ApiKeyClaims.SCOPE_ALL);
        JsonNode scopesNode = root.get("scopes");
        if (scopesNode != null && !scopesNode.isNull()) {
            if (!scopesNode.isArray() || scopesNode.isEmpty()) {
                EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                        "scopes must be a non-empty array of non-blank strings when present");
                return;
            }
            List<String> parsed = new ArrayList<>(scopesNode.size());
            for (JsonNode scope : scopesNode) {
                if (!scope.isTextual() || scope.asText().isBlank()) {
                    EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                            "scopes must be a non-empty array of non-blank strings when present");
                    return;
                }
                String value = scope.asText().strip();
                if (!isWellFormedScope(value)) {
                    // The store persists scopes as a comma-joined list: a comma inside one
                    // scope would reload as two (an unintended "*" grant); control
                    // characters and whitespace have no place in a scope token.
                    EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                            "scopes must not contain ',', whitespace, or control characters");
                    return;
                }
                parsed.add(value);
            }
            scopes = parsed;
        }

        String siteId = null;
        JsonNode siteNode = root.get("siteId");
        if (siteNode != null && !siteNode.isNull()) {
            if (!siteNode.isTextual() || siteNode.asText().isBlank()) {
                EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                        "siteId must be a non-blank string when present");
                return;
            }
            siteId = siteNode.asText().strip();
        }

        String rawToken = store.mint(displayName, scopes, siteId);
        String keyId = store.validate(rawToken)
                .map(ApiKeyIdentity::keyId)
                .orElseThrow(() -> new IllegalStateException(
                        "a token minted this instant does not validate against its own store"));
        audit(caller.keyId(), "mint", keyId);

        Map<String, Object> data = new LinkedHashMap<>(2);
        data.put("keyId", keyId);
        data.put("token", rawToken);
        respond(ctx, 201, data);
    }

    /**
     * Pure {@code DELETE} logic: 204 when an active token was revoked, 404
     * otherwise; the audit line on success.
     *
     * @param ctx    the request/response SPI; never {@code null}
     * @param caller the authenticated identity, or {@code null} when absent
     */
    void revoke(EndpointContext ctx, ApiKeyIdentity caller) {
        ctx.header("Cache-Control", "no-store");
        if (!authorize(ctx, caller)) {
            return;
        }
        String keyId = ctx.pathParam("keyId");
        if (keyId == null || keyId.isBlank()) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "keyId path parameter is required");
            return;
        }
        if (!store.revoke(keyId)) {
            EndpointResponses.problem(ctx, ProblemType.NOT_FOUND,
                    "No active token with keyId " + keyId);
            return;
        }
        audit(caller.keyId(), "revoke", keyId);
        ctx.status(204);
    }

    /** A scope token: non-blank, no {@code ,} (the persisted delimiter), no whitespace, no control characters. */
    private static boolean isWellFormedScope(String scope) {
        for (int i = 0; i < scope.length(); i++) {
            char c = scope.charAt(i);
            if (c == ',' || c <= 0x20 || c == 0x7F) {
                return false;
            }
        }
        return !scope.isEmpty();
    }

    /**
     * The second authorization layer: the caller must be present and its claims
     * must grant full access. Writes the 403 problem and returns {@code false}
     * otherwise.
     */
    private boolean authorize(EndpointContext ctx, ApiKeyIdentity caller) {
        if (caller == null) {
            EndpointResponses.problem(ctx, ProblemType.FORBIDDEN,
                    "token administration requires an authenticated full-access token");
            return false;
        }
        Optional<ApiKeyClaims> claims = store.claimsFor(caller.keyId());
        if (claims.isEmpty() || !claims.get().fullAccess()) {
            EndpointResponses.problem(ctx, ProblemType.FORBIDDEN,
                    "token administration requires a full-access token; key "
                            + caller.keyId() + " is scoped");
            return false;
        }
        return true;
    }

    private void audit(String actor, String verb, String target) {
        auditSink.accept(auditLine(actor, verb, target));
    }

    /** The exact audit line format — key ids only, never a name, a hash, or a token. */
    static String auditLine(String actor, String verb, String target) {
        return "token admin: actor=" + actor + " verb=" + verb + " target=" + target;
    }

    /** The {@code {data, meta}} envelope (the inline hand-built idiom); {@code no-store} was set on entry. */
    private void respond(EndpointContext ctx, int status, Map<String, Object> data) {
        Map<String, Object> meta = new LinkedHashMap<>(1);
        meta.put("timestamp", clock.instant().toString());
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", data);
        body.put("meta", meta);
        ctx.status(status);
        ctx.json(body);
    }
}

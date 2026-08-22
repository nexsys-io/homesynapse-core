/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TokenAdminEndpoints} (R-6 TOKEN-OPS, the token-holder
 * half of the rotation operator path). Drives the pure {@code list/mint/revoke}
 * logic through a {@link RecordingEndpointContext} with an explicit caller
 * identity (what the auth filter would have attached) over a REAL
 * {@link OpaqueTokenStore} in a temp dir, fixed clock. The audit sink is the
 * injected recording list — no logging binding exists on this classpath.
 */
@DisplayName("TokenAdminEndpoints -- /internal/tokens (list · mint · revoke)")
final class TokenAdminEndpointsTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SHA256_HEX_RUN = "[0-9a-f]{64}";
    private static final String RAW_TOKEN_RUN = "[A-Za-z0-9_-]{43}";

    @TempDir
    Path configDir;

    private OpaqueTokenStore store;
    private ApiKeyIdentity admin;
    private String adminToken;
    private ApiKeyIdentity scoped;
    private List<String> audit;
    private TokenAdminEndpoints endpoints;

    TokenAdminEndpointsTest() {
    }

    @BeforeEach
    void setUp() {
        store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        adminToken = store.mint("admin", List.of(ApiKeyClaims.SCOPE_ALL), null);
        admin = store.validate(adminToken).orElseThrow();
        String scopedToken = store.mint("reader", List.of("entities:read"), "site-1");
        scoped = store.validate(scopedToken).orElseThrow();
        audit = new ArrayList<>();
        endpoints = new TokenAdminEndpoints(store, FIXED_CLOCK, audit::add);
    }

    @Test
    @DisplayName("GET lists every token's public summary in the {data, meta} envelope — never "
            + "a hash, never a raw token, no-store, no cursor")
    void listBodyShapeNeverExposesHashes() throws Exception {
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoints.list(ctx, admin);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat(ctx.headers).containsEntry("Cache-Control", "no-store");
        assertThat(ctx.headers).doesNotContainKeys("ETag", ListEntitiesEndpoint.VIEW_POSITION_HEADER);
        Map<String, Object> body = asMap(ctx.body);
        assertThat(body.keySet()).containsExactly("data", "meta");
        Map<String, Object> data = asMap(body.get("data"));
        assertThat(data.keySet()).containsExactly("tokens");
        @SuppressWarnings("unchecked")
        List<Object> tokens = (List<Object>) data.get("tokens");
        assertThat(tokens).hasSize(2);
        Map<String, Object> first = asMap(tokens.get(0));
        assertThat(first.keySet()).containsExactly(
                "keyId", "displayName", "createdAt", "expiresAt", "scopes", "siteId", "revoked");
        assertThat(tokens).extracting(t -> asMap(t).get("keyId"))
                .containsExactlyInAnyOrder(admin.keyId(), scoped.keyId());
        Map<String, Object> reader = tokens.stream().map(TokenAdminEndpointsTest::asMap)
                .filter(t -> scoped.keyId().equals(t.get("keyId"))).findFirst().orElseThrow();
        assertThat(reader)
                .containsEntry("displayName", "reader")
                .containsEntry("createdAt", "2026-08-22T12:00:00Z")
                .containsEntry("expiresAt", null)
                .containsEntry("scopes", List.of("entities:read"))
                .containsEntry("siteId", "site-1")
                .containsEntry("revoked", false);
        Map<String, Object> meta = asMap(body.get("meta"));
        assertThat(meta.keySet()).containsExactly("timestamp");
        assertThat(meta).containsEntry("timestamp", "2026-08-22T12:00:00Z");

        String serialized = MAPPER.writeValueAsString(ctx.body);
        assertThat(serialized).doesNotContainPattern(SHA256_HEX_RUN);
        assertThat(serialized).doesNotContain(adminToken);
        assertThat(serialized).doesNotContainPattern(RAW_TOKEN_RUN);
    }

    @Test
    @DisplayName("POST mints: 201 with keyId + the raw token ONCE (validates at the store, "
            + "full-access by default), no-store, meta.timestamp from the clock")
    void mint201ReturnsTheTokenOnce() {
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withBody("{\"displayName\":\"Companion app\"}");

        endpoints.mint(ctx, admin);

        assertThat(ctx.statusSet).isEqualTo(201);
        assertThat(ctx.headers).containsEntry("Cache-Control", "no-store");
        Map<String, Object> body = asMap(ctx.body);
        assertThat(body.keySet()).containsExactly("data", "meta");
        Map<String, Object> data = asMap(body.get("data"));
        assertThat(data.keySet()).containsExactly("keyId", "token");
        String token = (String) data.get("token");
        assertThat(token).matches(RAW_TOKEN_RUN);
        ApiKeyIdentity minted = store.validate(token).orElseThrow();
        assertThat(minted.keyId()).isEqualTo(data.get("keyId"));
        assertThat(minted.displayName()).isEqualTo("Companion app");
        assertThat(store.claimsFor(minted.keyId())).hasValueSatisfying(claims -> {
            assertThat(claims.fullAccess()).isTrue();
            assertThat(claims.siteId()).isNull();
        });
        assertThat(asMap(body.get("meta"))).containsEntry("timestamp", "2026-08-22T12:00:00Z");
        // Shown once: the list surface never carries it again.
        RecordingEndpointContext list = new RecordingEndpointContext();
        endpoints.list(list, admin);
        assertThat(list.body.toString()).doesNotContain(token);
    }

    @Test
    @DisplayName("POST honors explicit scopes and siteId")
    void mintHonorsScopesAndSiteId() {
        RecordingEndpointContext ctx = new RecordingEndpointContext().withBody(
                "{\"displayName\":\"Reader\",\"scopes\":[\"entities:read\",\"events:read\"],"
                        + "\"siteId\":\"site-7\"}");

        endpoints.mint(ctx, admin);

        assertThat(ctx.statusSet).isEqualTo(201);
        String keyId = (String) asMap(asMap(ctx.body).get("data")).get("keyId");
        assertThat(store.claimsFor(keyId)).hasValueSatisfying(claims -> {
            assertThat(claims.scopes()).containsExactly("entities:read", "events:read");
            assertThat(claims.siteId()).isEqualTo("site-7");
            assertThat(claims.fullAccess()).isFalse();
        });
    }

    @Test
    @DisplayName("POST is 400 problem+json on a missing/blank displayName, a non-object body, "
            + "invalid JSON, and a malformed scopes array — nothing is minted")
    void mint400OnMissingBlankOrMalformed() {
        int before = store.summaries().size();
        for (String body : new String[] {
                "{}",
                "{\"displayName\":\"   \"}",
                "{\"displayName\":42}",
                "[]",
                "not json",
                "{\"displayName\":\"x\",\"scopes\":\"*\"}",
                "{\"displayName\":\"x\",\"scopes\":[]}",
                "{\"displayName\":\"x\",\"scopes\":[\"\"]}",
                "{\"displayName\":\"x\",\"scopes\":[\"entities:read,*\"]}",
                "{\"displayName\":\"x\",\"scopes\":[\"entities read\"]}",
                "{\"displayName\":\"x\",\"scopes\":[\"ent\\u0007ities\"]}",
                "{\"displayName\":\"x\",\"siteId\":\"\"}"}) {
            RecordingEndpointContext ctx = new RecordingEndpointContext().withBody(body);

            endpoints.mint(ctx, admin);

            assertThat(ctx.statusSet).as("body %s", body).isEqualTo(400);
            assertThat(ctx.headers).as("body %s", body)
                    .containsEntry("Content-Type", EndpointResponses.PROBLEM_JSON)
                    .containsEntry("Cache-Control", "no-store");
            assertThat(asMap(ctx.body)).as("body %s", body)
                    .containsEntry("type", ProblemType.INVALID_PARAMETERS.typeUri())
                    .containsEntry("status", 400);
        }
        assertThat(store.summaries()).hasSize(before);
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("DELETE is 204 when an active token is revoked (the token dies at the store) "
            + "and 404 problem+json for an unknown or already-revoked keyId")
    void revoke204ThenRevoked404() {
        String victimToken = store.mint("victim", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String victimKey = store.validate(victimToken).orElseThrow().keyId();
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("keyId", victimKey);

        endpoints.revoke(ctx, admin);

        assertThat(ctx.statusSet).isEqualTo(204);
        assertThat(ctx.body).isNull();
        assertThat(store.validate(victimToken)).isEmpty();
        assertThat(store.summaries()).filteredOn(s -> s.keyId().equals(victimKey))
                .singleElement().satisfies(s -> assertThat(s.revoked()).isTrue());

        RecordingEndpointContext again = new RecordingEndpointContext()
                .withPathParam("keyId", victimKey);
        endpoints.revoke(again, admin);
        assertThat(again.statusSet).isEqualTo(404);
        assertThat(again.headers)
                .containsEntry("Content-Type", EndpointResponses.PROBLEM_JSON)
                .containsEntry("Cache-Control", "no-store");
        assertThat(asMap(again.body)).containsEntry("type", ProblemType.NOT_FOUND.typeUri());

        RecordingEndpointContext unknown = new RecordingEndpointContext()
                .withPathParam("keyId", "no-such-key");
        endpoints.revoke(unknown, admin);
        assertThat(unknown.statusSet).isEqualTo(404);
    }

    @Test
    @DisplayName("a valid but scoped (non-full-access) caller is 403 on all three routes — the "
            + "second authorization layer — and nothing changes")
    void forbiddenWhenCallerIsNotFullAccess() {
        RecordingEndpointContext list = new RecordingEndpointContext();
        RecordingEndpointContext mint = new RecordingEndpointContext()
                .withBody("{\"displayName\":\"x\"}");
        RecordingEndpointContext revoke = new RecordingEndpointContext()
                .withPathParam("keyId", admin.keyId());

        endpoints.list(list, scoped);
        endpoints.mint(mint, scoped);
        endpoints.revoke(revoke, scoped);

        for (RecordingEndpointContext ctx : List.of(list, mint, revoke)) {
            assertThat(ctx.statusSet).isEqualTo(403);
            assertThat(ctx.headers)
                    .containsEntry("Content-Type", EndpointResponses.PROBLEM_JSON)
                    .containsEntry("Cache-Control", "no-store");
            assertThat(asMap(ctx.body))
                    .containsEntry("type", ProblemType.FORBIDDEN.typeUri())
                    .containsEntry("status", 403);
        }
        assertThat(store.validate(adminToken)).isPresent();
        assertThat(store.summaries()).hasSize(2);
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("an absent identity (the filter did not run) is 403, never an NPE, on all "
            + "three routes")
    void forbiddenWhenIdentityAbsent() {
        RecordingEndpointContext list = new RecordingEndpointContext();
        RecordingEndpointContext mint = new RecordingEndpointContext()
                .withBody("{\"displayName\":\"x\"}");
        RecordingEndpointContext revoke = new RecordingEndpointContext()
                .withPathParam("keyId", admin.keyId());

        endpoints.list(list, null);
        endpoints.mint(mint, null);
        endpoints.revoke(revoke, null);

        for (RecordingEndpointContext ctx : List.of(list, mint, revoke)) {
            assertThat(ctx.statusSet).isEqualTo(403);
            assertThat(asMap(ctx.body)).containsEntry("type", ProblemType.FORBIDDEN.typeUri());
        }
        assertThat(store.validate(adminToken)).isPresent();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("the audit line: nothing on list, exactly one `token admin: actor= verb= "
            + "target=` line per mint/revoke, and no secret run ever appears in it")
    void auditLineExactlyOncePerMutatingCall() {
        endpoints.list(new RecordingEndpointContext(), admin);
        assertThat(audit).isEmpty();

        RecordingEndpointContext mint = new RecordingEndpointContext()
                .withBody("{\"displayName\":\"Ops laptop\"}");
        endpoints.mint(mint, admin);
        String mintedKey = (String) asMap(asMap(mint.body).get("data")).get("keyId");
        assertThat(audit).containsExactly(
                "token admin: actor=" + admin.keyId() + " verb=mint target=" + mintedKey);

        RecordingEndpointContext revoke = new RecordingEndpointContext()
                .withPathParam("keyId", mintedKey);
        endpoints.revoke(revoke, admin);
        assertThat(audit).hasSize(2);
        assertThat(audit.get(1)).isEqualTo(
                "token admin: actor=" + admin.keyId() + " verb=revoke target=" + mintedKey);

        // A refused revoke (404) and a refused mint (400) log nothing.
        endpoints.revoke(new RecordingEndpointContext().withPathParam("keyId", mintedKey), admin);
        endpoints.mint(new RecordingEndpointContext().withBody("{}"), admin);
        assertThat(audit).hasSize(2);

        for (String line : audit) {
            assertThat(line).doesNotContainPattern(RAW_TOKEN_RUN);
            assertThat(line).doesNotContainPattern(SHA256_HEX_RUN);
            assertThat(line).doesNotContain("Ops laptop");
        }
    }

    @Test
    @DisplayName("self-revocation is allowed: the caller revokes its own key, the response "
            + "completes 204, and the token is dead for every later request")
    void selfRevocationCompletes() {
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("keyId", admin.keyId());

        endpoints.revoke(ctx, admin);

        assertThat(ctx.statusSet).isEqualTo(204);
        assertThat(store.validate(adminToken)).isEmpty();
        assertThat(audit).containsExactly(
                "token admin: actor=" + admin.keyId() + " verb=revoke target=" + admin.keyId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3.7 end-to-end tests exercising the M3.6e.2 REST endpoints with a real
 * Jetty server, a real {@code java.net.http.HttpClient}, and shape-tolerant
 * JSON assertions via {@code json-unit-assertj} (Research 3 REC-17).
 *
 * <p>Each test brings up a fresh {@link HomeSynapseE2eHarness} on a
 * {@code @TempDir} SQLite file with an ephemeral HTTP port, awaits the
 * projection's transition to {@code LIVE}, then issues real HTTP requests
 * and asserts on the response status, headers, and JSON body shape.</p>
 *
 * @see HomeSynapseE2eHarness
 * @see LiveModeAwaiter
 */
@DisplayName("M3.7 -- endpoint E2E")
final class EndpointE2eIT {

    /** Stable home identity for these tests (AMD-34). */
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    /**
     * Deterministic fixed clock per {@code NO_DIRECT_TIME_ACCESS} (DEC-M3-09).
     * One frozen instant for every event {@code ingestTime} and every
     * response timestamp the endpoints synthesize.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** HTTP client shared across cases — stateless, thread-safe per the JDK. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror}. */
    EndpointE2eIT() {
    }

    private HomeSynapseE2eHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    @Test
    @DisplayName("GET /api/v1/entities returns empty data array on a fresh harness")
    void listEntitiesReturnsEmptyArrayWhenNoEntities(@TempDir Path tempDir) throws Exception {
        harness = HomeSynapseE2eHarness.start(
                tempDir.resolve("homesynapse-events.db"), FIXED_CLOCK, TEST_HOME_ID);
        LiveModeAwaiter.awaitLive(harness);

        HttpResponse<String> response = executeGet(
                harness.baseUri().resolve("/api/v1/entities"));

        assertThat(response.statusCode()).isEqualTo(200);
        // ListEntitiesEndpoint returns { data: [], meta: { ... } } with a
        // view-position header (DEC-M3-09).
        assertThatJson(response.body()).inPath("$.data").isArray().isEmpty();
        assertThatJson(response.body()).inPath("$.meta").isObject();
    }

    @Test
    @DisplayName("GET /api/v1/entities surfaces entities after state_reported events")
    void listEntitiesReturnsEntitiesAfterStateReported(@TempDir Path tempDir) throws Exception {
        harness = HomeSynapseE2eHarness.start(
                tempDir.resolve("homesynapse-events.db"), FIXED_CLOCK, TEST_HOME_ID);
        LiveModeAwaiter.awaitLive(harness);

        // Publish state_reported for three distinct entities. The projection's
        // applyToState materializes each as a new entry.
        EntityId entityA = newEntityId("01JBBBBBBBBBBBBBBBBBBBBBBA");
        EntityId entityB = newEntityId("01JBBBBBBBBBBBBBBBBBBBBBBB");
        EntityId entityC = newEntityId("01JBBBBBBBBBBBBBBBBBBBBBBC");
        harness.eventPublisher().publishRoot(
                TestEvents.stateReported(entityA, "power", "on"));
        harness.eventPublisher().publishRoot(
                TestEvents.stateReported(entityB, "power", "off"));
        harness.eventPublisher().publishRoot(
                TestEvents.stateReported(entityC, "power", "on"));

        // Wait for the projection to apply all three events.
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> harness.stateQueryService().getViewPosition() >= 3L);

        HttpResponse<String> response = executeGet(
                harness.baseUri().resolve("/api/v1/entities"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThatJson(response.body()).inPath("$.data").isArray().hasSize(3);
    }

    @Test
    @DisplayName("GET /api/v1/entities/{id} returns 200 with the entity state")
    void getEntityReturns200WithEntityState(@TempDir Path tempDir) throws Exception {
        harness = HomeSynapseE2eHarness.start(
                tempDir.resolve("homesynapse-events.db"), FIXED_CLOCK, TEST_HOME_ID);
        LiveModeAwaiter.awaitLive(harness);

        EntityId entityId = newEntityId("01JBBBBBBBBBBBBBBBBBBBBBBB");
        harness.eventPublisher().publishRoot(
                TestEvents.stateReported(entityId, "power", "on"));

        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> harness.stateQueryService().getState(entityId).isPresent());

        HttpResponse<String> response = executeGet(
                harness.baseUri().resolve("/api/v1/entities/" + entityId));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThatJson(response.body()).isObject();
    }

    @Test
    @DisplayName("GET /api/v1/entities/{id}/state returns 404 with RFC 9457 problem detail "
            + "for an unknown entity")
    void getEntityStateReturns404ForUnknownEntity(@TempDir Path tempDir) throws Exception {
        harness = HomeSynapseE2eHarness.start(
                tempDir.resolve("homesynapse-events.db"), FIXED_CLOCK, TEST_HOME_ID);
        LiveModeAwaiter.awaitLive(harness);

        // Well-formed ULID for an entity the projection has never seen.
        String unknownId = "01JZZZZZZZZZZZZZZZZZZZZZZZ";

        HttpResponse<String> response = executeGet(
                harness.baseUri().resolve("/api/v1/entities/" + unknownId + "/state"));

        assertThat(response.statusCode()).isEqualTo(404);
        // RFC 9457 problem-detail shape — type/status/title at minimum.
        assertThatJson(response.body()).inPath("$.status").isNumber()
                .isEqualByComparingTo("404");
        assertThatJson(response.body()).inPath("$.type").isString();
        assertThatJson(response.body()).inPath("$.title").isString();
    }

    @Test
    @DisplayName("GET /internal/dlq lists the projection and automation_engine "
            + "subscribers (AB-3)")
    void getDlqStatusListsRuntimeSubscribers(@TempDir Path tempDir) throws Exception {
        // Causing a real DLQ park requires deliberate event corruption (the
        // brief noted this is brittle and explicitly allows skipping). This
        // test asserts the response *shape* on the empty-DLQ path: the runtime
        // subscribers are listed. AB-3 wires the automation_engine subscriber
        // alongside the state projection, so the set is no longer "exactly one";
        // assert by subscriber id (position-independent — the bus does not
        // guarantee array order) rather than pinning the array index/size.
        harness = HomeSynapseE2eHarness.start(
                tempDir.resolve("homesynapse-events.db"), FIXED_CLOCK, TEST_HOME_ID);
        LiveModeAwaiter.awaitLive(harness);

        HttpResponse<String> response = executeGet(
                harness.baseUri().resolve("/internal/dlq"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThatJson(response.body()).inPath("$.subscribers").isArray();
        assertThatJson(response.body()).inPath("$.subscribers[*].subscriberId")
                .isArray().contains("state_projection", "automation_engine");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static EntityId newEntityId(String encoded) {
        return new EntityId(Ulid.parse(encoded));
    }

    private static HttpResponse<String> executeGet(URI uri)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import com.homesynapse.event.bus.EventBusConfig;
import com.homesynapse.lifecycle.HomeSynapseConfig;
import com.homesynapse.persistence.DeploymentProfile;
import com.homesynapse.persistence.PersistenceConfig;
import com.homesynapse.persistence.RetentionPolicy;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.FixedCheckpointPolicy;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
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
import java.util.ArrayList;
import java.util.List;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3.7 — crash-recovery scenario at the HTTP layer (Research 3 REC-19).
 *
 * <p>Mirrors M3.4b's {@code CrashRecoveryIT} but with HTTP queries at the
 * verification stage:
 * <ol>
 *   <li>Start a harness against a temp directory.</li>
 *   <li>Publish events and wait for the projection to materialize them.</li>
 *   <li>Abandon the harness without calling {@code stop()} — simulates an
 *       ungraceful process termination ({@code kill -9}). SQLite's WAL
 *       recovery handles the rest on the next open.</li>
 *   <li>Start a fresh harness on the SAME temp directory.</li>
 *   <li>Issue a {@code GET /api/v1/entities} and assert all pre-crash
 *       entities are still materialized.</li>
 * </ol>
 *
 * <p>{@code @TempDir(cleanup = CleanupMode.NEVER)} per the M3.4b Windows
 * file-handle lesson — the abandoned harness holds SQLite handles open;
 * JUnit's default {@code ON_SUCCESS} cleanup fails on Windows. The OS
 * reclaims the temp directory eventually.</p>
 */
@DisplayName("M3.7 -- crash recovery (HTTP)")
final class CrashRecoveryHttpIT {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror}. */
    CrashRecoveryHttpIT() {
    }

    @Test
    @DisplayName("entities survive abandon -> restart and remain queryable via HTTP")
    void entitiesSurviveCrashAndRestart(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("homesynapse-events.db");

        // Phase 1 — start, publish, abandon.
        EntityId entityA = newEntityId("01JBBBBBBBBBBBBBBBBBBBBBBA");
        EntityId entityB = newEntityId("01JBBBBBBBBBBBBBBBBBBBBBBB");

        HomeSynapseE2eHarness preCrash =
                HomeSynapseE2eHarness.start(dbPath, FIXED_CLOCK, TEST_HOME_ID);
        try {
            LiveModeAwaiter.awaitLive(preCrash);
            preCrash.eventPublisher().publishRoot(
                    TestEvents.stateReported(entityA, "power", "on"));
            preCrash.eventPublisher().publishRoot(
                    TestEvents.stateReported(entityB, "power", "off"));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> preCrash.stateQueryService().getViewPosition() >= 2L);
        } finally {
            // Simulate kill -9: release OS handles (JDBC, HTTP socket, bus
            // threads) but skip WAL checkpoint and projection checkpoint flush.
            // SQLite's WAL recovery on the next open is what we are asserting.
            preCrash.abandon();
        }

        // Phase 2 — restart on the same dbPath.
        HomeSynapseE2eHarness postCrash =
                HomeSynapseE2eHarness.start(dbPath, FIXED_CLOCK, TEST_HOME_ID);
        try {
            LiveModeAwaiter.awaitLive(postCrash);

            // With FixedCheckpointPolicy.TESTING (N=1), every onEvent() in
            // Phase 1 fires a checkpoint. After AMD-45 that checkpoint is the
            // coupled write: the projection writes the subscriber checkpoint
            // AND the view checkpoint blob atomically under "state_projection"
            // (the bus's per-delivery subscriber write is suppressed). On
            // restart, the fresh SqliteStateStore.loadFromCheckpoint() finds
            // the blob and rehydrates the in-memory ConcurrentHashMap, while
            // the coupled subscriber checkpoint (position 2) causes the bus to
            // skip replay — state was recovered from the blob, not from replay.
            // (The HOME_DEFAULT-policy sibling test exercises the complementary
            // no-checkpoint replay-from-zero path.)

            HttpResponse<String> response = executeGet(
                    postCrash.baseUri().resolve("/api/v1/entities"));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThatJson(response.body()).inPath("$.data").isArray().hasSize(2);
        } finally {
            postCrash.close();
        }
    }

    /**
     * AMD-45 §3 — the concrete regression for the coupled-checkpoint fix.
     *
     * <p>Unlike {@link #entitiesSurviveCrashAndRestart} (which uses the
     * {@code TESTING} N=1 checkpoint policy, so recovery is via the checkpoint
     * blob), this test uses {@code FixedCheckpointPolicy.HOME_DEFAULT}
     * (200 events / 2 s) with a sub-2 s fixed clock and fewer than 200 events,
     * so <b>no checkpoint fires at all</b> before the crash. Recovery therefore
     * MUST be via replay-from-zero — exactly the case the fix targets.</p>
     *
     * <ol>
     *   <li>HOME_DEFAULT policy, ephemeral port, fixed (frozen) clock.</li>
     *   <li>Publish 5 entities, reach LIVE, verify all 5 queryable via HTTP.</li>
     *   <li>{@code abandon()} before any checkpoint fires (N &lt; 200 and the
     *       frozen clock keeps elapsed time at 0 &lt; 2 s — neither threshold
     *       can fire). Routes through {@code HomeSynapseE2eHarness.abandon()},
     *       the production-grade path.</li>
     *   <li>Restart on the same database path; reach LIVE.</li>
     *   <li>Assert all 5 entities are queryable via HTTP.</li>
     * </ol>
     *
     * <p><b>Before AMD-45:</b> fails at step 5 — the bus's per-delivery
     * subscriber checkpoint reaches position 5, so on restart the bus skips
     * replay; no view checkpoint exists, so the projection starts empty and the
     * 5 entities are lost. <b>After AMD-45:</b> passes — the coupled checkpoint
     * never advanced (policy never fired), so the bus replays from position 0
     * and the projection rebuilds all 5 entities.</p>
     */
    @Test
    @DisplayName("AMD-45 §3: no checkpoint fired -> bus replays from zero, all N entities rebuilt")
    void busAheadOfViewWindowClosedByReplayFromZero(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("homesynapse-events.db");

        // HOME_DEFAULT checkpoint policy (200 events / 2 s) — NOT the TESTING
        // N=1 policy (which would let a checkpoint fire and mask the bug). The
        // TESTING deployment profile keeps startup fast and binds an ephemeral
        // port; the FIXED_CLOCK never advances, so the 2 s time threshold cannot
        // fire and (with N < 200) neither can the event-count threshold.
        HomeSynapseConfig homeDefaultPolicy = new HomeSynapseConfig(
                new PersistenceConfig(DeploymentProfile.TESTING, RetentionPolicy.SOURCE_DEFAULT),
                EventBusConfig.HOME_DEFAULT,
                0,                                  // ephemeral port
                FixedCheckpointPolicy.HOME_DEFAULT); // 200 events / 2 s

        // N < 200 distinct entities, each with one state_reported.
        List<EntityId> entities = new ArrayList<>();
        String base = "01J" + "C".repeat(22); // 25-char ULID prefix
        for (char suffix : new char[]{'A', 'B', 'C', 'D', 'E'}) {
            entities.add(new EntityId(Ulid.parse(base + suffix)));
        }

        // Phase 1 — start, publish N events, reach LIVE, verify queryable, abandon.
        HomeSynapseE2eHarness preCrash =
                HomeSynapseE2eHarness.start(dbPath, FIXED_CLOCK, TEST_HOME_ID, homeDefaultPolicy);
        try {
            LiveModeAwaiter.awaitLive(preCrash);
            for (EntityId entityId : entities) {
                preCrash.eventPublisher().publishRoot(
                        TestEvents.stateReported(entityId, "power", "on"));
            }
            // All N materialized — and (by construction) NO checkpoint has fired:
            // 5 < 200 events and the frozen clock keeps elapsed time below 2 s.
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> preCrash.stateQueryService().getViewPosition() >= 5L);

            HttpResponse<String> preResponse = executeGet(
                    preCrash.baseUri().resolve("/api/v1/entities"));
            assertThat(preResponse.statusCode()).isEqualTo(200);
            assertThatJson(preResponse.body()).inPath("$.data").isArray().hasSize(5);
        } finally {
            // kill -9 BEFORE any checkpoint fired (production-grade abandon path).
            preCrash.abandon();
        }

        // Phase 2 — restart on the same dbPath; the coupled checkpoint never
        // advanced, so the bus subscriber checkpoint is 0 -> replay from zero
        // rebuilds the projection from the durable event log.
        HomeSynapseE2eHarness postCrash =
                HomeSynapseE2eHarness.start(dbPath, FIXED_CLOCK, TEST_HOME_ID, homeDefaultPolicy);
        try {
            LiveModeAwaiter.awaitLive(postCrash);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> postCrash.stateQueryService().getViewPosition() >= 5L);

            HttpResponse<String> response = executeGet(
                    postCrash.baseUri().resolve("/api/v1/entities"));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThatJson(response.body()).inPath("$.data").isArray().hasSize(5);
        } finally {
            postCrash.close();
        }
    }

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

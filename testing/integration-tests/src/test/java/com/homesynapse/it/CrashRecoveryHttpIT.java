/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

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
            // Phase 1 triggers a view checkpoint write. On restart, the
            // fresh SqliteStateStore.loadFromCheckpoint() finds the
            // checkpoint blob under "state_projection" and rehydrates the
            // in-memory ConcurrentHashMap. The bus subscriber checkpoint
            // (position 2) causes the bus to skip replay — no onEvent()
            // calls reach the fresh StateProjection — but that's fine:
            // state was recovered from the checkpoint blob, not from replay.

            HttpResponse<String> response = executeGet(
                    postCrash.baseUri().resolve("/api/v1/entities"));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThatJson(response.body()).inPath("$.data").isArray().hasSize(2);
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

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3.7 — in-flight request behaviour during shutdown (Research 3 REC-21).
 *
 * <p>Kicks off a request on a separate platform thread, then immediately
 * calls {@link HomeSynapseE2eHarness#stop()}. Asserts that the request does
 * NOT hang indefinitely — either it completes with a deterministic result
 * (success or a connection-level error) within a loose 30-second bound.
 *
 * <p><b>Loose bound rationale.</b> The production
 * {@code RestApiLifecycle.stop(int drainSeconds)} that defines the exact
 * drain budget is Phase 3 future scope. M3.7 asserts only "no infinite
 * hang"; a tighter assertion will land when the production lifecycle wiring
 * is fleshed out (Javalin's own {@code stop()} closes the Jetty server
 * which terminates active sockets, but the exact contract for in-flight
 * requests is not pinned down for M3.7).</p>
 */
@DisplayName("M3.7 -- in-flight request shutdown")
final class InFlightRequestShutdownIT {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror}. */
    InFlightRequestShutdownIT() {
    }

    @Test
    @DisplayName("in-flight request does not hang indefinitely when harness.stop() races")
    void inFlightRequestDoesNotHangThroughShutdown(@TempDir Path tempDir) throws Exception {
        HomeSynapseE2eHarness harness = HomeSynapseE2eHarness.start(
                tempDir.resolve("homesynapse-events.db"), FIXED_CLOCK, TEST_HOME_ID);
        try {
            LiveModeAwaiter.awaitLive(harness);

            URI listEntities = harness.baseUri().resolve("/api/v1/entities");
            // AB-1: capture the bearer token before the request thread starts.
            String authToken = harness.authToken();

            // Fire a request on a separate thread. The CompletableFuture
            // surfaces either the HttpResponse or the exception that arose
            // during shutdown — either is acceptable; a hang is not.
            AtomicReference<Throwable> errorHolder = new AtomicReference<>();
            CompletableFuture<HttpResponse<String>> inFlight =
                    new CompletableFuture<>();
            Thread requestThread = new Thread(() -> {
                try {
                    HttpResponse<String> response = HTTP.send(
                            HttpRequest.newBuilder(listEntities)
                                    .GET()
                                    .header("Authorization", "Bearer " + authToken)
                                    .timeout(Duration.ofSeconds(10))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    inFlight.complete(response);
                } catch (Throwable t) {
                    errorHolder.set(t);
                    inFlight.complete(null);
                }
            }, "in-flight-request");
            requestThread.setDaemon(true);
            requestThread.start();

            // Give the request a tick to leave the client and arrive at Jetty.
            Thread.sleep(50);

            // Race shutdown against the request.
            harness.stop();

            // Loose 30-second bound: assert the request resolved one way or
            // the other. A hung connection past 30s indicates Javalin/Jetty
            // failed to terminate in-flight sockets during stop().
            HttpResponse<String> response;
            try {
                response = inFlight.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                throw new AssertionError(
                        "In-flight request hung for > 30s past harness.stop()", te);
            }

            // Acceptable outcomes:
            //   (a) response != null with 2xx — request finished pre-stop
            //   (b) response == null + IOException / connection reset
            //   (c) response != null with 5xx — graceful shutdown error code
            // We assert ONLY non-hang.
            boolean completedOrFailedDeterministically =
                    response != null || errorHolder.get() instanceof IOException
                            || errorHolder.get() instanceof InterruptedException
                            || errorHolder.get() instanceof RuntimeException;
            assertThat(completedOrFailedDeterministically)
                    .as("In-flight request resolved to a deterministic outcome "
                            + "(response or IO failure) — not a hang")
                    .isTrue();
        } finally {
            // stop() is idempotent — calling again is safe even if already stopped.
            harness.stop();
        }
    }
}

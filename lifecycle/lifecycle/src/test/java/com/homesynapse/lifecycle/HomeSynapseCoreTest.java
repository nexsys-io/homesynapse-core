/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.persistence.EncryptedPayload;
import com.homesynapse.persistence.PayloadCipher;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@link HomeSynapseCore} (M3.6d-b, updated for AB-3).
 *
 * <p>Exercises the composition root from boot through shutdown: configuration +
 * persistence + bus + device registries + projection + automation engine must
 * all stand up together; the accessors must guard against use-before-start; and
 * the projection must persist a published event.</p>
 *
 * <p>AB-3: {@link HomeSynapseCore#start()} returns {@code void} (implements
 * {@link SystemLifecycleManager}) and gates the HTTP surface CLOSED — the HTTP
 * tests below call {@link HomeSynapseCore#exposeHttpSurface()} explicitly.</p>
 *
 * <p>Uses {@link Clock#systemUTC()} because these are lifecycle tests that need
 * real time for the bus's per-subscriber VTs to park and unpark.</p>
 */
@DisplayName("HomeSynapseCore -- composition root")
final class HomeSynapseCoreTest {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private HomeSynapseCore core;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    HomeSynapseCoreTest() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    @Test
    @DisplayName("start brings up the runtime; stop tears it down")
    void startAndStop(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("homesynapse-events.db");
        core = new HomeSynapseCore(
                dbPath, tempDir.resolve("config"), HomeSynapseConfig.testing(),
                Clock.systemUTC(), TEST_HOME_ID);

        assertThatCode(() -> core.start()).doesNotThrowAnyException();

        assertThat(core.eventPublisher()).isNotNull();
        assertThat(core.eventStore()).isNotNull();
        assertThat(core.eventBus()).isNotNull();

        assertThatCode(core::stop).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accessors throw IllegalStateException before start")
    void accessorsThrowBeforeStart(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);

        assertThatThrownBy(core::eventPublisher)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(core::eventStore)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(core::eventBus)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(core::stateQueryService)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("stop is idempotent")
    void stopIsIdempotent(@TempDir Path tempDir) throws Exception {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);
        core.start();
        core.stop();

        assertThatCode(core::stop).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("mode returns COLD before start")
    void modeReturnsColdBeforeStart(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);

        assertThat(core.mode()).isEqualTo(SubscriberMode.COLD);
    }

    @Test
    @DisplayName("publish flows through the event store after start")
    void startPublishAndQuery(@TempDir Path tempDir) throws Exception {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);
        core.start();

        EntityId entityId = new EntityId(Ulid.parse("01JBBBBBBBBBBBBBBBBBBBBBBB"));
        EventDraft draft = new EventDraft(
                EventTypes.STATE_REPORTED,
                1,
                null,
                SubjectRef.entity(entityId),
                EventPriority.DIAGNOSTIC,
                EventOrigin.DEVICE_AUTONOMOUS,
                new StateReportedEvent("power", "on", null, null, null),
                null,
                null);

        EventEnvelope published = core.eventPublisher().publishRoot(draft);
        assertThat(published).isNotNull();
        assertThat(published.globalPosition()).isGreaterThan(0);

        EventPage page = core.eventStore().readFrom(0L, 10);
        assertThat(page.events()).isNotEmpty();
        assertThat(page.events().get(0).eventType()).isEqualTo(EventTypes.STATE_REPORTED);
    }

    @Test
    @DisplayName("stateQueryService returns the MaterializedStateQueryService after M3.6e.1")
    void stateQueryServiceReturnsMaterializedAfterM3_6e_1(@TempDir Path tempDir) throws Exception {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);
        core.start();

        // The real query service no longer throws; an unknown entity returns
        // Optional.empty(), and isReady() reflects the projection's lifecycle
        // mode rather than throwing.
        assertThat(core.stateQueryService()).isNotNull();
        assertThat(core.stateQueryService().getState(
                new EntityId(Ulid.parse("01JCCCCCCCCCCCCCCCCCCCCCCC"))))
                .isEmpty();
        // The same instance is returned on every call.
        assertThat(core.stateQueryService()).isSameAs(core.stateQueryService());
    }

    // ── AB-1 — start() opens HTTP only behind auth, loopback-bound (C1 close) ──

    @Test
    @DisplayName("start opens HTTP only behind auth: unauth /api/* AND /internal/* are 401, "
            + "loopback-bound, an authenticated request is admitted (AB-1; C1 closed)")
    void opensHttpOnlyBehindAuth(@TempDir Path tempDir) throws Exception {
        Path configDir = tempDir.resolve("config");
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), configDir,
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);
        core.start();

        // C1 closed: production start() now binds the (ephemeral, loopback) port.
        assertThat(core.isHttpExposed()).isTrue();
        int port = core.boundHttpPort();
        assertThat(port).isGreaterThan(0);

        // exposeHttpSurface() is idempotent — a second call does not rebind.
        core.exposeHttpSurface();
        assertThat(core.boundHttpPort()).isEqualTo(port);

        // (a) Unauthenticated /api/* AND /internal/* are rejected 401 — the auth
        //     filter runs before the readiness gate and before any handler (INV-SE-02).
        assertThat(get(port, "/api/v1/entities", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/internal/dlq", null).statusCode()).isEqualTo(401);

        // (b) An authenticated request (the first-run pairing token) is admitted
        //     past the auth filter — not 401, not 403.
        String token = Files.readString(configDir.resolve("initial_api_token")).trim();
        int authed = get(port, "/api/v1/entities", token).statusCode();
        assertThat(authed).isNotEqualTo(401);
        assertThat(authed).isNotEqualTo(403);

        // The production default is loopback:7070 (constant check — no extra bind).
        assertThat(HomeSynapseConfig.HOME_DEFAULT.httpPort()).isEqualTo(7070);
        assertThat(HomeSynapseConfig.HOME_DEFAULT.bindHost())
                .isEqualTo(HomeSynapseConfig.LOOPBACK_HOST);
    }

    @Test
    @DisplayName("the bound socket answers only on loopback by default (CC-1) — a non-loopback "
            + "local address is refused")
    void httpSurfaceBindsLoopbackOnly(@TempDir Path tempDir) throws Exception {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);
        core.start();
        int port = core.boundHttpPort();

        // Loopback reaches the server (the auth filter answers 401).
        assertThat(get(port, "/api/v1/entities", null).statusCode()).isEqualTo(401);

        // A non-loopback local address must be refused for a loopback-bound server.
        // Skipped when the host has no usable non-loopback interface (e.g. CI).
        Optional<InetAddress> nonLoopback = firstNonLoopbackSiteLocal();
        assumeTrue(nonLoopback.isPresent(),
                "no non-loopback site-local address available to test refusal");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(nonLoopback.get(), port), 1000);
            org.junit.jupiter.api.Assertions.fail(
                    "a non-loopback connection to a loopback-bound server must be refused");
        } catch (IOException expected) {
            // Connection refused or timed out — the server is not on this interface.
        }
    }

    private static HttpResponse<String> get(int port, String path, String bearerToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .timeout(Duration.ofSeconds(5));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Optional<InetAddress> firstNonLoopbackSiteLocal() throws Exception {
        for (NetworkInterface nic : java.util.Collections.list(
                NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
                if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                    return Optional.of(address);
                }
            }
        }
        return Optional.empty();
    }

    @Test
    @DisplayName("boundHttpPort throws IllegalStateException before start")
    void boundHttpPort_throwsBeforeStart(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);

        assertThatThrownBy(core::boundHttpPort)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");
    }

    @Test
    @DisplayName("mode returns LIVE once the projection completes replay")
    void mode_returnsLiveAfterProjectionCompletesReplay(@TempDir Path tempDir) throws Exception {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"), tempDir.resolve("config"),
                HomeSynapseConfig.testing(), Clock.systemUTC(), TEST_HOME_ID);
        core.start();

        // start() already gates on the projection reaching LIVE (the automation
        // catch-up ordering invariant), so mode() is LIVE immediately on return;
        // Awaitility's polling keeps the established M3.7 idiom and stays
        // NO_DIRECT_TIME_ACCESS-safe.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> core.mode() == SubscriberMode.LIVE);
        assertThat(core.mode()).isEqualTo(SubscriberMode.LIVE);
    }

    // ── M6.2 — PayloadCipher seam (Doc 15 §3.8 / CARRY 1) ───────────────

    @Test
    @DisplayName("the six-arg constructor accepts and holds the PayloadCipher seam;"
            + " the runtime boots and stops with it")
    void constructorAcceptsPayloadCipherSeam(@TempDir Path tempDir) {
        // A trivial stand-in is enough here: the lifecycle module forwards the
        // cipher to the persistence write path. The real adapter round-trip lives
        // in the app module's PayloadCipherBridgeTest.
        PayloadCipher cipher = new PayloadCipher() {
            @Override
            public EncryptedPayload encrypt(String scopeId, byte[] plaintext, byte[] aad) {
                return new EncryptedPayload(plaintext.clone(), new byte[12], 1);
            }

            @Override
            public byte[] decrypt(String scopeId, int keyVersion,
                                  byte[] ciphertext, byte[] iv, byte[] aad) {
                return ciphertext.clone();
            }
        };
        // Clock.fixed per the §4c rule — the runtime boots and stops under a
        // fixed clock (the CrashRecoveryHttpIT precedent).
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC),
                TEST_HOME_ID,
                cipher);

        assertThatCode(() -> core.start()).doesNotThrowAnyException();
        assertThatCode(core::stop).doesNotThrowAnyException();
    }
}

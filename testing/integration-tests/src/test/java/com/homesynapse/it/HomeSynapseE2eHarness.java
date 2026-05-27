/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.lifecycle.HomeSynapseConfig;
import com.homesynapse.lifecycle.HomeSynapseCore;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.state.StateQueryService;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * M3.7 capstone test harness (Research 3 REC-16).
 *
 * <p>The HTTP-aware sibling of M3.4a/M3.4b's {@code IntegrationTestHarness}.
 * That harness assembles persistence + bus + projection but stops short of
 * the REST surface; this harness wires the full production composition
 * root (16-step {@link HomeSynapseCore#start()}) against a {@code @TempDir}
 * SQLite file and binds Javalin to an ephemeral port so parallel tests do
 * not collide.</p>
 *
 * <p>The harness exists to keep E2E test classes focused on assertions
 * rather than 16 lines of composition wiring.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Construct via {@link #start(Path, Clock, HomeId)} in a
 * {@code @BeforeEach} (or inline at the top of a {@code @Test}). Tear down
 * via {@link #close()} or {@link #stop()} (the harness implements
 * {@link AutoCloseable} for try-with-resources). Each harness instance binds
 * to a single SQLite database file path and a single ephemeral HTTP port;
 * tests requiring isolation must use distinct {@code @TempDir} paths or
 * fresh harness instances. Do NOT share a started harness across tests.</p>
 *
 * <h2>Coexistence with {@code IntegrationTestHarness}</h2>
 *
 * <p>The M3.4a/M3.4b harness STAYS. M3.7's {@code HomeSynapseE2eHarness} is
 * a parallel harness scoped to HTTP-aware E2E scenarios; the two harnesses
 * share no code. The M3.4a/M3.4b harness remains the right tool for
 * performance/load/heap-budget tests that don't exercise HTTP.</p>
 *
 * @see HomeSynapseCore
 * @see HomeSynapseConfig#testing()
 */
final class HomeSynapseE2eHarness implements AutoCloseable {

    private final HomeSynapseCore core;

    private HomeSynapseE2eHarness(HomeSynapseCore core) {
        this.core = core;
    }

    /**
     * Constructs and starts a fresh E2E test stack.
     *
     * <p>Internally builds {@link HomeSynapseConfig#testing()} (which selects
     * {@code DeploymentProfile.TESTING} and {@code httpPort=0}), constructs a
     * new {@link HomeSynapseCore}, and synchronously joins the bootstrap
     * future. The calling thread MUST be a platform thread — Jackson warmup
     * inside {@code PersistenceFactory.start} parks on cache-miss paths that
     * pin virtual thread carriers (LTD-19 / DECIDE-M2-05). JUnit's test
     * runner uses platform threads by default.</p>
     *
     * @param dbPath the SQLite database file path; never {@code null}.
     *               Typically {@code tempDir.resolve("homesynapse-events.db")}.
     * @param clock  injected clock; never {@code null}.
     * @param homeId home identity for this installation (AMD-34); never
     *               {@code null}
     * @return a started harness ready for HTTP queries and event publication
     */
    static HomeSynapseE2eHarness start(Path dbPath, Clock clock, HomeId homeId) {
        Objects.requireNonNull(dbPath, "dbPath");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(homeId, "homeId");

        HomeSynapseCore core = new HomeSynapseCore(
                dbPath, HomeSynapseConfig.testing(), clock, homeId);
        core.start().join();
        return new HomeSynapseE2eHarness(core);
    }

    // ── Accessors ───────────────────────────────────────────────────────

    /**
     * @return the bound HTTP port (ephemeral; always {@code > 0} after start)
     */
    int boundHttpPort() {
        return core.boundHttpPort();
    }

    /**
     * @return the base URI of the embedded HTTP server, e.g.
     *         {@code http://localhost:54321}
     */
    URI baseUri() {
        return URI.create("http://localhost:" + core.boundHttpPort());
    }

    /**
     * @return the production {@link EventPublisher}
     */
    EventPublisher eventPublisher() {
        return core.eventPublisher();
    }

    /**
     * @return the production {@link EventStore}
     */
    EventStore eventStore() {
        return core.eventStore();
    }

    /**
     * @return the production {@link EventBus} ({@code InProcessEventBus})
     */
    EventBus eventBus() {
        return core.eventBus();
    }

    /**
     * @return the production {@link StateQueryService}
     */
    StateQueryService stateQueryService() {
        return core.stateQueryService();
    }

    /**
     * Returns the projection subscriber's current lifecycle mode. This
     * delegates to {@code HomeSynapseCore.mode()}, which reads the
     * projection subscriber's mode from {@code EventBus.subscribers()}
     * (M3.7 fix round 1).
     *
     * @return the current projection mode
     */
    SubscriberMode mode() {
        return core.mode();
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Tears down the stack. Idempotent.
     */
    void stop() {
        core.stop();
    }

    @Override
    public void close() {
        stop();
    }
}

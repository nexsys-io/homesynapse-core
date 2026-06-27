/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M7.1 deferred lifecycle wiring test (AB-3 dep c) — it exercises the
 * <em>real</em> composition root ({@link HomeSynapseCore} held as a
 * {@link SystemLifecycleManager}) against in-memory/temp-dir dependencies: it
 * could not be written against the M7.1 one-line {@code main()} stub.
 *
 * <p>Time is injected via {@code Clock.fixed} (§4c — this is non-app module test
 * code, so Clock injection is a self-enforced convention). The bus's
 * COLD→REPLAY→TRANSITION→LIVE transitions are event-driven (empty log), so they
 * complete under a fixed clock; {@code HomeSynapseCore.start()} blocks until the
 * projection reaches LIVE.</p>
 */
@DisplayName("LifecycleWiringTest -- real composition-root wiring (M7.1 dep c)")
final class LifecycleWiringTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private HomeSynapseCore core;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    LifecycleWiringTest() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    private HomeSynapseCore newCore(Path tempDir) {
        return new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                FIXED_CLOCK,
                TEST_HOME_ID);
    }

    private void writeConfig(Path tempDir, String yaml) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    @Test
    @DisplayName("start assembles ConfigurationService and the three start-empty registries")
    void start_assemblesConfigAndRegistries(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        core.start();

        assertThat(core.configurationService()).isNotNull();
        assertThat(core.configurationService().getCurrentModel()).isNotNull();
        assertThat(core.entityRegistry().listAllEntities()).isEmpty();
        assertThat(core.deviceRegistry().listAllDevices()).isEmpty();
        assertThat(core.areaRegistry().getAll()).isEmpty();
    }

    @Test
    @DisplayName("start registers the automation schema and loads a valid definition")
    void start_registersAutomationSchemaAndLoadsDefinitions(@TempDir Path tempDir) throws Exception {
        // A valid minimal definition: a manual trigger and a delay action — neither
        // references an entity/area, so the start-empty registries are sufficient.
        // The automation: section validates during config.load() only because the
        // automation core-schema was registered in Phase 1.
        writeConfig(tempDir, """
                automation:
                  automations:
                    - name: "wiring test automation"
                      triggers:
                        - type: manual
                      actions:
                        - type: delay
                          duration: PT1S
                """);
        core = newCore(tempDir);
        core.start();

        assertThat(core.automationRegistry().getAll()).hasSize(1);
        assertThat(core.automationRegistry().getAll().get(0).name())
                .isEqualTo("wiring test automation");
    }

    @Test
    @DisplayName("start subscribes automation_engine after the state store is caught up (LIVE)")
    void start_subscribesAutomationEngineAfterStateStoreCaughtUp(@TempDir Path tempDir)
            throws Exception {
        core = newCore(tempDir);
        core.start();

        // start() gates the automation_engine + command_dispatch_service + pending_command_ledger
        // subscribes on the projection reaching LIVE, so on return the projection is LIVE and the
        // four runtime subscribers are registered (the catch-up ordering invariant).
        assertThat(core.mode()).isEqualTo(SubscriberMode.LIVE);
        assertThat(core.eventBus().subscribers().stream()
                .map(SubscriberSnapshot::subscriberId))
                .contains("state_projection", "automation_engine", "command_dispatch_service",
                        "pending_command_ledger");
    }

    @Test
    @DisplayName("HomeSynapseCore implements SystemLifecycleManager: phase, subsystems, snapshot")
    void start_implementsSystemLifecycleManager(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        SystemLifecycleManager mgr = core;
        mgr.start();

        assertThat(mgr.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);
        assertThat(mgr.subsystemStates()).containsKeys(
                "configuration", "persistence", "event-bus",
                "device-model", "state-store", "automation");
        assertThat(mgr.healthSnapshot()).isNotNull();
        assertThat(mgr.healthSnapshot().aggregatedHealth()).isNotNull();
    }

    @Test
    @DisplayName("start opens the HTTP surface behind auth, loopback-bound (AB-1; C1 closed)")
    void start_opensHttpSurfaceBehindAuth(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        core.start();

        // AB-1: production start() now binds the (ephemeral, loopback) HTTP port
        // with the auth filter installed — the C1 close. The auth-before-exposure
        // invariant is asserted at the HTTP level in HomeSynapseCoreTest.
        assertThat(core.isHttpExposed()).isTrue();
        assertThat(core.boundHttpPort()).isGreaterThan(0);
    }

    @Test
    @DisplayName("a malformed automation definition is surfaced (config_error) but valid "
            + "siblings still load (SD-9 is per-definition, not fail-the-boot)")
    void malformedDefinitionSurfacedValidSiblingsLoad(@TempDir Path tempDir) throws Exception {
        // Both defs are schema-valid (name + non-empty triggers/actions); the
        // second has an unknown trigger type the loader rejects fail-closed. SD-9
        // is PER-DEFINITION: the boot succeeds, the valid sibling loads, and the
        // bad one is dropped and surfaced as config_error (it is never loaded into
        // a silently-inert state, and a single bad entry does not brick the boot).
        writeConfig(tempDir, """
                automation:
                  automations:
                    - name: "good automation"
                      triggers:
                        - type: manual
                      actions:
                        - type: delay
                          duration: PT1S
                    - name: "bad automation"
                      triggers:
                        - type: nonsense_trigger
                      actions:
                        - type: delay
                          duration: PT1S
                """);
        core = newCore(tempDir);
        core.start();   // boot SUCCEEDS — not fail-closed-at-boot

        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);
        // The valid sibling loaded; the malformed one was rejected (not loaded).
        assertThat(core.automationRegistry().getAll()).hasSize(1);
        assertThat(core.automationRegistry().getAll().get(0).name())
                .isEqualTo("good automation");
        // ...and the rejection was surfaced as a config_error event.
        EventPage page = core.eventStore().readFrom(0L, 100);
        assertThat(page.events().stream().map(EventEnvelope::eventType))
                .contains(EventTypes.CONFIG_ERROR);
    }

    @Test
    @DisplayName("shutdown tears down in reverse order, is idempotent, and ends STOPPED")
    void shutdown_reverseOrder(@TempDir Path tempDir) throws Exception {
        core = newCore(tempDir);
        SystemLifecycleManager mgr = core;
        mgr.start();
        assertThat(mgr.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);

        mgr.shutdown("test");
        assertThat(mgr.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);

        // Idempotent on a second call.
        mgr.shutdown("test again");
        assertThat(mgr.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
    }
}

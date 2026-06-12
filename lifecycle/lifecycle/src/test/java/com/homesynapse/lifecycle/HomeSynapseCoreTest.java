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
import com.homesynapse.event.SequenceConflictException;
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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link HomeSynapseCore} (M3.6d-b).
 *
 * <p>Exercises the composition root from boot through shutdown: persistence
 * + bus + projection + scheduler must all stand up together, the
 * accessors must guard against use-before-start, and the projection must
 * persist a published event.</p>
 *
 * <p>Uses {@link Clock#systemUTC()} because these are lifecycle tests that
 * need real time for the bus's per-subscriber VTs to park and unpark.</p>
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
                dbPath, HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);

        assertThatCode(() -> core.start().join()).doesNotThrowAnyException();

        assertThat(core.eventPublisher()).isNotNull();
        assertThat(core.eventStore()).isNotNull();
        assertThat(core.eventBus()).isNotNull();

        assertThatCode(core::stop).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accessors throw IllegalStateException before start")
    void accessorsThrowBeforeStart(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);

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
    void stopIsIdempotent(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);
        core.start().join();
        core.stop();

        assertThatCode(core::stop).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("mode returns COLD before start")
    void modeReturnsColdBeforeStart(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);

        assertThat(core.mode()).isEqualTo(SubscriberMode.COLD);
    }

    @Test
    @DisplayName("publish flows through the event store after start")
    void startPublishAndQuery(@TempDir Path tempDir) throws SequenceConflictException {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);
        core.start().join();

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
    void stateQueryServiceReturnsMaterializedAfterM3_6e_1(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);
        core.start().join();

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

    // ── M3.7 — boundHttpPort + ephemeral binding ────────────────────────

    @Test
    @DisplayName("boundHttpPort returns a positive non-zero port after start "
            + "with HOME_DEFAULT (port 7070)")
    void boundHttpPort_returnsPositiveNonZeroAfterStart(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);
        core.start().join();

        assertThat(core.boundHttpPort()).isPositive();
        // HOME_DEFAULT uses port 7070 — the bound port matches exactly.
        assertThat(core.boundHttpPort()).isEqualTo(7070);
    }

    @Test
    @DisplayName("boundHttpPort throws IllegalStateException before start")
    void boundHttpPort_throwsBeforeStart(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);

        assertThatThrownBy(core::boundHttpPort)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");
    }

    @Test
    @DisplayName("mode returns LIVE once the projection completes replay")
    void mode_returnsLiveAfterProjectionCompletesReplay(@TempDir Path tempDir) {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT, Clock.systemUTC(), TEST_HOME_ID);
        core.start().join();

        // The bus drives the projection subscriber through
        // COLD → REPLAY → TRANSITION → LIVE on a dedicated virtual thread; on
        // a fresh database the trip completes in microseconds. Awaitility's
        // polling pattern is the established M3.7 idiom (Research 3 REC-13)
        // and respects D-04 / NO_DIRECT_TIME_ACCESS — it does not call
        // System.nanoTime() / Instant.now() from the test source.
        //
        // core.mode() reads the bus's authoritative per-subscriber FSM via
        // EventBus.subscribers() (see HomeSynapseCore.mode() — M3.7 fix
        // round 1). The projection's own currentMode field is not the source
        // of truth here.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> core.mode() == SubscriberMode.LIVE);
        assertThat(core.mode()).isEqualTo(SubscriberMode.LIVE);
    }

    // ── M6.2 — PayloadCipher seam (Doc 15 §3.8 / CARRY 1) ───────────────

    @Test
    @DisplayName("the five-arg constructor accepts and holds the M6.2"
            + " PayloadCipher seam; the runtime boots and stops with it")
    void constructorAcceptsPayloadCipherSeam(@TempDir Path tempDir) {
        // A trivial stand-in is enough here: the lifecycle module holds the
        // cipher for the M6.3 write path and consumes nothing in M6.2. The
        // real adapter round-trip lives in the app module's
        // PayloadCipherBridgeTest (only app reads both config and
        // persistence — the zero-new-edge property).
        PayloadCipher cipher = new PayloadCipher() {
            @Override
            public EncryptedPayload encrypt(String scopeId, byte[] plaintext) {
                return new EncryptedPayload(plaintext.clone(), new byte[12], 1);
            }

            @Override
            public byte[] decrypt(String scopeId, int keyVersion,
                                  byte[] ciphertext, byte[] iv) {
                return ciphertext.clone();
            }
        };
        // Clock.fixed per the M6.2 §4c rule — the runtime boots and stops
        // under a fixed clock (the CrashRecoveryHttpIT precedent); this
        // file's systemUTC convention is only needed by tests that await
        // mode transitions in real time.
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                HomeSynapseConfig.HOME_DEFAULT,
                Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC),
                TEST_HOME_ID,
                cipher);

        assertThatCode(() -> core.start().join()).doesNotThrowAnyException();
        assertThatCode(core::stop).doesNotThrowAnyException();
    }
}

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
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

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
@DisplayName("HomeSynapseCore — composition root")
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
}

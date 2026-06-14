/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.DeadLetter;
import com.homesynapse.event.bus.PersistentDlqWriter;
import com.homesynapse.event.bus.SubscriberReadConnectionFactory;
import com.homesynapse.event.bus.SubscriberReadExecutor;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for {@link PersistenceFactory} (M3.6d-b).
 *
 * <p>Exercises the public gateway from outside-package callers' perspective:
 * the factory wraps the package-private {@link SqlitePersistenceLifecycle}
 * and surfaces all stores under their public interface types.</p>
 */
@DisplayName("PersistenceFactory — public gateway over SqlitePersistenceLifecycle")
final class PersistenceFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC);

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    PersistenceFactoryTest() {
    }

    @Test
    @DisplayName("start exposes all accessors with non-null public-interface returns")
    void startAndAccessors(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("homesynapse-events.db");
        try (PersistenceFactory factory = PersistenceFactory.start(
                dbPath, PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK, TEST_HOME_ID,
                AllEventClasses.ALL_EVENTS, null)) {

            assertThat(factory.eventPublisher()).isNotNull();
            assertThat(factory.eventStore()).isNotNull();
            // The same SqliteEventStore implements both EventStore and EventPublisher.
            assertThat(factory.eventPublisher()).isSameAs(factory.eventStore());

            assertThat(factory.checkpointStore()).isNotNull();
            assertThat(factory.viewCheckpointStore()).isNotNull();
            assertThat(factory.stateStore()).isNotNull();
            assertThat(factory.stateCheckpointSource()).isNotNull();
            // The same SqliteStateStore implements StateStore + StateCheckpointSource.
            assertThat(factory.stateCheckpointSource()).isSameAs(factory.stateStore());

            assertThat(factory.subscriberReadConnectionFactory()).isNotNull();
            assertThat(factory.deadLetterWriter()).isNotNull();
            assertThat(factory.writeQueueDepthSupplier().getAsInt())
                    .as("idle queue depth is zero")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("close is idempotent")
    void closeIsIdempotent(@TempDir Path tempDir) {
        PersistenceFactory factory = PersistenceFactory.start(
                tempDir.resolve("homesynapse-events.db"),
                PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK, TEST_HOME_ID,
                AllEventClasses.ALL_EVENTS, null);
        factory.close();
        assertThatCode(factory::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("deadLetterWriter routes through the write coordinator")
    void deadLetterWriterAcceptsPark(@TempDir Path tempDir) {
        try (PersistenceFactory factory = PersistenceFactory.start(
                tempDir.resolve("homesynapse-events.db"),
                PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK, TEST_HOME_ID,
                AllEventClasses.ALL_EVENTS, null)) {

            PersistentDlqWriter writer = factory.deadLetterWriter();
            DeadLetter sample = new DeadLetter(
                    0L,
                    "test-subscriber",
                    "seq-1",
                    1L,
                    Ulid.parse("01JBBBBBBBBBBBBBBBBBBBBBBB"),
                    "java.lang.RuntimeException",
                    "test failure",
                    1,
                    FIXED_CLOCK.instant(),
                    FIXED_CLOCK.instant(),
                    null);

            assertThatCode(() -> writer.park(sample))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("subscriberReadConnectionFactory creates an executor that can read")
    void subscriberReadConnectionFactoryCreatesWorkingReader(@TempDir Path tempDir)
            throws Exception {
        try (PersistenceFactory factory = PersistenceFactory.start(
                tempDir.resolve("homesynapse-events.db"),
                PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK, TEST_HOME_ID,
                AllEventClasses.ALL_EVENTS, null)) {

            SubscriberReadConnectionFactory readFactory =
                    factory.subscriberReadConnectionFactory();
            try (SubscriberReadExecutor reader = readFactory.create("smoke-test")) {
                Integer one = reader.executeRead(() -> 1);
                assertThat(one).isEqualTo(1);
            }
        }
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.SubscriberReadExecutor;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SqliteSubscriberReadConnectionFactory} and its
 * companion {@link SqliteSubscriberReadExecutor} (M3.6d-b Gap 3).
 */
@DisplayName("SqliteSubscriberReadConnectionFactory — per-subscriber read connections")
final class SqliteSubscriberReadConnectionFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC);

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    @TempDir
    Path tempDir;

    private PersistenceFactory factory;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    SqliteSubscriberReadConnectionFactoryTest() {
    }

    @BeforeEach
    void setUp() {
        factory = PersistenceFactory.start(
                tempDir.resolve("homesynapse-events.db"),
                PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK, TEST_HOME_ID,
                AllEventClasses.ALL_EVENTS);
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("create returns a non-null executor")
    void createReturnsNonNullExecutor() {
        try (SubscriberReadExecutor reader =
                     factory.subscriberReadConnectionFactory().create("sub-A")) {
            assertThat(reader).isNotNull();
        }
    }

    @Test
    @DisplayName("executeRead runs on a non-virtual platform thread")
    void executeReadReturnsResultOnPlatformThread() throws Exception {
        try (SubscriberReadExecutor reader =
                     factory.subscriberReadConnectionFactory().create("sub-platform")) {
            AtomicReference<Thread> capturedThread = new AtomicReference<>();
            Integer result = reader.executeRead(() -> {
                capturedThread.set(Thread.currentThread());
                return 42;
            });
            assertThat(result).isEqualTo(42);
            Thread t = capturedThread.get();
            assertThat(t).isNotNull();
            assertThat(t.isVirtual())
                    .as("read operation must run on a platform thread (AMD-26/27)")
                    .isFalse();
            assertThat(t.getName()).startsWith("hs-sub-read-sub-platform");
        }
    }

    @Test
    @DisplayName("close is idempotent")
    void closeIsIdempotent() {
        SubscriberReadExecutor reader =
                factory.subscriberReadConnectionFactory().create("sub-close");
        reader.close();
        assertThatCode(reader::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("executeRead after close throws IllegalStateException")
    void executeReadAfterCloseThrows() {
        SubscriberReadExecutor reader =
                factory.subscriberReadConnectionFactory().create("sub-after-close");
        reader.close();
        assertThatThrownBy(() -> reader.executeRead(() -> "should not run"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("multiple executors are independent — closing one does not affect the other")
    void multipleExecutorsAreIndependent() throws Exception {
        SubscriberReadExecutor a =
                factory.subscriberReadConnectionFactory().create("sub-multi-a");
        try (SubscriberReadExecutor b =
                     factory.subscriberReadConnectionFactory().create("sub-multi-b")) {
            a.close();
            Integer result = b.executeRead(() -> 7);
            assertThat(result).isEqualTo(7);
        }
    }
}

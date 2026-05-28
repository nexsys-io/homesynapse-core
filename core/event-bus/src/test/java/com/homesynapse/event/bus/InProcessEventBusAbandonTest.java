/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.bus.test.InMemoryCheckpointStore;
import com.homesynapse.event.bus.test.RecordingReadConnectionFactory;
import com.homesynapse.event.test.InMemoryEventStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link InProcessEventBus#abandon()} and its mutual exclusion
 * with {@link InProcessEventBus#unsubscribe(String)} (M3.7 abandon contract).
 *
 * <p>The four scenarios match the four-case unit test pattern documented in
 * the M3.7 task brief: abandon closes all subscriber runtimes,
 * unsubscribe-after-abandon is a no-op, abandon-after-unsubscribe is a
 * no-op, double-abandon is a no-op.</p>
 *
 * <p>Constructs the bus through the public
 * {@link InProcessEventBusFactory} per the brief's testing guidance, so the
 * tests exercise the same construction path production callers do. Lives in
 * the {@code com.homesynapse.event.bus} package so it can name the concrete
 * {@code InProcessEventBus} type returned by the factory cast — package-
 * private access is not actually needed.</p>
 */
@DisplayName("InProcessEventBus.abandon — mutual exclusion with unsubscribe")
final class InProcessEventBusAbandonTest {

    private static final String SUBSCRIBER_ID = "test-subscriber";

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-27T12:00:00Z"), ZoneOffset.UTC);

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    InProcessEventBusAbandonTest() {
    }

    @Test
    @DisplayName("abandon clears the active registry of every subscribed runtime")
    void abandonClosesAllSubscriberRuntimes() {
        InProcessEventBus bus = newBus();
        bus.subscribeRuntime(infoFor(SUBSCRIBER_ID), new NoopSubscriber());

        assertThat(bus.subscribers())
                .as("subscribers() reports the active runtime")
                .hasSize(1);

        bus.abandon();

        assertThat(bus.subscribers())
                .as("subscribers() is empty after abandon clears the registry")
                .isEmpty();
    }

    @Test
    @DisplayName("unsubscribe after abandon is a no-op (no exception, no error)")
    void unsubscribeAfterAbandonIsNoOp() {
        InProcessEventBus bus = newBus();
        bus.subscribeRuntime(infoFor(SUBSCRIBER_ID), new NoopSubscriber());
        bus.abandon();

        assertThatCode(() -> bus.unsubscribe(SUBSCRIBER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("abandon after unsubscribe is a no-op (no exception, no double-release)")
    void abandonAfterUnsubscribeIsNoOp() {
        InProcessEventBus bus = newBus();
        bus.subscribeRuntime(infoFor(SUBSCRIBER_ID), new NoopSubscriber());
        bus.unsubscribe(SUBSCRIBER_ID);

        assertThatCode(bus::abandon).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("double abandon is a no-op")
    void doubleAbandonIsNoOp() {
        InProcessEventBus bus = newBus();
        bus.subscribeRuntime(infoFor(SUBSCRIBER_ID), new NoopSubscriber());
        bus.abandon();

        assertThatCode(bus::abandon).doesNotThrowAnyException();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static InProcessEventBus newBus() {
        InMemoryEventStore eventStore = new InMemoryEventStore(FIXED_CLOCK);
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        RecordingReadConnectionFactory readFactory =
                new RecordingReadConnectionFactory();
        // The factory returns the production bus typed as EventBus; cast back
        // to the concrete type to reach abandon() and subscribers().
        return (InProcessEventBus) InProcessEventBusFactory.create(
                eventStore, checkpointStore, FIXED_CLOCK, readFactory);
    }

    private static SubscriberInfo infoFor(String subscriberId) {
        return new SubscriberInfo(
                subscriberId, SubscriptionFilter.all(), true);
    }

    /** Test subscriber that records nothing and does nothing on delivery. */
    private static final class NoopSubscriber implements Subscriber {

        NoopSubscriber() {
            // Explicit no-arg constructor for -Xlint:all -Werror.
        }

        @Override
        public void onEvent(EventEnvelope event) {
            // No-op.
        }
    }
}

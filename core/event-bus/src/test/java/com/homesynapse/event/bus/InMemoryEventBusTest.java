/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.bus.test.EventBusContractTest;
import com.homesynapse.event.bus.test.InMemoryCheckpointStore;
import com.homesynapse.event.bus.test.InMemoryEventBus;
import com.homesynapse.event.test.InMemoryEventStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/**
 * Wires {@link InMemoryEventBus} into the {@link EventBusContractTest} abstract
 * contract test suite.
 *
 * <p>This concrete test class provides factory methods that connect the in-memory
 * implementation to the 18-method contract test. No additional test methods are
 * needed — the contract test suite provides complete behavioral coverage.</p>
 *
 * @see EventBusContractTest
 * @see InMemoryEventBus
 * @see InMemoryEventStore
 * @see InMemoryCheckpointStore
 */
class InMemoryEventBusTest extends EventBusContractTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-04-07T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

    private InMemoryEventStore eventStore;
    private InMemoryCheckpointStore checkpointStore;
    private InMemoryEventBus bus;

    /** Creates a new test instance. */
    InMemoryEventBusTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    protected EventBus bus() {
        return bus;
    }

    @Override
    protected EventPublisher publisher() {
        return eventStore;
    }

    @Override
    protected EventStore store() {
        return eventStore;
    }

    @Override
    protected CheckpointStore checkpointStore() {
        return checkpointStore;
    }

    @Override
    protected void resetAll() {
        eventStore = new InMemoryEventStore(FIXED_CLOCK);
        checkpointStore = new InMemoryCheckpointStore();
        bus = new InMemoryEventBus(eventStore, checkpointStore);
    }

    @Override
    protected void subscribeWithCallback(SubscriberInfo info, Consumer<Long> handler) {
        bus.subscribeWithHandler(info, handler);
    }
}

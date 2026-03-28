/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.homesynapse.event.test.EventStoreContractTest;

/**
 * Wires {@link InMemoryEventStore} into the
 * {@link EventStoreContractTest} contract.
 *
 * <p>Uses a fixed clock for deterministic {@code ingestTime} and ULID generation.
 * The fixed clock ensures reproducible test behavior — ULID monotonicity is
 * maintained by {@link com.homesynapse.platform.identity.UlidFactory}'s
 * increment-on-same-millisecond behavior.</p>
 *
 * <p>This class has no additional test methods beyond the 27 inherited from
 * {@code EventStoreContractTest}. If all 27 pass, the in-memory implementation
 * satisfies the full publisher + store behavioral contract.</p>
 */
class InMemoryEventStoreTest extends EventStoreContractTest {

    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-01-01T00:00:00Z");

    private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private final InMemoryEventStore store = new InMemoryEventStore(clock);

    @Override
    protected EventPublisher publisher() {
        return store;
    }

    @Override
    protected EventStore store() {
        return store;
    }

    @Override
    protected void resetStore() {
        store.reset();
    }
}

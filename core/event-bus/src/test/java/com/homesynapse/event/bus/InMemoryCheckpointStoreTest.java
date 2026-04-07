/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.bus.test.CheckpointStoreContractTest;
import com.homesynapse.event.bus.test.InMemoryCheckpointStore;

/**
 * Wires {@link InMemoryCheckpointStore} into the {@link CheckpointStoreContractTest}
 * abstract contract test suite.
 *
 * <p>This concrete test class provides factory methods that connect the in-memory
 * implementation to the 9-method contract test. No additional test methods are needed —
 * the contract test suite provides complete behavioral coverage.</p>
 *
 * @see CheckpointStoreContractTest
 * @see InMemoryCheckpointStore
 */
class InMemoryCheckpointStoreTest extends CheckpointStoreContractTest {

    private InMemoryCheckpointStore store;

    /** Creates a new test instance. */
    InMemoryCheckpointStoreTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    protected CheckpointStore store() {
        return store;
    }

    @Override
    protected void resetStore() {
        store = new InMemoryCheckpointStore();
    }
}

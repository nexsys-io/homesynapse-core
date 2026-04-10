/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import org.junit.jupiter.api.DisplayName;

/**
 * Concrete contract test for {@link InMemoryReadExecutor}.
 *
 * <p>Wires the {@link ReadExecutorContractTest} abstract contract to the
 * in-memory test fixture implementation. All 5 inherited tests validate
 * that {@code InMemoryReadExecutor} satisfies the {@link ReadExecutor}
 * behavioral contract.</p>
 *
 * @see ReadExecutorContractTest
 * @see InMemoryReadExecutor
 */
@DisplayName("InMemoryReadExecutor")
final class InMemoryReadExecutorTest extends ReadExecutorContractTest {

    /** Creates a new test instance. */
    InMemoryReadExecutorTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    protected ReadExecutor createReadExecutor() {
        return new InMemoryReadExecutor();
    }
}

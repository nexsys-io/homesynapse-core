/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Concrete contract test for {@link PlatformThreadReadExecutor}.
 *
 * <p>Wires the {@link ReadExecutorContractTest} abstract contract to the
 * production read executor backed by a bounded platform thread pool. This
 * validates that the real pool-backed implementation honors the same
 * contract as the in-memory reference.</p>
 *
 * <p>Each test gets a fresh executor (2 platform read threads) because the
 * production executor owns platform threads and cannot be reused after
 * shutdown. The {@link #tearDown()} method guarantees shutdown after every
 * test to prevent thread leaks.</p>
 *
 * @see ReadExecutorContractTest
 * @see PlatformThreadReadExecutor
 */
@DisplayName("PlatformThreadReadExecutor")
final class PlatformThreadReadExecutorTest extends ReadExecutorContractTest {

    private PlatformThreadReadExecutor currentExecutor;

    /** Creates a new test instance. */
    PlatformThreadReadExecutorTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    protected ReadExecutor createReadExecutor() {
        if (currentExecutor != null) {
            currentExecutor.shutdown();
        }
        currentExecutor = new PlatformThreadReadExecutor(2);
        return currentExecutor;
    }

    @AfterEach
    void tearDown() {
        if (currentExecutor != null) {
            currentExecutor.shutdown();
            currentExecutor = null;
        }
    }
}

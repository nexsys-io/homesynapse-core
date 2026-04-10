/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Concrete contract test for {@link PlatformThreadWriteCoordinator}.
 *
 * <p>Wires the 11-method {@link WriteCoordinatorContractTest} abstract
 * contract to the production write coordinator. This validates that the
 * real platform-thread implementation — a single daemon platform thread
 * servicing a {@link java.util.concurrent.PriorityBlockingQueue} — honors
 * the same contract as the in-memory reference implementation.</p>
 *
 * <p>Each test gets a fresh coordinator via {@link #resetCoordinator()}
 * because the production coordinator owns a platform thread and cannot be
 * safely reused across tests once shutdown has been invoked. The
 * {@link #tearDown()} method ensures the coordinator is shut down after
 * every test, preventing thread leaks.</p>
 *
 * @see WriteCoordinatorContractTest
 * @see PlatformThreadWriteCoordinator
 */
@DisplayName("PlatformThreadWriteCoordinator")
final class PlatformThreadWriteCoordinatorTest extends WriteCoordinatorContractTest {

    private PlatformThreadWriteCoordinator coordinator;

    /** Creates a new test instance. */
    PlatformThreadWriteCoordinatorTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    protected WriteCoordinator coordinator() {
        return coordinator;
    }

    @Override
    protected void resetCoordinator() {
        // The production coordinator owns a live platform thread. A fresh
        // instance per test is the only safe option.
        if (coordinator != null) {
            coordinator.shutdown();
        }
        coordinator = new PlatformThreadWriteCoordinator();
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
            coordinator = null;
        }
    }
}

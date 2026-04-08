/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import org.junit.jupiter.api.DisplayName;

/**
 * Concrete contract test for {@link InMemoryWriteCoordinator}.
 *
 * <p>Wires the {@link WriteCoordinatorContractTest} abstract contract to
 * the in-memory test fixture implementation. All 11 inherited tests validate
 * that {@code InMemoryWriteCoordinator} satisfies the full
 * {@link WriteCoordinator} behavioral contract.</p>
 *
 * <p>This class lives in the {@code com.homesynapse.persistence} package
 * (same as the interface) within the {@code test} source set, giving it
 * package-private access to {@link InMemoryWriteCoordinator}.</p>
 *
 * @see WriteCoordinatorContractTest
 * @see InMemoryWriteCoordinator
 */
@DisplayName("InMemoryWriteCoordinator")
final class InMemoryWriteCoordinatorTest extends WriteCoordinatorContractTest {

    private InMemoryWriteCoordinator coordinator;

    /** Creates a new test instance. */
    InMemoryWriteCoordinatorTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    protected WriteCoordinator coordinator() {
        return coordinator;
    }

    @Override
    protected void resetCoordinator() {
        if (coordinator == null) {
            coordinator = new InMemoryWriteCoordinator();
        } else {
            coordinator.reset();
        }
    }
}

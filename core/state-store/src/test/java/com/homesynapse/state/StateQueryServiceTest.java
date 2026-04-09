/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Optional;

import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Interface shape verification for {@link StateQueryService}.
 *
 * <p>Follows the same pattern as EventBusTest and CheckpointStoreTest —
 * verifies the interface has exactly the expected methods with correct
 * signatures. These tests lock the public API shape so that Phase 3
 * implementations compile against a verified contract.</p>
 *
 * @see StateQueryService
 */
@DisplayName("StateQueryService")
class StateQueryServiceTest {

    /** Creates a new test instance. */
    StateQueryServiceTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    @DisplayName("StateQueryService is an interface")
    void isInterface() {
        assertThat(StateQueryService.class.isInterface()).isTrue();
    }

    @Test
    @DisplayName("exactly 5 declared methods")
    void exactlyFiveMethods() {
        assertThat(StateQueryService.class.getDeclaredMethods()).hasSize(5);
    }

    @Test
    @DisplayName("getState(EntityId) returns Optional<EntityState>")
    void getState_returnsOptionalEntityState() throws NoSuchMethodException {
        Method method = StateQueryService.class.getDeclaredMethod("getState", EntityId.class);

        assertThat(method.getReturnType()).isEqualTo(Optional.class);
        assertThat(method.getParameterTypes()).containsExactly(EntityId.class);
    }

    @Test
    @DisplayName("getSnapshot() returns StateSnapshot with no parameters")
    void getSnapshot_returnsStateSnapshot() throws NoSuchMethodException {
        Method method = StateQueryService.class.getDeclaredMethod("getSnapshot");

        assertThat(method.getReturnType()).isEqualTo(StateSnapshot.class);
        assertThat(method.getParameterCount()).isZero();
    }
}

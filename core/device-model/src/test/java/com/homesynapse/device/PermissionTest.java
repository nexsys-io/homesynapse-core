// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link Permission} — attribute access modes.
 */
@DisplayName("Permission")
class PermissionTest {

    @Test
    @DisplayName("exactly 3 values declared")
    void exactlyThreeValues() {
        assertThat(Permission.values()).hasSize(3);
    }

    @Test
    @DisplayName("all expected values present in order")
    void allExpectedValues() {
        assertThat(Permission.values()).containsExactly(
                Permission.READ,
                Permission.WRITE,
                Permission.NOTIFY);
    }

    @ParameterizedTest
    @EnumSource(Permission.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(Permission permission) {
        assertThat(Permission.valueOf(permission.name())).isEqualTo(permission);
    }
}

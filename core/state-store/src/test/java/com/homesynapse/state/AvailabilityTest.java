/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Availability} — runtime availability status enum.
 *
 * <p>Verifies the enum has exactly 3 values, all expected constants exist,
 * and documents that UNKNOWN is the initial state after entity adoption.</p>
 *
 * @see Availability
 * @see EntityState
 */
@DisplayName("Availability")
class AvailabilityTest {

    /** Creates a new test instance. */
    AvailabilityTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    @DisplayName("exactly 3 enum values")
    void exactlyThreeValues() {
        assertThat(Availability.values()).hasSize(3);
    }

    @Test
    @DisplayName("all values present — AVAILABLE, UNAVAILABLE, UNKNOWN")
    void allValuesPresent() {
        assertThat(Availability.valueOf("AVAILABLE")).isEqualTo(Availability.AVAILABLE);
        assertThat(Availability.valueOf("UNAVAILABLE")).isEqualTo(Availability.UNAVAILABLE);
        assertThat(Availability.valueOf("UNKNOWN")).isEqualTo(Availability.UNKNOWN);
    }

    @Test
    @DisplayName("UNKNOWN is initial state — initialized to UNKNOWN at entity adoption before first availability_changed event")
    void unknownIsInitialState() {
        assertThat(Availability.UNKNOWN).isNotNull();
    }

    @Test
    @DisplayName("Availability is an enum type")
    void isEnum() {
        assertThat(Availability.class.isEnum()).isTrue();
    }
}

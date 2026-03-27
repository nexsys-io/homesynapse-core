/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SubjectType} — subject type discriminator for event bus filtering.
 */
@DisplayName("SubjectType")
class SubjectTypeTest {

    @Test
    @DisplayName("exactly 6 enum values")
    void exactlySixValues() {
        assertThat(SubjectType.values()).hasSize(6);
    }

    @Test
    @DisplayName("all expected values present in order")
    void expectedValues() {
        assertThat(SubjectType.values()).containsExactly(
                SubjectType.ENTITY,
                SubjectType.DEVICE,
                SubjectType.INTEGRATION,
                SubjectType.AUTOMATION,
                SubjectType.SYSTEM,
                SubjectType.PERSON);
    }

    @Test
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip() {
        for (SubjectType type : SubjectType.values()) {
            assertThat(SubjectType.valueOf(type.name())).isEqualTo(type);
        }
    }
}

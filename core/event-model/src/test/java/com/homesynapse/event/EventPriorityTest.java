/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventPriority} — CRITICAL / NORMAL / DIAGNOSTIC.
 */
@DisplayName("EventPriority")
class EventPriorityTest {

    @Test
    @DisplayName("exactly 3 enum values")
    void exactlyThreeValues() {
        assertThat(EventPriority.values()).hasSize(3);
    }

    @Test
    @DisplayName("values are CRITICAL, NORMAL, DIAGNOSTIC in order")
    void expectedValues() {
        assertThat(EventPriority.values()).containsExactly(
                EventPriority.CRITICAL,
                EventPriority.NORMAL,
                EventPriority.DIAGNOSTIC);
    }

    @Test
    @DisplayName("CRITICAL severity is 0 (highest)")
    void criticalSeverity() {
        assertThat(EventPriority.CRITICAL.severity()).isZero();
    }

    @Test
    @DisplayName("NORMAL severity is 1")
    void normalSeverity() {
        assertThat(EventPriority.NORMAL.severity()).isEqualTo(1);
    }

    @Test
    @DisplayName("DIAGNOSTIC severity is 2 (lowest)")
    void diagnosticSeverity() {
        assertThat(EventPriority.DIAGNOSTIC.severity()).isEqualTo(2);
    }

    @Test
    @DisplayName("lower severity means higher priority — CRITICAL < NORMAL < DIAGNOSTIC")
    void severityOrdering() {
        assertThat(EventPriority.CRITICAL.severity())
                .isLessThan(EventPriority.NORMAL.severity());
        assertThat(EventPriority.NORMAL.severity())
                .isLessThan(EventPriority.DIAGNOSTIC.severity());
    }

    @Test
    @DisplayName("severity can be used for comparison (not ordinal)")
    void severityComparison() {
        // Filter: events at or above NORMAL priority
        EventPriority threshold = EventPriority.NORMAL;

        assertThat(EventPriority.CRITICAL.severity() <= threshold.severity())
                .as("CRITICAL meets NORMAL threshold").isTrue();
        assertThat(EventPriority.NORMAL.severity() <= threshold.severity())
                .as("NORMAL meets NORMAL threshold").isTrue();
        assertThat(EventPriority.DIAGNOSTIC.severity() <= threshold.severity())
                .as("DIAGNOSTIC does not meet NORMAL threshold").isFalse();
    }
}

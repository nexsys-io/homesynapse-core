/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReplayWindowQueue} — covers the M3.6b
 * parameterised-capacity constructor introduced for audit finding D4-09
 * plus the no-arg constructor's backward-compatible default behaviour.
 *
 * <p>The queue's lock discipline ({@code lock()}/{@code unlock()}) and the
 * REPLAY-restart semantics around the latched overflow flag are exercised
 * end-to-end by {@code EventBusContractTest.Tier9} and {@code
 * ReplayTransitionIT}. This class focuses on the constructor surface and
 * the {@code enqueue() == false} overflow signal at custom capacities.</p>
 */
@DisplayName("ReplayWindowQueue")
class ReplayWindowQueueTest {

    /** Creates a new test instance. */
    ReplayWindowQueueTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    @DisplayName("overflow at custom capacity latches overflowed flag")
    void overflowAtCustomCapacity() {
        ReplayWindowQueue queue = new ReplayWindowQueue(5);

        for (int i = 1; i <= 5; i++) {
            assertThat(queue.enqueue(i))
                    .as("enqueue %d (within capacity)", i)
                    .isTrue();
        }
        assertThat(queue.overflowed())
                .as("overflow flag not yet set at capacity boundary")
                .isFalse();

        assertThat(queue.enqueue(6))
                .as("enqueue at capacity+1 returns false")
                .isFalse();
        assertThat(queue.overflowed())
                .as("overflow flag latched after rejected enqueue")
                .isTrue();
        assertThat(queue.size())
                .as("rejected entry is not stored")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("no-arg constructor uses the prior 10,000-entry default")
    void defaultCapacityIsUnchanged() {
        // Verify the no-arg constructor still bounds at the legacy 10,000
        // default — backward compatibility for in-package callers that have
        // not migrated to passing EventBusConfig through. A small proxy
        // would not catch a regression where the no-arg form silently
        // shrinks to a smaller bound, so we exercise the actual default.
        ReplayWindowQueue queue = new ReplayWindowQueue();
        for (int i = 1; i <= 10_000; i++) {
            assertThat(queue.enqueue(i))
                    .as("enqueue within legacy 10,000 default")
                    .isTrue();
        }
        assertThat(queue.overflowed())
                .as("overflow flag not set at default capacity boundary")
                .isFalse();
        assertThat(queue.enqueue(10_001))
                .as("enqueue at default capacity + 1 returns false")
                .isFalse();
        assertThat(queue.overflowed())
                .as("overflow flag latched after rejected enqueue at default")
                .isTrue();
    }

    @Test
    @DisplayName("constructor rejects zero capacity")
    void capacityValidationRejectsZero() {
        assertThatThrownBy(() -> new ReplayWindowQueue(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCapacity");
    }

    @Test
    @DisplayName("constructor rejects negative capacity")
    void capacityValidationRejectsNegative() {
        assertThatThrownBy(() -> new ReplayWindowQueue(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCapacity");
    }
}

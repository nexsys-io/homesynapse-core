/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-private {@link SelfProducedFilter} (AMD-41 §3.2.2).
 *
 * <p>Verifies record/check round-trip, lazy eviction of expired entries, and
 * mode bypass (REPLAY/TRANSITION return {@code false} regardless of recorded
 * state).</p>
 */
class SelfProducedFilterTest {

    private TestClock clock;
    private SelfProducedFilter filter;

    SelfProducedFilterTest() {
        // No initialization here; @BeforeEach configures the filter per-test.
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        filter = new SelfProducedFilter(clock, Duration.ofSeconds(60));
    }

    @Test
    void recordedEventIsDetectedAsSelfProduced() {
        Ulid id = UlidFactory.generate(clock);

        filter.record(id);

        assertThat(filter.isSelfProduced(id, SubscriberMode.LIVE)).isTrue();
    }

    @Test
    void unknownEventIsNotSelfProduced() {
        Ulid unknown = UlidFactory.generate(clock);

        assertThat(filter.isSelfProduced(unknown, SubscriberMode.LIVE)).isFalse();
    }

    @Test
    void expiredEntryEvicted() {
        Ulid id = UlidFactory.generate(clock);
        filter.record(id);

        clock.advance(Duration.ofSeconds(61));

        assertThat(filter.isSelfProduced(id, SubscriberMode.LIVE)).isFalse();
        assertThat(filter.size()).isZero();
    }

    @Test
    void replayModeBypassesFilter() {
        Ulid id = UlidFactory.generate(clock);
        filter.record(id);

        assertThat(filter.isSelfProduced(id, SubscriberMode.REPLAY)).isFalse();
    }

    @Test
    void transitionModeBypassesFilter() {
        Ulid id = UlidFactory.generate(clock);
        filter.record(id);

        assertThat(filter.isSelfProduced(id, SubscriberMode.TRANSITION)).isFalse();
    }

    @Test
    void lazyEvictionRemovesExpiredOnCheck() {
        // Record 100 entries at t=0.
        for (int i = 0; i < 100; i++) {
            filter.record(UlidFactory.generate(clock));
        }
        assertThat(filter.size()).isEqualTo(100);

        // Advance past TTL.
        clock.advance(Duration.ofSeconds(61));

        // Check one entry (any will do, since none survive eviction).
        Ulid probeId = UlidFactory.generate(clock);
        filter.isSelfProduced(probeId, SubscriberMode.LIVE);

        // All 100 expired entries are evicted by the single check call.
        assertThat(filter.size()).isZero();
    }
}

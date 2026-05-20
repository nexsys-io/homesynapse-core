/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ThrottledWriteCoordinator}.
 *
 * <p>The decorator is tested against the in-memory coordinator
 * ({@link InMemoryWriteCoordinator}) so the wrapped {@link java.util.concurrent.Callable}
 * runs synchronously on the calling thread and wall-clock measurement around
 * {@code submit(...)} reflects the injected delay. The contract is verified
 * with deliberately small delays (50 ms baseline, 100 ms spike) so the full
 * suite finishes in well under one second.</p>
 *
 * <p>This test lives in {@code src/test/java}, not {@code testFixtures} —
 * matching the {@link InMemoryWriteCoordinatorTest} placement pattern — so
 * it executes as part of {@code :core:persistence:test}. The brief
 * (M3.4b §Files to Create) explicitly allowed either source set.</p>
 */
@DisplayName("ThrottledWriteCoordinator")
final class ThrottledWriteCoordinatorTest {

    /** Wall-clock tolerance for scheduler jitter on slow CI hardware. */
    private static final long TOLERANCE_MILLIS = 30L;

    /** Creates a new test instance. */
    ThrottledWriteCoordinatorTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    @DisplayName("submit returns the underlying Callable's result")
    void submit_delegatesResult() {
        ThrottledWriteCoordinator coordinator = new ThrottledWriteCoordinator(
                new InMemoryWriteCoordinator(),
                Duration.ZERO, Duration.ZERO, 0.0);

        Integer result = coordinator.submit(
                WritePriority.EVENT_PUBLISH, () -> 42);

        assertThat(result).isEqualTo(42);
    }

    @Test
    @DisplayName("submit applies the baseline delay")
    void submit_appliesBaselineDelay() {
        Duration baseline = Duration.ofMillis(50);
        ThrottledWriteCoordinator coordinator = new ThrottledWriteCoordinator(
                new InMemoryWriteCoordinator(),
                baseline, Duration.ZERO, 0.0);

        long startNanos = System.nanoTime();
        coordinator.submit(WritePriority.EVENT_PUBLISH, () -> null);
        long elapsedMillis =
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(elapsedMillis)
                .as("baseline delay applied (target=%dms)", baseline.toMillis())
                .isGreaterThanOrEqualTo(baseline.toMillis() - TOLERANCE_MILLIS);
    }

    @Test
    @DisplayName("submit applies spike when probability is 1.0")
    void submit_appliesSpikeWhenProbabilityIsOne() {
        Duration spike = Duration.ofMillis(100);
        ThrottledWriteCoordinator coordinator = new ThrottledWriteCoordinator(
                new InMemoryWriteCoordinator(),
                Duration.ZERO, spike, 1.0);

        long startNanos = System.nanoTime();
        coordinator.submit(WritePriority.EVENT_PUBLISH, () -> null);
        long elapsedMillis =
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(elapsedMillis)
                .as("spike delay applied at probability 1.0 (target=%dms)",
                        spike.toMillis())
                .isGreaterThanOrEqualTo(spike.toMillis() - TOLERANCE_MILLIS);
    }

    @Test
    @DisplayName("submit omits spike when probability is 0.0")
    void submit_omitsSpikeWhenProbabilityIsZero() {
        Duration spike = Duration.ofMillis(500);
        ThrottledWriteCoordinator coordinator = new ThrottledWriteCoordinator(
                new InMemoryWriteCoordinator(),
                Duration.ZERO, spike, 0.0);

        long startNanos = System.nanoTime();
        coordinator.submit(WritePriority.EVENT_PUBLISH, () -> null);
        long elapsedMillis =
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        // With prob=0 the 500ms spike never fires; expect well under spike duration.
        assertThat(elapsedMillis)
                .as("no spike at probability 0.0")
                .isLessThan(spike.toMillis() / 2);
    }

    @Test
    @DisplayName("submit propagates runtime exception from the wrapped Callable")
    void submit_propagatesException() {
        ThrottledWriteCoordinator coordinator = new ThrottledWriteCoordinator(
                new InMemoryWriteCoordinator(),
                Duration.ZERO, Duration.ZERO, 0.0);

        assertThatThrownBy(() -> coordinator.submit(
                WritePriority.EVENT_PUBLISH, () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    @DisplayName("shutdown propagates to the delegate")
    void shutdown_propagatesToDelegate() {
        InMemoryWriteCoordinator delegate = new InMemoryWriteCoordinator();
        ThrottledWriteCoordinator coordinator = new ThrottledWriteCoordinator(
                delegate, Duration.ZERO, Duration.ZERO, 0.0);

        coordinator.shutdown();

        assertThatThrownBy(() -> coordinator.submit(
                WritePriority.EVENT_PUBLISH, () -> null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("withDefaults constructs a decorator using the Pi-4 profile")
    void withDefaults_usesPi4Profile() {
        ThrottledWriteCoordinator coordinator =
                ThrottledWriteCoordinator.withDefaults(new InMemoryWriteCoordinator());

        // The defaults are 10 ms baseline, 200 ms spike, 0.5% probability.
        // A single submit should pay at least the baseline; the spike is
        // probabilistic and not asserted here.
        long startNanos = System.nanoTime();
        coordinator.submit(WritePriority.EVENT_PUBLISH, () -> null);
        long elapsedMillis =
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(elapsedMillis)
                .as("Pi-4 default baseline (>=10ms) applied")
                .isGreaterThanOrEqualTo(
                        ThrottledWriteCoordinator.DEFAULT_BASELINE_DELAY.toMillis()
                                - TOLERANCE_MILLIS);
    }

    @Test
    @DisplayName("constructor rejects null delegate")
    void constructor_rejectsNullDelegate() {
        assertThatThrownBy(() -> new ThrottledWriteCoordinator(
                null, Duration.ZERO, Duration.ZERO, 0.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects negative baseline delay")
    void constructor_rejectsNegativeBaseline() {
        assertThatThrownBy(() -> new ThrottledWriteCoordinator(
                new InMemoryWriteCoordinator(),
                Duration.ofMillis(-1), Duration.ZERO, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baselineDelay");
    }

    @Test
    @DisplayName("constructor rejects spike probability outside [0, 1]")
    void constructor_rejectsBadProbability() {
        assertThatThrownBy(() -> new ThrottledWriteCoordinator(
                new InMemoryWriteCoordinator(),
                Duration.ZERO, Duration.ZERO, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spikeProbability");

        assertThatThrownBy(() -> new ThrottledWriteCoordinator(
                new InMemoryWriteCoordinator(),
                Duration.ZERO, Duration.ZERO, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spikeProbability");
    }
}

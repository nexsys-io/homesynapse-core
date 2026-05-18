/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventPublisher;
import com.homesynapse.state.test.StateProjectionContractTest;

import java.time.Clock;
import java.time.Duration;

/**
 * Concrete {@link StateProjectionContractTest} that wires an in-memory
 * {@link StateProjection} using a fresh {@link SelfProducedFilter} with the
 * default 60-second TTL.
 *
 * <p>This concrete class lives in {@code src/test/java} in the production
 * package {@code com.homesynapse.state} so it can access the package-private
 * {@link SelfProducedFilter}. The abstract contract test base is in the
 * {@code testFixtures} {@code .test} sub-package (which cannot reach the
 * package-private filter).</p>
 */
class InMemoryStateProjectionTest extends StateProjectionContractTest {

    InMemoryStateProjectionTest() {
        // Inherits the no-arg constructor contract.
    }

    @Override
    protected StateProjection createProjection(
            ProjectionId projectionId,
            int projectionVersion,
            ViewCheckpointStore checkpointStore,
            StateStore stateStore,
            DerivationRule rule,
            EventPublisher publisher,
            ProjectionAdvancer advancer,
            CheckpointPolicy checkpointPolicy,
            Clock clock,
            DerivedPublishGate publishGate) {
        // Construct a SelfProducedFilter with the production default TTL.
        return new StateProjection(
                projectionId,
                projectionVersion,
                checkpointStore,
                stateStore,
                rule,
                publisher,
                advancer,
                checkpointPolicy,
                clock,
                publishGate,
                new SelfProducedFilter(clock, Duration.ofSeconds(60)));
    }
}

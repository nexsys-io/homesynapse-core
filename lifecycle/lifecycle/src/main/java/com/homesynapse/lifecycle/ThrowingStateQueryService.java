/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Placeholder {@link StateQueryService} that throws on every method call.
 *
 * <p>Exists so the composition root's {@code stateQueryService()} accessor
 * (M3.6d-b) can return a non-{@code null} value before the real
 * {@code MaterializedStateQueryService} lands in M3.6e. Any accidental call
 * before M3.6e wiring produces a clear, actionable error message instead of
 * a {@link NullPointerException} at an arbitrary downstream callsite.</p>
 *
 * <p>Every method throws {@link IllegalStateException} with the message
 * "{@code StateQueryService not yet wired — available after M3.6e}". The
 * message names the work unit that lands the production implementation so
 * a stack trace points the operator at the right milestone.</p>
 *
 * <p>Package-private — instantiated only by the composition root.</p>
 *
 * @see StateQueryService
 */
final class ThrowingStateQueryService implements StateQueryService {

    /** Single shared message — referenced by tests for content assertion. */
    static final String NOT_WIRED_MESSAGE =
            "StateQueryService not yet wired — available after M3.6e";

    ThrowingStateQueryService() {
        // Default constructor.
    }

    @Override
    public Optional<EntityState> getState(EntityId entityId) {
        throw new IllegalStateException(NOT_WIRED_MESSAGE);
    }

    @Override
    public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
        throw new IllegalStateException(NOT_WIRED_MESSAGE);
    }

    @Override
    public StateSnapshot getSnapshot() {
        throw new IllegalStateException(NOT_WIRED_MESSAGE);
    }

    @Override
    public long getViewPosition() {
        throw new IllegalStateException(NOT_WIRED_MESSAGE);
    }

    @Override
    public boolean isReady() {
        throw new IllegalStateException(NOT_WIRED_MESSAGE);
    }
}

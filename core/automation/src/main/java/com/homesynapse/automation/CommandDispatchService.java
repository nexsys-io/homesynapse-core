/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;

import com.homesynapse.event.EventId;
import com.homesynapse.platform.identity.EntityId;

/**
 * Routing resolver that dispatches commands to the correct integration adapter.
 *
 * <p>Resolves the target entity to its integration via the two-hop
 * {@code EntityRegistry.findEntity → Entity.deviceId() → DeviceRegistry.findDevice → integration}
 * (AMD-95: there is no {@code DeviceRegistry.getIntegrationForEntity}). Validates the command via
 * {@code CommandValidator.validate()}. On successful handoff, produces a
 * {@code command_dispatched} DIAGNOSTIC event. On failure (invalid command or
 * unroutable entity), produces a {@code command_result} with status {@code invalid}
 * or {@code unroutable}.</p>
 *
 * <p>From M7.4a the production path is event-driven (§1 D1 / AMD-95): the implementation is the
 * co-located {@code command_dispatch_service} bus subscriber that consumes {@code command_issued}
 * and dispatches in {@code LIVE} mode only (D2 pure-function-replay). The {@link #dispatch} method
 * below is the in-process primitive (retained for direct/programmatic invocation and unit
 * coverage); the bus path threads the full causal chain (Doc 07 §3.11.2).</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §3.11.1, §8.1.</p>
 *
 * @see PendingCommandLedger
 * @see CommandAction
 * @see CommandDispatchAssembly
 */
public interface CommandDispatchService extends AutoCloseable {

    /**
     * Routes a command to the correct integration adapter.
     *
     * @param commandEventId the event ID of the originating {@code command_issued} event,
     *                       never {@code null}
     * @param targetRef      the target entity, never {@code null}
     * @param commandName    the command to dispatch, never {@code null}
     * @param parameters     command parameters, never {@code null}
     */
    void dispatch(EventId commandEventId, EntityId targetRef,
                  String commandName, Map<String, Object> parameters);

    /**
     * Releases any resource the dispatch service holds (the paired teardown the composition root
     * MUST call alongside unsubscribe — the reverted-M7.3 lesson: a runtime subscriber registered
     * with no matching teardown leaks its held resources). Idempotent. Narrowed from
     * {@link AutoCloseable#close()} to declare no checked exception so callers need no
     * {@code try/catch} (mirroring the trigger evaluator's teardown).
     */
    @Override
    void close();
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;

import com.homesynapse.event.EventId;
import com.homesynapse.platform.identity.EntityId;

/**
 * Thin routing resolver that dispatches commands to the correct integration adapter.
 *
 * <p>Resolves the target entity to its integration via
 * {@code DeviceRegistry.getIntegrationForEntity()}. Validates the command via
 * {@code CommandValidator.validate()}. On successful handoff, produces a
 * {@code command_dispatched} DIAGNOSTIC event. On failure (invalid command or
 * unroutable entity), produces a {@code command_result} with status {@code invalid}
 * or {@code unroutable}.</p>
 *
 * <p>Runs as a separate subscriber ({@code command_dispatch_service}) on its own
 * virtual thread in Phase 3.</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §3.11.1, §8.1.</p>
 *
 * @see PendingCommandLedger
 * @see CommandAction
 */
public interface CommandDispatchService {

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
}

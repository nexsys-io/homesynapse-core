/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Optional;

import com.homesynapse.event.EventId;
import com.homesynapse.platform.identity.EntityId;

/**
 * Tracks in-flight commands and correlates them with state confirmations.
 *
 * <p>Subscribes to {@code command_issued}, {@code command_result},
 * {@code state_reported}, and {@code state_confirmed} events. Produces
 * {@code state_confirmed} when the expected outcome matches an incoming
 * {@code state_reported}. Produces {@code command_confirmation_timed_out}
 * when the deadline expires.</p>
 *
 * <p>Coalescing is DISABLED for the pending command ledger subscriber — this is
 * correctness-critical per Doc 01 §3.6.</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §3.11.2, §8.1.</p>
 *
 * @see PendingCommand
 * @see PendingStatus
 * @see CommandDispatchService
 */
public interface PendingCommandLedger {

    /**
     * Adds a command to the pending tracking map.
     *
     * @param command the pending command to track, never {@code null}
     */
    void trackCommand(PendingCommand command);

    /**
     * Looks up a pending command by its command event ID.
     *
     * @param commandEventId the event ID of the {@code command_issued} event,
     *                       never {@code null}
     * @return the pending command, or empty if not found
     */
    Optional<PendingCommand> getCommand(EventId commandEventId);

    /**
     * Returns all pending commands targeting a specific entity.
     *
     * @param entityRef the target entity, never {@code null}
     * @return an unmodifiable list of pending commands, never {@code null}
     */
    List<PendingCommand> getPendingForEntity(EntityId entityRef);

    /**
     * Returns the total number of pending commands.
     *
     * @return the pending count, always {@code >= 0}
     */
    int pendingCount();
}

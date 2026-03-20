/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;
import java.util.Objects;

/**
 * Issues a command to one or more target entities via the Command Pipeline (§3.11).
 *
 * <p>Commands are non-blocking — the action completes after dispatching the command,
 * without waiting for state confirmation. State confirmation tracking is handled
 * by the {@link PendingCommandLedger}.</p>
 *
 * <p>When a target entity's availability is {@code offline}, behavior is governed
 * by the {@code onUnavailable} policy. The default policy ({@link UnavailablePolicy#SKIP})
 * is applied at YAML load time if the user omits the {@code on_unavailable} field.</p>
 *
 * <p>Defined in Doc 07 §3.9, §8.2.</p>
 *
 * @param target        the entity selector identifying command targets, never {@code null}
 * @param commandName   the name of the command to issue (e.g., {@code "set_level"}),
 *                      never {@code null}
 * @param parameters    command parameters as key-value pairs, unmodifiable,
 *                      never {@code null} (may be empty)
 * @param onUnavailable behavior when a target entity is offline, never {@code null}
 * @see ActionDefinition
 * @see ActionExecutor
 * @see CommandDispatchService
 * @see UnavailablePolicy
 */
public record CommandAction(
        Selector target,
        String commandName,
        Map<String, Object> parameters,
        UnavailablePolicy onUnavailable
) implements ActionDefinition {

    /**
     * Validates non-null fields and makes the parameters map unmodifiable.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public CommandAction {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(commandName, "commandName must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(onUnavailable, "onUnavailable must not be null");
        parameters = Map.copyOf(parameters);
    }
}

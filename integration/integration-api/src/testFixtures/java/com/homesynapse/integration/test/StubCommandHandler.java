/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.test;

import com.homesynapse.integration.CommandEnvelope;
import com.homesynapse.integration.CommandHandler;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Command-recording {@link CommandHandler} implementation for testing
 * command dispatch and adapter command processing.
 *
 * <p>{@code StubCommandHandler} supports three operational modes:</p>
 * <ul>
 *   <li><strong>Accepting</strong> ({@link #accepting()}) — accepts all
 *       commands silently, recording them for assertion.</li>
 *   <li><strong>Rejecting</strong> ({@link #rejecting(Exception)}) — throws
 *       the configured exception for every command. Useful for testing
 *       supervisor error classification (permanent vs. transient).</li>
 *   <li><strong>Conditional</strong> ({@link #conditional(Predicate)}) —
 *       accepts commands matching the predicate, throws
 *       {@link UnsupportedOperationException} for non-matching commands.</li>
 * </ul>
 *
 * <p>All modes record every received command (before throwing, if applicable)
 * in a {@link CopyOnWriteArrayList} for thread-safe assertion. Use
 * {@link #commands()} to inspect and {@link #clear()} to reset between
 * tests.</p>
 *
 * @see CommandHandler
 * @see CommandEnvelope
 * @see TestAdapter
 * @see StubIntegrationContext
 */
public final class StubCommandHandler implements CommandHandler {

    private final CopyOnWriteArrayList<CommandEnvelope> commands =
            new CopyOnWriteArrayList<>();
    private final Exception rejectWith;
    private final Predicate<CommandEnvelope> acceptPredicate;

    private StubCommandHandler(Exception rejectWith,
                               Predicate<CommandEnvelope> acceptPredicate) {
        this.rejectWith = rejectWith;
        this.acceptPredicate = acceptPredicate;
    }

    // ──────────────────────────────────────────────────────────────────
    // Static factories
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a handler that accepts all commands silently.
     *
     * @return an accepting stub command handler
     */
    public static StubCommandHandler accepting() {
        return new StubCommandHandler(null, null);
    }

    /**
     * Creates a handler that throws the given exception for every command.
     *
     * <p>The command is still recorded before the exception is thrown,
     * allowing tests to verify both receipt and error behavior.</p>
     *
     * @param exception the exception to throw; never {@code null}
     * @return a rejecting stub command handler
     */
    public static StubCommandHandler rejecting(Exception exception) {
        Objects.requireNonNull(exception, "exception must not be null");
        return new StubCommandHandler(exception, null);
    }

    /**
     * Creates a handler that accepts commands matching the predicate and
     * throws {@link UnsupportedOperationException} for non-matching commands.
     *
     * <p>The command is always recorded regardless of whether it matches
     * the predicate.</p>
     *
     * @param predicate the acceptance predicate; never {@code null}
     * @return a conditional stub command handler
     */
    public static StubCommandHandler conditional(
            Predicate<CommandEnvelope> predicate) {
        Objects.requireNonNull(predicate, "predicate must not be null");
        return new StubCommandHandler(null, predicate);
    }

    // ──────────────────────────────────────────────────────────────────
    // CommandHandler implementation
    // ──────────────────────────────────────────────────────────────────

    @Override
    public void handle(CommandEnvelope command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        commands.add(command);

        if (rejectWith != null) {
            throw rejectWith;
        }

        if (acceptPredicate != null && !acceptPredicate.test(command)) {
            throw new UnsupportedOperationException(
                    "Command rejected by predicate: " + command.commandName());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Observation accessors
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns all commands received by this handler.
     *
     * @return unmodifiable list of commands in order of receipt;
     *         never {@code null}
     */
    public List<CommandEnvelope> commands() {
        return Collections.unmodifiableList(commands);
    }

    /**
     * Returns the number of commands received.
     *
     * @return command count
     */
    public int commandCount() {
        return commands.size();
    }

    /**
     * Returns the last command received, or {@code null} if none.
     *
     * @return the most recently received command, or {@code null}
     */
    public CommandEnvelope lastCommand() {
        return commands.isEmpty() ? null : commands.get(commands.size() - 1);
    }

    /**
     * Clears all recorded commands.
     */
    public void clear() {
        commands.clear();
    }
}

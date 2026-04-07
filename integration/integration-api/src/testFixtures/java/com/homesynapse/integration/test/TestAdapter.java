/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.test;

import com.homesynapse.integration.CommandHandler;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.PermanentIntegrationException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configurable {@link IntegrationAdapter} implementation for testing adapter
 * lifecycle, supervisor interactions, and command dispatch.
 *
 * <p>{@code TestAdapter} supports four operational modes via the {@link Mode}
 * enum:</p>
 * <ul>
 *   <li>{@link Mode#NOOP} — all lifecycle methods return immediately. The
 *       adapter does nothing. Default mode for most tests.</li>
 *   <li>{@link Mode#ECHO} — {@link #run()} records each invocation count.
 *       Useful for verifying the supervisor calls lifecycle methods in the
 *       correct order.</li>
 *   <li>{@link Mode#FAILING} — {@link #initialize()} throws
 *       {@link PermanentIntegrationException}. Useful for testing supervisor
 *       error handling and health state transitions.</li>
 *   <li>{@link Mode#CUSTOM} — lifecycle callbacks are delegated to
 *       user-provided {@link Runnable}/{@link Consumer} instances via the
 *       {@link Builder}. Maximum flexibility for complex test scenarios.</li>
 * </ul>
 *
 * <p>All modes track lifecycle state: {@link #initialized()},
 * {@link #running()}, {@link #closed()}, and invocation counts for
 * {@link #initializeCount()}, {@link #runCount()}, {@link #closeCount()}.
 * These accessors use {@link AtomicBoolean}/{@link AtomicInteger} for
 * thread-safe observation from test assertion threads.</p>
 *
 * <h2>Static Factories</h2>
 *
 * <p>Four static factory methods provide one-liner construction for common
 * modes:</p>
 * <ul>
 *   <li>{@link #noop()} — creates a NOOP adapter</li>
 *   <li>{@link #echo()} — creates an ECHO adapter</li>
 *   <li>{@link #failing()} — creates a FAILING adapter</li>
 *   <li>{@link #failing(String)} — creates a FAILING adapter with a custom
 *       error message</li>
 * </ul>
 *
 * @see IntegrationAdapter
 * @see StubIntegrationContext
 * @see StubCommandHandler
 */
public final class TestAdapter implements IntegrationAdapter {

    private final Mode mode;
    private final String failureMessage;
    private final CommandHandler commandHandler;
    private final Runnable onInitialize;
    private final Runnable onRun;
    private final Runnable onClose;

    // Lifecycle tracking — thread-safe for cross-thread assertion
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger initializeCount = new AtomicInteger(0);
    private final AtomicInteger runCount = new AtomicInteger(0);
    private final AtomicInteger closeCount = new AtomicInteger(0);

    private TestAdapter(Mode mode,
                        String failureMessage,
                        CommandHandler commandHandler,
                        Runnable onInitialize,
                        Runnable onRun,
                        Runnable onClose) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.failureMessage = failureMessage;
        this.commandHandler = commandHandler;
        this.onInitialize = onInitialize;
        this.onRun = onRun;
        this.onClose = onClose;
    }

    // ──────────────────────────────────────────────────────────────────
    // Static factories
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a NOOP adapter where all lifecycle methods return immediately.
     *
     * @return a NOOP test adapter
     */
    public static TestAdapter noop() {
        return new TestAdapter(Mode.NOOP, null, null, null, null, null);
    }

    /**
     * Creates an ECHO adapter that records invocation counts.
     *
     * @return an ECHO test adapter
     */
    public static TestAdapter echo() {
        return new TestAdapter(Mode.ECHO, null, null, null, null, null);
    }

    /**
     * Creates a FAILING adapter whose {@link #initialize()} throws
     * {@link PermanentIntegrationException} with a default message.
     *
     * @return a FAILING test adapter
     */
    public static TestAdapter failing() {
        return failing("Test adapter permanent failure");
    }

    /**
     * Creates a FAILING adapter whose {@link #initialize()} throws
     * {@link PermanentIntegrationException} with the given message.
     *
     * @param message the failure message; never {@code null}
     * @return a FAILING test adapter
     */
    public static TestAdapter failing(String message) {
        Objects.requireNonNull(message, "message must not be null");
        return new TestAdapter(Mode.FAILING, message, null, null, null, null);
    }

    /**
     * Returns a builder for CUSTOM mode with full lifecycle callback control.
     *
     * @return a new Builder for a CUSTOM adapter
     */
    public static Builder builder() {
        return new Builder();
    }

    // ──────────────────────────────────────────────────────────────────
    // IntegrationAdapter implementation
    // ──────────────────────────────────────────────────────────────────

    @Override
    public void initialize() throws PermanentIntegrationException {
        initializeCount.incrementAndGet();
        initialized.set(true);

        switch (mode) {
            case NOOP, ECHO -> { /* no-op */ }
            case FAILING -> throw new PermanentIntegrationException(failureMessage);
            case CUSTOM -> {
                if (onInitialize != null) {
                    onInitialize.run();
                }
            }
        }
    }

    @Override
    public void run() throws Exception {
        runCount.incrementAndGet();
        running.set(true);

        try {
            switch (mode) {
                case NOOP, FAILING -> { /* no-op */ }
                case ECHO -> { /* echo records via counters above */ }
                case CUSTOM -> {
                    if (onRun != null) {
                        onRun.run();
                    }
                }
            }
        } finally {
            running.set(false);
        }
    }

    @Override
    public void close() {
        closeCount.incrementAndGet();
        closed.set(true);

        switch (mode) {
            case NOOP, ECHO, FAILING -> { /* no-op */ }
            case CUSTOM -> {
                if (onClose != null) {
                    onClose.run();
                }
            }
        }
    }

    @Override
    public CommandHandler commandHandler() {
        return commandHandler;
    }

    // ──────────────────────────────────────────────────────────────────
    // Lifecycle observation accessors
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns the operational mode of this adapter.
     *
     * @return the mode; never {@code null}
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Returns whether {@link #initialize()} has been called at least once.
     *
     * @return {@code true} if initialized
     */
    public boolean initialized() {
        return initialized.get();
    }

    /**
     * Returns whether {@link #run()} is currently executing.
     *
     * @return {@code true} if currently running
     */
    public boolean running() {
        return running.get();
    }

    /**
     * Returns whether {@link #close()} has been called at least once.
     *
     * @return {@code true} if closed
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * Returns the number of times {@link #initialize()} has been called.
     *
     * @return the invocation count
     */
    public int initializeCount() {
        return initializeCount.get();
    }

    /**
     * Returns the number of times {@link #run()} has been called.
     *
     * @return the invocation count
     */
    public int runCount() {
        return runCount.get();
    }

    /**
     * Returns the number of times {@link #close()} has been called.
     *
     * @return the invocation count
     */
    public int closeCount() {
        return closeCount.get();
    }

    // ──────────────────────────────────────────────────────────────────
    // Mode enum
    // ──────────────────────────────────────────────────────────────────

    /**
     * Operational modes for {@link TestAdapter}.
     */
    public enum Mode {

        /** All lifecycle methods return immediately. */
        NOOP,

        /** Lifecycle methods record invocation counts. */
        ECHO,

        /** {@link IntegrationAdapter#initialize()} throws {@link PermanentIntegrationException}. */
        FAILING,

        /** Lifecycle callbacks delegated to user-provided runnables. */
        CUSTOM
    }

    // ──────────────────────────────────────────────────────────────────
    // Builder (CUSTOM mode)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable builder for CUSTOM-mode {@link TestAdapter} instances.
     *
     * <p>Provides fluent setters for lifecycle callbacks and command handler.
     * Callbacks that are not set default to no-op.</p>
     */
    public static final class Builder {

        private CommandHandler commandHandler;
        private Runnable onInitialize;
        private Runnable onRun;
        private Runnable onClose;

        Builder() {
            // Package-private — created via TestAdapter.builder()
        }

        /**
         * Sets the command handler returned by
         * {@link IntegrationAdapter#commandHandler()}.
         *
         * @param commandHandler the command handler, or {@code null} for
         *                       read-only adapters
         * @return this builder
         */
        public Builder commandHandler(CommandHandler commandHandler) {
            this.commandHandler = commandHandler;
            return this;
        }

        /**
         * Sets the callback invoked during {@link IntegrationAdapter#initialize()}.
         *
         * @param onInitialize the initialization callback; {@code null} for no-op
         * @return this builder
         */
        public Builder onInitialize(Runnable onInitialize) {
            this.onInitialize = onInitialize;
            return this;
        }

        /**
         * Sets the callback invoked during {@link IntegrationAdapter#run()}.
         *
         * @param onRun the run callback; {@code null} for no-op
         * @return this builder
         */
        public Builder onRun(Runnable onRun) {
            this.onRun = onRun;
            return this;
        }

        /**
         * Sets the callback invoked during {@link IntegrationAdapter#close()}.
         *
         * @param onClose the close callback; {@code null} for no-op
         * @return this builder
         */
        public Builder onClose(Runnable onClose) {
            this.onClose = onClose;
            return this;
        }

        /**
         * Builds a CUSTOM-mode {@link TestAdapter}.
         *
         * @return a new TestAdapter in CUSTOM mode
         */
        public TestAdapter build() {
            return new TestAdapter(
                    Mode.CUSTOM,
                    null,
                    commandHandler,
                    onInitialize,
                    onRun,
                    onClose);
        }
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.config.ConfigChangeSet;

/**
 * Lifecycle contract for integration adapter modules (Doc 05 §8.1, §8.4).
 *
 * <p>An integration adapter bridges HomeSynapse to a specific protocol or
 * external system (Zigbee, MQTT, cloud APIs). The adapter implements three
 * lifecycle phases:</p>
 *
 * <ol>
 *   <li><strong>{@link #initialize()}</strong> — Startup work independent of
 *       external device connectivity. Register identity, declare capabilities,
 *       set up internal data structures. Must not block on network or serial
 *       connections (INV-RF-03).</li>
 *   <li><strong>{@link #run()}</strong> — Main processing loop. Blocks on I/O
 *       (socket reads, serial port reads via {@code BlockingQueue}). Returns
 *       normally when signaled to stop. The thread type is determined by
 *       {@link IntegrationDescriptor#ioType()}: platform thread for
 *       {@link IoType#SERIAL}, virtual thread for {@link IoType#NETWORK}.
 *       The adapter does not choose its thread.</li>
 *   <li><strong>{@link #close()}</strong> — Resource cleanup. Close protocol
 *       connections, flush buffers, cancel timers. Called after {@link #run()}
 *       returns. Must be idempotent.</li>
 * </ol>
 *
 * <p>The adapter extends {@link AutoCloseable} to enable try-with-resources
 * usage in the supervisor. Exception handling during {@link #run()} follows
 * Doc 05 §3.7: {@link PermanentIntegrationException} transitions to
 * {@link HealthState#FAILED} immediately; any other {@link RuntimeException}
 * is treated as transient and triggers retry with backoff.</p>
 *
 * <p><strong>Thread safety:</strong> The adapter is single-threaded — all
 * lifecycle methods are invoked sequentially on the adapter's allocated
 * thread. However, the adapter may internally spawn additional virtual
 * threads for concurrent protocol operations, provided it coordinates
 * their shutdown in {@link #close()}.</p>
 *
 * @see IntegrationFactory
 * @see IntegrationContext
 * @see CommandHandler
 * @see HealthReporter
 */
public interface IntegrationAdapter extends AutoCloseable {

    /**
     * Performs startup work that does not depend on external device connectivity.
     *
     * <p>The adapter should register its identity, declare capabilities, and
     * set up internal data structures. This method must complete within the
     * configured timeout and must not block on network or serial connections
     * (INV-RF-03 — startup independence). Connection to external devices is
     * handled by the adapter's internal reconnection logic during
     * {@link #run()}.</p>
     *
     * @throws PermanentIntegrationException if the adapter cannot initialize
     *         due to an unrecoverable condition
     */
    void initialize() throws PermanentIntegrationException;

    /**
     * The adapter's main processing loop.
     *
     * <p>For network adapters ({@link IoType#NETWORK}), this method blocks on
     * socket I/O on a virtual thread. For serial adapters
     * ({@link IoType#SERIAL}), this method drains the inbound
     * {@code BlockingQueue} fed by the platform thread serial reader and
     * processes events.</p>
     *
     * <p>The method returns normally when the adapter is signaled to stop
     * (via thread interrupt or queue poison pill). Throwing from this method
     * triggers the supervisor's exception classification: a
     * {@link PermanentIntegrationException} transitions to
     * {@link HealthState#FAILED}; any other exception is treated as transient
     * and triggers retry with backoff (Doc 05 §3.7).</p>
     *
     * <p>The adapter should call
     * {@link HealthReporter#reportHeartbeat()} on every loop iteration,
     * including iterations where no data arrived.</p>
     *
     * @throws PermanentIntegrationException if the adapter encounters an
     *         unrecoverable failure during execution
     * @throws Exception if a transient error occurs (triggers retry with backoff)
     */
    void run() throws Exception;

    /**
     * Releases resources held by the adapter: protocol connections, buffers,
     * timers, internal thread pools.
     *
     * <p>Called after {@link #run()} returns, whether normally or exceptionally.
     * Must complete within the shutdown grace period. This method must be
     * idempotent — safe to call multiple times without side effects.</p>
     *
     * <p>Inherited from {@link AutoCloseable} to enable try-with-resources
     * usage in the supervisor.</p>
     */
    @Override
    void close();

    /**
     * Returns the adapter's command handler for processing dispatched commands.
     *
     * <p>The supervisor calls this method after {@link #initialize()} completes
     * to obtain the adapter's {@link CommandHandler} implementation. The
     * supervisor then invokes the handler when {@code command_dispatched}
     * events target devices owned by this integration.</p>
     *
     * <p>This is a getter, not a lifecycle method — it returns the adapter's
     * command handler instance. Returning {@code null} indicates the adapter
     * does not handle commands (read-only integrations such as sensors-only
     * adapters).</p>
     *
     * @return the adapter's command handler, or {@code null} if the adapter
     *         does not handle commands
     */
    CommandHandler commandHandler();

    // ──────────────────────────────────────────────────────────────────
    // Post-setup lifecycle hooks (AMD-55)
    //
    // All four are default methods so that every existing adapter remains
    // source- and binary-compatible with behaviour identical to today
    // (AMD-55-INV-01). The conservative defaults never silently claim a
    // capability the adapter does not have. The supervisor invokes these
    // sequentially on the adapter's thread, never concurrently with another
    // lifecycle method (AMD-55-INV-02).
    // ──────────────────────────────────────────────────────────────────

    /**
     * Called when this integration's configuration changed at runtime.
     *
     * <p>The adapter decides whether it can apply the change in place. The
     * conservative default requests a restart-to-apply
     * ({@link ConfigUpdateOutcome#RESTART_REQUIRED}), preserving today's
     * semantics for an adapter that does not override this hook.</p>
     *
     * @param changes the configuration diff; never {@code null}
     * @return how the adapter handled the change; never {@code null}
     */
    default ConfigUpdateOutcome onConfigUpdated(ConfigChangeSet changes) {
        return ConfigUpdateOutcome.RESTART_REQUIRED;
    }

    /**
     * Called when this integration's runtime-tunable options changed (the
     * additive/minor subset of configuration — polling intervals, rate limits,
     * log verbosity).
     *
     * <p>The conservative default requests a restart-to-apply
     * ({@link ConfigUpdateOutcome#RESTART_REQUIRED}).</p>
     *
     * @param changes the options diff; never {@code null}
     * @return how the adapter handled the change; never {@code null}
     */
    default ConfigUpdateOutcome onOptionsUpdated(ConfigChangeSet changes) {
        return ConfigUpdateOutcome.RESTART_REQUIRED;
    }

    /**
     * Called when the supervisor detects an authentication failure
     * ({@link com.homesynapse.integration.runtime.ExceptionClassification#AUTH_FAILED},
     * AMD-56).
     *
     * <p>{@link ReauthOutcome#INITIATED} signals that the adapter has begun
     * asynchronous re-authentication and will report completion via the
     * {@code integration.reauth.completed} lifecycle event
     * ({@link IntegrationReauthCompleted}); {@link ReauthOutcome#UNSUPPORTED}
     * signals that the adapter does not implement re-authentication, so the
     * supervisor falls back to the standard restart/suspension policy. The
     * default is {@link ReauthOutcome#UNSUPPORTED} — truthfully reporting that no
     * reauth path exists.</p>
     *
     * @return whether re-authentication was initiated or is unsupported;
     *         never {@code null}
     */
    default ReauthOutcome onReauthRequired() {
        return ReauthOutcome.UNSUPPORTED;
    }

    /**
     * Called before {@link #initialize()} when the adapter's stored configuration
     * schema (the {@link IntegrationDescriptor} config-schema pair) is older than
     * the version the adapter declares.
     *
     * <p>The adapter migrates its configuration section via the injected
     * {@code ConfigurationAccess}. A {@link PermanentIntegrationException} from
     * this method drives the FAILED transition without retry, mirroring
     * {@link #initialize()} (AMD-55-INV-03). The default reports nothing to
     * migrate ({@link MigrationOutcome#NOT_REQUIRED}).</p>
     *
     * @param fromMajor the stored configuration schema major version
     * @param fromMinor the stored configuration schema minor version
     * @return whether a migration was performed; never {@code null}
     * @throws PermanentIntegrationException if the migration cannot be completed
     */
    default MigrationOutcome migrate(int fromMajor, int fromMinor)
            throws PermanentIntegrationException {
        return MigrationOutcome.NOT_REQUIRED;
    }
}

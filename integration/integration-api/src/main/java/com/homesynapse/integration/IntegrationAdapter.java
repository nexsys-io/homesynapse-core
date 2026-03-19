/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

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
}

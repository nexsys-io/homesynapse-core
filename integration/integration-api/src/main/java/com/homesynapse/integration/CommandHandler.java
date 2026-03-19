/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Callback interface for processing commands dispatched to an integration's
 * devices (Doc 05 §8.1, §8.6).
 *
 * <p>The adapter provides its {@code CommandHandler} implementation via
 * {@link IntegrationAdapter#commandHandler()}, called by the supervisor after
 * {@link IntegrationAdapter#initialize()} completes. When a
 * {@code command_dispatched} event targets a device owned by this integration,
 * the supervisor extracts the command data into a {@link CommandEnvelope} and
 * invokes {@link #handle(CommandEnvelope)} on the adapter's thread.</p>
 *
 * <p>The adapter translates the command to protocol-specific operations (e.g.,
 * a ZCL frame for Zigbee, an HTTP request for Philips Hue) and publishes a
 * {@code command_result} event via
 * {@link com.homesynapse.event.EventPublisher} with the outcome. The
 * {@link CommandEnvelope#commandEventId()} and
 * {@link CommandEnvelope#correlationId()} fields provide the causal context
 * for linking the result event back to the original command.</p>
 *
 * <p><strong>Thread safety:</strong> This method is invoked on the adapter's
 * allocated thread — virtual thread for {@link IoType#NETWORK} adapters,
 * virtual thread event processor for {@link IoType#SERIAL} adapters.
 * The adapter does not need to synchronize internally unless it shares
 * mutable state with its {@link IntegrationAdapter#run()} processing.</p>
 *
 * <p><strong>Exception handling:</strong> If this method throws, the exception
 * is classified per Doc 05 §3.7: a {@link PermanentIntegrationException}
 * transitions to {@link HealthState#FAILED}; any other exception is treated
 * as transient and triggers retry with backoff.</p>
 *
 * @see CommandEnvelope
 * @see IntegrationAdapter#commandHandler()
 * @see com.homesynapse.event.EventPublisher
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Processes a dispatched command targeting a device owned by this integration.
     *
     * <p>The adapter must translate the command to protocol-specific operations
     * and publish a {@code command_result} event via
     * {@link com.homesynapse.event.EventPublisher}. The result event should
     * use the {@link CommandEnvelope#commandEventId()} as the causation ID
     * and {@link CommandEnvelope#correlationId()} as the correlation ID to
     * maintain the causal chain.</p>
     *
     * @param command the dispatched command envelope containing the target
     *                entity, command name, parameters, and causal context;
     *                never {@code null}
     * @throws PermanentIntegrationException if the command cannot be processed
     *         due to an unrecoverable condition
     * @throws Exception if a transient error occurs (triggers retry with backoff)
     */
    void handle(CommandEnvelope command) throws Exception;
}

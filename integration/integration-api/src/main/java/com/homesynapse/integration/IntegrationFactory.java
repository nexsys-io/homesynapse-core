/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Factory for integration adapter instances, discovered via
 * {@link java.util.ServiceLoader} (LTD-17) at system startup (Doc 05 §8.1, §8.3).
 *
 * <p>Each integration module provides exactly one {@code IntegrationFactory}
 * implementation, declared in its {@code module-info.java} as a
 * {@code provides IntegrationFactory with ...} directive. The supervisor
 * discovers all factories at startup, calls {@link #descriptor()} once to
 * read the integration's requirements, and later calls
 * {@link #create(IntegrationContext)} to instantiate the adapter when the
 * integration is loaded.</p>
 *
 * <p>The factory is responsible for two things: declaring what the integration
 * needs (via the descriptor) and constructing the adapter with the provided
 * context. It must not perform I/O, access external resources, or hold mutable
 * state.</p>
 *
 * @see IntegrationDescriptor
 * @see IntegrationAdapter
 * @see IntegrationContext
 */
public interface IntegrationFactory {

    /**
     * Returns the static descriptor declaring this integration's requirements.
     *
     * <p>Called once during {@link java.util.ServiceLoader} discovery. This
     * method must be a pure function with no side effects — it must not perform
     * I/O, access configuration, or modify any state. The supervisor uses the
     * descriptor to plan resource allocation before constructing the adapter.</p>
     *
     * @return the integration descriptor, never {@code null}
     */
    IntegrationDescriptor descriptor();

    /**
     * Creates an adapter instance using the provided integration context.
     *
     * <p>Called by the supervisor during the LOADING phase. The adapter may
     * use the context to store references to the services it will use during
     * {@link IntegrationAdapter#initialize()} and
     * {@link IntegrationAdapter#run()}, but must not perform blocking I/O
     * or start network connections during construction.</p>
     *
     * @param context the composed API surface for this integration instance,
     *                containing all services declared in the descriptor's
     *                {@link IntegrationDescriptor#requiredServices()};
     *                never {@code null}
     * @return a new adapter instance, never {@code null}
     * @throws PermanentIntegrationException if the adapter cannot be constructed
     *         due to an unrecoverable condition (e.g., missing required
     *         configuration, incompatible environment)
     */
    IntegrationAdapter create(IntegrationContext context) throws PermanentIntegrationException;
}

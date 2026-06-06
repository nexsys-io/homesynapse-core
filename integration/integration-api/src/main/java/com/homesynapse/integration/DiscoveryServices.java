/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Aggregator for the discovery-related services an integration adapter may
 * receive, on {@link IntegrationContext#discovery()} (AMD-59 §2.4).
 *
 * <p>Per the NQ-1 doctrine, {@link IntegrationContext} grows only by
 * service-family aggregator fields — future discovery services become components
 * of this record, and the context never grows for them. The aggregator itself is
 * nullable on the context (gated by {@link RequiredService#DISCOVERY}).</p>
 *
 * @param capabilityPublisher the post-adoption capability-change publisher;
 *                            never {@code null} when this aggregator is present
 *
 * @see IntegrationContext#discovery()
 * @see CapabilityPublisher
 * @see RequiredService#DISCOVERY
 */
public record DiscoveryServices(CapabilityPublisher capabilityPublisher) { }

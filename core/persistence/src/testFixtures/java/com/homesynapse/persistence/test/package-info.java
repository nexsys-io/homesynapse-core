/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Test fixtures for persistence: InMemoryCheckpointStore, InMemoryTelemetryStore.
 *
 * <p>Additional test fixtures live in the parent package
 * ({@code com.homesynapse.persistence}) rather than this subpackage because
 * they require package-private access to {@link com.homesynapse.persistence.WriteCoordinator}
 * and {@link com.homesynapse.persistence.WritePriority}:</p>
 * <ul>
 *   <li>{@code WriteCoordinatorContractTest} — abstract contract test
 *       defining the 11-method behavioral contract</li>
 *   <li>{@code InMemoryWriteCoordinator} — in-memory implementation
 *       for testing</li>
 * </ul>
 */
package com.homesynapse.persistence.test;

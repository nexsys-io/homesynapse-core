/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Test fixtures for the event-bus module.
 *
 * <p>Contains the {@link com.homesynapse.event.bus.test.CheckpointStoreContractTest}
 * abstract contract test suite and the {@link com.homesynapse.event.bus.test.InMemoryCheckpointStore}
 * in-memory implementation. Downstream modules consume these fixtures via
 * {@code testImplementation(testFixtures(project(":core:event-bus")))} to validate
 * their own {@link com.homesynapse.event.bus.CheckpointStore} implementations against
 * the same behavioral contract.</p>
 */
package com.homesynapse.event.bus.test;

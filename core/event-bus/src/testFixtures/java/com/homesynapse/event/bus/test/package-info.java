/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Test fixtures for the event-bus module.
 *
 * <p>Contains the {@link com.homesynapse.event.bus.test.CheckpointStoreContractTest}
 * and {@link com.homesynapse.event.bus.test.EventBusContractTest} abstract contract
 * test suites, the {@link com.homesynapse.event.bus.test.InMemoryCheckpointStore}
 * in-memory checkpoint implementation, and the
 * {@link com.homesynapse.event.bus.test.InMemoryEventBus} in-memory event bus
 * implementation. Downstream modules consume these fixtures via
 * {@code testImplementation(testFixtures(project(":core:event-bus")))} to validate
 * their own {@link com.homesynapse.event.bus.CheckpointStore} and
 * {@link com.homesynapse.event.bus.EventBus} implementations against the same
 * behavioral contracts.</p>
 */
package com.homesynapse.event.bus.test;

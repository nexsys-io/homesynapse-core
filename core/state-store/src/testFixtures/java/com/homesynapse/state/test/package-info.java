/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Test fixtures for the state-store module.
 *
 * <p>Contains the {@link com.homesynapse.state.test.ViewCheckpointStoreContractTest}
 * abstract contract test suite and the
 * {@link com.homesynapse.state.test.InMemoryViewCheckpointStore} in-memory
 * implementation. Downstream modules consume these fixtures via
 * {@code testImplementation(testFixtures(project(":core:state-store")))} to validate
 * their own {@link com.homesynapse.state.ViewCheckpointStore} implementations against
 * the same behavioral contract.</p>
 */
package com.homesynapse.state.test;

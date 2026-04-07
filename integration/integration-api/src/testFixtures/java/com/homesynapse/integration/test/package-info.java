/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
/**
 * Test fixtures for integration-api: StubIntegrationContext, TestAdapter,
 * StubCommandHandler.
 *
 * <p>These fixtures enable deterministic, contract-level adapter testing without
 * protocol specifics. StubIntegrationContext provides a fully wired test context
 * backed by in-memory implementations (InMemoryEventStore for EventPublisher,
 * stub registries for entity/state queries). TestAdapter provides configurable
 * adapter behavior (noop, echo, failing, custom). StubCommandHandler records
 * commands for assertion.</p>
 */
package com.homesynapse.integration.test;

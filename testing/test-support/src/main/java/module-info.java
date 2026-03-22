/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Cross-cutting test infrastructure for HomeSynapse Core.
 * <p>
 * Provides controllable clocks, synchronous event bus, I/O guard extensions,
 * and custom AssertJ assertions for deterministic, fast testing of all modules.
 */
module com.homesynapse.test {
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.event.bus;
    requires transitive com.homesynapse.device;
    requires transitive com.homesynapse.integration;

    requires transitive org.junit.jupiter.api;
    requires transitive org.assertj.core;

    exports com.homesynapse.test;
    exports com.homesynapse.test.assertions;
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Integration runtime module — supervisor, health state machine, and thread
 * allocation for integration adapters.
 *
 * <p>Depends on {@code com.homesynapse.integration} (integration-api) for
 * adapter-facing contracts; {@code com.homesynapse.event},
 * {@code com.homesynapse.device}, {@code com.homesynapse.config},
 * {@code com.homesynapse.state}, and {@code com.homesynapse.platform} all
 * resolve transitively through it (M9.1 — the stale "implementation-only
 * import" prose about event-model was corrected in passing; no direct
 * requires exists or is needed).</p>
 */
module com.homesynapse.integration.runtime {
    requires transitive com.homesynapse.integration;

    // M9.1: IntegrationSupervisorAssembly.Components exposes the event-bus
    // Subscriber interface on this module's exported API (the composition root
    // consumes it) -> requires transitive, paired with api(...) in Gradle
    // (the -Xlint:exports / M2.9-M3.6e.1-M5-A lockstep rule).
    requires transitive com.homesynapse.event.bus;

    // M9.1: supervisor + router log via SLF4J. Implementation-only (LTD-15 /
    // DECIDE-01) -- no SLF4J type on the exported API.
    requires org.slf4j;

    exports com.homesynapse.integration.runtime;
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Application assembly and entry point for HomeSynapse Core.
 *
 * <p>This package contains the top-level application wiring that connects
 * all subsystem modules into a running system. It is the apex of the
 * module dependency graph — every other HomeSynapse module is reachable
 * from here, but no other module depends on this package.
 *
 * <p>The application lifecycle is delegated to
 * {@link com.homesynapse.lifecycle.SystemLifecycleManager}, which
 * orchestrates the seven-phase initialization sequence defined in
 * Doc 12 (Startup, Lifecycle &amp; Shutdown):
 *
 * <ul>
 *   <li><b>Phase 0 — BOOTSTRAP:</b> Platform paths, logging, JFR recording,
 *       health reporter initialization</li>
 *   <li><b>Phase 1 — FOUNDATION:</b> Configuration loading and validation</li>
 *   <li><b>Phase 2 — DATA_INFRASTRUCTURE:</b> Persistence layer, event bus</li>
 *   <li><b>Phase 3 — CORE_DOMAIN:</b> Device model, state store, automation engine</li>
 *   <li><b>Phase 4 — OBSERVABILITY:</b> Health aggregation, JFR metrics</li>
 *   <li><b>Phase 5 — EXTERNAL_INTERFACES:</b> REST API, WebSocket API, web dashboard</li>
 *   <li><b>Phase 6 — INTEGRATIONS:</b> Integration adapter discovery and startup</li>
 * </ul>
 *
 * <p>Phase 3 implementation will expand {@link com.homesynapse.app.Main}
 * with subsystem construction, dependency injection (manual wiring, no
 * framework), JVM shutdown hook registration, and signal handling.
 *
 * @see com.homesynapse.lifecycle.SystemLifecycleManager
 * @see com.homesynapse.lifecycle.LifecyclePhase
 * @see com.homesynapse.platform.HealthReporter
 * @see com.homesynapse.platform.PlatformPaths
 */
package com.homesynapse.app;

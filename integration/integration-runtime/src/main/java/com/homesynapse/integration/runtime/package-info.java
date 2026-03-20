/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Integration runtime — the supervisory layer that loads, isolates, monitors,
 * and lifecycle-manages every integration adapter within the HomeSynapse process
 * (Doc 05 §4, §5, §6, §8).
 *
 * <p>This module sits between the adapter-facing contracts defined in
 * {@code com.homesynapse.integration} (integration-api, Block I) and the
 * protocol-specific adapter implementations (e.g., Zigbee, MQTT). Where
 * integration-api defines <em>what an adapter declares and receives</em>,
 * this module defines <em>what the supervisor does with those declarations</em>.</p>
 *
 * <h2>Responsibilities (Phase 3 implementation)</h2>
 *
 * <p><strong>Lifecycle management:</strong> The {@link IntegrationSupervisor}
 * discovers integrations from a provided factory list, validates descriptors,
 * builds a dependency graph with topological sort (Kahn's algorithm with cycle
 * detection per AMD-14), and starts all enabled integrations concurrently.
 * Shutdown proceeds in reverse startup order with per-adapter grace periods.</p>
 *
 * <p><strong>Health monitoring:</strong> A four-state health model
 * ({@link com.homesynapse.integration.HealthState}: HEALTHY → DEGRADED →
 * SUSPENDED → FAILED) with asymmetric hysteresis. The supervisor tracks
 * error, timeout, and slow-call rates via sliding windows, calculates a
 * weighted health score, and enforces transition guards. Per-integration
 * health state is captured as immutable {@link IntegrationHealthRecord}
 * snapshots exposed via REST API.</p>
 *
 * <p><strong>Thread allocation:</strong> Virtual threads for network I/O
 * adapters, dedicated platform threads for serial I/O (JNI) adapters
 * (LTD-01). Thread allocation is determined by the adapter's declared
 * {@link com.homesynapse.integration.IoType}.</p>
 *
 * <p>The supervisor implements OTP-style one-for-one supervision (Doc 05 §3.4):
 * only the failed adapter is restarted; all other integrations continue
 * operating. This directly serves the reliability invariant INV-RF-01
 * (integration isolation) and prevents the Home Assistant anti-pattern where
 * a single broken integration degrades the entire platform.</p>
 *
 * @see IntegrationSupervisor
 * @see IntegrationHealthRecord
 * @see SlidingWindow
 * @see ExceptionClassification
 * @see com.homesynapse.integration.IntegrationFactory
 * @see com.homesynapse.integration.IntegrationAdapter
 * @see com.homesynapse.integration.HealthState
 */
package com.homesynapse.integration.runtime;

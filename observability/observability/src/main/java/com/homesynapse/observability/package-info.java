/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Observability and debugging infrastructure for HomeSynapse Core.
 *
 * <p>This package provides four capabilities:</p>
 *
 * <ul>
 *   <li><strong>Health Aggregation</strong> — {@link HealthAggregator} composes
 *       per-subsystem health indicators (reported via {@link HealthContributor})
 *       into a tiered {@link SystemHealth} model with three tiers
 *       ({@link HealthTier}) and three lifecycle states ({@link LifecycleState}).
 *       Health evaluation is deterministic, reactive, and O(10).</li>
 *   <li><strong>Trace Queries</strong> — {@link TraceQueryService} makes the
 *       causal chain metadata (correlation_id, causation_id) on every event
 *       envelope navigable. Returns {@link TraceChain} results with hierarchical
 *       {@link TraceNode} tree structures and {@link TraceCompleteness}
 *       status.</li>
 *   <li><strong>JFR Metrics Infrastructure</strong> — {@link MetricsRegistry}
 *       manages custom JFR event type registration. {@link MetricsStreamBridge}
 *       reads JFR events via RecordingStream and produces aggregated
 *       {@link MetricSnapshot} instances for real-time consumer push.</li>
 *   <li><strong>Dynamic Log Level Control</strong> — {@link LogLevelController}
 *       adjusts per-package SLF4J/Logback log levels at runtime without restart.
 *       Active overrides are tracked as {@link LogLevelOverride} records.</li>
 * </ul>
 *
 * <p>Design authority: Doc 11 — Observability &amp; Debugging (Locked).</p>
 *
 * @see HealthAggregator
 * @see TraceQueryService
 * @see MetricsStreamBridge
 * @see LogLevelController
 */
package com.homesynapse.observability;

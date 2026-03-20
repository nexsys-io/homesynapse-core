/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Observability and debugging infrastructure for HomeSynapse Core.
 *
 * <p>This module provides system-wide health aggregation (composing per-subsystem
 * health indicators into an actionable tiered model), causal chain trace queries
 * (making the correlation_id/causation_id metadata navigable), JFR metrics
 * infrastructure (continuous recording, custom event registry, streaming bridge),
 * and dynamic log level control.</p>
 *
 * @see com.homesynapse.observability
 */
module com.homesynapse.observability {
    requires transitive com.homesynapse.event;

    exports com.homesynapse.observability;
}

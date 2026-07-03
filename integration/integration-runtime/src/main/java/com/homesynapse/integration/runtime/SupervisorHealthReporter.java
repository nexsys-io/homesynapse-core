/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.HealthState;
import com.homesynapse.platform.identity.IntegrationId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;

/**
 * The per-integration {@link HealthReporter} backing
 * {@code IntegrationContext.healthReporter} (M9.1 slice): a write-through to
 * the owning integration's supervisor state, guarded by the supervisor's
 * state lock.
 *
 * <p><strong>Honest storage, no evaluation.</strong> Heartbeats are stamped
 * from the supervisor's injected {@code Clock}; keepalives store the
 * adapter-reported protocol-level timestamp verbatim; errors increment the
 * error-window count. There is no heartbeat-timeout sweep, no window-rate
 * evaluation, and no DEGRADED/SUSPENDED transition logic — that is deferred
 * supervisor breadth (post-hero unit, NQ-6). {@link #reportHealthTransition}
 * is therefore recorded as a structured log line only: the M9.1 FSM does not
 * evaluate adapter state suggestions.</p>
 */
final class SupervisorHealthReporter implements HealthReporter {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorHealthReporter.class);

    private final StandardIntegrationSupervisor supervisor;
    private final IntegrationId integrationId;

    SupervisorHealthReporter(StandardIntegrationSupervisor supervisor,
                             IntegrationId integrationId) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.integrationId = Objects.requireNonNull(integrationId, "integrationId");
    }

    @Override
    public void reportHeartbeat() {
        supervisor.recordHeartbeat(integrationId);
    }

    @Override
    public void reportKeepalive(Instant lastSuccess) {
        Objects.requireNonNull(lastSuccess, "lastSuccess");
        supervisor.recordKeepalive(integrationId, lastSuccess);
    }

    @Override
    public void reportError(Throwable error) {
        Objects.requireNonNull(error, "error");
        supervisor.recordReportedError(integrationId, error);
    }

    @Override
    public void reportHealthTransition(HealthState suggestedState, String reason) {
        Objects.requireNonNull(suggestedState, "suggestedState");
        // M9.1: recorded, not evaluated — the probe ladder / DEGRADED / SUSPENDED
        // machinery that would act on a suggestion is deferred breadth.
        LOG.info("integration.health_transition_suggested: integration_id={} suggested_state={} "
                + "reason={}", integrationId, suggestedState, reason);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Result of an adapter's in-place configuration or options update hook
 * ({@link IntegrationAdapter#onConfigUpdated(com.homesynapse.config.ConfigChangeSet)},
 * {@link IntegrationAdapter#onOptionsUpdated(com.homesynapse.config.ConfigChangeSet)})
 * — AMD-55 §2.1.
 *
 * <p>The supervisor reads this outcome to decide how the configuration change
 * takes effect. The outcome-enum channel — rather than exception typing — is the
 * established hook-result pattern:
 * {@link PermanentIntegrationException} drives FAILED with no-retry semantics,
 * far too heavy for an in-place configuration edit.</p>
 *
 * @see IntegrationAdapter#onConfigUpdated(com.homesynapse.config.ConfigChangeSet)
 * @see IntegrationAdapter#onOptionsUpdated(com.homesynapse.config.ConfigChangeSet)
 * @see IntegrationConfigUpdated
 * @see IntegrationOptionsUpdated
 */
public enum ConfigUpdateOutcome {

    /**
     * The adapter applied the new configuration in place; no restart is needed.
     * The supervisor emits the corresponding lifecycle event with this outcome.
     */
    APPLIED,

    /**
     * The adapter cannot apply the change without a restart. The supervisor
     * schedules a planned restart (interacting with
     * {@link IntegrationDescriptor#plannedRestartTimeout()}) and emits the
     * corresponding lifecycle event with this outcome. This is the conservative
     * default for an adapter that does not override the update hooks.
     */
    RESTART_REQUIRED,

    /**
     * The adapter could not apply the new configuration in place and the new
     * configuration must not take effect (AMD-55 §2.1, ratification edit E3).
     * The supervisor restores the prior configuration section — which remains the
     * valid running configuration — via a planned restart, and emits the
     * corresponding lifecycle event with this outcome. A rejected apply never
     * leaves the rejected configuration active (AMD-55-INV-04).
     */
    REJECTED
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Result of an adapter's configuration-schema migration hook
 * ({@link IntegrationAdapter#migrate(int, int)}) — AMD-55 §2.1.
 *
 * <p>A failed migration is intentionally NOT represented here: a
 * {@link PermanentIntegrationException} from {@code migrate} drives the FAILED
 * transition (AMD-55-INV-03), observable via the existing health/lifecycle
 * transition events. The asymmetry with {@link ConfigUpdateOutcome#REJECTED} is
 * deliberate — a rejected configuration apply has a valid fallback state (the
 * prior configuration), so it is an expressible outcome; a failed migration has
 * no valid state to fall back to (old-schema configuration + new-schema code),
 * so it is correctly a permanent failure (AMD-58 §2.1).</p>
 *
 * @see IntegrationAdapter#migrate(int, int)
 * @see IntegrationMigrationCompleted
 */
public enum MigrationOutcome {

    /**
     * The adapter migrated its stored configuration section from the older
     * schema to the version it declares. The supervisor emits
     * {@link IntegrationMigrationCompleted} with this outcome.
     */
    MIGRATED,

    /**
     * No migration was required — the stored configuration schema already
     * matches the version the adapter declares. This is the default for an
     * adapter that does not override {@link IntegrationAdapter#migrate(int, int)}.
     */
    NOT_REQUIRED
}

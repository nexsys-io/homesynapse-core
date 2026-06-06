/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.EventType;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.IntegrationId;

import java.util.Objects;

/**
 * Payload for {@code integration.migration.completed} events, produced when an
 * adapter migrates its stored configuration section from an older schema via
 * {@link IntegrationAdapter#migrate(int, int)} (AMD-58 §2.1).
 *
 * <p>The from/to schema pair records the {@link IntegrationDescriptor}
 * config-schema versions involved, and {@link #outcome()} reports whether a
 * migration was actually performed. A <em>failed</em> migration emits no event —
 * a {@link PermanentIntegrationException} from {@code migrate} drives the FAILED
 * transition instead (AMD-55-INV-03), which is itself the signal. A migration
 * does not change {@code HealthState}, so {@link #previousState()} and
 * {@link #newState()} both carry the current state (non-null).</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param previousState   the health state before migration; never {@code null}
 * @param newState        the health state after migration; never {@code null}
 * @param reason          human-readable reason / description; never {@code null}
 * @param fromMajor       the stored configuration schema major version migrated from
 * @param fromMinor       the stored configuration schema minor version migrated from
 * @param toMajor         the configuration schema major version migrated to
 * @param toMinor         the configuration schema minor version migrated to
 * @param outcome         whether a migration was performed; never {@code null}
 *
 * @see IntegrationLifecycleEvent
 * @see MigrationOutcome
 * @see IntegrationAdapter#migrate(int, int)
 * @see IntegrationDescriptor#configSchemaMajor()
 */
@EventType(EventTypes.INTEGRATION_MIGRATION_COMPLETED)
public record IntegrationMigrationCompleted(
        IntegrationId integrationId,
        String integrationType,
        HealthState previousState,
        HealthState newState,
        String reason,
        int fromMajor,
        int fromMinor,
        int toMajor,
        int toMinor,
        MigrationOutcome outcome
) implements IntegrationLifecycleEvent {

    public IntegrationMigrationCompleted {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}

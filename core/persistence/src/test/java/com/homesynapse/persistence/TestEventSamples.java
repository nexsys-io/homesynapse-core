/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.ConfigChangedEvent;
import com.homesynapse.event.ConfigErrorEvent;
import com.homesynapse.event.DeviceAdoptedEvent;
import com.homesynapse.event.DeviceDiscoveredEvent;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.EventId;
import com.homesynapse.event.PresenceChangedEvent;
import com.homesynapse.event.PresenceSignalEvent;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportRejectedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.StoragePressureChangedEvent;
import com.homesynapse.event.SystemStartedEvent;
import com.homesynapse.event.SystemStoppedEvent;
import com.homesynapse.event.TelemetrySummaryEvent;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationHealthChanged;
import com.homesynapse.integration.IntegrationResourceExceeded;
import com.homesynapse.integration.IntegrationRestarted;
import com.homesynapse.integration.IntegrationStarted;
import com.homesynapse.integration.IntegrationStopped;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Deterministic, valid sample instances for every registered event record.
 * Used by the M2.4 serialization tests to verify round-trip encoding. Every
 * sample satisfies its record's compact-constructor validation so tests can
 * depend on them without instantiation errors.
 */
final class TestEventSamples {

    /** Stable ULID used across all samples for determinism. */
    static final Ulid ULID_1 = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    static final Ulid ULID_2 = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ");
    static final Ulid ULID_3 = Ulid.parse("01F8MECHZX3TBDSZ7XRADM79XE");

    static final EventId EVENT_ID_1 = EventId.of(ULID_1);
    static final EventId EVENT_ID_2 = EventId.of(ULID_2);

    static final IntegrationId INTEGRATION_ID_1 = IntegrationId.of(ULID_3);

    private TestEventSamples() {
        // Utility class — non-instantiable
    }

    static CommandIssuedEvent commandIssued() {
        return new CommandIssuedEvent(
                ULID_1,
                "turn_on",
                "{\"level\":100}",
                5000,
                CommandIdempotency.IDEMPOTENT);
    }

    static CommandDispatchedEvent commandDispatched() {
        return new CommandDispatchedEvent(ULID_1, ULID_3, "zigbee://0x1234");
    }

    static CommandResultEvent commandResult() {
        return new CommandResultEvent(ULID_1, "turn_on", "success", null);
    }

    static CommandConfirmationTimedOutEvent commandConfirmationTimedOut() {
        return new CommandConfirmationTimedOutEvent(EVENT_ID_1, EVENT_ID_2);
    }

    static StateReportedEvent stateReported() {
        return new StateReportedEvent("power", "on", null, null, null);
    }

    static StateReportRejectedEvent stateReportRejected() {
        return new StateReportRejectedEvent(
                "power", "maybe", "invalid enum value", "power in [on,off]");
    }

    static StateChangedEvent stateChanged() {
        return new StateChangedEvent("power", "off", "on", EVENT_ID_1);
    }

    static StateConfirmedEvent stateConfirmed() {
        return new StateConfirmedEvent(
                EVENT_ID_1, EVENT_ID_2, "power", "on", "on", "exact");
    }

    static DeviceDiscoveredEvent deviceDiscovered() {
        return new DeviceDiscoveredEvent(ULID_3, "zigbee://0xABCD", "Acme", "SmartPlug v2");
    }

    static DeviceAdoptedEvent deviceAdopted() {
        return new DeviceAdoptedEvent(ULID_1);
    }

    static DeviceRemovedEvent deviceRemoved() {
        return new DeviceRemovedEvent("user initiated removal");
    }

    static AvailabilityChangedEvent availabilityChanged() {
        return new AvailabilityChangedEvent("online", "offline");
    }

    static AutomationTriggeredEvent automationTriggered() {
        return new AutomationTriggeredEvent("state_change", "power changed to on");
    }

    static AutomationCompletedEvent automationCompleted() {
        return new AutomationCompletedEvent("success", null, 1234L);
    }

    static AutomationCompletedEvent automationCompletedWithFailure() {
        return new AutomationCompletedEvent("failed", "device unreachable", 500L);
    }

    static PresenceSignalEvent presenceSignal() {
        return new PresenceSignalEvent("motion", "hallway_sensor", "detected");
    }

    static PresenceChangedEvent presenceChanged() {
        return new PresenceChangedEvent("away", "home");
    }

    static SystemStartedEvent systemStarted() {
        return new SystemStartedEvent("1.0.0", 2500L);
    }

    static SystemStoppedEvent systemStopped() {
        return new SystemStoppedEvent("graceful shutdown", true);
    }

    static StoragePressureChangedEvent storagePressureChanged() {
        return new StoragePressureChangedEvent("NORMAL", "WARNING", 1_000_000L, 900_000L);
    }

    static ConfigChangedEvent configChanged() {
        return new ConfigChangedEvent("retention.diagnostic_days", "7", "14");
    }

    static ConfigErrorEvent configError() {
        return new ConfigErrorEvent(
                "retention.diagnostic_days", "warning", "value out of range", "7");
    }

    static TelemetrySummaryEvent telemetrySummary() {
        return new TelemetrySummaryEvent(
                "temperature",
                18.5,
                22.3,
                20.4,
                2040.0,
                100L,
                1_700_000_000_000L,
                1_700_000_300_000L,
                false);
    }

    // ===== Integration lifecycle events =====

    static IntegrationStarted integrationStarted() {
        return new IntegrationStarted(
                INTEGRATION_ID_1, "zigbee", HealthState.HEALTHY, "initial startup");
    }

    static IntegrationStopped integrationStopped() {
        return new IntegrationStopped(
                INTEGRATION_ID_1,
                "zigbee",
                HealthState.HEALTHY,
                HealthState.FAILED,
                "shutdown requested");
    }

    static IntegrationHealthChanged integrationHealthChanged() {
        return new IntegrationHealthChanged(
                INTEGRATION_ID_1,
                "zigbee",
                HealthState.HEALTHY,
                HealthState.DEGRADED,
                "health score below threshold (0.35)",
                0.35);
    }

    static IntegrationRestarted integrationRestarted() {
        return new IntegrationRestarted(
                INTEGRATION_ID_1,
                "zigbee",
                HealthState.SUSPENDED,
                HealthState.HEALTHY,
                "probe succeeded",
                2);
    }

    static IntegrationResourceExceeded integrationResourceExceeded() {
        return new IntegrationResourceExceeded(
                INTEGRATION_ID_1,
                "zigbee",
                HealthState.HEALTHY,
                HealthState.DEGRADED,
                "memory quota exceeded",
                "memory",
                "256 MB",
                "128 MB");
    }
}

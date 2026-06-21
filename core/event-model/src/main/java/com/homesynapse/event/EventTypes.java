/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.List;

/**
 * Canonical registry of event type strings AND the canonical roster of core
 * {@link DomainEvent} payload classes used in the HomeSynapse event taxonomy.
 *
 * <p>This class defines all core event type string constants referenced by
 * {@link EventEnvelope#eventType()} and the {@link EventType} annotation values on
 * the 37 core payload records. Each constant uses UPPER_SNAKE_CASE names with
 * lower_snake_case string values for consistency with the taxonomy defined in
 * Doc 01 §4.3.
 *
 * <p><strong>Core vs. Integration Types:</strong>
 * Core event types are defined as constants in this class. Integration-defined types use a
 * dotted namespace convention: {@code {integration}.{type}}. At registration time, integrations
 * must ensure their types do not collide with core type names.
 *
 * <p><strong>Extensibility:</strong>
 * This class is not exhaustive for the system. Integrations add their own event types at runtime
 * following the namespace convention. This class captures only the core, built-in event types
 * shared across all HomeSynapse deployments.
 *
 * <p><strong>Core production event class manifest (M3.6c):</strong>
 * {@link #CORE_PRODUCTION_EVENT_CLASSES} is the canonical, ordered list of the 37 core
 * {@link DomainEvent} payload record classes that ship with HomeSynapse Core. The composition
 * root aggregates this list with the per-module manifests contributed by other modules
 * (currently {@code IntegrationEvents.LIFECYCLE_EVENT_CLASSES} in
 * {@code com.homesynapse.integration}) to build the
 * {@code EventTypeRegistry} at startup. Adding, removing, or renaming a core event record
 * <strong>requires editing this list</strong> — the same forcing function that
 * {@code EventTypeRegistry} enforces at registration time. Per DECIDE-04, classpath scanning
 * and {@code ServiceLoader} discovery are banned; aggregation is explicit.
 *
 * <p>Closes Q3 of the M3.6 gap-closure research: per-module event-class manifests are the
 * DECIDE-04-compliant alternative to {@code ServiceLoader}.
 *
 * @see EventEnvelope
 * @see EventType
 * @see DomainEvent
 */
public final class EventTypes {

	private EventTypes() {
		// Utility class: non-instantiable
	}

	// ========== Command Lifecycle ==========

	/** Event issued when a command is first created and queued. */
	public static final String COMMAND_ISSUED = "command_issued";

	/** Event issued when a command is dispatched to its target device or service. */
	public static final String COMMAND_DISPATCHED = "command_dispatched";

	/** Event issued when a command completes with a result (success or failure). */
	public static final String COMMAND_RESULT = "command_result";

	/** Event issued when a command confirmation times out without acknowledgment. */
	public static final String COMMAND_CONFIRMATION_TIMED_OUT = "command_confirmation_timed_out";

	// ========== State Lifecycle ==========

	/** Event issued when a device reports its current state. */
	public static final String STATE_REPORTED = "state_reported";

	/** Event issued when a state report is rejected due to validation or business logic errors. */
	public static final String STATE_REPORT_REJECTED = "state_report_rejected";

	/** Event issued when an entity's state changes. */
	public static final String STATE_CHANGED = "state_changed";

	/** Event issued when a state change is confirmed by the device. */
	public static final String STATE_CONFIRMED = "state_confirmed";

	// ========== Device Lifecycle ==========

	/** Event issued when a device is discovered on the network. */
	public static final String DEVICE_DISCOVERED = "device_discovered";

	/** Event issued when a device is adopted into the system. */
	public static final String DEVICE_ADOPTED = "device_adopted";

	/** Event issued when a device is removed from the system. */
	public static final String DEVICE_REMOVED = "device_removed";

	/** Event issued when device metadata (name, location, etc.) changes. */
	public static final String DEVICE_METADATA_CHANGED = "device_metadata_changed";

	/** Event issued when an entity is transferred between devices or subsystems. */
	public static final String ENTITY_TRANSFERRED = "entity_transferred";

	/** Event issued when an entity's type is changed. */
	public static final String ENTITY_TYPE_CHANGED = "entity_type_changed";

	/** Event issued when a device's availability status changes. */
	public static final String AVAILABILITY_CHANGED = "availability_changed";

	// ========== Device and Entity Profile ==========

	/** Event issued when an entity's profile or capabilities change. */
	public static final String ENTITY_PROFILE_CHANGED = "entity_profile_changed";

	/** Event issued when an entity is enabled. */
	public static final String ENTITY_ENABLED = "entity_enabled";

	/** Event issued when an entity is disabled. */
	public static final String ENTITY_DISABLED = "entity_disabled";

	// ========== Automation Lifecycle ==========

	/** Event issued when an automation rule is triggered. */
	public static final String AUTOMATION_TRIGGERED = "automation_triggered";

	/** Event issued when an automation rule completes execution. */
	public static final String AUTOMATION_COMPLETED = "automation_completed";

	// ========== Automation Run-Initiation Vocabulary (M7.1, AMD-92) ==========

	/** Event issued when an automation is explicitly invoked (the ManualTrigger source). */
	public static final String AUTOMATION_INVOKED = "automation_invoked";

	/** Diagnostic: a selector followed a slug tombstone chain to a current entity. */
	public static final String AUTOMATION_SLUG_REDIRECT = "automation_slug_redirect";

	/** Diagnostic: a {@code for_duration} timer (AMD-25) was started. */
	public static final String TRIGGER_DURATION_STARTED = "trigger_duration_started";

	/** Diagnostic: a {@code for_duration} timer was cancelled before expiry. */
	public static final String TRIGGER_DURATION_CANCELLED = "trigger_duration_cancelled";

	/** Diagnostic: a {@code for_duration} timer expired and fired its trigger. */
	public static final String TRIGGER_DURATION_EXPIRED = "trigger_duration_expired";

	/** Diagnostic: a {@code for_duration} expiry state-validation read diverged from expectation. */
	public static final String TRIGGER_DURATION_STATE_VALIDATED = "trigger_duration_state_validated";

	/** Diagnostic: a {@code for_duration} timer was rejected for exceeding the concurrent-timer ceiling. */
	public static final String TRIGGER_DURATION_LIMIT_EXCEEDED = "trigger_duration_limit_exceeded";

	// ========== M7.2 run-lifecycle vocabulary (AMD-92) ==========

	/** Diagnostic: a matched trigger was dropped by concurrency-mode enforcement (no Run created). */
	public static final String AUTOMATION_RUN_SKIPPED = "automation_run_skipped";

	/** Diagnostic: a RESTART-mode trigger cancelled an in-flight Run before starting its replacement. */
	public static final String AUTOMATION_RUN_CANCELLED = "automation_run_cancelled";

	/** Event issued when an automation is auto-disabled after repeated Run failures. */
	public static final String AUTOMATION_DISABLED = "automation_disabled";

	/** Diagnostic: a cascade Run was suppressed for exceeding the causal-chain depth ceiling. */
	public static final String CASCADE_DEPTH_EXCEEDED = "cascade_depth_exceeded";

	/** Diagnostic: a cascade Run was suppressed because its automation already appears in the chain. */
	public static final String CASCADE_LOOP_DETECTED = "cascade_loop_detected";

	// ========== Presence ==========

	/** Event issued when a presence signal is received from a sensor or detector. */
	public static final String PRESENCE_SIGNAL = "presence_signal";

	/** Event issued when detected presence status changes. */
	public static final String PRESENCE_CHANGED = "presence_changed";

	// ========== System Lifecycle ==========

	/** Event issued when the system starts up. */
	public static final String SYSTEM_STARTED = "system_started";

	/** Event issued when the system shuts down. */
	public static final String SYSTEM_STOPPED = "system_stopped";

	/** Event issued when system configuration changes. */
	public static final String CONFIG_CHANGED = "config_changed";

	/** Event issued when a configuration error is detected. */
	public static final String CONFIG_ERROR = "config_error";

	/** Event issued when a schema migration is applied. */
	public static final String MIGRATION_APPLIED = "migration_applied";

	/** Event issued when a system snapshot is created. */
	public static final String SNAPSHOT_CREATED = "snapshot_created";

	/** Event issued when system storage reaches a critical threshold. */
	public static final String SYSTEM_STORAGE_CRITICAL = "system_storage_critical";

	/** Event issued when the system registry is rebuilt. */
	public static final String SYSTEM_REGISTRY_REBUILT = "system_registry_rebuilt";

	/** Event issued when storage pressure level changes. */
	public static final String STORAGE_PRESSURE_CHANGED = "storage_pressure_changed";

	// ========== Persistence and Storage Health ==========

	/** Event issued when a system integrity failure is detected. */
	public static final String SYSTEM_INTEGRITY_FAILURE = "system_integrity_failure";

	/** Event issued when a system backup operation fails. */
	public static final String SYSTEM_BACKUP_FAILED = "system_backup_failed";

	/** Event issued when the telemetry store is rebuilt. */
	public static final String TELEMETRY_STORE_REBUILT = "telemetry_store_rebuilt";

	/** Event issued when a persistence vacuum operation fails. */
	public static final String PERSISTENCE_VACUUM_FAILED = "persistence_vacuum_failed";

	/** Event issued when persistence retention is incomplete. */
	public static final String PERSISTENCE_RETENTION_INCOMPLETE = "persistence_retention_incomplete";

	// ========== Cross-Subsystem Diagnostic ==========

	/** Event issued when an automation has mismatched capabilities with its targets. */
	public static final String AUTOMATION_CAPABILITY_MISMATCH = "automation_capability_mismatch";

	// ========== Telemetry ==========

	/** Event issued as a summary of telemetry data. */
	public static final String TELEMETRY_SUMMARY = "telemetry_summary";

	// ========== Health / Subscriber ==========

	/** Event issued when a subscriber checkpoint expires. */
	public static final String SUBSCRIBER_CHECKPOINT_EXPIRED = "subscriber_checkpoint_expired";

	/** Event issued when a subscriber is falling behind in event consumption. */
	public static final String SUBSCRIBER_FALLING_BEHIND = "subscriber_falling_behind";

	/** Event issued when causality depth reaches a warning threshold. */
	public static final String CAUSALITY_DEPTH_WARNING = "causality_depth_warning";

	// ========== Integration Lifecycle (Doc 05 §4.4) ==========

	/** Event issued when an integration adapter transitions to the running state. */
	public static final String INTEGRATION_STARTED = "integration_started";

	/** Event issued when an integration adapter transitions from running to stopped. */
	public static final String INTEGRATION_STOPPED = "integration_stopped";

	/** Event issued when an integration adapter transitions between health states. */
	public static final String INTEGRATION_HEALTH_CHANGED = "integration_health_changed";

	/** Event issued when an integration adapter is successfully restarted after a failure. */
	public static final String INTEGRATION_RESTARTED = "integration_restarted";

	/** Event issued when an integration adapter exceeds a resource quota. */
	public static final String INTEGRATION_RESOURCE_EXCEEDED = "integration_resource_exceeded";

	// ========== Integration Lifecycle — dot-namespaced (AMD-58) ==========

	/** Event issued when an adapter applies (or rejects) a runtime configuration change. */
	public static final String INTEGRATION_CONFIG_UPDATED = "integration.config.updated";

	/** Event issued when an adapter applies (or rejects) a runtime-tunable options change. */
	public static final String INTEGRATION_OPTIONS_UPDATED = "integration.options.updated";

	/** Event issued when the supervisor demands re-authentication from an adapter. */
	public static final String INTEGRATION_REAUTH_REQUIRED = "integration.reauth.required";

	/** Event issued when an adapter completes asynchronous re-authentication. */
	public static final String INTEGRATION_REAUTH_COMPLETED = "integration.reauth.completed";

	/** Event issued when an adapter migrates its configuration schema. */
	public static final String INTEGRATION_MIGRATION_COMPLETED = "integration.migration.completed";

	// ========== Capability Lifecycle — dot-namespaced (AMD-59) ==========

	/** Event issued when an entity gains a capability after adoption. */
	public static final String CAPABILITY_ADDED = "capability.added";

	/** Event issued when an entity loses a capability after adoption. */
	public static final String CAPABILITY_REMOVED = "capability.removed";

	// ========== Configuration Pipeline — dot-namespaced (AMD-70) ==========

	/** Event issued when a configuration load/reload validation pass completes. */
	public static final String CONFIG_VALIDATION_COMPLETED = "config.validation_completed";

	/** Event issued per configuration section actually changed by a reload. */
	public static final String CONFIG_SECTION_RELOADED = "config.section_reloaded";

	// ========== Core Production Event Class Manifest (M3.6c, DECIDE-04) ==========

	/**
	 * Canonical, ordered list of the 37 core {@link DomainEvent} payload record classes
	 * that ship with HomeSynapse Core. Every entry carries an {@link EventType} annotation
	 * whose value is one of the string constants above and which is registered with the
	 * {@code EventTypeRegistry} at startup.
	 *
	 * <p>The composition root aggregates this list with the per-module manifests
	 * contributed by other modules (currently
	 * {@code IntegrationEvents.LIFECYCLE_EVENT_CLASSES} in
	 * {@code com.homesynapse.integration}) using {@link java.util.stream.Stream#concat}.
	 * Per DECIDE-04, this explicit aggregation is the only sanctioned discovery
	 * mechanism — classpath scanning and {@code ServiceLoader} are banned and enforced
	 * by ArchUnit Rule 3 ({@code noServiceLoader}).
	 *
	 * <p>{@link java.util.List#of(Object...)} returns an immutable list (JEP 269); the
	 * field is intentionally exposed directly rather than through a defensive copy.
	 *
	 * <p><strong>Forcing function:</strong> adding a new core event record requires
	 * editing this list. {@code EventTypeRegistry} construction will fail loudly at
	 * startup if a class in the list lacks {@link EventType}, and the upstream
	 * {@code EventTypeAnnotationTest.EXPECTED_EVENT_RECORDS} list must be updated in
	 * the same change. {@code DegradedEvent} is deliberately excluded — it is the
	 * fallback wrapper for failed upcasts and must never be registered.
	 *
	 * @see EventType
	 * @see DomainEvent
	 */
	public static final List<Class<? extends DomainEvent>> CORE_PRODUCTION_EVENT_CLASSES =
			List.of(
					CommandIssuedEvent.class,
					CommandDispatchedEvent.class,
					CommandResultEvent.class,
					CommandConfirmationTimedOutEvent.class,
					StateReportedEvent.class,
					StateReportRejectedEvent.class,
					StateChangedEvent.class,
					StateConfirmedEvent.class,
					DeviceDiscoveredEvent.class,
					DeviceAdoptedEvent.class,
					DeviceRemovedEvent.class,
					AvailabilityChangedEvent.class,
					AutomationTriggeredEvent.class,
					AutomationCompletedEvent.class,
					PresenceSignalEvent.class,
					PresenceChangedEvent.class,
					SystemStartedEvent.class,
					SystemStoppedEvent.class,
					StoragePressureChangedEvent.class,
					ConfigChangedEvent.class,
					ConfigErrorEvent.class,
					ConfigValidationCompletedEvent.class,
					ConfigSectionReloadedEvent.class,
					TelemetrySummaryEvent.class,
					// M7.1 automation run-initiation slice (AMD-92 rows 3, 11-16, 19)
					AutomationInvokedEvent.class,
					AutomationSlugRedirectEvent.class,
					TriggerDurationStartedEvent.class,
					TriggerDurationCancelledEvent.class,
					TriggerDurationExpiredEvent.class,
					TriggerDurationStateValidatedEvent.class,
					TriggerDurationLimitExceededEvent.class,
					AutomationCapabilityMismatchEvent.class,
					// M7.2 run-lifecycle slice (AMD-92 rows 7, 8, 10, 17, 18;
					// row 2 AutomationCompletedEvent reshaped in place above)
					AutomationRunSkippedEvent.class,
					AutomationRunCancelledEvent.class,
					AutomationDisabledEvent.class,
					CascadeDepthExceededEvent.class,
					CascadeLoopDetectedEvent.class);
}

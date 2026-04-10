/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Canonical registry of event type strings used in the HomeSynapse event taxonomy.
 *
 * <p>This class defines all core event type constants referenced by {@link EventEnvelope#eventType()}.
 * Each constant uses UPPER_SNAKE_CASE names with lower_snake_case string values for consistency
 * with the taxonomy defined in Doc 01 §4.3.
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
 * @see EventEnvelope
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
}

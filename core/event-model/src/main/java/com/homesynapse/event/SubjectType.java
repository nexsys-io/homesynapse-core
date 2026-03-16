/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Enumerates the subject type categories for event envelope subject references.
 *
 * <p>Every event in HomeSynapse has a subject — the domain object the event is "about."
 * The subject type category classifies the subject for bus-side subscription filtering
 * (Doc 01 §3.4) and for resolving typed identity references at append time.</p>
 *
 * <p>The Event Bus uses subject type to evaluate {@code subjectTypeFilter} on
 * {@code SubscriptionFilter} — subscribers can restrict delivery to events whose
 * subject belongs to a specific category without inspecting event payloads.</p>
 *
 * @see SubjectRef
 */
public enum SubjectType {

    /**
     * The subject is a logical entity (a device capability instance).
     * Most common subject type — state events, command events, and availability events
     * are entity-scoped.
     */
    ENTITY,

    /**
     * The subject is a physical device.
     * Used for device lifecycle events: {@code device_discovered}, {@code device_adopted},
     * {@code device_removed}, {@code device_metadata_changed}.
     */
    DEVICE,

    /**
     * The subject is an integration adapter instance.
     * Used for integration lifecycle events: {@code integration_started},
     * {@code integration_stopped}, {@code integration_health_changed}.
     */
    INTEGRATION,

    /**
     * The subject is an automation rule definition.
     * Used for automation lifecycle events: {@code automation_triggered},
     * {@code automation_completed}.
     */
    AUTOMATION,

    /**
     * The subject is the HomeSynapse system instance.
     * Used for system lifecycle events: {@code system_started}, {@code system_stopped},
     * {@code migration_applied}, {@code snapshot_created}, {@code config_changed}.
     */
    SYSTEM,

    /**
     * The subject is a person (occupant or user).
     * Used for presence events: {@code presence_signal}, {@code presence_changed}.
     * Privacy-sensitive — falls under the {@code presence} event category.
     */
    PERSON
}

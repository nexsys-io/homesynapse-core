/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Classifies the UX role of a device {@link Entity} — what the entity means to the
 * user — orthogonal to the data-shape-and-write-semantics classification carried by
 * {@link EntityType}.
 *
 * <p>A single physical device commonly exposes entities at more than one role. A
 * kitchen smart plug, for example, exposes the controllable outlet ({@link #PRIMARY}),
 * voltage/RSSI/LQI health sensors ({@link #DIAGNOSTIC}), and a power-on-behavior switch
 * ({@link #CONFIG}). The role drives UI grouping, automation-selector scope, and
 * voice-assistant exposure; it does not change what the entity does.</p>
 *
 * <p>There are exactly three values. There is deliberately no {@code SYSTEM} value:
 * Home Assistant scrubbed {@code SYSTEM} from its {@code EntityCategory} enum before its
 * 2021.11 release because it conflated platform-internal concerns with
 * integration-visible classification. Visibility semantics live on
 * {@link Entity#enabled()} (and a future {@code visibleByDefault} flag), not on this
 * enum.</p>
 *
 * <p>Each {@link EntityType} declares which roles are legal for it via
 * {@link EntityType#allows(EntityRole)} / {@link EntityType#legalRoles()}; an illegal
 * {@code (entityType, entityRole)} pair is rejected at construction of {@link Entity}
 * and {@link ProposedEntity}, so it is unrepresentable. By convention, coordinator-state
 * entities (USB-stick status, connected-device counts) default to {@link #DIAGNOSTIC}
 * (Decision 9); adapter authors for standalone-gateway products may instead declare them
 * {@link #PRIMARY} where the matrix permits it.</p>
 *
 * <p>The role is mutable after adoption: reclassification flows through the
 * {@code entity_profile_changed} event and preserves the {@link com.homesynapse.platform.identity.EntityId}
 * (INV-CS-02) — an entity is never re-created to change its role.</p>
 *
 * <p>Defined in Doc 02 §3.10 and AMD-44 §2.5.</p>
 *
 * @see EntityType
 * @see Entity
 * @since 1.0
 */
public enum EntityRole {

    /** The entity's value is what the user installed the device to observe or control. */
    PRIMARY,

    /** The entity reports on the device's own health or infrastructure status. */
    DIAGNOSTIC,

    /** The entity controls a device configuration parameter (e.g., polling interval, power-on behavior). */
    CONFIG
}

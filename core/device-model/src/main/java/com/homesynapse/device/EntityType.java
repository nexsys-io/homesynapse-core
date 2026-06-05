/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies the functional role of a device entity within HomeSynapse.
 *
 * <p>Each entity belongs to exactly one entity type, which determines the required
 * and optional capabilities the entity must or may support. Entity types are assigned
 * during device adoption via the discovery pipeline.</p>
 *
 * <p>This enum defines the six MVP entity types. Additional entity types
 * (LOCK, CLIMATE, COVER, MEDIA_PLAYER, CAMERA, THERMOSTAT, etc.) are reserved
 * for post-MVP development.</p>
 *
 * <p>Each constant additionally declares the set of {@link EntityRole}s legal for
 * entities of that type — the AMD-44 §2.5.1 legality matrix encoded directly on the
 * enum so that adding a new entity type forces its role declaration at compile time.
 * Query it through {@link #allows(EntityRole)} and {@link #legalRoles()}; the
 * constraint is hard — {@link Entity} and {@link ProposedEntity} reject an illegal
 * {@code (entityType, entityRole)} pair at construction.</p>
 *
 * <p>Defined in Doc 02 §3.10; legality matrix per AMD-44 §2.5.1.</p>
 *
 * @see Entity
 * @see Capability
 * @see DiscoveryPipeline
 * @see EntityRole
 * @since 1.0
 */
public enum EntityType {

    /**
     * A controllable light source.
     *
     * <p>Required capabilities: {@link OnOff}. Optional capabilities:
     * {@link Brightness}, {@link ColorTemperature}, and post-MVP color
     * capabilities (color_hs, color_xy).</p>
     *
     * <p>Legal roles: {@link EntityRole#PRIMARY}, {@link EntityRole#DIAGNOSTIC} —
     * status-indicator LEDs (pairing/link/signal LEDs) are real diagnostic LIGHT
     * entities (Decision 3).</p>
     */
    LIGHT(EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC)),

    /**
     * A binary on/off switch (e.g., wall switch, smart relay).
     *
     * <p>Required capabilities: {@link OnOff}.</p>
     *
     * <p>Legal roles: {@link EntityRole#PRIMARY}, {@link EntityRole#DIAGNOSTIC},
     * {@link EntityRole#CONFIG} — switches back power-on-behavior and other
     * configuration toggles.</p>
     */
    SWITCH(EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC, EntityRole.CONFIG)),

    /**
     * A switchable power outlet with optional energy monitoring.
     *
     * <p>Required capabilities: {@link OnOff}. Optional capabilities:
     * {@link PowerMeasurement}, {@link EnergyMeter}.</p>
     *
     * <p>Legal roles: {@link EntityRole#PRIMARY} only.</p>
     */
    PLUG(EnumSet.of(EntityRole.PRIMARY)),

    /**
     * A sensor that reports continuous or discrete measurements
     * (e.g., temperature, humidity, illuminance, power).
     *
     * <p>Required capabilities: at least one measurement capability
     * ({@link TemperatureMeasurement}, {@link HumidityMeasurement},
     * {@link IlluminanceMeasurement}, {@link PowerMeasurement}).
     * Optional capabilities: {@link Battery}, {@link DeviceHealth}.</p>
     *
     * <p>Legal roles: {@link EntityRole#PRIMARY}, {@link EntityRole#DIAGNOSTIC} —
     * voltage/RSSI/LQI and coordinator-state sensors are DIAGNOSTIC (Decision 9).</p>
     */
    SENSOR(EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC)),

    /**
     * A sensor that reports a binary state (e.g., contact, motion, occupancy).
     *
     * <p>Required capabilities: at least one of {@link BinaryState},
     * {@link Contact}, {@link Motion}, {@link Occupancy}.
     * Optional capabilities: {@link Battery}, {@link DeviceHealth}.</p>
     *
     * <p>Legal roles: {@link EntityRole#PRIMARY}, {@link EntityRole#DIAGNOSTIC}.</p>
     */
    BINARY_SENSOR(EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC)),

    /**
     * An energy metering device that tracks cumulative consumption or generation.
     *
     * <p>Required capabilities: {@link EnergyMeter}. Optional capabilities:
     * {@link PowerMeter}, {@link Battery}, {@link DeviceHealth}.</p>
     *
     * <p>Legal roles: {@link EntityRole#PRIMARY}, {@link EntityRole#DIAGNOSTIC}.</p>
     */
    ENERGY_METER(EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC));

    private final Set<EntityRole> legalRoles;

    EntityType(Set<EntityRole> legalRoles) {
        this.legalRoles = Set.copyOf(legalRoles);   // immutable snapshot — the mutable EnumSet is never retained
    }

    /**
     * Whether {@code role} is a legal classification for entities of this type.
     * Enforcement is hard — adoption and reclassification reject illegal pairs;
     * there is no soft-fail or override (AMD-44 §2.5.1).
     *
     * @param role the role to test, never {@code null}
     * @return {@code true} if {@code role} is legal for this entity type
     * @throws NullPointerException if {@code role} is {@code null}
     */
    public boolean allows(EntityRole role) {
        Objects.requireNonNull(role, "role must not be null");
        return legalRoles.contains(role);
    }

    /**
     * The immutable set of roles legal for this entity type.
     *
     * @return an unmodifiable {@link Set} of legal {@link EntityRole}s
     */
    public Set<EntityRole> legalRoles() {
        return legalRoles;
    }
}

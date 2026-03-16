/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

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
 * <p>Defined in Doc 02 §3.10.</p>
 *
 * @see Entity
 * @see Capability
 * @see DiscoveryPipeline
 * @since 1.0
 */
public enum EntityType {

    /**
     * A controllable light source.
     *
     * <p>Required capabilities: {@link OnOff}. Optional capabilities:
     * {@link Brightness}, {@link ColorTemperature}, and post-MVP color
     * capabilities (color_hs, color_xy).</p>
     */
    LIGHT,

    /**
     * A binary on/off switch (e.g., wall switch, smart relay).
     *
     * <p>Required capabilities: {@link OnOff}.</p>
     */
    SWITCH,

    /**
     * A switchable power outlet with optional energy monitoring.
     *
     * <p>Required capabilities: {@link OnOff}. Optional capabilities:
     * {@link PowerMeasurement}, {@link EnergyMeter}.</p>
     */
    PLUG,

    /**
     * A sensor that reports continuous or discrete measurements
     * (e.g., temperature, humidity, illuminance, power).
     *
     * <p>Required capabilities: at least one measurement capability
     * ({@link TemperatureMeasurement}, {@link HumidityMeasurement},
     * {@link IlluminanceMeasurement}, {@link PowerMeasurement}).
     * Optional capabilities: {@link Battery}, {@link DeviceHealth}.</p>
     */
    SENSOR,

    /**
     * A sensor that reports a binary state (e.g., contact, motion, occupancy).
     *
     * <p>Required capabilities: at least one of {@link BinaryState},
     * {@link Contact}, {@link Motion}, {@link Occupancy}.
     * Optional capabilities: {@link Battery}, {@link DeviceHealth}.</p>
     */
    BINARY_SENSOR,

    /**
     * An energy metering device that tracks cumulative consumption or generation.
     *
     * <p>Required capabilities: {@link EnergyMeter}. Optional capabilities:
     * {@link PowerMeter}, {@link Battery}, {@link DeviceHealth}.</p>
     */
    ENERGY_METER
}

package com.homesynapse.integration.zigbee;

/**
 * IAS (Intruder Alarm Systems) Zone type identifiers that determine capability mapping.
 *
 * <p>The IAS Zone cluster (0x0500) uses a ZoneType attribute to declare what kind of
 * security sensor the device is. The adapter maps each zone type to a HomeSynapse
 * capability name for device adoption and state reporting.
 *
 * <p>Doc 08 §3.12 IAS Zone enrollment.
 *
 * <p>Thread-safe: enum.
 *
 * @see ClusterHandler
 */
public enum ZoneType {

    /** Motion sensor. ZCL Zone Type 0x000D. */
    MOTION(0x000D, "motion"),

    /** Contact sensor (door/window). ZCL Zone Type 0x0015. */
    CONTACT(0x0015, "contact"),

    /** Water leak sensor. ZCL Zone Type 0x002A. */
    WATER_LEAK(0x002A, "water_leak"),

    /** Smoke detector. ZCL Zone Type 0x0028. */
    SMOKE(0x0028, "smoke"),

    /** Vibration sensor. ZCL Zone Type 0x002D. */
    VIBRATION(0x002D, "vibration");

    private final int zclId;
    private final String capabilityId;

    ZoneType(int zclId, String capabilityId) {
        this.zclId = zclId;
        this.capabilityId = capabilityId;
    }

    /**
     * Returns the ZCL Zone Type attribute value.
     *
     * @return the ZCL zone type identifier
     */
    public int zclId() {
        return zclId;
    }

    /**
     * Returns the HomeSynapse capability name this zone type maps to.
     *
     * @return the capability identifier string, never {@code null}
     */
    public String capabilityId() {
        return capabilityId;
    }
}

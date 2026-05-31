/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device.test;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.value.AttributeType;
import com.homesynapse.device.Battery;
import com.homesynapse.device.BinaryState;
import com.homesynapse.device.Brightness;
import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.ColorTemperature;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.Contact;
import com.homesynapse.device.CustomCapability;
import com.homesynapse.device.DeviceHealth;
import com.homesynapse.device.EnergyMeter;
import com.homesynapse.device.HumidityMeasurement;
import com.homesynapse.device.IlluminanceMeasurement;
import com.homesynapse.device.Motion;
import com.homesynapse.device.Occupancy;
import com.homesynapse.device.OnOff;
import com.homesynapse.device.Permission;
import com.homesynapse.device.PowerMeasurement;
import com.homesynapse.device.PowerMeter;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.device.TemperatureMeasurement;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static factory methods for creating {@link Capability} and {@link CapabilityInstance}
 * instances with realistic defaults in tests.
 *
 * <p>Provides one factory method for each of the 16 capability types in the sealed
 * hierarchy (15 standard records + {@link CustomCapability}), plus 5
 * {@link CapabilityInstance} convenience methods. Each capability is constructed with
 * attribute schemas, command definitions, and confirmation policies that match the
 * behavioral contracts documented in Doc 02 §3.</p>
 *
 * <p>The 15 standard capability factories <strong>delegate to the production
 * {@link StandardCapabilities} catalogue</strong> (M4.0b-3 / DP-K): the construction logic
 * was lifted into device-model {@code main} so the State Projection's typed change-detection
 * comparator (AMD-51) can reconstruct inbound values against the standard schemas, and this
 * fixture now defers to that single source of truth to avoid duplication. Only
 * {@link #customCapability()} (a vendor-namespace fixture not part of the standard set) is
 * still constructed here.</p>
 *
 * <p>This class is a test fixture — it lives in the {@code testFixtures} source set
 * and is consumed by downstream modules via
 * {@code testFixtures(project(":core:device-model"))}.</p>
 *
 * @see Capability
 * @see CapabilityInstance
 * @see StandardCapabilities
 * @see TestDeviceFactory
 * @see TestEntityFactory
 */
public final class TestCapabilityFactory {

    /** Confirmation policy for read-only capabilities (sensors). */
    private static final ConfirmationPolicy DISABLED_POLICY = new ConfirmationPolicy(
            ConfirmationMode.DISABLED, List.of(), null, 0L);

    private TestCapabilityFactory() {
        // Utility class — no instantiation.
    }

    // ══════════════════════════════════════════════════════════════════
    // SECTION 1: Standard Capability Factory Methods (15 records)
    //
    // All fifteen delegate to the production StandardCapabilities catalogue
    // (M4.0b-3 / DP-K) — the construction logic lives there now.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a default {@link OnOff} capability.
     *
     * @return a valid OnOff capability with standard schema
     */
    public static OnOff onOff() {
        return StandardCapabilities.onOff();
    }

    /**
     * Creates a default {@link Brightness} capability.
     *
     * @return a valid Brightness capability with standard schema
     */
    public static Brightness brightness() {
        return StandardCapabilities.brightness();
    }

    /**
     * Creates a default {@link ColorTemperature} capability.
     *
     * @return a valid ColorTemperature capability with standard schema
     */
    public static ColorTemperature colorTemperature() {
        return StandardCapabilities.colorTemperature();
    }

    /**
     * Creates a default {@link TemperatureMeasurement} capability.
     *
     * @return a valid TemperatureMeasurement capability
     */
    public static TemperatureMeasurement temperatureMeasurement() {
        return StandardCapabilities.temperatureMeasurement();
    }

    /**
     * Creates a default {@link HumidityMeasurement} capability.
     *
     * @return a valid HumidityMeasurement capability
     */
    public static HumidityMeasurement humidityMeasurement() {
        return StandardCapabilities.humidityMeasurement();
    }

    /**
     * Creates a default {@link IlluminanceMeasurement} capability.
     *
     * @return a valid IlluminanceMeasurement capability
     */
    public static IlluminanceMeasurement illuminanceMeasurement() {
        return StandardCapabilities.illuminanceMeasurement();
    }

    /**
     * Creates a default {@link PowerMeasurement} capability.
     *
     * @return a valid PowerMeasurement capability
     */
    public static PowerMeasurement powerMeasurement() {
        return StandardCapabilities.powerMeasurement();
    }

    /**
     * Creates a default {@link BinaryState} capability.
     *
     * @return a valid BinaryState capability
     */
    public static BinaryState binaryState() {
        return StandardCapabilities.binaryState();
    }

    /**
     * Creates a default {@link Contact} capability.
     *
     * @return a valid Contact capability
     */
    public static Contact contact() {
        return StandardCapabilities.contact();
    }

    /**
     * Creates a default {@link Motion} capability.
     *
     * @return a valid Motion capability
     */
    public static Motion motion() {
        return StandardCapabilities.motion();
    }

    /**
     * Creates a default {@link Occupancy} capability.
     *
     * @return a valid Occupancy capability
     */
    public static Occupancy occupancy() {
        return StandardCapabilities.occupancy();
    }

    /**
     * Creates a default {@link Battery} capability.
     *
     * @return a valid Battery capability
     */
    public static Battery battery() {
        return StandardCapabilities.battery();
    }

    /**
     * Creates a default {@link DeviceHealth} capability.
     *
     * @return a valid DeviceHealth capability
     */
    public static DeviceHealth deviceHealth() {
        return StandardCapabilities.deviceHealth();
    }

    /**
     * Creates a default {@link EnergyMeter} capability.
     *
     * @return a valid EnergyMeter capability
     */
    public static EnergyMeter energyMeter() {
        return StandardCapabilities.energyMeter();
    }

    /**
     * Creates a default {@link PowerMeter} capability.
     *
     * @return a valid PowerMeter capability
     */
    public static PowerMeter powerMeter() {
        return StandardCapabilities.powerMeter();
    }

    // ══════════════════════════════════════════════════════════════════
    // SECTION 2: CustomCapability Factory (test-only, not in the standard set)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a minimal {@link CustomCapability} with a non-core namespace.
     *
     * <p>Custom capabilities must have a namespace other than "core" per
     * the constructor validation in CustomCapability.</p>
     *
     * @return a valid CustomCapability with a single boolean attribute
     */
    public static CustomCapability customCapability() {
        Map<String, AttributeSchema> attrs = Map.of(
                "custom_flag", new AttributeSchema(
                        "custom_flag", AttributeType.BOOLEAN,
                        null, null, null, null, null, null,
                        Set.of(Permission.READ, Permission.NOTIFY), false, true));

        return new CustomCapability(
                "vendor_custom", 1, "vendor",
                attrs, Map.of(), DISABLED_POLICY);
    }

    // ══════════════════════════════════════════════════════════════════
    // SECTION 3: CapabilityInstance Factory Methods
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a {@link CapabilityInstance} from any {@link Capability}.
     *
     * <p>Extracts all fields from the capability and sets {@code featureMap} to 0
     * (all features available). This is the generic converter — prefer the
     * type-specific methods for common capabilities.</p>
     *
     * @param capability the capability to create an instance from
     * @return a CapabilityInstance with featureMap 0
     */
    public static CapabilityInstance instanceOf(Capability capability) {
        return new CapabilityInstance(
                capability.capabilityId(),
                capability.version(),
                capability.namespace(),
                0,
                capability.attributeSchemas(),
                capability.commandDefinitions(),
                capability.confirmationPolicy());
    }

    /**
     * Creates an OnOff {@link CapabilityInstance}.
     *
     * @return a CapabilityInstance backed by the default OnOff capability
     */
    public static CapabilityInstance onOffInstance() {
        return instanceOf(onOff());
    }

    /**
     * Creates a Brightness {@link CapabilityInstance}.
     *
     * @return a CapabilityInstance backed by the default Brightness capability
     */
    public static CapabilityInstance brightnessInstance() {
        return instanceOf(brightness());
    }

    /**
     * Creates a TemperatureMeasurement {@link CapabilityInstance}.
     *
     * @return a CapabilityInstance backed by the default TemperatureMeasurement
     */
    public static CapabilityInstance temperatureMeasurementInstance() {
        return instanceOf(temperatureMeasurement());
    }

    /**
     * Creates a Contact {@link CapabilityInstance}.
     *
     * @return a CapabilityInstance backed by the default Contact capability
     */
    public static CapabilityInstance contactInstance() {
        return instanceOf(contact());
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device.test;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.AttributeType;
import com.homesynapse.device.Battery;
import com.homesynapse.device.BinaryState;
import com.homesynapse.device.BooleanValue;
import com.homesynapse.device.Brightness;
import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.ColorTemperature;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.Contact;
import com.homesynapse.device.CustomCapability;
import com.homesynapse.device.DeviceHealth;
import com.homesynapse.device.EnergyMeter;
import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.ExpectedOutcome;
import com.homesynapse.device.HumidityMeasurement;
import com.homesynapse.device.IdempotencyClass;
import com.homesynapse.device.IlluminanceMeasurement;
import com.homesynapse.device.Motion;
import com.homesynapse.device.Occupancy;
import com.homesynapse.device.OnOff;
import com.homesynapse.device.ParameterSchema;
import com.homesynapse.device.Permission;
import com.homesynapse.device.PowerMeasurement;
import com.homesynapse.device.PowerMeter;
import com.homesynapse.device.TemperatureMeasurement;

import java.time.Duration;
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
 * <p>This class is a test fixture — it lives in the {@code testFixtures} source set
 * and is consumed by downstream modules via
 * {@code testFixtures(project(":core:device-model"))}.</p>
 *
 * @see Capability
 * @see CapabilityInstance
 * @see TestDeviceFactory
 * @see TestEntityFactory
 */
public final class TestCapabilityFactory {

    /** Standard namespace for built-in capabilities. */
    private static final String CORE_NAMESPACE = "core";

    /** Confirmation policy for read-only capabilities (sensors). */
    private static final ConfirmationPolicy DISABLED_POLICY = new ConfirmationPolicy(
            ConfirmationMode.DISABLED, List.of(), null, 0L);

    /** Default command timeout for actuator capabilities. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private TestCapabilityFactory() {
        // Utility class — no instantiation.
    }

    // ══════════════════════════════════════════════════════════════════
    // SECTION 1: Standard Capability Factory Methods (15 records)
    // ══════════════════════════════════════════════════════════════════

    // ── Actuator capabilities ────────────────────────────────────────

    /**
     * Creates a default {@link OnOff} capability.
     *
     * <p>Attribute: {@code on} (boolean, R/W/N). Commands: {@code turn_on},
     * {@code turn_off}, {@code toggle}. Confirmation: EXACT_MATCH on "on".</p>
     *
     * @return a valid OnOff capability with standard schema
     */
    public static OnOff onOff() {
        Map<String, AttributeSchema> attrs = Map.of(
                "on", booleanAttr("on", Set.of(
                        Permission.READ, Permission.WRITE, Permission.NOTIFY)));

        Map<String, CommandDefinition> cmds = Map.of(
                "turn_on", simpleCommand("turn_on",
                        List.of(new ExpectedOutcome("on",
                                new ExactMatch(new BooleanValue(true)), 5000L))),
                "turn_off", simpleCommand("turn_off",
                        List.of(new ExpectedOutcome("on",
                                new ExactMatch(new BooleanValue(false)), 5000L))),
                "toggle", simpleCommand("toggle", List.of()));

        ConfirmationPolicy policy = new ConfirmationPolicy(
                ConfirmationMode.EXACT_MATCH, List.of("on"), null, 5000L);

        return new OnOff("on_off", 1, CORE_NAMESPACE, attrs, cmds, policy);
    }

    /**
     * Creates a default {@link Brightness} capability.
     *
     * <p>Attribute: {@code brightness} (int, 0–100, R/W/N). Command:
     * {@code set_brightness}. Confirmation: TOLERANCE ±2.</p>
     *
     * @return a valid Brightness capability with standard schema
     */
    public static Brightness brightness() {
        Map<String, AttributeSchema> attrs = Map.of(
                "brightness", intAttr("brightness", 0, 100, null,
                        Set.of(Permission.READ, Permission.WRITE, Permission.NOTIFY)));

        ParameterSchema levelParam = new ParameterSchema(
                "level", AttributeType.INT, 0, 100, true, 0, null);
        Map<String, CommandDefinition> cmds = Map.of(
                "set_brightness", new CommandDefinition(
                        "set_brightness", List.of(levelParam), 0, List.of(),
                        DEFAULT_TIMEOUT, IdempotencyClass.IDEMPOTENT));

        ConfirmationPolicy policy = new ConfirmationPolicy(
                ConfirmationMode.TOLERANCE, List.of("brightness"), 2, 5000L);

        return new Brightness("brightness", 1, CORE_NAMESPACE, attrs, cmds, policy);
    }

    /**
     * Creates a default {@link ColorTemperature} capability.
     *
     * <p>Attribute: {@code color_temp_kelvin} (int, R/W/N). Command:
     * {@code set_color_temperature}. Confirmation: TOLERANCE ±50K.</p>
     *
     * @return a valid ColorTemperature capability with standard schema
     */
    public static ColorTemperature colorTemperature() {
        Map<String, AttributeSchema> attrs = Map.of(
                "color_temp_kelvin", intAttr("color_temp_kelvin", 2000, 6500, "K",
                        Set.of(Permission.READ, Permission.WRITE, Permission.NOTIFY)));

        ParameterSchema kelvinParam = new ParameterSchema(
                "kelvin", AttributeType.INT, 2000, 6500, true, 0, null);
        Map<String, CommandDefinition> cmds = Map.of(
                "set_color_temperature", new CommandDefinition(
                        "set_color_temperature", List.of(kelvinParam), 0, List.of(),
                        DEFAULT_TIMEOUT, IdempotencyClass.IDEMPOTENT));

        ConfirmationPolicy policy = new ConfirmationPolicy(
                ConfirmationMode.TOLERANCE, List.of("color_temp_kelvin"), 50, 5000L);

        return new ColorTemperature(
                "color_temperature", 1, CORE_NAMESPACE, attrs, cmds, policy);
    }

    // ── Measurement capabilities (read-only) ─────────────────────────

    /**
     * Creates a default {@link TemperatureMeasurement} capability.
     *
     * <p>Attribute: {@code temperature_c} (float, R/N, unit "°C").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return a valid TemperatureMeasurement capability
     */
    public static TemperatureMeasurement temperatureMeasurement() {
        Map<String, AttributeSchema> attrs = Map.of(
                "temperature_c", floatAttr("temperature_c", "°C",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new TemperatureMeasurement(
                "temperature_measurement", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    /**
     * Creates a default {@link HumidityMeasurement} capability.
     *
     * <p>Attribute: {@code humidity_pct} (float, 0–100, R/N, unit "%").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return a valid HumidityMeasurement capability
     */
    public static HumidityMeasurement humidityMeasurement() {
        Map<String, AttributeSchema> attrs = Map.of(
                "humidity_pct", floatAttr("humidity_pct", "%",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new HumidityMeasurement(
                "humidity_measurement", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    /**
     * Creates a default {@link IlluminanceMeasurement} capability.
     *
     * <p>Attribute: {@code illuminance_lux} (float, R/N, unit "lux").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return a valid IlluminanceMeasurement capability
     */
    public static IlluminanceMeasurement illuminanceMeasurement() {
        Map<String, AttributeSchema> attrs = Map.of(
                "illuminance_lux", floatAttr("illuminance_lux", "lux",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new IlluminanceMeasurement(
                "illuminance_measurement", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    /**
     * Creates a default {@link PowerMeasurement} capability.
     *
     * <p>Attribute: {@code power_w} (float, R/N, unit "W").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return a valid PowerMeasurement capability
     */
    public static PowerMeasurement powerMeasurement() {
        Map<String, AttributeSchema> attrs = Map.of(
                "power_w", floatAttr("power_w", "W",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new PowerMeasurement(
                "power_measurement", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    // ── Binary sensor capabilities (read-only) ──────────────────────

    /**
     * Creates a default {@link BinaryState} capability.
     *
     * <p>Attribute: {@code active} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return a valid BinaryState capability
     */
    public static BinaryState binaryState() {
        Map<String, AttributeSchema> attrs = Map.of(
                "active", booleanAttr("active",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new BinaryState(
                "binary_state", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    /**
     * Creates a default {@link Contact} capability.
     *
     * <p>Attribute: {@code open} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return a valid Contact capability
     */
    public static Contact contact() {
        Map<String, AttributeSchema> attrs = Map.of(
                "open", booleanAttr("open",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new Contact(
                "contact", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    /**
     * Creates a default {@link Motion} capability.
     *
     * <p>Attribute: {@code detected} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return a valid Motion capability
     */
    public static Motion motion() {
        Map<String, AttributeSchema> attrs = Map.of(
                "detected", booleanAttr("detected",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new Motion(
                "motion", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    /**
     * Creates a default {@link Occupancy} capability.
     *
     * <p>Attribute: {@code occupied} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return a valid Occupancy capability
     */
    public static Occupancy occupancy() {
        Map<String, AttributeSchema> attrs = Map.of(
                "occupied", booleanAttr("occupied",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new Occupancy(
                "occupancy", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    // ── Cross-cutting capabilities (read-only) ──────────────────────

    /**
     * Creates a default {@link Battery} capability.
     *
     * <p>Attributes: {@code battery_pct} (int, 0–100, R/N),
     * {@code battery_low} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return a valid Battery capability
     */
    public static Battery battery() {
        Map<String, AttributeSchema> attrs = Map.of(
                "battery_pct", intAttr("battery_pct", 0, 100, "%",
                        Set.of(Permission.READ, Permission.NOTIFY)),
                "battery_low", booleanAttr("battery_low",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new Battery(
                "battery", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    /**
     * Creates a default {@link DeviceHealth} capability.
     *
     * <p>Attributes: {@code rssi_dbm} (int, R/N, unit "dBm"),
     * {@code lqi} (int, 0–255, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return a valid DeviceHealth capability
     */
    public static DeviceHealth deviceHealth() {
        Map<String, AttributeSchema> attrs = Map.of(
                "rssi_dbm", intAttr("rssi_dbm", -128, 0, "dBm",
                        Set.of(Permission.READ, Permission.NOTIFY)),
                "lqi", intAttr("lqi", 0, 255, null,
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new DeviceHealth(
                "device_health", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    // ── Metering capabilities ───────────────────────────────────────

    /**
     * Creates a default {@link EnergyMeter} capability.
     *
     * <p>Attributes: {@code energy_wh} (float, R/N, unit "Wh"),
     * {@code direction} (enum, R/N), {@code cumulative} (boolean, R/N).
     * Command: {@code reset_meter}. Confirmation: EXACT_MATCH.</p>
     *
     * @return a valid EnergyMeter capability
     */
    public static EnergyMeter energyMeter() {
        Map<String, AttributeSchema> attrs = Map.of(
                "energy_wh", floatAttr("energy_wh", "Wh",
                        Set.of(Permission.READ, Permission.NOTIFY)),
                "direction", enumAttr("direction",
                        Set.of("IMPORT", "EXPORT", "BIDIRECTIONAL"),
                        Set.of(Permission.READ, Permission.NOTIFY)),
                "cumulative", booleanAttr("cumulative",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        Map<String, CommandDefinition> cmds = Map.of(
                "reset_meter", simpleCommand("reset_meter", List.of()));

        ConfirmationPolicy policy = new ConfirmationPolicy(
                ConfirmationMode.EXACT_MATCH, List.of("energy_wh"), null, 5000L);

        return new EnergyMeter(
                "energy_meter", 1, CORE_NAMESPACE, attrs, cmds, policy);
    }

    /**
     * Creates a default {@link PowerMeter} capability.
     *
     * <p>Attributes: {@code power_w} (float, R/N, unit "W"),
     * {@code voltage_v} (float, R/N, nullable, unit "V"),
     * {@code current_a} (float, R/N, nullable, unit "A").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return a valid PowerMeter capability
     */
    public static PowerMeter powerMeter() {
        Map<String, AttributeSchema> attrs = Map.of(
                "power_w", floatAttr("power_w", "W",
                        Set.of(Permission.READ, Permission.NOTIFY)),
                "voltage_v", nullableFloatAttr("voltage_v", "V",
                        Set.of(Permission.READ, Permission.NOTIFY)),
                "current_a", nullableFloatAttr("current_a", "A",
                        Set.of(Permission.READ, Permission.NOTIFY)));

        return new PowerMeter(
                "power_meter", 1, CORE_NAMESPACE,
                attrs, Map.of(), DISABLED_POLICY);
    }

    // ══════════════════════════════════════════════════════════════════
    // SECTION 2: CustomCapability Factory
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
                "custom_flag", booleanAttr("custom_flag",
                        Set.of(Permission.READ, Permission.NOTIFY)));

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

    // ══════════════════════════════════════════════════════════════════
    // Internal helper methods
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a boolean attribute schema with the given permissions.
     */
    private static AttributeSchema booleanAttr(String key, Set<Permission> perms) {
        return new AttributeSchema(
                key, AttributeType.BOOLEAN,
                null, null, null, null, null, null,
                perms, false, true);
    }

    /**
     * Creates an integer attribute schema with range constraints.
     */
    private static AttributeSchema intAttr(String key, Number min, Number max,
                                           String unit, Set<Permission> perms) {
        return new AttributeSchema(
                key, AttributeType.INT,
                min, max, null, null, unit, unit,
                perms, false, true);
    }

    /**
     * Creates a non-nullable float attribute schema.
     */
    private static AttributeSchema floatAttr(String key, String unit,
                                             Set<Permission> perms) {
        return new AttributeSchema(
                key, AttributeType.FLOAT,
                null, null, null, null, unit, unit,
                perms, false, true);
    }

    /**
     * Creates a nullable float attribute schema (e.g., voltage_v, current_a).
     */
    private static AttributeSchema nullableFloatAttr(String key, String unit,
                                                     Set<Permission> perms) {
        return new AttributeSchema(
                key, AttributeType.FLOAT,
                null, null, null, null, unit, unit,
                perms, true, true);
    }

    /**
     * Creates an enum attribute schema with valid values.
     */
    private static AttributeSchema enumAttr(String key, Set<String> validValues,
                                            Set<Permission> perms) {
        return new AttributeSchema(
                key, AttributeType.ENUM,
                null, null, null, validValues, null, null,
                perms, false, true);
    }

    /**
     * Creates a simple idempotent command with no parameters.
     */
    private static CommandDefinition simpleCommand(String commandType,
                                                   List<ExpectedOutcome> outcomes) {
        return new CommandDefinition(
                commandType, List.of(), 0, outcomes,
                DEFAULT_TIMEOUT, IdempotencyClass.IDEMPOTENT);
    }
}

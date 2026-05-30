/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Production factory for the standard (core-namespace) {@link Capability} set and its
 * aggregated attribute schemas (DP-K, AMD-51).
 *
 * <p>This is the compile-time-shaped catalogue of the fifteen standard capability records
 * (the {@link Capability} sealed hierarchy minus {@link CustomCapability}). It is the
 * single source of truth for the standard {@link AttributeSchema}s, and exists because the
 * State Projection's typed change-detection comparator (AMD-51) needs a schema to
 * reconstruct an inbound {@code state_reported} value to its declared {@link AttributeType}.
 * The construction logic was lifted from the {@code TestCapabilityFactory} test fixture,
 * which now delegates here to avoid duplication.</p>
 *
 * <p>The schema map produced by {@link #attributeSchemas()} is an <strong>immutable
 * compile-time snapshot</strong> — morally identical to {@code QuantityValue}'s conversion
 * catalogue, NOT a runtime registry read. The composition root builds an
 * {@code AttributeSchemaResolver} over it once and injects it into the derivation rule, so
 * the rule reads injected immutable config rather than a mutable registry (AMD-50-INV-03
 * determinism preserved). This factory is also the intended seed for the future
 * {@code CapabilityRegistry} implementation.</p>
 *
 * <p>The catalogue is hand-rolled, deterministic, and free of I/O, clock, and locale
 * dependence. All methods are pure and may be called from any thread.</p>
 *
 * @see Capability
 * @see AttributeSchema
 * @since 1.0
 */
public final class StandardCapabilities {

    /** Standard namespace for built-in capabilities. */
    private static final String CORE_NAMESPACE = "core";

    /** Confirmation policy for read-only capabilities (sensors). */
    private static final ConfirmationPolicy DISABLED_POLICY = new ConfirmationPolicy(
            ConfirmationMode.DISABLED, List.of(), null, 0L);

    /** Default command timeout for actuator capabilities. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private StandardCapabilities() {
        // Utility class — no instantiation.
    }

    // ══════════════════════════════════════════════════════════════════
    // Catalogue accessors
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns all fifteen standard capabilities with their realistic default schemas.
     *
     * @return an immutable list of the standard capabilities, never {@code null}
     */
    public static List<Capability> all() {
        return List.of(
                onOff(),
                brightness(),
                colorTemperature(),
                temperatureMeasurement(),
                humidityMeasurement(),
                illuminanceMeasurement(),
                powerMeasurement(),
                binaryState(),
                contact(),
                motion(),
                occupancy(),
                battery(),
                deviceHealth(),
                energyMeter(),
                powerMeter());
    }

    /**
     * Returns the aggregated attribute schemas across all standard capabilities, keyed by
     * {@code attributeKey}.
     *
     * <p>Where a key appears on more than one standard capability (e.g. {@code power_w} on
     * both {@link PowerMeasurement} and {@link PowerMeter}), the schemas must agree on
     * {@link AttributeSchema#type()}; the first occurrence wins. A key declared with two
     * <em>different</em> types is a catalogue contradiction and fails fast — the AMD-51
     * resolver assumes globally-consistent {@code attributeKey → AttributeType} mapping
     * because there is no entity→capability binding yet.</p>
     *
     * @return an immutable {@code attributeKey → schema} snapshot, never {@code null}
     * @throws IllegalStateException if two standard capabilities declare the same
     *         {@code attributeKey} with different {@link AttributeType}s
     */
    public static Map<String, AttributeSchema> attributeSchemas() {
        Map<String, AttributeSchema> aggregate = new LinkedHashMap<>();
        for (Capability capability : all()) {
            for (Map.Entry<String, AttributeSchema> entry
                    : capability.attributeSchemas().entrySet()) {
                AttributeSchema incoming = entry.getValue();
                AttributeSchema existing = aggregate.get(entry.getKey());
                if (existing != null && existing.type() != incoming.type()) {
                    throw new IllegalStateException(
                            "Standard attribute key '" + entry.getKey()
                                    + "' is declared with conflicting types "
                                    + existing.type() + " and " + incoming.type()
                                    + "; the attributeKey-keyed schema resolver requires a "
                                    + "globally-consistent attributeKey -> AttributeType mapping");
                }
                aggregate.putIfAbsent(entry.getKey(), incoming);
            }
        }
        return Map.copyOf(aggregate);
    }

    // ══════════════════════════════════════════════════════════════════
    // Standard Capability factory methods (15 records)
    // ══════════════════════════════════════════════════════════════════

    // ── Actuator capabilities ────────────────────────────────────────

    /**
     * Creates the standard {@link OnOff} capability.
     *
     * <p>Attribute: {@code on} (boolean, R/W/N). Commands: {@code turn_on},
     * {@code turn_off}, {@code toggle}. Confirmation: EXACT_MATCH on "on".</p>
     *
     * @return the standard OnOff capability
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
     * Creates the standard {@link Brightness} capability.
     *
     * <p>Attribute: {@code brightness} (int, 0–100, R/W/N). Command:
     * {@code set_brightness}. Confirmation: TOLERANCE ±2.</p>
     *
     * @return the standard Brightness capability
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
     * Creates the standard {@link ColorTemperature} capability.
     *
     * <p>Attribute: {@code color_temp_kelvin} (int, R/W/N). Command:
     * {@code set_color_temperature}. Confirmation: TOLERANCE ±50K.</p>
     *
     * @return the standard ColorTemperature capability
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
     * Creates the standard {@link TemperatureMeasurement} capability.
     *
     * <p>Attribute: {@code temperature_c} (float, R/N, unit "°C").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return the standard TemperatureMeasurement capability
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
     * Creates the standard {@link HumidityMeasurement} capability.
     *
     * <p>Attribute: {@code humidity_pct} (float, 0–100, R/N, unit "%").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return the standard HumidityMeasurement capability
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
     * Creates the standard {@link IlluminanceMeasurement} capability.
     *
     * <p>Attribute: {@code illuminance_lux} (float, R/N, unit "lux").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return the standard IlluminanceMeasurement capability
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
     * Creates the standard {@link PowerMeasurement} capability.
     *
     * <p>Attribute: {@code power_w} (float, R/N, unit "W").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return the standard PowerMeasurement capability
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
     * Creates the standard {@link BinaryState} capability.
     *
     * <p>Attribute: {@code active} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return the standard BinaryState capability
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
     * Creates the standard {@link Contact} capability.
     *
     * <p>Attribute: {@code open} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return the standard Contact capability
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
     * Creates the standard {@link Motion} capability.
     *
     * <p>Attribute: {@code detected} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return the standard Motion capability
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
     * Creates the standard {@link Occupancy} capability.
     *
     * <p>Attribute: {@code occupied} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return the standard Occupancy capability
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
     * Creates the standard {@link Battery} capability.
     *
     * <p>Attributes: {@code battery_pct} (int, 0–100, R/N),
     * {@code battery_low} (boolean, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return the standard Battery capability
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
     * Creates the standard {@link DeviceHealth} capability.
     *
     * <p>Attributes: {@code rssi_dbm} (int, R/N, unit "dBm"),
     * {@code lqi} (int, 0–255, R/N). No commands.
     * Confirmation: DISABLED.</p>
     *
     * @return the standard DeviceHealth capability
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
     * Creates the standard {@link EnergyMeter} capability.
     *
     * <p>Attributes: {@code energy_wh} (float, R/N, unit "Wh"),
     * {@code direction} (enum, R/N), {@code cumulative} (boolean, R/N).
     * Command: {@code reset_meter}. Confirmation: EXACT_MATCH.</p>
     *
     * @return the standard EnergyMeter capability
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
     * Creates the standard {@link PowerMeter} capability.
     *
     * <p>Attributes: {@code power_w} (float, R/N, unit "W"),
     * {@code voltage_v} (float, R/N, nullable, unit "V"),
     * {@code current_a} (float, R/N, nullable, unit "A").
     * No commands. Confirmation: DISABLED.</p>
     *
     * @return the standard PowerMeter capability
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
    // Internal schema/command helpers
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

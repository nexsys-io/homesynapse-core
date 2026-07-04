/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * The named standard {@link ValueConverter} factories the profile data channel may
 * reference (§D: device repairs live in NAMED code structures — profile JSON
 * carries a converter <em>name</em>, never an expression; no eval-in-data,
 * INV-SA-01 posture).
 *
 * <p>A repair that no named converter expresses is a named-codec addition routed
 * to Nick — never a profile schema escape hatch.
 *
 * <p>Thread-safe: stateless converters.
 */
final class StandardValueConverters {

    /**
     * Battery-voltage linear map floor: 20 (2.0 V in ZCL 100 mV units) → 0 %.
     * Chosen constant — Doc 08 provides no numeric; the 2.0–3.0 V window matches
     * the CR-coin-cell discharge band the corpus devices use.
     */
    static final int BATTERY_VOLTAGE_FLOOR_100MV = 20;
    /** Battery-voltage linear map ceiling: 30 (3.0 V) → 100 %. */
    static final int BATTERY_VOLTAGE_CEILING_100MV = 30;

    private StandardValueConverters() {
    }

    /**
     * Resolves a converter by its profile-data name.
     *
     * @param name the converter name, never {@code null}
     * @return the named converter
     * @throws ProfileLoadException if no converter carries the name — profile data
     *         never smuggles logic; unknown names fail the load
     */
    static ValueConverter byName(String name) {
        return switch (name) {
            case "raw" -> raw();
            case "divideBy10" -> divideBy10();
            case "divideBy100" -> divideBy100();
            case "booleanInvert" -> booleanInvert();
            case "batteryVoltageToPercent" -> batteryVoltageToPercent();
            default -> throw new ProfileLoadException(
                    "Unknown value converter '" + name + "'; profile data may "
                            + "reference only the named standard converters "
                            + "(raw, divideBy10, divideBy100, booleanInvert, "
                            + "batteryVoltageToPercent)");
        };
    }

    /** Returns the identity converter. */
    static ValueConverter raw() {
        return value -> value;
    }

    /** Returns the ÷10 numeric converter (e.g., Tuya VALUE in 0.1 units). */
    static ValueConverter divideBy10() {
        return value -> ((Number) value).doubleValue() / 10.0;
    }

    /** Returns the ÷100 numeric converter (e.g., 0.01 °C ZCL temperature). */
    static ValueConverter divideBy100() {
        return value -> ((Number) value).doubleValue() / 100.0;
    }

    /** Returns the boolean inverter (active-low protocol flags). */
    static ValueConverter booleanInvert() {
        return value -> !((Boolean) value);
    }

    /**
     * Returns the battery voltage (ZCL 100 mV units) → percentage converter:
     * linear over the {@value #BATTERY_VOLTAGE_FLOOR_100MV}–{@value
     * #BATTERY_VOLTAGE_CEILING_100MV} band, clamped to 0–100.
     */
    static ValueConverter batteryVoltageToPercent() {
        return value -> {
            int raw = ((Number) value).intValue();
            int percent = (raw - BATTERY_VOLTAGE_FLOOR_100MV) * 100
                    / (BATTERY_VOLTAGE_CEILING_100MV - BATTERY_VOLTAGE_FLOOR_100MV);
            return Math.max(0, Math.min(100, percent));
        };
    }
}

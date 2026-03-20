package com.homesynapse.integration.zigbee;

/**
 * Converts protocol-specific raw values to HomeSynapse canonical values.
 *
 * <p>Standard converters (divideBy10, divideBy100, raw, booleanInvert,
 * batteryVoltageToPercent) are provided as static factory methods in Phase 3.
 * The functional interface enables lambda implementations for per-profile
 * custom converters.
 *
 * <p>Doc 08 §3.8 {@link TuyaDatapointMapping} converter field.
 *
 * <p>Thread-safe: implementations must be stateless or thread-safe.
 *
 * @see TuyaDatapointMapping
 * @see XiaomiTagMapping
 */
@FunctionalInterface
public interface ValueConverter {

    /**
     * Converts a protocol-specific raw value to a HomeSynapse canonical value.
     *
     * @param rawValue the raw value from the protocol frame, never {@code null}
     * @return the converted canonical value, never {@code null}
     */
    Object convert(Object rawValue);
}

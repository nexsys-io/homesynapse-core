package com.homesynapse.integration.zigbee;

/**
 * Classifies Zigbee devices by the degree of adapter-specific handling they require.
 *
 * <p>Device category determines how the adapter processes frames from a device:
 * standard ZCL cluster handlers, profile-based overrides, mixed standard/custom codecs,
 * or fully proprietary protocol handling. Approximately 60% of consumer Zigbee devices
 * are {@link #STANDARD_ZCL} (generic cluster handlers, no profile needed); the remaining
 * ~40% require device profiles.
 *
 * <p>Doc 08 §3.6 category distribution table.
 *
 * <p>Thread-safe: enum.
 *
 * @see DeviceProfile
 */
public enum DeviceCategory {

    /**
     * Generic ZCL cluster handlers, no device profile needed.
     *
     * <p>Devices such as Sonoff SNZB series and Philips Hue bulbs that implement
     * standard ZCL clusters without manufacturer-specific quirks.
     */
    STANDARD_ZCL,

    /**
     * Standard ZCL with profile overrides for reporting intervals or attribute ranges.
     *
     * <p>Devices such as IKEA TRÅDFRI that use standard clusters but require
     * non-default reporting configuration or attribute range adjustments.
     */
    MINOR_QUIRKS,

    /**
     * Mix of standard ZCL clusters and manufacturer-specific codec for some capabilities.
     *
     * <p>Devices such as Xiaomi/Aqara sensors that report standard attributes via
     * ZCL clusters but embed additional sensor data in a custom TLV structure on
     * the Basic cluster (attribute 0xFF01) or cluster 0xFCC0.
     */
    MIXED_CUSTOM,

    /**
     * Fully proprietary protocol tunneled through a single ZCL cluster.
     *
     * <p>Devices such as Tuya TS0601 that tunnel a proprietary datapoint protocol
     * through cluster 0xEF00, bypassing standard ZCL cluster semantics entirely.
     */
    FULLY_CUSTOM
}

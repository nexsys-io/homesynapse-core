package com.homesynapse.integration.zigbee;

/**
 * Tuya datapoint type identifiers as defined in the Tuya MCU protocol specification.
 *
 * <p>Each Tuya DP (datapoint) in a cluster 0xEF00 frame carries a type byte that
 * determines how the value bytes are interpreted. The DP frame format is:
 * {@code [DPID 1B] [Type 1B] [Length 2B BE] [Value NB BE]}.
 *
 * <p>Doc 08 §3.8 Tuya DP frame parsing.
 *
 * <p>Thread-safe: enum.
 *
 * @see TuyaDatapointMapping
 * @see TuyaDpCodec
 */
public enum TuyaDpType {

    /** Raw byte sequence (0x00). Variable length, application-defined encoding. */
    RAW(0x00),

    /** Boolean value (0x01). Single byte: 0x00 = false, 0x01 = true. */
    BOOL(0x01),

    /** Unsigned 32-bit integer value (0x02). 4 bytes, big-endian. */
    VALUE(0x02),

    /** UTF-8 string value (0x03). Variable length. */
    STRING(0x03),

    /** Enumeration value (0x04). Single byte index. */
    ENUM(0x04),

    /** Bitmap value (0x05). Variable length bitfield. */
    BITMAP(0x05);

    private final int protocolId;

    TuyaDpType(int protocolId) {
        this.protocolId = protocolId;
    }

    /**
     * Returns the Tuya protocol type identifier byte.
     *
     * @return the protocol type ID (0x00 through 0x05)
     */
    public int protocolId() {
        return protocolId;
    }
}

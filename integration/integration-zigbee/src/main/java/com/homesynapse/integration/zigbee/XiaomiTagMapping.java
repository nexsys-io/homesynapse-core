/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * Maps a Xiaomi/Aqara TLV tag to a HomeSynapse attribute with value conversion.
 *
 * <p>Xiaomi and Aqara devices embed sensor data in a custom TLV (Tag-Length-Value)
 * structure reported on attribute 0xFF01 of the Basic cluster (manufacturer code 0x115F)
 * or attribute 0x00F7 of cluster 0xFCC0. Tags use standard ZCL type IDs for value
 * encoding, allowing the adapter's ZCL type codec to be reused for value decoding.
 *
 * <p>Documented tags: 0x01 (battery mV), 0x03 (device temp), 0x64 (model-dependent
 * primary measurement), 0x65 (humidity), 0x66 (pressure). Tag 0x64 interpretation
 * requires the device model identifier from the interview.
 *
 * <p>Doc 08 §3.9 — Xiaomi TLV tag-to-attribute mapping.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param tag the TLV tag byte (0x00–0xFF)
 * @param attributeKey the HomeSynapse attribute key, never {@code null}
 * @param zclDataType the ZCL data type ID used for value decoding
 * @param converter the value converter from raw TLV value to canonical form, never {@code null}
 * @see XiaomiTlvCodec
 * @see ValueConverter
 */
public record XiaomiTagMapping(int tag, String attributeKey, int zclDataType, ValueConverter converter) {

    /**
     * Creates a Xiaomi tag mapping with validation.
     *
     * @param tag must be 0–255
     * @param attributeKey the attribute key, never {@code null}
     * @param zclDataType the ZCL type ID
     * @param converter the value converter, never {@code null}
     */
    public XiaomiTagMapping {
        if (tag < 0 || tag > 255) {
            throw new IllegalArgumentException(
                    "tag must be 0–255, got " + tag);
        }
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(converter, "converter must not be null");
    }
}

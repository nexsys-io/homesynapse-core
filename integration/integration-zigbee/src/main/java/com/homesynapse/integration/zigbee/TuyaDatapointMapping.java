/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * Maps a Tuya datapoint ID and type to a HomeSynapse attribute with value conversion.
 *
 * <p>Each mapping translates a single Tuya DP (from cluster 0xEF00 frames) to a
 * HomeSynapse attribute. The converter transforms the raw DP value (big-endian uint32
 * for {@link TuyaDpType#VALUE}, single byte for {@link TuyaDpType#BOOL}/{@link TuyaDpType#ENUM})
 * to the HomeSynapse canonical form.
 *
 * <p>Doc 08 §3.8 — DP-to-capability mapping per Tuya device profile.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param dpId the Tuya datapoint ID (1–255)
 * @param attributeKey the HomeSynapse attribute key, never {@code null}
 * @param expectedType the expected Tuya DP type for this datapoint, never {@code null}
 * @param converter the value converter from raw DP value to canonical form, never {@code null}
 * @see TuyaDpType
 * @see TuyaDpCodec
 * @see ValueConverter
 */
public record TuyaDatapointMapping(int dpId, String attributeKey, TuyaDpType expectedType, ValueConverter converter) {

    /**
     * Creates a Tuya datapoint mapping with validation.
     *
     * @param dpId must be 1–255
     * @param attributeKey the attribute key, never {@code null}
     * @param expectedType the expected DP type, never {@code null}
     * @param converter the value converter, never {@code null}
     */
    public TuyaDatapointMapping {
        if (dpId < 1 || dpId > 255) {
            throw new IllegalArgumentException(
                    "dpId must be 1–255, got " + dpId);
        }
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(expectedType, "expectedType must not be null");
        Objects.requireNonNull(converter, "converter must not be null");
    }
}

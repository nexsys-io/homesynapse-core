/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * One normalized attribute observation with its raw protocol value retained —
 * the richer package-private companion to the frozen {@link AttributeReport}
 * DTO (which has no raw slot): the ingestion path consumes THIS to fill the
 * {@code state_reported} payload's {@code rawProtocolValue}/{@code rawProtocolUnit}
 * fields (Doc 02 §3.7 auditability — canonical + original protocol value).
 *
 * <p>Thread-safe: immutable record.
 *
 * @param attributeKey the canonical HomeSynapse attribute key (the in-tree
 *        capability vocabulary: {@code on}, {@code brightness},
 *        {@code color_temp_kelvin}, {@code occupied}, {@code battery_pct}, …)
 * @param value the canonical value, never {@code null}
 * @param unit the canonical unit; {@code null} for unitless attributes
 * @param rawProtocolValue the original protocol value as text; {@code null}
 *        when identical to the canonical value
 * @param rawProtocolUnit the original protocol unit; {@code null} when none
 * @see ZigbeeClusterHandler
 */
record NormalizedAttribute(
        String attributeKey,
        Object value,
        String unit,
        String rawProtocolValue,
        String rawProtocolUnit) {

    /**
     * Creates a normalized observation.
     *
     * @param attributeKey never {@code null}
     * @param value never {@code null}
     * @param unit {@code null} for unitless attributes
     * @param rawProtocolValue {@code null} when identical to the canonical value
     * @param rawProtocolUnit {@code null} when none
     */
    NormalizedAttribute {
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}

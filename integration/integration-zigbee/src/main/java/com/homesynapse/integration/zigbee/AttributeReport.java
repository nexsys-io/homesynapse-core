package com.homesynapse.integration.zigbee;

import java.time.Instant;
import java.util.Objects;

/**
 * Normalized attribute observation produced by cluster handlers and manufacturer codecs.
 *
 * <p>This is the adapter's internal DTO — it is converted to a {@code state_reported}
 * event via {@code EventPublisher} in Phase 3. The {@code value} field carries a
 * HomeSynapse canonical value (°C, %, lx, etc.), not the raw ZCL protocol value.
 * Value normalization (ZCL protocol units → HomeSynapse canonical units, e.g.,
 * 0.01°C → °C, 0.5% battery → percentage) is performed by the cluster handler
 * or manufacturer codec before constructing this record.
 *
 * <p>Doc 08 §4.4 {@code state_reported} payload, §3.5 value normalization.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param entityRef the HomeSynapse entity reference for event production, never {@code null}
 * @param attributeKey the HomeSynapse attribute key per device-model capability, never {@code null}
 * @param value the normalized canonical value typed per device-model AttributeValue, never {@code null}
 * @param eventTime the timestamp of the observation, never {@code null}
 * @see ClusterHandler
 * @see ManufacturerCodec
 */
public record AttributeReport(String entityRef, String attributeKey, Object value, Instant eventTime) {

    /**
     * Creates an attribute report with non-null validation.
     *
     * @param entityRef the entity reference, never {@code null}
     * @param attributeKey the attribute key, never {@code null}
     * @param value the canonical value, never {@code null}
     * @param eventTime the observation timestamp, never {@code null}
     */
    public AttributeReport {
        Objects.requireNonNull(entityRef, "entityRef must not be null");
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(eventTime, "eventTime must not be null");
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base of the per-cluster handlers (Doc 08 §3.5): each subclass owns ONE
 * cluster's value normalization into {@link NormalizedAttribute} rows carrying
 * the canonical value AND the raw protocol value.
 *
 * <p>Handlers are bound per device (IEEE + clock) so the frozen
 * {@link ClusterHandler} surface can produce complete {@link AttributeReport}
 * records (entityRef + eventTime) from its endpoint-only signature; the
 * ingestion path calls the richer package-private {@link #normalize} directly
 * (the M7.4b richer-method pattern) because the frozen DTO drops the raw slot.
 *
 * <p>{@link #buildCommand} is the M9.4 command-dispatch half and throws until
 * that milestone lands.
 *
 * <p>Thread-safe: stateless behavior over immutable per-device bindings.
 */
abstract class ZigbeeClusterHandler implements ClusterHandler {

    private final IEEEAddress device;
    private final Clock clock;

    /**
     * Binds the handler to its device.
     *
     * @param device the device this handler instance serves, never {@code null}
     * @param clock the time source for interface-path report timestamps, never {@code null}
     */
    ZigbeeClusterHandler(IEEEAddress device, Clock clock) {
        this.device = Objects.requireNonNull(device, "device");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Normalizes a cluster's reported attributes to canonical observations.
     *
     * @param endpoint the source application endpoint
     * @param clusterId the ZCL cluster id
     * @param attributes the reported attributes keyed by ZCL attribute id, never {@code null}
     * @return the normalized observations; empty if nothing maps to a capability
     */
    abstract List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes);

    @Override
    public final List<AttributeReport> handleAttributeReport(int endpoint,
            int clusterId, Map<Integer, Object> attributes) {
        List<NormalizedAttribute> normalized =
                normalize(endpoint, clusterId, attributes);
        List<AttributeReport> reports = new ArrayList<>(normalized.size());
        String entityRef = "zigbee:" + device.toHexString() + "/" + endpoint;
        for (NormalizedAttribute attribute : normalized) {
            reports.add(new AttributeReport(entityRef, attribute.attributeKey(),
                    attribute.value(), clock.instant()));
        }
        return reports;
    }

    @Override
    public final ZclFrame buildCommand(String commandType,
            Map<String, Object> parameters) {
        throw new UnsupportedOperationException(
                "ZCL command building is delivered in M9.4; the M9.3 ingestion "
                        + "layer implements the report path only");
    }
}

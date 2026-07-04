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
 * <p>{@link #buildCommand} is the command-dispatch half (M9.4a): the actuator
 * handlers (OnOff/LevelControl/ColorControl) override it; the ingestion-only
 * handlers inherit the default throw — an unsupported command cannot succeed
 * on retry, so the {@code UnsupportedOperationException} deliberately
 * classifies PERMANENT at the supervisor (Doc 05 §3.7, the M9.4 UOE arm).
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
    public ZclFrame buildCommand(String commandType,
            Map<String, Object> parameters) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " does not support command '" + commandType + "'");
    }

    /**
     * Coerces a decoded JSON parameter to an int — the command write path's
     * single numeric coercion point (decoded parameters arrive as
     * Integer/Long/Double depending on the JSON source).
     */
    static int intParameter(Map<String, Object> parameters, String name,
            int fallback) {
        Object value = parameters.get(name);
        return value instanceof Number number
                ? (int) Math.round(number.doubleValue()) : fallback;
    }

    /**
     * The transition-time field shared by Move-to-Level / Move-to-Color-Temperature
     * (ZCL8 §3.10.2.3.1: uint16, 0.1 s units): {@code 0x0000} (immediate) unless a
     * {@code transition_ms} parameter is present (rounded to deciseconds).
     */
    static int transitionDeciseconds(Map<String, Object> parameters) {
        Object value = parameters.get("transition_ms");
        return value instanceof Number number
                ? (int) Math.round(number.doubleValue() / 100.0) : 0;
    }
}

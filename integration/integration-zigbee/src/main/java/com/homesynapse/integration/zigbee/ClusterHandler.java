package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Map;

/**
 * Per-cluster translator between ZCL protocol operations and HomeSynapse capabilities.
 *
 * <p>Phase 3 provides implementations for each MVP cluster: OnOff, LevelControl,
 * ColorControl (color temperature), TemperatureMeasurement, RelativeHumidity,
 * IlluminanceMeasurement, OccupancySensing, IASZone, ElectricalMeasurement,
 * Metering, and PowerConfiguration (Doc 08 §3.5 table).
 *
 * <p>Handlers perform value normalization: ZCL protocol units → HomeSynapse canonical
 * units (e.g., 0.01°C → °C, 0.5% battery → percentage). Each handler is registered
 * for one or more ZCL cluster IDs and dispatched by the protocol layer based on the
 * incoming frame's cluster.
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Thread-safe: implementations must be stateless or thread-safe.
 *
 * @see AttributeReport
 * @see ZclFrame
 */
public interface ClusterHandler {

    /**
     * Translates ZCL attribute reports into normalized HomeSynapse attribute observations.
     *
     * <p>Called when the adapter receives an attribute report or read response for a
     * cluster this handler manages. The handler normalizes protocol values to canonical
     * units and produces one or more {@link AttributeReport} records for event publication.
     *
     * @param endpoint the source application endpoint (1–240)
     * @param clusterId the ZCL cluster ID
     * @param attributes the reported attributes keyed by ZCL attribute ID, never {@code null}
     * @return the normalized attribute reports, never {@code null}; may be empty if no
     *         attributes are relevant to HomeSynapse capabilities
     */
    List<AttributeReport> handleAttributeReport(int endpoint, int clusterId, Map<Integer, Object> attributes);

    /**
     * Constructs a ZCL frame from a HomeSynapse command definition.
     *
     * <p>Called when the adapter dispatches a command to a device. The handler translates
     * the HomeSynapse command type and parameters into a properly formatted ZCL frame
     * with the correct cluster-specific command ID and payload encoding.
     *
     * @param commandType the HomeSynapse command type identifier (e.g., {@code "set_brightness"})
     * @param parameters the command parameters keyed by parameter name, never {@code null}
     * @return the constructed ZCL frame ready for transmission, never {@code null}
     */
    ZclFrame buildCommand(String commandType, Map<String, Object> parameters);
}

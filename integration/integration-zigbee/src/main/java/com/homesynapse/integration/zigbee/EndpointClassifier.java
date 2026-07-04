/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.StandardCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Classifies one interviewed endpoint into a HomeSynapse entity type plus its
 * proposed capability set (Doc 08 §3.5 entity-type table + the cluster-based
 * fallback), using the in-tree {@link StandardCapabilities} definitions.
 *
 * <p>Unmapped endpoints classify to empty — the adoption slice skips them with
 * a log line; an unknown device type is never an adoption failure.
 *
 * <p>Thread-safe: stateless utility.
 */
final class EndpointClassifier {

    /** ZCL device type ids (Doc 08 §3.5). */
    private static final int DEVICE_TYPE_ON_OFF_LIGHT = 0x0100;
    private static final int DEVICE_TYPE_DIMMABLE_LIGHT = 0x0101;
    private static final int DEVICE_TYPE_CT_LIGHT = 0x010C;
    private static final int DEVICE_TYPE_EXTENDED_COLOR_LIGHT = 0x010D;
    private static final int DEVICE_TYPE_OCCUPANCY_SENSOR = 0x0107;
    private static final int DEVICE_TYPE_TEMPERATURE_SENSOR = 0x0302;
    private static final int DEVICE_TYPE_SMART_PLUG = 0x0051;

    /**
     * One classified endpoint.
     *
     * @param entityType the proposed entity type
     * @param capabilities the proposed capability instances
     */
    record Classification(EntityType entityType,
            List<CapabilityInstance> capabilities) {
    }

    private EndpointClassifier() {
    }

    /**
     * Classifies an endpoint.
     *
     * @param descriptor the endpoint's simple descriptor, never {@code null}
     * @return the classification, or empty when nothing maps
     */
    static Optional<Classification> classify(EndpointDescriptor descriptor) {
        List<Integer> in = descriptor.inputClusters();
        boolean hasOnOff = in.contains(OnOffHandler.CLUSTER_ID);
        boolean hasLevel = in.contains(LevelControlHandler.CLUSTER_ID);
        boolean hasColor = in.contains(ColorControlHandler.CLUSTER_ID);
        boolean hasOccupancy = in.contains(OccupancySensingHandler.CLUSTER_ID);
        boolean hasBattery = in.contains(PowerConfigurationHandler.CLUSTER_ID);
        boolean hasIasZone = in.contains(IasZoneHandler.CLUSTER_ID);

        return switch (descriptor.deviceTypeId()) {
            case DEVICE_TYPE_ON_OFF_LIGHT, DEVICE_TYPE_DIMMABLE_LIGHT,
                    DEVICE_TYPE_CT_LIGHT, DEVICE_TYPE_EXTENDED_COLOR_LIGHT ->
                    Optional.of(light(hasLevel, hasColor));
            case DEVICE_TYPE_OCCUPANCY_SENSOR -> Optional.of(binarySensor(
                    hasOccupancy, hasIasZone, hasBattery));
            case DEVICE_TYPE_TEMPERATURE_SENSOR -> Optional.of(new Classification(
                    EntityType.SENSOR, capabilities(
                            StandardCapabilities.temperatureMeasurement())));
            case DEVICE_TYPE_SMART_PLUG -> Optional.of(new Classification(
                    // Doc 08 §3.5 maps the smart plug to `switch`.
                    EntityType.SWITCH, capabilities(StandardCapabilities.onOff())));
            default -> fallback(hasOnOff, hasLevel, hasColor, hasOccupancy,
                    hasIasZone, hasBattery);
        };
    }

    private static Classification light(boolean hasLevel, boolean hasColor) {
        List<Capability> caps = new ArrayList<>();
        caps.add(StandardCapabilities.onOff());
        if (hasLevel) {
            caps.add(StandardCapabilities.brightness());
        }
        if (hasColor) {
            caps.add(StandardCapabilities.colorTemperature());
        }
        return new Classification(EntityType.LIGHT,
                capabilities(caps.toArray(Capability[]::new)));
    }

    private static Classification binarySensor(boolean hasOccupancy,
            boolean hasIasZone, boolean hasBattery) {
        List<Capability> caps = new ArrayList<>();
        // The measured dual-path rule: Occupancy is the active path when
        // present; IAS-only devices (the older SNZB-03 class) map to motion.
        if (hasOccupancy) {
            caps.add(StandardCapabilities.occupancy());
        } else if (hasIasZone) {
            caps.add(StandardCapabilities.motion());
        }
        if (hasBattery) {
            caps.add(StandardCapabilities.battery());
        }
        return new Classification(EntityType.BINARY_SENSOR,
                capabilities(caps.toArray(Capability[]::new)));
    }

    private static Optional<Classification> fallback(boolean hasOnOff,
            boolean hasLevel, boolean hasColor, boolean hasOccupancy,
            boolean hasIasZone, boolean hasBattery) {
        if (hasOnOff && hasLevel) {
            return Optional.of(light(true, hasColor));
        }
        if (hasOnOff) {
            return Optional.of(new Classification(EntityType.SWITCH,
                    capabilities(StandardCapabilities.onOff())));
        }
        if (hasOccupancy || hasIasZone) {
            return Optional.of(binarySensor(hasOccupancy, hasIasZone, hasBattery));
        }
        return Optional.empty();
    }

    private static List<CapabilityInstance> capabilities(Capability... caps) {
        List<CapabilityInstance> instances = new ArrayList<>(caps.length);
        for (Capability cap : caps) {
            instances.add(new CapabilityInstance(cap.capabilityId(), cap.version(),
                    cap.namespace(), 0, cap.attributeSchemas(),
                    cap.commandDefinitions(), cap.confirmationPolicy()));
        }
        return instances;
    }
}

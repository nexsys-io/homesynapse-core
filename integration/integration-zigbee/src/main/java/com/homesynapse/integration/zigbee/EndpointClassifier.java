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
 * <p>M9.7-W2 §3: classification optionally consults a wire-learned IAS zone
 * type (CONTACT ⇒ {@code contact}; unlearned ⇒ the motion fallback — DP-6),
 * the 0x0302 arm carries humidity/battery when their clusters are present
 * (DP-8), and a battery-only remainder classifies {@code SENSOR} + battery
 * (DP-7, the R2(B) ruling).
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
     * Classifies an endpoint with no zone-type knowledge — IAS selection takes
     * the motion fallback (byte-equivalent to the pre-M9.7-W2 single-arg path).
     *
     * @param descriptor the endpoint's simple descriptor, never {@code null}
     * @return the classification, or empty when nothing maps
     */
    static Optional<Classification> classify(EndpointDescriptor descriptor) {
        return classify(descriptor, null);
    }

    /**
     * Classifies an endpoint under a wire-learned IAS zone type (M9.7-W2 §4,
     * DP-6): CONTACT selects the {@code contact} capability on the IAS arms;
     * {@code null}/MOTION — and, deliberately unchanged this WU,
     * WATER_LEAK/SMOKE/VIBRATION — select {@code motion} (no such device in
     * the fleet; the limitation is recorded in MODULE_CONTEXT).
     *
     * @param descriptor the endpoint's simple descriptor, never {@code null}
     * @param learnedZoneType the wire-learned IAS zone type; {@code null} when
     *        none was learned (the motion fallback)
     * @return the classification, or empty when nothing maps
     */
    static Optional<Classification> classify(EndpointDescriptor descriptor,
            ZoneType learnedZoneType) {
        List<Integer> in = descriptor.inputClusters();
        boolean hasOnOff = in.contains(OnOffHandler.CLUSTER_ID);
        boolean hasLevel = in.contains(LevelControlHandler.CLUSTER_ID);
        boolean hasColor = in.contains(ColorControlHandler.CLUSTER_ID);
        boolean hasOccupancy = in.contains(OccupancySensingHandler.CLUSTER_ID);
        boolean hasBattery = in.contains(PowerConfigurationHandler.CLUSTER_ID);
        boolean hasHumidity = in.contains(RelativeHumidityHandler.CLUSTER_ID);
        boolean hasIasZone = in.contains(IasZoneHandler.CLUSTER_ID);
        boolean hasIdentify = in.contains(ZigbeeCommandHandler.IDENTIFY_CLUSTER_ID);

        Optional<Classification> classified = switch (descriptor.deviceTypeId()) {
            case DEVICE_TYPE_ON_OFF_LIGHT, DEVICE_TYPE_DIMMABLE_LIGHT,
                    DEVICE_TYPE_CT_LIGHT, DEVICE_TYPE_EXTENDED_COLOR_LIGHT ->
                    Optional.of(light(hasLevel, hasColor));
            case DEVICE_TYPE_OCCUPANCY_SENSOR -> Optional.of(binarySensor(
                    hasOccupancy, hasIasZone, hasBattery, learnedZoneType));
            case DEVICE_TYPE_TEMPERATURE_SENSOR -> Optional.of(
                    temperatureSensor(hasHumidity, hasBattery));
            case DEVICE_TYPE_SMART_PLUG -> Optional.of(new Classification(
                    // Doc 08 §3.5 maps the smart plug to `switch`.
                    EntityType.SWITCH, capabilities(StandardCapabilities.onOff())));
            default -> fallback(hasOnOff, hasLevel, hasColor, hasOccupancy,
                    hasIasZone, hasBattery, learnedZoneType);
        };
        // SD-3 (M9.4b §3.2): cluster 0x0003 present ⇒ the entity is
        // identify-issuable through the real Tier-1 validator. Post-processed so
        // EVERY classification arm (device-type table AND fallback) gains it;
        // 0x0003 alone never invents an entity (unmapped endpoints stay empty).
        return hasIdentify ? classified.map(EndpointClassifier::withIdentify) : classified;
    }

    private static Classification withIdentify(Classification classification) {
        List<CapabilityInstance> caps = new ArrayList<>(classification.capabilities());
        caps.addAll(capabilities(StandardCapabilities.identify()));
        return new Classification(classification.entityType(), caps);
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

    /**
     * The 0x0302 arm (M9.7-W2 DP-8): temperature always; humidity and battery
     * ride their clusters' presence (the SNZB-02P shape) — absent clusters
     * never invent capabilities.
     */
    private static Classification temperatureSensor(boolean hasHumidity,
            boolean hasBattery) {
        List<Capability> caps = new ArrayList<>();
        caps.add(StandardCapabilities.temperatureMeasurement());
        if (hasHumidity) {
            caps.add(StandardCapabilities.humidityMeasurement());
        }
        if (hasBattery) {
            caps.add(StandardCapabilities.battery());
        }
        return new Classification(EntityType.SENSOR,
                capabilities(caps.toArray(Capability[]::new)));
    }

    private static Classification binarySensor(boolean hasOccupancy,
            boolean hasIasZone, boolean hasBattery, ZoneType learnedZoneType) {
        List<Capability> caps = new ArrayList<>();
        // The measured dual-path rule: Occupancy is the active path when
        // present — it outranks IAS regardless of the learned zone type.
        if (hasOccupancy) {
            caps.add(StandardCapabilities.occupancy());
        } else if (hasIasZone) {
            caps.add(iasCapability(learnedZoneType));
        }
        if (hasBattery) {
            caps.add(StandardCapabilities.battery());
        }
        return new Classification(EntityType.BINARY_SENSOR,
                capabilities(caps.toArray(Capability[]::new)));
    }

    /**
     * The DP-6 IAS selection: only a wire-learned CONTACT re-selects (the
     * SNZB-04P shape); {@code null}/MOTION — and, this WU, the remaining zone
     * types (WATER_LEAK/SMOKE/VIBRATION: no fleet device; limitation recorded
     * in MODULE_CONTEXT) — stay the motion fallback, byte-for-byte today's
     * behavior. Adoption is a one-way door at V1, so a wrong selection here
     * is durable — never guess beyond the wire truth.
     */
    private static Capability iasCapability(ZoneType learnedZoneType) {
        return learnedZoneType == ZoneType.CONTACT
                ? StandardCapabilities.contact() : StandardCapabilities.motion();
    }

    private static Optional<Classification> fallback(boolean hasOnOff,
            boolean hasLevel, boolean hasColor, boolean hasOccupancy,
            boolean hasIasZone, boolean hasBattery, ZoneType learnedZoneType) {
        if (hasOnOff && hasLevel) {
            return Optional.of(light(true, hasColor));
        }
        if (hasOnOff) {
            return Optional.of(new Classification(EntityType.SWITCH,
                    capabilities(StandardCapabilities.onOff())));
        }
        if (hasOccupancy || hasIasZone) {
            return Optional.of(binarySensor(hasOccupancy, hasIasZone, hasBattery,
                    learnedZoneType));
        }
        // DP-7 (R2(B), the SNZB-01P ruling): a battery-only remainder adopts as
        // SENSOR + battery — presses stay log-visible, absent from the device
        // model (BTN-AMD is post-gate). Endpoints with none of the recognized
        // clusters still classify empty — never invent an entity.
        if (hasBattery) {
            return Optional.of(new Classification(EntityType.SENSOR,
                    capabilities(StandardCapabilities.battery())));
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

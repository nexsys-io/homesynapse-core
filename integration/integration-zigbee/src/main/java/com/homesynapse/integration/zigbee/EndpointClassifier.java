/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.StandardCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
 * <p>ENERGY-READ: the IAS selection maps WATER_LEAK/SMOKE/VIBRATION to
 * {@code binary_state} — the capability whose schema admits the {@code active}
 * key {@link IasZoneHandler} emits for them (R1, IR-15); the meters attach by
 * the CLUSTERS present — 0x0B04 ⇒ {@code power_meter}, 0x0702 ⇒
 * {@code energy_meter} — on EVERY arm, the device type a hint and never the
 * key (R2, IR-24: the owned Gen4 is 0x010A, not the 0x0051 the plug arm keys
 * on); and every classification prints ONE {@code zigbee.endpoint_classified}
 * INFO naming the device type, the input clusters and what was chosen (R6).
 * ENERGY-READ-b: an endpoint no arm classified that lists 0x0702 is an
 * {@code ENERGY_METER}, one listing 0x0B04 only a {@code SENSOR} (R-5).
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

    private static final Logger log =
            LoggerFactory.getLogger(EndpointClassifier.class);

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
     * DP-6; ENERGY-READ R1): CONTACT selects {@code contact},
     * WATER_LEAK/SMOKE/VIBRATION select {@code binary_state}, and
     * {@code null}/MOTION select {@code motion} on the IAS arms.
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
        // R2 (IR-24): the meters ride the CLUSTERS, post-processed so EVERY arm
        // (device-type table AND fallback) gains them — the device type is a
        // hint for the arms above and never the key to a meter.
        classified = withMeters(classified, descriptor);
        // SD-3 (M9.4b §3.2): cluster 0x0003 present ⇒ the entity is
        // identify-issuable through the real Tier-1 validator. Post-processed so
        // EVERY classification arm (device-type table AND fallback) gains it;
        // 0x0003 alone never invents an entity (unmapped endpoints stay empty).
        if (hasIdentify) {
            classified = classified.map(EndpointClassifier::withIdentify);
        }
        logClassified(descriptor, classified);
        return classified;
    }

    /**
     * R6 (DEVICE-SET note 1): the one line that says what the endpoint SAID it
     * was and what it became — the instrument the first real adoption is read
     * from. This utility sees the descriptor alone; the device is named by the
     * slice's call-site line of the same token, printed beside this one for
     * every classified endpoint (ENERGY-READ-b row 1). This line is KEPT at
     * INFO: it is the only one carrying {@code deviceType=} and
     * {@code inputClusters=}.
     */
    private static void logClassified(EndpointDescriptor descriptor,
            Optional<Classification> classified) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("zigbee.endpoint_classified: endpoint={} deviceType=0x{} "
                        + "inputClusters=[{}] entityType={} capabilities=[{}]",
                descriptor.endpointId(),
                Integer.toHexString(descriptor.deviceTypeId()),
                descriptor.inputClusters().stream()
                        .map(cluster -> "0x" + Integer.toHexString(cluster))
                        .collect(Collectors.joining(", ")),
                classified.map(c -> c.entityType().name()).orElse("none"),
                classified.map(c -> c.capabilities().stream()
                        .map(CapabilityInstance::capabilityId)
                        .collect(Collectors.joining(", "))).orElse(""));
    }

    private static Classification withIdentify(Classification classification) {
        List<CapabilityInstance> caps = new ArrayList<>(classification.capabilities());
        caps.addAll(capabilities(StandardCapabilities.identify()));
        return new Classification(classification.entityType(), caps);
    }

    /**
     * The cluster-first metering attach (R2): 0x0B04 ⇒ {@code power_meter},
     * 0x0702 ⇒ {@code energy_meter}, beside whatever the arm chose — the entity
     * type is unchanged by a meter (a metering switch stays SWITCH, a metering
     * light stays LIGHT). An endpoint NO arm classified that lists a metering
     * cluster is a measurement-only endpoint carrying the meter capabilities
     * alone: {@code ENERGY_METER} when it lists 0x0702, {@code SENSOR} when it
     * lists 0x0B04 only — a power measurement without an energy register
     * (ENERGY-READ-b row 2, the b5 ruling R-5). No cluster is ever inferred
     * from a device type.
     */
    private static Optional<Classification> withMeters(
            Optional<Classification> classified, EndpointDescriptor descriptor) {
        List<Integer> in = descriptor.inputClusters();
        List<Capability> meters = new ArrayList<>(2);
        if (in.contains(ElectricalMeasurementHandler.CLUSTER_ID)) {
            meters.add(StandardCapabilities.powerMeter());
        }
        if (in.contains(MeteringHandler.CLUSTER_ID)) {
            meters.add(StandardCapabilities.energyMeter());
        }
        if (meters.isEmpty()) {
            return classified;
        }
        List<CapabilityInstance> caps = new ArrayList<>(classified
                .map(Classification::capabilities).orElse(List.of()));
        caps.addAll(capabilities(meters.toArray(Capability[]::new)));
        // ENERGY-READ-b row 2 (the b5 ruling R-5): the measurement-only endpoint
        // is an ENERGY_METER when it lists 0x0702 (the type's required energy
        // register), a SENSOR when it lists 0x0B04 only.
        EntityType measurementOnly = in.contains(MeteringHandler.CLUSTER_ID)
                ? EntityType.ENERGY_METER : EntityType.SENSOR;
        return Optional.of(new Classification(classified
                .map(Classification::entityType).orElse(measurementOnly), caps));
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
     * The IAS selection (DP-6; ENERGY-READ R1, IR-15): the capability installed
     * is the one whose schema admits the key {@link IasZoneHandler} emits for
     * the zone type — CONTACT ⇒ {@code contact} ({@code open}),
     * WATER_LEAK/SMOKE/VIBRATION ⇒ {@code binary_state} ({@code active}),
     * MOTION and the unlearned {@code null} ⇒ {@code motion}
     * ({@code detected}). Adoption is a one-way door at V1, so a wrong
     * selection here is durable — never guess beyond the wire truth.
     */
    private static Capability iasCapability(ZoneType learnedZoneType) {
        if (learnedZoneType == null) {
            return StandardCapabilities.motion();
        }
        return switch (learnedZoneType) {
            case CONTACT -> StandardCapabilities.contact();
            case MOTION -> StandardCapabilities.motion();
            case WATER_LEAK, SMOKE, VIBRATION -> StandardCapabilities.binaryState();
        };
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

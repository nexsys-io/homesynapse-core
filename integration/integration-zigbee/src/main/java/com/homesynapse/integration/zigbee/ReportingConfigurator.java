/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Doc 08 §3.7 reporting configurator: bind → Configure Reporting → VERIFY
 * read-back → posture classification → re-apply on rejoin.
 *
 * <p><strong>The ACK-lies case is first-class:</strong> a configure that the
 * device ACKs but whose read-back shows different values downgrades honestly —
 * the posture derives from what the device actually DOES (the read-back), never
 * from what it agreed to. A read-back showing reporting off
 * ({@code maxInterval 0xFFFF}) is {@code READBACK_ONLY/NONE}.
 *
 * <p><strong>The degrade ladder (dossier §E):</strong> configure SUCCESS +
 * matching read-back → {@code VERIFIED_REPORTS}; {@code UNSUPPORTED_ATTRIBUTE}
 * → the attribute is genuinely absent → {@code NONE/NONE};
 * {@code UNREPORTABLE_ATTRIBUTE} → {@code READBACK_ONLY/NONE}; a sleepy
 * TIMEOUT → the device still reports on its own wake schedule →
 * {@code VERIFIED_REPORTS/SLEEPY}. Devices whose profile skips
 * {@code configure_reporting} (all Xiaomi/Aqara) receive NO commands and record
 * {@code VERIFIED_REPORTS/PERIODIC} (their own firmware schedule).
 *
 * <p><strong>IAS Zone (§3.12, tolerate-not-require):</strong> the CIE write is
 * ATTEMPTED and its outcome recorded as a device fact — never an interview or
 * adoption gate; {@code ZoneStatusChangeNotification} ingests regardless. The
 * recorded fact DERIVES from the attempt (F-7b, M9.4b §6.4): the IAS path
 * performs no verifying read-back, so the row records
 * {@code READBACK_ONLY/NONE} — never {@code VERIFIED_REPORTS}.
 *
 * <p>The classifications are recorded {@link ReportingPostureFact} DEVICE FACTS
 * feeding the AMD-97 {@code confirmability} consumption at M9.4.
 *
 * <p>Thread-safe: stateless over the injected ops seam.
 */
final class ReportingConfigurator {

    /** One §3.7 default row: attribute, its ZCL data type, min/max/change. */
    private record DefaultRow(int attributeId, int dataType, int minInterval,
            int maxInterval, int reportableChange) {
    }

    /** ZCL "reporting off" max interval. */
    private static final int REPORTING_OFF_MAX_INTERVAL = 0xFFFF;
    /** The interview skip key that disables reporting configuration. */
    private static final String SKIP_CONFIGURE_REPORTING = "configure_reporting";

    /**
     * The §3.7 default reporting table (including the AMD-96 OccupancySensing
     * row — the hero-trigger cluster's posture is explicit, never accidental).
     */
    private static final Map<Integer, DefaultRow> DEFAULTS = Map.ofEntries(
            Map.entry(0x0006, new DefaultRow(0x0000, 0x10, 0, 3600, 0)),
            Map.entry(0x0406, new DefaultRow(0x0000, 0x18, 0, 3600, 0)),
            Map.entry(0x0008, new DefaultRow(0x0000, 0x20, 5, 3600, 1)),
            Map.entry(0x0300, new DefaultRow(0x0007, 0x21, 5, 3600, 1)),
            Map.entry(0x0402, new DefaultRow(0x0000, 0x29, 10, 3600, 10)),
            Map.entry(0x0405, new DefaultRow(0x0000, 0x21, 10, 3600, 100)),
            Map.entry(0x0001, new DefaultRow(0x0021, 0x20, 3600, 62000, 0)),
            Map.entry(0x0B04, new DefaultRow(0x050B, 0x29, 5, 3600, 10)),
            Map.entry(0x0702, new DefaultRow(0x0000, 0x25, 5, 3600, 5)));

    private static final Logger log =
            LoggerFactory.getLogger(ReportingConfigurator.class);

    private final ReportingOps ops;

    /**
     * Creates the configurator.
     *
     * @param ops the ZCL/ZDO operation seam, never {@code null}
     */
    ReportingConfigurator(ReportingOps ops) {
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    /**
     * Configures reporting for every bound cluster of a device and records the
     * measured posture facts.
     *
     * @param device the target device, never {@code null}
     * @param endpoints the device's application endpoints, never {@code null}
     * @param profile the matched device profile; {@code null} when none
     * @return the recorded posture facts
     */
    List<ReportingPostureFact> configureDevice(IEEEAddress device,
            List<EndpointDescriptor> endpoints, DeviceProfile profile) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(endpoints, "endpoints");
        List<ReportingPostureFact> facts = new ArrayList<>();
        boolean skipConfiguration = profile != null
                && profile.interviewSkips() != null
                && profile.interviewSkips().contains(SKIP_CONFIGURE_REPORTING);
        for (EndpointDescriptor endpoint : endpoints) {
            for (int clusterId : endpoint.inputClusters()) {
                if (clusterId == IasZoneHandler.CLUSTER_ID) {
                    facts.add(enrollIasZone(device, endpoint.endpointId()));
                    continue;
                }
                DefaultRow row = DEFAULTS.get(clusterId);
                if (row == null) {
                    continue;
                }
                if (skipConfiguration) {
                    // The Xiaomi class: reports flow on the device's own
                    // firmware schedule; configuration attempts are rejected or
                    // cause disconnects (§3.7 exclusions).
                    facts.add(new ReportingPostureFact(device,
                            endpoint.endpointId(), clusterId, row.attributeId(),
                            ReportsAuthoritative.VERIFIED_REPORTS,
                            ReportingPosture.PERIODIC,
                            "profile skips configure_reporting; device reports "
                                    + "on its own schedule"));
                    continue;
                }
                facts.add(configureCluster(device, endpoint.endpointId(),
                        clusterId, effectiveRow(row, clusterId, profile)));
            }
        }
        return facts;
    }

    /**
     * Re-applies and re-verifies reporting after a rejoin/device-announce —
     * the measured failure modes are the Hue power-loss rejoin and the
     * factory-reset config wipe; an OTA re-interview also re-characterizes here
     * (the fingerprint may have changed).
     *
     * @param device the rejoined device, never {@code null}
     * @param endpoints the device's application endpoints, never {@code null}
     * @param profile the (re-)matched profile; {@code null} when none
     * @return the re-recorded posture facts
     */
    List<ReportingPostureFact> onRejoin(IEEEAddress device,
            List<EndpointDescriptor> endpoints, DeviceProfile profile) {
        log.info("zigbee.reporting_reapply: device={} — rejoin re-applies and "
                + "re-verifies reporting configuration", device);
        return configureDevice(device, endpoints, profile);
    }

    private ReportingPostureFact configureCluster(IEEEAddress device,
            int endpoint, int clusterId, DefaultRow row) {
        if (!ops.bind(device, endpoint, clusterId)) {
            log.warn("zigbee.bind_failed: device={} endpoint={} cluster=0x{}",
                    device, endpoint, Integer.toHexString(clusterId));
            return new ReportingPostureFact(device, endpoint, clusterId,
                    row.attributeId(), ReportsAuthoritative.READBACK_ONLY,
                    ReportingPosture.NONE, "bind failed; reports cannot flow");
        }
        ReportingOps.ConfigureResult result = ops.configureReporting(device,
                endpoint, clusterId, row.attributeId(), row.dataType(),
                row.minInterval(), row.maxInterval(), row.reportableChange());
        return switch (result) {
            case SUCCESS -> verifyReadback(device, endpoint, clusterId, row);
            case UNSUPPORTED_ATTRIBUTE -> new ReportingPostureFact(device,
                    endpoint, clusterId, row.attributeId(),
                    ReportsAuthoritative.NONE, ReportingPosture.NONE,
                    "UNSUPPORTED_ATTRIBUTE: the attribute is absent");
            case UNREPORTABLE_ATTRIBUTE -> new ReportingPostureFact(device,
                    endpoint, clusterId, row.attributeId(),
                    ReportsAuthoritative.READBACK_ONLY, ReportingPosture.NONE,
                    "UNREPORTABLE_ATTRIBUTE: present but never reported");
            case TIMEOUT -> new ReportingPostureFact(device, endpoint, clusterId,
                    row.attributeId(), ReportsAuthoritative.VERIFIED_REPORTS,
                    ReportingPosture.SLEEPY,
                    "configure timed out (sleepy); reports arrive on wake");
        };
    }

    private ReportingPostureFact verifyReadback(IEEEAddress device, int endpoint,
            int clusterId, DefaultRow row) {
        Optional<ReportingOps.ReportingConfigRecord> readback =
                ops.readReportingConfiguration(device, endpoint, clusterId,
                        row.attributeId());
        if (readback.isEmpty()) {
            return new ReportingPostureFact(device, endpoint, clusterId,
                    row.attributeId(), ReportsAuthoritative.VERIFIED_REPORTS,
                    ReportingPosture.SLEEPY,
                    "read-back unavailable; configure was accepted");
        }
        ReportingOps.ReportingConfigRecord effective = readback.get();
        boolean matches = effective.minInterval() == row.minInterval()
                && effective.maxInterval() == row.maxInterval()
                && effective.reportableChange() == row.reportableChange();
        if (matches) {
            return new ReportingPostureFact(device, endpoint, clusterId,
                    row.attributeId(), ReportsAuthoritative.VERIFIED_REPORTS,
                    postureFor(effective), null);
        }
        // ACK-lies: the device agreed, the read-back disagrees. The posture
        // derives from what the device actually does.
        if (effective.maxInterval() == REPORTING_OFF_MAX_INTERVAL) {
            log.warn("zigbee.reporting_ack_lies: device={} cluster=0x{} accepted "
                            + "configure but read-back shows reporting OFF",
                    device, Integer.toHexString(clusterId));
            return new ReportingPostureFact(device, endpoint, clusterId,
                    row.attributeId(), ReportsAuthoritative.READBACK_ONLY,
                    ReportingPosture.NONE,
                    "configure accepted but read-back shows reporting off "
                            + "(ACK-lies); posture downgraded honestly");
        }
        log.warn("zigbee.reporting_ack_lies: device={} cluster=0x{} read-back "
                        + "differs: {}/{}/{}", device,
                Integer.toHexString(clusterId), effective.minInterval(),
                effective.maxInterval(), effective.reportableChange());
        return new ReportingPostureFact(device, endpoint, clusterId,
                row.attributeId(), ReportsAuthoritative.VERIFIED_REPORTS,
                postureFor(effective),
                "configure accepted but read-back differs; posture derived "
                        + "from the read-back values");
    }

    private static ReportingPosture postureFor(
            ReportingOps.ReportingConfigRecord effective) {
        // A change threshold of zero with a floor-zero min interval is
        // change-driven; a min interval at/above an hour is timer-driven.
        if (effective.minInterval() >= 3600) {
            return ReportingPosture.PERIODIC;
        }
        return ReportingPosture.ON_CHANGE;
    }

    private ReportingPostureFact enrollIasZone(IEEEAddress device, int endpoint) {
        boolean written = ops.writeCieAddress(device, endpoint);
        if (!written) {
            log.warn("zigbee.ias_enroll_incomplete: device={} endpoint={} — "
                    + "tolerated; zone notifications ingest regardless",
                    device, endpoint);
        }
        // F-7b (M9.4b §6.4): no bind/configure/read-back runs on the IAS path,
        // so an ACKed CIE write is an attempt, never a verification — the fact
        // records the matrix's unverified-delivery pair (the measured SNZB-03P
        // is enrolled-but-silent); zone notifications ingest regardless (§3.12).
        return new ReportingPostureFact(device, endpoint,
                IasZoneHandler.CLUSTER_ID, IasZoneHandler.ATTRIBUTE_ZONE_STATUS,
                ReportsAuthoritative.READBACK_ONLY, ReportingPosture.NONE,
                written ? "IAS CIE written and ACKed; reporting never "
                        + "read-back-verified — enrollment awaited as a "
                        + "device fact"
                        : "IAS CIE write failed; enrollment not achieved — "
                                + "tolerated, never a gate");
    }

    private static DefaultRow effectiveRow(DefaultRow row, int clusterId,
            DeviceProfile profile) {
        if (profile == null || profile.reportingOverrides() == null) {
            return row;
        }
        ReportingOverride override = profile.reportingOverrides().get(clusterId);
        if (override == null) {
            return row;
        }
        return new DefaultRow(row.attributeId(), row.dataType(),
                override.minInterval(), override.maxInterval(),
                override.reportableChange());
    }
}

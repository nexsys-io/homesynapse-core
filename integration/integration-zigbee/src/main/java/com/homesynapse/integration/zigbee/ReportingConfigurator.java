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
 * <p><strong>Metering (ENERGY-READ R3 + R5):</strong> the two metering
 * clusters (0x0B04, 0x0702) are NOT in the §3.7 default table — a raw change
 * means a different wattage on every device. Each endpoint's formatting is
 * READ from the device first (one frame per metering cluster, before any
 * configure on it; a rejoin re-applies from the {@link FormattingStore} and
 * reads only what it lacks), and the reportable change is the engineering
 * threshold — 1 W on {@code ActivePower} (5–600 s), 5 Wh on
 * {@code CurrentSummationDelivered} (5–3600 s) — scaled by what was read.
 * Unreadable formatting (no answer, a failed or zero multiplier/divisor) or a
 * summation not in kWh configures NOTHING on that cluster and records a
 * NONE-class posture saying why. The formatting is never predicted and never
 * overridden.
 *
 * <p>Thread-safe: stateless over the injected ops and formatting-store seams.
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
            Map.entry(0x0001, new DefaultRow(0x0021, 0x20, 3600, 62000, 0)));

    /**
     * One metering row (ENERGY-READ R5): the reported attribute, its ZCL data
     * type and the interval pair. It carries NO change on purpose — a metering
     * cluster's reportable change exists only as an ENGINEERING threshold
     * scaled by the formatting the device declared; a raw number here is how a
     * divisor-1,000,000 meter floods the network at the 5-s floor.
     */
    private record MeteringRow(int attributeId, int dataType, int minInterval,
            int maxInterval) {
    }

    /**
     * The two metering rows — NOT in {@link #DEFAULTS}: {@code ActivePower}
     * 5–600 s, {@code CurrentSummationDelivered} 5–3600 s. The 5-s minimum is
     * the floor HEAD already held; the 600-s maximum on {@code ActivePower}
     * is the heartbeat that proves a quiet load is a live meter.
     */
    private static final Map<Integer, MeteringRow> METERING_ROWS = Map.of(
            ElectricalMeasurementHandler.CLUSTER_ID, new MeteringRow(
                    ElectricalMeasurementHandler.ATTRIBUTE_ACTIVE_POWER,
                    ElectricalMeasurementHandler.DATA_TYPE_ACTIVE_POWER, 5, 600),
            MeteringHandler.CLUSTER_ID, new MeteringRow(
                    MeteringHandler.ATTRIBUTE_CURRENT_SUMMATION_DELIVERED,
                    MeteringHandler.DATA_TYPE_CURRENT_SUMMATION, 5, 3600));

    /** R5: the {@code ActivePower} reportable change, in watts. */
    static final double ACTIVE_POWER_CHANGE_WATTS = 1.0;
    /** R5: the {@code CurrentSummationDelivered} reportable change, in watt-hours. */
    static final double SUMMATION_CHANGE_WATT_HOURS = 5.0;
    /** The summation's kWh base: Wh per unit of a kWh-declared meter. */
    private static final double WATT_HOURS_PER_KILOWATT_HOUR = 1000.0;
    /** The largest change an int16 attribute's reportable-change field holds. */
    private static final long INT16_CHANGE_MAX = 0x7FFF;

    /** The posture note of a metering cluster the device would not describe. */
    static final String NOTE_FORMATTING_UNREADABLE =
            "formatting unreadable; reporting not configured";

    private static final Logger log =
            LoggerFactory.getLogger(ReportingConfigurator.class);

    /**
     * The learned-formatting seam (ENERGY-READ R3): the persisted view the
     * rejoin arm consults, and the sink every formatting read writes through.
     * The configurator stays stateless — the knowledge lives in the device
     * cache, behind this seam.
     */
    interface FormattingStore {

        /**
         * The formatting already learned for an endpoint.
         *
         * @param device the device, never {@code null}
         * @param endpoint the endpoint
         * @return the learned formatting, or empty when none was recorded
         */
        Optional<MeteringFormatting> cached(IEEEAddress device, int endpoint);

        /**
         * Records a formatting that a read produced — state mutation only,
         * never I/O (the caller is the ingestion thread).
         *
         * @param device the device, never {@code null}
         * @param endpoint the endpoint
         * @param formatting what the device declared, never {@code null}
         */
        void learned(IEEEAddress device, int endpoint,
                MeteringFormatting formatting);
    }

    /** The store of a configurator that persists nothing (the pre-ENERGY-READ shape). */
    private static final FormattingStore NO_STORE = new FormattingStore() {
        @Override
        public Optional<MeteringFormatting> cached(IEEEAddress device,
                int endpoint) {
            return Optional.empty();
        }

        @Override
        public void learned(IEEEAddress device, int endpoint,
                MeteringFormatting formatting) {
            // Nothing persists: every drive reads what it needs.
        }
    };

    private final ReportingOps ops;
    private final FormattingStore formattingStore;

    /**
     * Creates a configurator that persists no formatting: every drive reads
     * what it needs and nothing is handed back.
     *
     * @param ops the ZCL/ZDO operation seam, never {@code null}
     */
    ReportingConfigurator(ReportingOps ops) {
        this(ops, NO_STORE);
    }

    /**
     * Creates the configurator over a learned-formatting store.
     *
     * @param ops the ZCL/ZDO operation seam, never {@code null}
     * @param formattingStore the learned-formatting seam, never {@code null}
     */
    ReportingConfigurator(ReportingOps ops, FormattingStore formattingStore) {
        this.ops = Objects.requireNonNull(ops, "ops");
        this.formattingStore =
                Objects.requireNonNull(formattingStore, "formattingStore");
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
        return drive(device, endpoints, profile, false);
    }

    /**
     * The one drive both arms share. {@code rejoin} selects where a metering
     * endpoint's formatting comes from (ENERGY-READ R3): a fresh adoption
     * ALWAYS asks the device; a rejoin uses what the store already holds and
     * asks only for the cluster it lacks.
     */
    private List<ReportingPostureFact> drive(IEEEAddress device,
            List<EndpointDescriptor> endpoints, DeviceProfile profile,
            boolean rejoin) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(endpoints, "endpoints");
        List<ReportingPostureFact> facts = new ArrayList<>();
        boolean skipConfiguration = profile != null
                && profile.interviewSkips() != null
                && profile.interviewSkips().contains(SKIP_CONFIGURE_REPORTING);
        for (EndpointDescriptor endpoint : endpoints) {
            // R3: what the endpoint's metering clusters declare about their
            // own scale is asked FIRST — before any configure on them. A
            // skip-profile device receives NO commands, the read included.
            MeteringFormatting formatting = skipConfiguration
                    ? MeteringFormatting.unknown()
                    : meteringFormatting(device, endpoint, rejoin);
            for (int clusterId : endpoint.inputClusters()) {
                if (clusterId == IasZoneHandler.CLUSTER_ID) {
                    facts.add(enrollIasZone(device, endpoint.endpointId()));
                    continue;
                }
                DefaultRow row = DEFAULTS.get(clusterId);
                MeteringRow meteringRow = METERING_ROWS.get(clusterId);
                if (row == null && meteringRow == null) {
                    continue;
                }
                if (skipConfiguration) {
                    // The Xiaomi class: reports flow on the device's own
                    // firmware schedule; configuration attempts are rejected or
                    // cause disconnects (§3.7 exclusions).
                    facts.add(new ReportingPostureFact(device,
                            endpoint.endpointId(), clusterId,
                            row != null ? row.attributeId()
                                    : meteringRow.attributeId(),
                            ReportsAuthoritative.VERIFIED_REPORTS,
                            ReportingPosture.PERIODIC,
                            "profile skips configure_reporting; device reports "
                                    + "on its own schedule"));
                    continue;
                }
                if (meteringRow != null) {
                    facts.add(configureMetering(device, endpoint.endpointId(),
                            clusterId, meteringRow, formatting, profile));
                    continue;
                }
                facts.add(configureCluster(device, endpoint.endpointId(),
                        clusterId, effectiveRow(row, clusterId, profile)));
            }
        }
        return facts;
    }

    /**
     * R3 — the formatting of one endpoint's metering clusters: ONE Read
     * Attributes frame per metering cluster the endpoint lists (none for an
     * endpoint that lists neither), synchronously on the calling (ingestion)
     * thread, exactly as the configures run. What a read produced is handed to
     * the store; a read that produced nothing hands nothing, so the next
     * rejoin asks again. Every cluster prints its outcome: the read values at
     * INFO (the journal's instrument), an unreadable cluster at WARN.
     */
    private MeteringFormatting meteringFormatting(IEEEAddress device,
            EndpointDescriptor endpoint, boolean rejoin) {
        boolean electrical = endpoint.inputClusters()
                .contains(ElectricalMeasurementHandler.CLUSTER_ID);
        boolean metering = endpoint.inputClusters()
                .contains(MeteringHandler.CLUSTER_ID);
        if (!electrical && !metering) {
            return MeteringFormatting.unknown();
        }
        int endpointId = endpoint.endpointId();
        MeteringFormatting cached = rejoin
                ? formattingStore.cached(device, endpointId)
                        .orElse(MeteringFormatting.unknown())
                : MeteringFormatting.unknown();
        boolean readElectrical = electrical && !cached.hasElectrical();
        boolean readMetering = metering && !cached.hasMetering();
        MeteringFormatting read = MeteringFormatting.ofReads(
                readElectrical ? ops.readAttributes(device, endpointId,
                        ElectricalMeasurementHandler.CLUSTER_ID,
                        ElectricalMeasurementHandler.formattingAttributes())
                        .orElse(null) : null,
                readMetering ? ops.readAttributes(device, endpointId,
                        MeteringHandler.CLUSTER_ID,
                        MeteringHandler.formattingAttributes())
                        .orElse(null) : null);
        MeteringFormatting formatting = cached.filledFrom(read);
        if (electrical) {
            logFormatting(device, endpointId,
                    ElectricalMeasurementHandler.CLUSTER_ID,
                    formatting.hasElectrical(), readElectrical,
                    formatting.electricalNote());
        }
        if (metering) {
            logFormatting(device, endpointId, MeteringHandler.CLUSTER_ID,
                    formatting.hasMetering(), readMetering,
                    formatting.summationNote());
        }
        if (read.hasElectrical() || read.hasMetering()) {
            formattingStore.learned(device, endpointId, formatting);
        }
        return formatting;
    }

    private static void logFormatting(IEEEAddress device, int endpoint,
            int clusterId, boolean readable, boolean askedDevice, String note) {
        if (readable) {
            log.info("zigbee.metering_formatting_read: device={} endpoint={} "
                            + "cluster=0x{} source={} {}", device, endpoint,
                    Integer.toHexString(clusterId),
                    askedDevice ? "device" : "cache", note);
        } else {
            log.warn("zigbee.metering_formatting_unreadable: device={} "
                            + "endpoint={} cluster=0x{}; reporting not "
                            + "configured, reports stay unscaled", device,
                    endpoint, Integer.toHexString(clusterId));
        }
    }

    /**
     * R5 — one metering cluster's configure: the reportable change is the
     * ENGINEERING threshold (1 W; 5 Wh) scaled by the formatting the device
     * declared — {@code round(engineering × divisor / multiplier)}, never
     * below 1 — so the threshold means the same watts on every divisor. A
     * cluster whose formatting is unreadable, or whose summation is not in
     * kWh, is NOT configured: no bind, no configure, a NONE-class posture
     * saying why — never a raw threshold sent on a guess. A profile override
     * moves the INTERVALS only; the change is always the scaled value.
     */
    private ReportingPostureFact configureMetering(IEEEAddress device,
            int endpoint, int clusterId, MeteringRow meteringRow,
            MeteringFormatting formatting, DeviceProfile profile) {
        boolean electrical =
                clusterId == ElectricalMeasurementHandler.CLUSTER_ID;
        if (!(electrical ? formatting.hasElectrical()
                : formatting.hasMetering())) {
            return new ReportingPostureFact(device, endpoint, clusterId,
                    meteringRow.attributeId(), ReportsAuthoritative.NONE,
                    ReportingPosture.NONE, NOTE_FORMATTING_UNREADABLE);
        }
        String formattingNote = "formatting read: " + (electrical
                ? formatting.powerNote() : formatting.summationNote());
        if (!electrical && !formatting.kilowattHours()) {
            log.warn("zigbee.metering_unit_unsupported: device={} endpoint={} "
                            + "unit={}; reporting not configured (only kWh 0x0 "
                            + "is scaled)", device, endpoint,
                    formatting.unitOfMeasure() == null ? "unread" : "0x"
                            + Integer.toHexString(formatting.unitOfMeasure()));
            return new ReportingPostureFact(device, endpoint, clusterId,
                    meteringRow.attributeId(), ReportsAuthoritative.NONE,
                    ReportingPosture.NONE, formattingNote
                            + "; unit is not kWh; reporting not configured");
        }
        long scaled = electrical
                ? Math.min(INT16_CHANGE_MAX, Math.round(ACTIVE_POWER_CHANGE_WATTS
                        * formatting.powerDivisor()
                        / formatting.powerMultiplier()))
                : Math.round(SUMMATION_CHANGE_WATT_HOURS
                        * formatting.summationDivisor()
                        / (WATT_HOURS_PER_KILOWATT_HOUR
                                * formatting.summationMultiplier()));
        int change = (int) Math.max(1, Math.min(Integer.MAX_VALUE, scaled));
        ReportingOverride override = profile == null
                || profile.reportingOverrides() == null ? null
                : profile.reportingOverrides().get(clusterId);
        ReportingPostureFact fact = configureCluster(device, endpoint, clusterId,
                new DefaultRow(meteringRow.attributeId(), meteringRow.dataType(),
                        override == null ? meteringRow.minInterval()
                                : override.minInterval(),
                        override == null ? meteringRow.maxInterval()
                                : override.maxInterval(),
                        change));
        // The strict VERIFIED rung stays unremarkable (note == null — what
        // ConfirmationOverrideInstaller.verifiedFact counts): a healthy meter
        // must never render as degraded. Every rung that already carries a
        // note names the read values in front of it.
        return fact.note() == null ? fact : new ReportingPostureFact(device,
                endpoint, clusterId, fact.attributeId(),
                fact.reportsAuthoritative(), fact.reportingPosture(),
                formattingNote + "; " + fact.note());
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
        return drive(device, endpoints, profile, true);
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

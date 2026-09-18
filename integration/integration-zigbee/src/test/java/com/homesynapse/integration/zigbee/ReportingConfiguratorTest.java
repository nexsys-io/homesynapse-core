/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReportingConfigurator} tests (Doc 08 §3.7 + AMD-96): the
 * bind → configure → VERIFY read-back sequence, the first-class ACK-lies
 * downgrade, per-capability posture classification, the Xiaomi
 * {@code configure_reporting} skip, the IAS CIE tolerate-path with the F-7b
 * (M9.4b §6.4) honest posture derivation, and re-apply-on-rejoin.
 *
 * <p>ENERGY-READ R3 + R5: the two metering clusters are read for their
 * formatting BEFORE any configure (one frame each), the reportable change is
 * the engineering threshold scaled by what the device declared, an unreadable
 * formatting configures NOTHING, and a rejoin re-applies from the store
 * without re-reading. The little-endian change BYTES ride
 * {@code ZigbeeReportingDriveTest} — this seam carries integers.
 */
class ReportingConfiguratorTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x00124B0012345678L);

    private FakeReportingOps ops;
    private ReportingConfigurator configurator;

    @BeforeEach
    void setUp() {
        ops = new FakeReportingOps();
        configurator = new ReportingConfigurator(ops);
    }

    private static final class FakeReportingOps implements ReportingOps {
        final List<String> calls = new ArrayList<>();
        final Map<Integer, ConfigureResult> configureResults = new HashMap<>();
        final Map<Integer, ReportingConfigRecord> readbacks = new HashMap<>();
        boolean bindResult = true;
        boolean cieWriteResult = true;

        @Override
        public boolean bind(IEEEAddress device, int endpoint, int clusterId) {
            calls.add("bind:" + Integer.toHexString(clusterId));
            return bindResult;
        }

        @Override
        public ConfigureResult configureReporting(IEEEAddress device, int endpoint,
                int clusterId, int attributeId, int dataType, int minInterval,
                int maxInterval, int reportableChange) {
            calls.add("configure:" + Integer.toHexString(clusterId)
                    + ":" + minInterval + ":" + maxInterval + ":" + reportableChange);
            return configureResults.getOrDefault(clusterId,
                    ConfigureResult.SUCCESS);
        }

        @Override
        public Optional<ReportingConfigRecord> readReportingConfiguration(
                IEEEAddress device, int endpoint, int clusterId, int attributeId) {
            calls.add("readback:" + Integer.toHexString(clusterId));
            return Optional.ofNullable(readbacks.get(clusterId));
        }

        @Override
        public boolean writeCieAddress(IEEEAddress device, int endpoint) {
            calls.add("cie:" + endpoint);
            return cieWriteResult;
        }

        /** Scripted formatting answers by cluster; an unscripted cluster never answers. */
        final Map<Integer, Map<Integer, Object>> reads = new HashMap<>();

        @Override
        public Optional<Map<Integer, Object>> readAttributes(IEEEAddress device,
                int endpoint, int clusterId, int[] attributeIds) {
            StringBuilder ids = new StringBuilder();
            for (int attributeId : attributeIds) {
                ids.append(ids.isEmpty() ? "" : ",")
                        .append(Integer.toHexString(attributeId));
            }
            calls.add("read:" + Integer.toHexString(clusterId) + ":" + ids);
            return Optional.ofNullable(reads.get(clusterId));
        }
    }

    /** One {@code learned} hand-off to the store. */
    private record Learned(IEEEAddress device, int endpoint,
            MeteringFormatting formatting) { }

    /** A recording {@link ReportingConfigurator.FormattingStore}. */
    private static final class FakeFormattingStore
            implements ReportingConfigurator.FormattingStore {
        final Map<Integer, MeteringFormatting> cachedByEndpoint = new HashMap<>();
        final List<Learned> learned = new ArrayList<>();

        @Override
        public Optional<MeteringFormatting> cached(IEEEAddress device,
                int endpoint) {
            return Optional.ofNullable(cachedByEndpoint.get(endpoint));
        }

        @Override
        public void learned(IEEEAddress device, int endpoint,
                MeteringFormatting formatting) {
            learned.add(new Learned(device, endpoint, formatting));
        }
    }

    private static EndpointDescriptor endpoint(int... inClusters) {
        List<Integer> in = new ArrayList<>();
        for (int cluster : inClusters) {
            in.add(cluster);
        }
        return new EndpointDescriptor(1, 0x0104, 0x0107, in, List.of());
    }

    @Test
    @DisplayName("bind → configure → read-back verify: a matching read-back is VERIFIED_REPORTS/ON_CHANGE")
    void verifiedReportsOnMatch() {
        ops.readbacks.put(0x0406,
                new ReportingOps.ReportingConfigRecord(0, 3600, 0));

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0406)), null);

        assertThat(ops.calls).containsExactly(
                "bind:406", "configure:406:0:3600:0", "readback:406");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.VERIFIED_REPORTS);
        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.ON_CHANGE);
    }

    @Test
    @DisplayName("the AMD-96 OccupancySensing row: 0 s / 3600 s / discrete")
    void amd96OccupancyRow() {
        ops.readbacks.put(0x0406,
                new ReportingOps.ReportingConfigRecord(0, 3600, 0));

        configurator.configureDevice(DEVICE, List.of(endpoint(0x0406)), null);

        assertThat(ops.calls).contains("configure:406:0:3600:0");
    }

    @Test
    @DisplayName("ACK-lies is first-class: configure SUCCESS but read-back shows reporting off → honest downgrade")
    void ackLiesDowngrades() {
        ops.readbacks.put(0x0406,
                new ReportingOps.ReportingConfigRecord(0, 0xFFFF, 0));

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0406)), null);

        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.READBACK_ONLY);
        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.NONE);
        assertThat(facts.get(0).note()).contains("read-back");
    }

    @Test
    @DisplayName("posture matrix: UNSUPPORTED_ATTRIBUTE → NONE/NONE (the attribute is absent)")
    void unsupportedAttributeRow() {
        ops.configureResults.put(0x0406,
                ReportingOps.ConfigureResult.UNSUPPORTED_ATTRIBUTE);

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0406)), null);

        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.NONE);
        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.NONE);
    }

    @Test
    @DisplayName("posture matrix: UNREPORTABLE_ATTRIBUTE → READBACK_ONLY/NONE")
    void unreportableAttributeRow() {
        ops.configureResults.put(0x0406,
                ReportingOps.ConfigureResult.UNREPORTABLE_ATTRIBUTE);

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0406)), null);

        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.READBACK_ONLY);
        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.NONE);
    }

    @Test
    @DisplayName("posture matrix: a sleepy configure TIMEOUT keeps reports but marks SLEEPY")
    void sleepyTimeoutRow() {
        ops.configureResults.put(0x0406, ReportingOps.ConfigureResult.TIMEOUT);

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0406)), null);

        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.VERIFIED_REPORTS);
        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.SLEEPY);
    }

    @Test
    @DisplayName("periodic classification: the battery row (3600/62000) reads back as PERIODIC")
    void batteryPeriodicRow() {
        ops.readbacks.put(0x0001,
                new ReportingOps.ReportingConfigRecord(3600, 62000, 0));

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0001)), null);

        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.PERIODIC);
    }

    @Test
    @DisplayName("profile reportingOverrides replace the §3.7 defaults")
    void profileOverridesApply() {
        ops.readbacks.put(0x0402,
                new ReportingOps.ReportingConfigRecord(5, 1800, 5));
        DeviceProfile profile = new DeviceProfile("p", Set.of(
                new ExactModel("LUMI", "lumi.weather")),
                DeviceCategory.MINOR_QUIRKS, null,
                Map.of(0x0402, new ReportingOverride(0x0402, 5, 1800, 5)),
                null, null, null, null, null);

        configurator.configureDevice(DEVICE, List.of(endpoint(0x0402)), profile);

        assertThat(ops.calls).contains("configure:402:5:1800:5");
    }

    @Test
    @DisplayName("the Xiaomi skip: interviewSkips configure_reporting sends NOTHING and records the device schedule")
    void xiaomiSkip() {
        DeviceProfile profile = new DeviceProfile("aqara", Set.of(
                new ExactModel("LUMI", "lumi.weather")),
                DeviceCategory.MIXED_CUSTOM, null, null, "xiaomi_ff01",
                Set.of("configure_reporting"), null, null, null);

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0402)), profile);

        assertThat(ops.calls).isEmpty();
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.VERIFIED_REPORTS);
        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.PERIODIC);
        assertThat(facts.get(0).note()).contains("configure_reporting");
    }

    @Test
    @DisplayName("IAS Zone: the CIE write is ATTEMPTED and recorded — never a gate")
    void iasCieToleratePath() {
        ops.cieWriteResult = false;

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0500)), null);

        assertThat(ops.calls).contains("cie:1");
        assertThat(facts)
                .as("a failed enrollment still yields a fact, never an abort")
                .isNotEmpty();
        // F-7b reconciliation: the failed attempt records the truthful
        // attempted-tolerated pair — the pre-fold row claimed
        // VERIFIED_REPORTS/ON_CHANGE here without any read-back.
        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.READBACK_ONLY);
        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.NONE);
        assertThat(facts.get(0).note()).contains("enroll");
    }

    @Test
    @DisplayName("IAS Zone F-7b: an ACKed CIE write without read-back records READBACK_ONLY/NONE, never VERIFIED_REPORTS")
    void iasAckedCieWriteDoesNotOverclaim() {
        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0500)), null);

        assertThat(ops.calls)
                .as("the IAS path issues the CIE write and NOTHING else — no "
                        + "read-back exists to verify against")
                .containsExactly("cie:1");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).reportsAuthoritative())
                .as("F-7b: an ACK is an attempt, not a verification")
                .isEqualTo(ReportsAuthoritative.READBACK_ONLY);
        assertThat(facts.get(0).reportingPosture())
                .isEqualTo(ReportingPosture.NONE);
        assertThat(facts.get(0).note()).contains("read-back");
    }

    @Test
    @DisplayName("IAS Zone F-7b: the honest IAS row leaves a genuinely verified cluster at VERIFIED_REPORTS")
    void iasHonestyDoesNotTouchVerifiedRows() {
        ops.readbacks.put(0x0406,
                new ReportingOps.ReportingConfigRecord(0, 3600, 0));

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0500, 0x0406)), null);

        assertThat(facts).hasSize(2);
        ReportingPostureFact ias = facts.stream()
                .filter(fact -> fact.clusterId() == 0x0500)
                .findFirst().orElseThrow();
        ReportingPostureFact occupancy = facts.stream()
                .filter(fact -> fact.clusterId() == 0x0406)
                .findFirst().orElseThrow();
        assertThat(ias.reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.READBACK_ONLY);
        assertThat(occupancy.reportsAuthoritative())
                .as("a genuinely verified read-back still records "
                        + "VERIFIED_REPORTS")
                .isEqualTo(ReportsAuthoritative.VERIFIED_REPORTS);
        assertThat(occupancy.reportingPosture())
                .isEqualTo(ReportingPosture.ON_CHANGE);
    }

    @Test
    @DisplayName("re-apply on rejoin: the full configure sequence runs again")
    void reapplyOnRejoin() {
        ops.readbacks.put(0x0406,
                new ReportingOps.ReportingConfigRecord(0, 3600, 0));
        configurator.configureDevice(DEVICE, List.of(endpoint(0x0406)), null);
        int callsAfterFirst = ops.calls.size();

        configurator.onRejoin(DEVICE, List.of(endpoint(0x0406)), null);

        assertThat(ops.calls.size()).isEqualTo(callsAfterFirst * 2);
    }

    @Test
    @DisplayName("clusters without a §3.7 default row are left untouched")
    void unknownClusterUntouched() {
        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0xFC57)), null);

        assertThat(ops.calls).isEmpty();
        assertThat(facts).isEmpty();
    }

    // ── ENERGY-READ R3 + R5 — the formatting read and the scaled change ─────

    private static final String READ_ELECTRICAL =
            "read:b04:604,605,600,601,602,603";
    private static final String READ_METERING = "read:702:300,301,302,303";

    /** A scripted 0x0B04 formatting answer: the power pair + fixed V/I pairs. */
    private static Map<Integer, Object> electricalRead(long multiplier,
            long divisor) {
        Map<Integer, Object> read = new HashMap<>();
        read.put(0x0604, multiplier);
        read.put(0x0605, divisor);
        read.put(0x0600, 1L);
        read.put(0x0601, 10L);
        read.put(0x0602, 1L);
        read.put(0x0603, 1000L);
        return read;
    }

    /** A scripted 0x0702 formatting answer. */
    private static Map<Integer, Object> meteringRead(long unit, long multiplier,
            long divisor) {
        Map<Integer, Object> read = new HashMap<>();
        read.put(0x0300, unit);
        read.put(0x0301, multiplier);
        read.put(0x0302, divisor);
        read.put(0x0303, 0L);
        return read;
    }

    private static ReportingPostureFact factFor(List<ReportingPostureFact> facts,
            int clusterId) {
        return facts.stream().filter(fact -> fact.clusterId() == clusterId)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("T6: the reportable change is the ENGINEERING threshold scaled by "
            + "the READ formatting — 1 W at div 100 → 100 (5–600 s); 5 Wh at div "
            + "1,000,000 kWh → 5,000 (5–3600 s); both reads BEFORE any configure")
    void meteringChange_scaledByReadFormatting() {
        ops.reads.put(0x0B04, electricalRead(1, 100));
        ops.reads.put(0x0702, meteringRead(0x00, 1, 1_000_000));
        ops.readbacks.put(0x0B04,
                new ReportingOps.ReportingConfigRecord(5, 600, 100));
        ops.readbacks.put(0x0702,
                new ReportingOps.ReportingConfigRecord(5, 3600, 5000));

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0702, 0x0B04)), null);

        assertThat(ops.calls).containsExactly(
                READ_ELECTRICAL, READ_METERING,
                "bind:702", "configure:702:5:3600:5000", "readback:702",
                "bind:b04", "configure:b04:5:600:100", "readback:b04");
        assertThat(facts).hasSize(2);
        assertThat(factFor(facts, 0x0B04).attributeId()).isEqualTo(0x050B);
        assertThat(factFor(facts, 0x0702).attributeId()).isEqualTo(0x0000);
    }

    @Test
    @DisplayName("T6: the TR3's recorded divisor (3,600,000, 1 raw = 1 Ws) scales "
            + "5 Wh to 18,000; divisor 1,000 to 5; divisor 10 on ActivePower to 10")
    void meteringChange_scalesAcrossTheRecordedDivisors() {
        ops.reads.put(0x0B04, electricalRead(1, 10));
        ops.reads.put(0x0702, meteringRead(0x00, 1, 3_600_000));
        configurator.configureDevice(DEVICE, List.of(endpoint(0x0702, 0x0B04)),
                null);
        assertThat(ops.calls).contains("configure:702:5:3600:18000",
                "configure:b04:5:600:10");

        ops.calls.clear();
        ops.reads.put(0x0702, meteringRead(0x00, 1, 1_000));
        configurator.configureDevice(DEVICE, List.of(endpoint(0x0702)), null);
        assertThat(ops.calls).contains("configure:702:5:3600:5");
    }

    @Test
    @DisplayName("T6: at div 1 / mult 1 the changes are 1 and 1 — never below 1 "
            + "(a zero change would report on every sample)")
    void meteringChange_neverBelowOne() {
        ops.reads.put(0x0B04, electricalRead(1, 1));
        ops.reads.put(0x0702, meteringRead(0x00, 1, 1));

        configurator.configureDevice(DEVICE, List.of(endpoint(0x0702, 0x0B04)),
                null);

        assertThat(ops.calls).contains("configure:702:5:3600:1",
                "configure:b04:5:600:1");
    }

    @Test
    @DisplayName("R5: the multiplier divides the threshold — mult 10 / div 100 "
            + "scales 1 W to 10 raw; an ActivePower change never exceeds the "
            + "int16 field")
    void meteringChange_honorsTheMultiplier_andTheFieldCeiling() {
        ops.reads.put(0x0B04, electricalRead(10, 100));
        configurator.configureDevice(DEVICE, List.of(endpoint(0x0B04)), null);
        assertThat(ops.calls).contains("configure:b04:5:600:10");

        ops.calls.clear();
        ops.reads.put(0x0B04, electricalRead(1, 65_535));
        configurator.configureDevice(DEVICE, List.of(endpoint(0x0B04)), null);
        assertThat(ops.calls).contains("configure:b04:5:600:32767");
    }

    @Test
    @DisplayName("T7: unreadable formatting → the cluster is NOT configured (no "
            + "bind, no configure — no raw-threshold flood), a NONE-class posture "
            + "says why; the rest of the endpoint configures as ever")
    void formattingUnreadable_notConfigured() {
        ops.readbacks.put(0x0006,
                new ReportingOps.ReportingConfigRecord(0, 3600, 0));
        // No scripted read: neither metering cluster answers.

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0006, 0x0702, 0x0B04)), null);

        assertThat(ops.calls).containsExactly(READ_ELECTRICAL, READ_METERING,
                "bind:6", "configure:6:0:3600:0", "readback:6");
        assertThat(facts).hasSize(3);
        for (int clusterId : new int[] {0x0702, 0x0B04}) {
            ReportingPostureFact fact = factFor(facts, clusterId);
            assertThat(fact.reportsAuthoritative())
                    .isEqualTo(ReportsAuthoritative.NONE);
            assertThat(fact.reportingPosture()).isEqualTo(ReportingPosture.NONE);
            assertThat(fact.note())
                    .isEqualTo("formatting unreadable; reporting not configured");
        }
        assertThat(factFor(facts, 0x0006).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.VERIFIED_REPORTS);
    }

    @Test
    @DisplayName("T7: a ZERO multiplier or divisor read from the device is INVALID "
            + "formatting — unknown, not configured")
    void formattingZero_isUnreadable() {
        ops.reads.put(0x0B04, electricalRead(1, 0));
        ops.reads.put(0x0702, meteringRead(0x00, 0, 1_000));

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0702, 0x0B04)), null);

        assertThat(ops.calls).containsExactly(READ_ELECTRICAL, READ_METERING);
        assertThat(facts).extracting(ReportingPostureFact::note).containsOnly(
                "formatting unreadable; reporting not configured");
    }

    @Test
    @DisplayName("T7: an answer WITHOUT the divisor record (the codec drops "
            + "UNSUPPORTED_ATTRIBUTE records) is unreadable — the ZCL default of "
            + "1 is never assumed")
    void formattingUnsupportedAttribute_isUnreadable() {
        Map<Integer, Object> withoutDivisor = electricalRead(1, 100);
        withoutDivisor.remove(0x0605);
        ops.reads.put(0x0B04, withoutDivisor);

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0B04)), null);

        assertThat(ops.calls).containsExactly(READ_ELECTRICAL);
        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.NONE);
    }

    @Test
    @DisplayName("R5: a summation unit other than kWh is NOT configured — the "
            + "posture names the read values and the unit (no guess)")
    void meteringUnitNotKilowattHours_notConfigured() {
        ops.reads.put(0x0702, meteringRead(0x01, 1, 1_000));

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0702)), null);

        assertThat(ops.calls).containsExactly(READ_METERING);
        assertThat(facts.get(0).reportsAuthoritative())
                .isEqualTo(ReportsAuthoritative.NONE);
        assertThat(facts.get(0).note()).isEqualTo("formatting read: mult=1 "
                + "div=1000 unit=0x1; unit is not kWh; reporting not configured");
    }

    @Test
    @DisplayName("R3: a VERIFIED metering fact stays unremarkable (note == null — "
            + "the never-false-degraded count), a degraded one names the read "
            + "values, and ONE INFO per cluster carries them to the journal")
    void formattingRead_isLogged_andNotedOnlyWhereANoteIsLawful() {
        ops.reads.put(0x0B04, electricalRead(1, 100));
        ops.reads.put(0x0702, meteringRead(0x00, 1, 1_000_000));
        ops.readbacks.put(0x0B04,
                new ReportingOps.ReportingConfigRecord(5, 600, 100));
        ops.configureResults.put(0x0702,
                ReportingOps.ConfigureResult.UNREPORTABLE_ATTRIBUTE);
        Logger configuratorLogger =
                (Logger) LoggerFactory.getLogger(ReportingConfigurator.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        configuratorLogger.addAppender(capture);
        List<ReportingPostureFact> facts;
        try {
            facts = configurator.configureDevice(DEVICE,
                    List.of(endpoint(0x0702, 0x0B04)), null);
        } finally {
            configuratorLogger.detachAppender(capture);
        }

        assertThat(ConfirmationOverrideInstaller.verifiedFact(
                factFor(facts, 0x0B04)))
                .as("a healthy meter counts verified in reporting_configured")
                .isTrue();
        assertThat(factFor(facts, 0x0702).note()).isEqualTo("formatting read: "
                + "mult=1 div=1000000 unit=0x0; UNREPORTABLE_ATTRIBUTE: present "
                + "but never reported");
        assertThat(capture.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message
                        .startsWith("zigbee.metering_formatting_read"))
                .toList()).containsExactly(
                "zigbee.metering_formatting_read: device=0x00124B0012345678 "
                        + "endpoint=1 cluster=0xb04 source=device mult=1 div=100 "
                        + "voltage=1/10 current=1/1000",
                "zigbee.metering_formatting_read: device=0x00124B0012345678 "
                        + "endpoint=1 cluster=0x702 source=device mult=1 "
                        + "div=1000000 unit=0x0");
    }

    @Test
    @DisplayName("R3: unreadable formatting is ONE WARN per cluster — loud, never "
            + "silent")
    void formattingUnreadable_warnsOncePerCluster() {
        Logger configuratorLogger =
                (Logger) LoggerFactory.getLogger(ReportingConfigurator.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        configuratorLogger.addAppender(capture);
        try {
            configurator.configureDevice(DEVICE, List.of(endpoint(0x0B04)), null);
        } finally {
            configuratorLogger.detachAppender(capture);
        }

        assertThat(capture.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList())
                .containsExactly("zigbee.metering_formatting_unreadable: "
                        + "device=0x00124B0012345678 endpoint=1 cluster=0xb04; "
                        + "reporting not configured, reports stay unscaled");
    }

    @Test
    @DisplayName("R3: what the device declared is handed to the store — once per "
            + "endpoint, both clusters in ONE record")
    void formattingRead_isHandedToTheStore() {
        FakeFormattingStore store = new FakeFormattingStore();
        configurator = new ReportingConfigurator(ops, store);
        ops.reads.put(0x0B04, electricalRead(1, 100));
        ops.reads.put(0x0702, meteringRead(0x00, 1, 1_000_000));

        configurator.configureDevice(DEVICE, List.of(endpoint(0x0702, 0x0B04)),
                null);

        assertThat(store.learned).containsExactly(new Learned(DEVICE, 1,
                new MeteringFormatting(1, 100, 1, 10, 1, 1000, 1, 1_000_000,
                        0x00)));
    }

    @Test
    @DisplayName("R3: NOTHING readable hands NOTHING to the store — the next "
            + "rejoin reads again")
    void formattingUnreadable_handsNothingToTheStore() {
        FakeFormattingStore store = new FakeFormattingStore();
        configurator = new ReportingConfigurator(ops, store);

        configurator.configureDevice(DEVICE, List.of(endpoint(0x0702, 0x0B04)),
                null);

        assertThat(store.learned).isEmpty();
    }

    @Test
    @DisplayName("P7: a rejoin does NOT re-read — the CACHED formatting re-applies "
            + "the scaled configure; a fresh adoption always reads")
    void rejoin_usesCachedFormatting_noRead() {
        FakeFormattingStore store = new FakeFormattingStore();
        store.cachedByEndpoint.put(1, new MeteringFormatting(1, 100, 1, 10, 1,
                1000, 1, 1_000_000, 0x00));
        configurator = new ReportingConfigurator(ops, store);

        configurator.onRejoin(DEVICE, List.of(endpoint(0x0702, 0x0B04)), null);

        assertThat(ops.calls).as("zero read frames on the rejoin arm")
                .containsExactly(
                        "bind:702", "configure:702:5:3600:5000", "readback:702",
                        "bind:b04", "configure:b04:5:600:100", "readback:b04");
        assertThat(store.learned).as("nothing new was learned").isEmpty();

        ops.calls.clear();
        configurator.configureDevice(DEVICE, List.of(endpoint(0x0702, 0x0B04)),
                null);
        assertThat(ops.calls).as("adoption asks the device, whatever is cached")
                .startsWith(READ_ELECTRICAL, READ_METERING);
    }

    @Test
    @DisplayName("P7: a rejoin reads ONLY the cluster the cache lacks, and hands "
            + "the merged formatting back")
    void rejoin_readsOnlyTheClusterTheCacheLacks() {
        FakeFormattingStore store = new FakeFormattingStore();
        store.cachedByEndpoint.put(1, new MeteringFormatting(1, 100, 1, 10, 1,
                1000, null, null, null));
        configurator = new ReportingConfigurator(ops, store);
        ops.reads.put(0x0702, meteringRead(0x00, 1, 1_000));

        configurator.onRejoin(DEVICE, List.of(endpoint(0x0702, 0x0B04)), null);

        assertThat(ops.calls).startsWith(READ_METERING)
                .doesNotContain(READ_ELECTRICAL)
                .contains("configure:702:5:3600:5", "configure:b04:5:600:100");
        assertThat(store.learned).containsExactly(new Learned(DEVICE, 1,
                new MeteringFormatting(1, 100, 1, 10, 1, 1000, 1, 1_000, 0x00)));
    }

    @Test
    @DisplayName("R5: a profile override moves the INTERVALS only — the change is "
            + "always the scaled engineering value")
    void profileOverride_appliesToIntervalsOnly_changeStaysScaled() {
        ops.reads.put(0x0B04, electricalRead(1, 100));
        DeviceProfile profile = new DeviceProfile("p", Set.of(
                new ExactModel("Acme", "Meter")),
                DeviceCategory.MINOR_QUIRKS, null,
                Map.of(0x0B04, new ReportingOverride(0x0B04, 10, 300, 7)),
                null, null, null, null, null);

        configurator.configureDevice(DEVICE, List.of(endpoint(0x0B04)), profile);

        assertThat(ops.calls).contains("configure:b04:10:300:100");
    }

    @Test
    @DisplayName("the configure_reporting skip sends a metering cluster NOTHING — "
            + "no read either (the profile's NO-commands contract)")
    void skipProfile_sendsNothingToMeteringClusters() {
        DeviceProfile profile = new DeviceProfile("aqara", Set.of(
                new ExactModel("LUMI", "lumi.plug")),
                DeviceCategory.MIXED_CUSTOM, null, null, "xiaomi_ff01",
                Set.of("configure_reporting"), null, null, null);

        List<ReportingPostureFact> facts = configurator.configureDevice(DEVICE,
                List.of(endpoint(0x0702, 0x0B04)), profile);

        assertThat(ops.calls).isEmpty();
        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(ReportingPostureFact::reportingPosture)
                .containsOnly(ReportingPosture.PERIODIC);
    }

    @Test
    @DisplayName("an endpoint WITHOUT a metering cluster is never asked for "
            + "formatting")
    void endpointWithoutMeteringClusters_isNeverRead() {
        ops.readbacks.put(0x0406,
                new ReportingOps.ReportingConfigRecord(0, 3600, 0));

        configurator.configureDevice(DEVICE, List.of(endpoint(0x0406)), null);

        assertThat(ops.calls).containsExactly(
                "bind:406", "configure:406:0:3600:0", "readback:406");
    }

    // ── MeteringFormatting — what the two reads build ────────────────────────

    @Test
    @DisplayName("MeteringFormatting.ofReads: a pair is present only when BOTH "
            + "halves were read non-zero; absence is per pair; equal knowledge "
            + "is equal")
    void meteringFormatting_ofReads_presenceIsPerPair() {
        Map<Integer, Object> electrical = electricalRead(1, 100);
        electrical.remove(0x0603);                       // current divisor unread
        electrical.put(0x0601, 0L);                      // voltage divisor zero

        MeteringFormatting formatting = MeteringFormatting.ofReads(electrical,
                meteringRead(0x00, 1, 1_000_000));

        assertThat(formatting).isEqualTo(new MeteringFormatting(1, 100, 0, 0, 0,
                0, 1, 1_000_000, 0x00));
        assertThat(formatting.hasElectrical()).isTrue();
        assertThat(formatting.hasVoltage()).isFalse();
        assertThat(formatting.hasCurrent()).isFalse();
        assertThat(formatting.hasMetering()).isTrue();
        assertThat(formatting.kilowattHours()).isTrue();
        assertThat(MeteringFormatting.ofReads(null, null))
                .isEqualTo(MeteringFormatting.unknown());
        assertThat(MeteringFormatting.unknown().hasElectrical()).isFalse();
        assertThat(MeteringFormatting.unknown().hasMetering()).isFalse();
    }

    @Test
    @DisplayName("MeteringFormatting.ofReads: a value outside its wire type "
            + "(uint16 / uint24) or of the wrong Java type is unread, never "
            + "truncated")
    void meteringFormatting_ofReads_rejectsOutOfTypeValues() {
        Map<Integer, Object> electrical = electricalRead(1, 0x1_0000L);
        Map<Integer, Object> metering = meteringRead(0x00, 1, 0x100_0000L);
        metering.put(0x0300, "kWh");

        MeteringFormatting formatting =
                MeteringFormatting.ofReads(electrical, metering);

        assertThat(formatting.hasElectrical()).isFalse();
        assertThat(formatting.hasMetering()).isFalse();
        assertThat(formatting.unitOfMeasure()).isNull();
    }

    @Test
    @DisplayName("MeteringFormatting.filledFrom: a pair present is never replaced; "
            + "a pair absent is filled")
    void meteringFormatting_filledFrom_mergesPerPair() {
        MeteringFormatting cached = new MeteringFormatting(1, 100, 0, 0, 0, 0,
                null, null, null);
        MeteringFormatting read = new MeteringFormatting(1, 10, 1, 10, 0, 0, 1,
                1_000, 0x00);

        assertThat(cached.filledFrom(read)).isEqualTo(new MeteringFormatting(
                1, 100, 1, 10, 0, 0, 1, 1_000, 0x00));
    }
}

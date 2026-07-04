/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * {@code configure_reporting} skip, the IAS CIE tolerate-path, and
 * re-apply-on-rejoin.
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
        assertThat(facts.get(0).note()).contains("enroll");
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
}

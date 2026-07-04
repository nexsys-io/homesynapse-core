/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Optional;

/**
 * The reporting-configuration seam (Doc 08 §3.7): bind, Configure Reporting,
 * Read Reporting Configuration read-back, and the IAS CIE write (§3.12). The
 * EZSP binding lands with the M9.4 ZCL write path; M9.3 proves the configurator
 * logic against fakes and records posture facts.
 *
 * <p>No checked exception crosses this seam; failures are typed results (the
 * ZCL failure surface is NAMED — {@code UNSUPPORTED_ATTRIBUTE} vs
 * {@code UNREPORTABLE_ATTRIBUTE} vs a sleepy timeout — because each degrades
 * differently, dossier §E).
 */
interface ReportingOps {

    /** The Configure Reporting outcome classes the degrade ladder branches on. */
    enum ConfigureResult {
        /** The device accepted the configuration. */
        SUCCESS,
        /** ZCL 0x86: the attribute is not implemented. */
        UNSUPPORTED_ATTRIBUTE,
        /** ZCL 0x8C: the attribute exists but cannot be reported. */
        UNREPORTABLE_ATTRIBUTE,
        /** No response (the measured sleepy-device write timeout). */
        TIMEOUT
    }

    /**
     * A read-back reporting configuration.
     *
     * @param minInterval the minimum reporting interval in seconds
     * @param maxInterval the maximum reporting interval in seconds
     *        ({@code 0xFFFF} = reporting off)
     * @param reportableChange the reportable-change threshold
     */
    record ReportingConfigRecord(int minInterval, int maxInterval,
            int reportableChange) {
    }

    /**
     * Binds a cluster to the coordinator (ZDO 0x0021).
     *
     * @param device the target device
     * @param endpoint the target endpoint
     * @param clusterId the cluster to bind
     * @return {@code true} if the bind succeeded
     */
    boolean bind(IEEEAddress device, int endpoint, int clusterId);

    /**
     * Sends Configure Reporting (ZCL 0x06) for one attribute.
     *
     * @param device the target device
     * @param endpoint the target endpoint
     * @param clusterId the cluster
     * @param attributeId the attribute
     * @param dataType the ZCL data type of the attribute
     * @param minInterval the minimum interval in seconds
     * @param maxInterval the maximum interval in seconds
     * @param reportableChange the change threshold ({@code 0} for discrete)
     * @return the typed outcome
     */
    ConfigureResult configureReporting(IEEEAddress device, int endpoint,
            int clusterId, int attributeId, int dataType, int minInterval,
            int maxInterval, int reportableChange);

    /**
     * Reads back the effective reporting configuration (ZCL 0x08) — the verify
     * step; ACK-lies is first-class.
     *
     * @param device the target device
     * @param endpoint the target endpoint
     * @param clusterId the cluster
     * @param attributeId the attribute
     * @return the effective configuration, or empty when the read fails
     */
    Optional<ReportingConfigRecord> readReportingConfiguration(IEEEAddress device,
            int endpoint, int clusterId, int attributeId);

    /**
     * Writes the coordinator's IEEE to the device's {@code IAS_CIE_Address}
     * (§3.12 step 1) — ATTEMPTED, recorded, never an interview/adoption gate.
     *
     * @param device the target device
     * @param endpoint the IAS Zone endpoint
     * @return {@code true} if the write succeeded
     */
    boolean writeCieAddress(IEEEAddress device, int endpoint);
}

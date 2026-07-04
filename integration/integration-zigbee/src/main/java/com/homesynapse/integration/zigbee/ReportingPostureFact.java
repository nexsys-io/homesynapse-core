/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * A recorded per-cluster reporting-posture DEVICE FACT (Doc 08 §3.7): the
 * classification the configurator measured on this device — what feeds the
 * AMD-97 {@code confirmability} verdict the M9.4 confirmation engine consumes.
 * Facts re-record on every rejoin/OTA re-characterization (the fingerprint may
 * have changed — Q10).
 *
 * <p>Thread-safe: immutable record.
 *
 * @param device the characterized device, never {@code null}
 * @param endpoint the endpoint
 * @param clusterId the cluster
 * @param attributeId the reporting attribute
 * @param reportsAuthoritative how the attribute actually arrives
 * @param reportingPosture the cadence the device actually exhibits
 * @param note the measurement note (ACK-lies, skip reason, enrollment outcome);
 *        {@code null} when unremarkable
 */
record ReportingPostureFact(
        IEEEAddress device,
        int endpoint,
        int clusterId,
        int attributeId,
        ReportsAuthoritative reportsAuthoritative,
        ReportingPosture reportingPosture,
        String note) {

    /**
     * Creates a posture fact.
     *
     * @param device never {@code null}
     * @param endpoint the endpoint
     * @param clusterId the cluster
     * @param attributeId the attribute
     * @param reportsAuthoritative never {@code null}
     * @param reportingPosture never {@code null}
     * @param note {@code null} when unremarkable
     */
    ReportingPostureFact {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(reportsAuthoritative,
                "reportsAuthoritative must not be null");
        Objects.requireNonNull(reportingPosture,
                "reportingPosture must not be null");
    }
}

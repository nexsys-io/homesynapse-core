/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The interview step seam (Doc 08 §3.4 steps 2–5): one method per ZDO/ZCL
 * request-response exchange, each bounded by the caller-supplied timeout.
 *
 * <p>An empty result means the step FAILED (timeout, protocol rejection, or a
 * malformed response) — the retry policy is the state machine's and the pending
 * queue's, never the seam's. No checked exception crosses this seam (the M9.2
 * checked-exception seam discipline: interview failures surface through the
 * result, never as new checked throws).
 *
 * <p>Implementations: the EZSP binding inside {@code EzspCoordinatorProtocol}
 * (production) and scripted fakes (tests).
 */
interface InterviewOps {

    /**
     * The Basic-cluster identity read result (Doc 08 §3.4 step 5).
     *
     * @param manufacturerName Basic 0x0004, never {@code null}
     * @param modelIdentifier Basic 0x0005, never {@code null}
     * @param powerSource Basic 0x0007 (ZCL PowerSource enum value)
     * @param swBuildId Basic 0x4000; {@code null} when the device omits it
     */
    record BasicInfo(String manufacturerName, String modelIdentifier,
            int powerSource, String swBuildId) {

        /**
         * Creates the identity read result.
         *
         * @param manufacturerName never {@code null}
         * @param modelIdentifier never {@code null}
         * @param powerSource the ZCL PowerSource value
         * @param swBuildId {@code null} when absent
         */
        public BasicInfo {
            Objects.requireNonNull(manufacturerName,
                    "manufacturerName must not be null");
            Objects.requireNonNull(modelIdentifier,
                    "modelIdentifier must not be null");
        }
    }

    /**
     * Requests the ZDO Node Descriptor (step 2).
     *
     * @param networkAddress the target's 16-bit network address
     * @param timeoutMillis the step budget
     * @return the descriptor, or empty on step failure
     */
    Optional<NodeDescriptor> nodeDescriptor(int networkAddress, long timeoutMillis);

    /**
     * Requests the ZDO Active Endpoints list (step 3).
     *
     * @param networkAddress the target's 16-bit network address
     * @param timeoutMillis the step budget
     * @return the endpoint ids in wire order, or empty on step failure
     */
    Optional<List<Integer>> activeEndpoints(int networkAddress, long timeoutMillis);

    /**
     * Requests one endpoint's ZDO Simple Descriptor (step 4).
     *
     * @param networkAddress the target's 16-bit network address
     * @param endpoint the endpoint to describe (1–240)
     * @param timeoutMillis the step budget
     * @return the descriptor, or empty on step failure
     */
    Optional<EndpointDescriptor> simpleDescriptor(int networkAddress, int endpoint,
            long timeoutMillis);

    /**
     * Reads the Basic-cluster identity attributes (step 5) from an endpoint.
     *
     * @param networkAddress the target's 16-bit network address
     * @param endpoint the application endpoint to read from
     * @param timeoutMillis the step budget
     * @return the identity, or empty on step failure
     */
    Optional<BasicInfo> readBasic(int networkAddress, int endpoint,
            long timeoutMillis);
}

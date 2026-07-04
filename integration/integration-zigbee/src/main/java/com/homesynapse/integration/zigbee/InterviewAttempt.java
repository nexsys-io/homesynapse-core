/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of ONE interview attempt (Doc 08 §3.4): whatever metadata the
 * attempt gathered, plus the step it failed at (if any). The pending queue owns
 * the retry ladder; the ingestion/adoption slice renders the final
 * {@code device_discovered} from the last attempt's data.
 *
 * <p>The frozen {@link InterviewResult} requires a non-empty endpoint list —
 * an attempt that gathered no endpoints therefore yields
 * {@link #toInterviewResult() empty}; an attempt with endpoints but a failed
 * Basic read yields a PARTIAL result with empty identity strings (the adoption
 * layer maps missing identity to its sentinel; adoptability is the Device
 * Model's call downstream).
 *
 * <p>Thread-safe: immutable record with a defensively copied list.
 *
 * @param ieeeAddress the interviewed device, never {@code null}
 * @param networkAddress the device's 16-bit network address
 * @param nodeDescriptor the ZDO node descriptor; {@code null} if that step failed
 * @param endpoints the gathered application endpoint descriptors (Green Power
 *        excluded), never {@code null}, possibly empty
 * @param manufacturerName Basic 0x0004; {@code null} if the read failed
 * @param modelIdentifier Basic 0x0005; {@code null} if the read failed
 * @param powerSource Basic 0x0007; {@code 0} if unread
 * @param swBuildId Basic 0x4000; {@code null} if absent or unread
 * @param failedStep the step this attempt failed at; {@code null} when complete
 */
record InterviewAttempt(
        IEEEAddress ieeeAddress,
        int networkAddress,
        NodeDescriptor nodeDescriptor,
        List<EndpointDescriptor> endpoints,
        String manufacturerName,
        String modelIdentifier,
        int powerSource,
        String swBuildId,
        InterviewStateMachine.Step failedStep) {

    /**
     * Creates an attempt outcome.
     *
     * @param ieeeAddress never {@code null}
     * @param networkAddress the 16-bit network address
     * @param nodeDescriptor {@code null} if the step failed
     * @param endpoints never {@code null}
     * @param manufacturerName {@code null} if unread
     * @param modelIdentifier {@code null} if unread
     * @param powerSource {@code 0} if unread
     * @param swBuildId {@code null} if absent
     * @param failedStep {@code null} when the attempt completed
     */
    InterviewAttempt {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress must not be null");
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        endpoints = List.copyOf(endpoints);
    }

    /** Returns {@code true} when every step succeeded. */
    boolean complete() {
        return failedStep == null;
    }

    /**
     * Renders the frozen {@link InterviewResult} when one is constructible.
     *
     * @return the result (COMPLETE, or PARTIAL with empty identity strings for a
     *         failed Basic read), or empty when no endpoints were gathered
     */
    Optional<InterviewResult> toInterviewResult() {
        if (endpoints.isEmpty() || nodeDescriptor == null) {
            return Optional.empty();
        }
        return Optional.of(new InterviewResult(
                ieeeAddress,
                networkAddress,
                nodeDescriptor,
                endpoints,
                manufacturerName != null ? manufacturerName : "",
                modelIdentifier != null ? modelIdentifier : "",
                powerSource,
                complete() ? InterviewStatus.COMPLETE : InterviewStatus.PARTIAL));
    }
}

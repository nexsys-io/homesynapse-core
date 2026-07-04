/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Doc 08 §3.4 interview sequencer: node-desc → active-EPs → simple-desc per
 * endpoint → Basic-cluster reads, with the 10 s per-step and 60 s whole-interview
 * budgets, both Clock-derived (never wall-clock).
 *
 * <p>One call = ONE attempt. The 3-retry/5-15-30 s backoff ladder and the sleepy
 * park/resume machine live in {@link PendingInterviewQueue} as clock-scheduled
 * eligibility — no thread ever sleeps between attempts.
 *
 * <p><strong>Measured field truths (charter, binding):</strong> the Basic read
 * targets the FIRST APPLICATION endpoint in wire order (the Hue light lives on
 * EP 11, not EP 1); Green Power EP 242 is skipped gracefully — never
 * descriptor-queried, never an interview failure. Unknown manufacturer clusters
 * (0xFC01/0xFC04/0xFC57 measured) ride through in the descriptors untouched —
 * ignoring them is the cluster-handler layer's business, never the interview's.
 *
 * <p>Thread-safe: stateless between calls; each attempt is confined to the
 * calling thread.
 *
 * @see InterviewOps
 * @see PendingInterviewQueue
 */
final class InterviewStateMachine {

    /** Per-step ZDO/ZCL request-response budget (Doc 08 §3.4). */
    static final long STEP_TIMEOUT_MILLIS = 10_000;
    /** Whole-interview budget (Doc 08 §3.4). */
    static final long WHOLE_INTERVIEW_TIMEOUT_MILLIS = 60_000;
    /** The Green Power endpoint — present on Hue and skipped gracefully. */
    static final int GREEN_POWER_ENDPOINT_ID = 242;
    /** The Green Power profile id (defense beyond the well-known endpoint id). */
    static final int GREEN_POWER_PROFILE_ID = 0xA1E0;

    /** The interview steps, in Doc 08 §3.4 order, plus the whole-budget guard. */
    enum Step {
        NODE_DESCRIPTOR,
        ACTIVE_ENDPOINTS,
        SIMPLE_DESCRIPTOR,
        BASIC_ATTRIBUTES,
        WHOLE_INTERVIEW_TIMEOUT
    }

    private static final Logger log =
            LoggerFactory.getLogger(InterviewStateMachine.class);

    private final InterviewOps ops;
    private final Clock clock;

    /**
     * Creates the sequencer.
     *
     * @param ops the step seam, never {@code null}
     * @param clock the time source, never {@code null}
     */
    InterviewStateMachine(InterviewOps ops, Clock clock) {
        this.ops = Objects.requireNonNull(ops, "ops");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs one interview attempt.
     *
     * @param ieeeAddress the device to interview, never {@code null}
     * @param networkAddress the device's 16-bit network address
     * @return the attempt outcome with whatever metadata was gathered
     */
    InterviewAttempt attempt(IEEEAddress ieeeAddress, int networkAddress) {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress");
        Instant deadline = clock.instant()
                .plusMillis(WHOLE_INTERVIEW_TIMEOUT_MILLIS);

        NodeDescriptor nodeDescriptor = null;
        List<EndpointDescriptor> endpoints = new ArrayList<>();
        String manufacturerName = null;
        String modelIdentifier = null;
        int powerSource = 0;
        String swBuildId = null;

        long budget = stepBudget(deadline);
        if (budget <= 0) {
            return failed(ieeeAddress, networkAddress, null, endpoints,
                    Step.WHOLE_INTERVIEW_TIMEOUT);
        }
        Optional<NodeDescriptor> descriptor =
                ops.nodeDescriptor(networkAddress, budget);
        if (descriptor.isEmpty()) {
            return failed(ieeeAddress, networkAddress, null, endpoints,
                    Step.NODE_DESCRIPTOR);
        }
        nodeDescriptor = descriptor.get();

        budget = stepBudget(deadline);
        if (budget <= 0) {
            return failed(ieeeAddress, networkAddress, nodeDescriptor, endpoints,
                    Step.WHOLE_INTERVIEW_TIMEOUT);
        }
        Optional<List<Integer>> activeEndpoints =
                ops.activeEndpoints(networkAddress, budget);
        if (activeEndpoints.isEmpty()) {
            return failed(ieeeAddress, networkAddress, nodeDescriptor, endpoints,
                    Step.ACTIVE_ENDPOINTS);
        }

        for (int endpoint : activeEndpoints.get()) {
            if (endpoint == GREEN_POWER_ENDPOINT_ID) {
                log.debug("Interview device={} skips Green Power endpoint {}",
                        ieeeAddress, endpoint);
                continue;
            }
            budget = stepBudget(deadline);
            if (budget <= 0) {
                return failed(ieeeAddress, networkAddress, nodeDescriptor,
                        endpoints, Step.WHOLE_INTERVIEW_TIMEOUT);
            }
            Optional<EndpointDescriptor> simple =
                    ops.simpleDescriptor(networkAddress, endpoint, budget);
            if (simple.isEmpty()) {
                return failed(ieeeAddress, networkAddress, nodeDescriptor,
                        endpoints, Step.SIMPLE_DESCRIPTOR);
            }
            if (simple.get().profileId() == GREEN_POWER_PROFILE_ID) {
                log.debug("Interview device={} skips Green Power profile on "
                        + "endpoint {}", ieeeAddress, endpoint);
                continue;
            }
            endpoints.add(simple.get());
        }

        Optional<EndpointDescriptor> basicEndpoint = endpoints.stream().findFirst();
        if (basicEndpoint.isEmpty()) {
            log.warn("Interview device={} found no application endpoint; "
                    + "gathered endpoints: {}", ieeeAddress,
                    activeEndpoints.get());
            return failed(ieeeAddress, networkAddress, nodeDescriptor, endpoints,
                    Step.BASIC_ATTRIBUTES);
        }

        budget = stepBudget(deadline);
        if (budget <= 0) {
            return failed(ieeeAddress, networkAddress, nodeDescriptor, endpoints,
                    Step.WHOLE_INTERVIEW_TIMEOUT);
        }
        Optional<InterviewOps.BasicInfo> basic = ops.readBasic(networkAddress,
                basicEndpoint.get().endpointId(), budget);
        if (basic.isEmpty()) {
            return failed(ieeeAddress, networkAddress, nodeDescriptor, endpoints,
                    Step.BASIC_ATTRIBUTES);
        }
        manufacturerName = basic.get().manufacturerName();
        modelIdentifier = basic.get().modelIdentifier();
        powerSource = basic.get().powerSource();
        swBuildId = basic.get().swBuildId();

        return new InterviewAttempt(ieeeAddress, networkAddress, nodeDescriptor,
                endpoints, manufacturerName, modelIdentifier, powerSource,
                swBuildId, null);
    }

    private long stepBudget(Instant deadline) {
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        return Math.min(remaining, STEP_TIMEOUT_MILLIS);
    }

    private static InterviewAttempt failed(IEEEAddress ieeeAddress,
            int networkAddress, NodeDescriptor nodeDescriptor,
            List<EndpointDescriptor> endpoints, Step step) {
        log.debug("Interview device={} failed at step {}", ieeeAddress, step);
        return new InterviewAttempt(ieeeAddress, networkAddress, nodeDescriptor,
                endpoints, null, null, 0, null, step);
    }
}

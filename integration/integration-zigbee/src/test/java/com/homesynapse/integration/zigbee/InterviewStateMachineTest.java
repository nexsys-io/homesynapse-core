/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InterviewStateMachine} tests on synthetic descriptor sequences
 * (Doc 08 §3.4): the step order, per-step/whole-interview budgets, PARTIAL
 * semantics, EP-11 Basic-read selection (the measured Hue truth — the light
 * lives on EP 11, not EP 1), and the Green-Power-242 graceful skip.
 *
 * <p>The fake ops ADVANCE the injected {@link TestClock} (the M9.2 fake-channel
 * pattern) — zero real waits, zero sleeping.
 */
class InterviewStateMachineTest {

    private static final IEEEAddress IEEE = new IEEEAddress(0x00124B0012345678L);
    private static final int NWK = 0x6B9A;

    private TestClock clock;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
    }

    /** A scriptable {@link InterviewOps} whose steps advance the test clock. */
    private final class FakeOps implements InterviewOps {
        NodeDescriptor nodeDescriptor = new NodeDescriptor(1, 0x100B, 82, 142);
        List<Integer> activeEndpoints = List.of(11, 242);
        final Map<Integer, EndpointDescriptor> simpleDescriptors = new HashMap<>();
        InterviewOps.BasicInfo basicInfo = new InterviewOps.BasicInfo(
                "Signify Netherlands B.V.", "LCA017", 1, "0x01000D08");
        Duration perStepElapsed = Duration.ofMillis(100);
        boolean failNodeDescriptor;
        boolean failActiveEndpoints;
        boolean failBasic;
        final List<Integer> basicReadEndpoints = new ArrayList<>();

        FakeOps() {
            simpleDescriptors.put(11, new EndpointDescriptor(11, 0x0104, 0x010D,
                    List.of(0x0000, 0x0006, 0x0008, 0x0300), List.of(0x0019)));
        }

        private <T> Optional<T> step(Optional<T> result) {
            clock.advance(perStepElapsed);
            return result;
        }

        @Override
        public Optional<NodeDescriptor> nodeDescriptor(int networkAddress,
                long timeoutMillis) {
            return step(failNodeDescriptor ? Optional.empty()
                    : Optional.of(nodeDescriptor));
        }

        @Override
        public Optional<List<Integer>> activeEndpoints(int networkAddress,
                long timeoutMillis) {
            return step(failActiveEndpoints ? Optional.empty()
                    : Optional.of(activeEndpoints));
        }

        @Override
        public Optional<EndpointDescriptor> simpleDescriptor(int networkAddress,
                int endpoint, long timeoutMillis) {
            return step(Optional.ofNullable(simpleDescriptors.get(endpoint)));
        }

        @Override
        public Optional<BasicInfo> readBasic(int networkAddress, int endpoint,
                long timeoutMillis) {
            basicReadEndpoints.add(endpoint);
            return step(failBasic ? Optional.empty() : Optional.of(basicInfo));
        }
    }

    @Test
    @DisplayName("happy path: COMPLETE with node descriptor, endpoints, and Basic identity")
    void happyPath() {
        FakeOps ops = new FakeOps();
        InterviewStateMachine machine = new InterviewStateMachine(ops, clock);

        InterviewAttempt attempt = machine.attempt(IEEE, NWK);

        assertThat(attempt.complete()).isTrue();
        assertThat(attempt.manufacturerName()).isEqualTo("Signify Netherlands B.V.");
        assertThat(attempt.modelIdentifier()).isEqualTo("LCA017");
        assertThat(attempt.endpoints()).hasSize(1);
        InterviewResult result = attempt.toInterviewResult().orElseThrow();
        assertThat(result.interviewStatus()).isEqualTo(InterviewStatus.COMPLETE);
        assertThat(result.powerSource()).isEqualTo(1);
    }

    @Test
    @DisplayName("EP-11 selection: Basic reads from the first APPLICATION endpoint, not EP 1 by assumption")
    void basicReadsFromFirstApplicationEndpoint() {
        FakeOps ops = new FakeOps();
        InterviewStateMachine machine = new InterviewStateMachine(ops, clock);

        machine.attempt(IEEE, NWK);

        assertThat(ops.basicReadEndpoints).containsExactly(11);
    }

    @Test
    @DisplayName("Green Power EP 242 is skipped gracefully — never descriptor-queried, never a failure")
    void greenPowerEndpointSkipped() {
        FakeOps ops = new FakeOps();
        InterviewStateMachine machine = new InterviewStateMachine(ops, clock);

        InterviewAttempt attempt = machine.attempt(IEEE, NWK);

        assertThat(attempt.complete()).isTrue();
        assertThat(attempt.endpoints())
                .extracting(EndpointDescriptor::endpointId)
                .containsExactly(11);
    }

    @Test
    @DisplayName("a node-descriptor step failure aborts the attempt at that step")
    void nodeDescriptorFailureAborts() {
        FakeOps ops = new FakeOps();
        ops.failNodeDescriptor = true;
        InterviewStateMachine machine = new InterviewStateMachine(ops, clock);

        InterviewAttempt attempt = machine.attempt(IEEE, NWK);

        assertThat(attempt.complete()).isFalse();
        assertThat(attempt.failedStep())
                .isEqualTo(InterviewStateMachine.Step.NODE_DESCRIPTOR);
        assertThat(attempt.toInterviewResult())
                .as("no endpoints were gathered — no InterviewResult is constructible")
                .isEmpty();
    }

    @Test
    @DisplayName("a Basic-read failure yields PARTIAL: endpoints kept, identity empty")
    void basicFailureYieldsPartial() {
        FakeOps ops = new FakeOps();
        ops.failBasic = true;
        InterviewStateMachine machine = new InterviewStateMachine(ops, clock);

        InterviewAttempt attempt = machine.attempt(IEEE, NWK);

        assertThat(attempt.complete()).isFalse();
        assertThat(attempt.failedStep())
                .isEqualTo(InterviewStateMachine.Step.BASIC_ATTRIBUTES);
        InterviewResult result = attempt.toInterviewResult().orElseThrow();
        assertThat(result.interviewStatus()).isEqualTo(InterviewStatus.PARTIAL);
        assertThat(result.endpoints()).hasSize(1);
        assertThat(result.manufacturerName()).isEmpty();
        assertThat(result.modelIdentifier()).isEmpty();
    }

    @Test
    @DisplayName("the whole-interview 60 s budget aborts a crawling interview")
    void wholeInterviewTimeout() {
        FakeOps ops = new FakeOps();
        // Each step consumes just under the 10 s per-step budget; the 60 s whole
        // budget expires during the per-endpoint descriptor walk.
        ops.perStepElapsed = Duration.ofMillis(9_500);
        ops.activeEndpoints = List.of(1, 2, 3, 4, 5, 6, 7, 8);
        for (int ep = 1; ep <= 8; ep++) {
            ops.simpleDescriptors.put(ep, new EndpointDescriptor(ep, 0x0104, 0x0100,
                    List.of(0x0006), List.of()));
        }
        InterviewStateMachine machine = new InterviewStateMachine(ops, clock);

        InterviewAttempt attempt = machine.attempt(IEEE, NWK);

        assertThat(attempt.complete()).isFalse();
        assertThat(attempt.failedStep())
                .isEqualTo(InterviewStateMachine.Step.WHOLE_INTERVIEW_TIMEOUT);
    }

    @Test
    @DisplayName("a device with only a Green Power endpoint yields PARTIAL, not a crash")
    void onlyGreenPowerEndpointYieldsPartial() {
        FakeOps ops = new FakeOps();
        ops.activeEndpoints = List.of(242);
        InterviewStateMachine machine = new InterviewStateMachine(ops, clock);

        InterviewAttempt attempt = machine.attempt(IEEE, NWK);

        assertThat(attempt.complete()).isFalse();
        assertThat(attempt.toInterviewResult()).isEmpty();
    }
}

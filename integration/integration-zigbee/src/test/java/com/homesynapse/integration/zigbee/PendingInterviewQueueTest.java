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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PendingInterviewQueue} tests: the Doc 08 §3.4 retry schedule (3 retries
 * at 5/15/30 s), the sleepy park → resume-on-ANY-frame machine, and the 24 h
 * expiry — all clock-driven, no sleeping.
 */
class PendingInterviewQueueTest {

    private static final IEEEAddress IEEE = new IEEEAddress(0x00124B0012345678L);
    private static final int NWK = 0x6B9A;

    private TestClock clock;
    private PendingInterviewQueue queue;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        queue = new PendingInterviewQueue(clock);
    }

    @Test
    @DisplayName("a scheduled interview is due immediately")
    void scheduledIsDueImmediately() {
        queue.schedule(IEEE, NWK);

        assertThat(queue.due()).extracting(PendingInterviewQueue.Pending::ieeeAddress)
                .containsExactly(IEEE);
    }

    @Test
    @DisplayName("failures back off at 5 s, 15 s, 30 s (Doc 08 §3.4)")
    void retryBackoffSchedule() {
        queue.schedule(IEEE, NWK);
        long[] backoffSeconds = {5, 15, 30};

        for (long backoff : backoffSeconds) {
            queue.recordFailure(IEEE);
            assertThat(queue.due()).as("not eligible during the %ds backoff", backoff)
                    .isEmpty();
            clock.advance(Duration.ofSeconds(backoff - 1));
            assertThat(queue.due()).isEmpty();
            clock.advance(Duration.ofSeconds(1));
            assertThat(queue.due())
                    .extracting(PendingInterviewQueue.Pending::ieeeAddress)
                    .containsExactly(IEEE);
        }
    }

    @Test
    @DisplayName("after the third failed retry the interview parks (sleepy — resume on frame only)")
    void parksAfterRetriesExhausted() {
        queue.schedule(IEEE, NWK);
        for (int i = 0; i < 4; i++) {
            queue.recordFailure(IEEE);
        }

        clock.advance(Duration.ofHours(1));

        assertThat(queue.due()).isEmpty();
        assertThat(queue.isParked(IEEE)).isTrue();
        assertThat(queue.isPending(IEEE)).isTrue();
    }

    @Test
    @DisplayName("ANY frame from a parked device resumes its interview immediately")
    void frameResumesParkedInterview() {
        queue.schedule(IEEE, NWK);
        for (int i = 0; i < 4; i++) {
            queue.recordFailure(IEEE);
        }

        queue.onFrameReceived(IEEE);

        assertThat(queue.due())
                .extracting(PendingInterviewQueue.Pending::ieeeAddress)
                .containsExactly(IEEE);
        assertThat(queue.isParked(IEEE)).isFalse();
    }

    @Test
    @DisplayName("a frame from a device in backoff also makes it eligible (the wake signal)")
    void frameShortCircuitsBackoff() {
        queue.schedule(IEEE, NWK);
        queue.recordFailure(IEEE);
        assertThat(queue.due()).isEmpty();

        queue.onFrameReceived(IEEE);

        assertThat(queue.due()).hasSize(1);
    }

    @Test
    @DisplayName("pending interviews expire after 24 h (behaviorally: removed and reported)")
    void pendingExpiresAfter24Hours() {
        queue.schedule(IEEE, NWK);
        for (int i = 0; i < 4; i++) {
            queue.recordFailure(IEEE);
        }

        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(queue.expireStale()).containsExactly(IEEE);
        assertThat(queue.isPending(IEEE)).isFalse();
        assertThat(queue.due()).isEmpty();
    }

    @Test
    @DisplayName("a fresh interview does not expire early")
    void freshDoesNotExpire() {
        queue.schedule(IEEE, NWK);

        clock.advance(Duration.ofHours(23));

        assertThat(queue.expireStale()).isEmpty();
        assertThat(queue.isPending(IEEE)).isTrue();
    }

    @Test
    @DisplayName("completion removes the entry")
    void completeRemoves() {
        queue.schedule(IEEE, NWK);

        queue.complete(IEEE);

        assertThat(queue.isPending(IEEE)).isFalse();
        assertThat(queue.due()).isEmpty();
    }

    @Test
    @DisplayName("re-announce reschedules with fresh attempts (rejoin resets the ladder)")
    void rescheduleResetsAttempts() {
        queue.schedule(IEEE, NWK);
        for (int i = 0; i < 4; i++) {
            queue.recordFailure(IEEE);
        }
        assertThat(queue.isParked(IEEE)).isTrue();

        queue.schedule(IEEE, 0x1234);

        assertThat(queue.isParked(IEEE)).isFalse();
        assertThat(queue.due()).hasSize(1);
        assertThat(queue.due().get(0).networkAddress()).isEqualTo(0x1234);
    }

    // ── F-R4-1 (R-10 Row 10 (a)): the admission source rides the queue entry ─

    @Test
    @DisplayName("F-R4-1: schedule(ieee, nwk) records the ANNOUNCE source (the "
            + "pre-existing callers byte-unchanged); the rejoin overload records REJOIN; "
            + "the tokens are the device_proposed log vocabulary")
    void scheduleRecordsTheAdmissionSource() {
        queue.schedule(IEEE, NWK);
        assertThat(queue.due()).extracting(PendingInterviewQueue.Pending::source)
                .containsExactly(PendingInterviewQueue.Source.ANNOUNCE);

        queue.schedule(IEEE, NWK, PendingInterviewQueue.Source.REJOIN);
        assertThat(queue.due()).extracting(PendingInterviewQueue.Pending::source)
                .containsExactly(PendingInterviewQueue.Source.REJOIN);

        assertThat(PendingInterviewQueue.Source.ANNOUNCE.token()).isEqualTo("announce");
        assertThat(PendingInterviewQueue.Source.REJOIN.token()).isEqualTo("rejoin");
    }

    @Test
    @DisplayName("F-R4-1: the source survives a failed attempt and a wake, and a later "
            + "re-announce owns the provenance (put-replace resets it to ANNOUNCE)")
    void sourceSurvivesFailureAndWake_reannounceOwnsProvenance() {
        queue.schedule(IEEE, NWK, PendingInterviewQueue.Source.REJOIN);

        queue.recordFailure(IEEE);
        clock.advance(Duration.ofSeconds(5));
        assertThat(queue.due()).extracting(PendingInterviewQueue.Pending::source)
                .as("the retry keeps the admission source")
                .containsExactly(PendingInterviewQueue.Source.REJOIN);

        queue.recordFailure(IEEE);
        queue.onFrameReceived(IEEE);
        assertThat(queue.due()).extracting(PendingInterviewQueue.Pending::source)
                .as("the wake keeps the admission source")
                .containsExactly(PendingInterviewQueue.Source.REJOIN);

        queue.schedule(IEEE, NWK);
        assertThat(queue.due()).extracting(PendingInterviewQueue.Pending::source)
                .as("a rejoin is a fresh contact; a later announce is the newer truth")
                .containsExactly(PendingInterviewQueue.Source.ANNOUNCE);
    }
}

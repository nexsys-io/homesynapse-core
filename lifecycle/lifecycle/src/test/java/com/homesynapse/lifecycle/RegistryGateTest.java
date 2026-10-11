/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * REGISTRY-COLD-1 unit 2 (T2) — the progress-based registry gate: {@code awaitRegistryProjectionLive}
 * fails only if the replay STOPS MOVING, never after a fixed count. Progress is the snapshot's
 * {@code checkpoint} or {@code mode} differing from the previous poll's; the gate counts polls, not
 * time, and sleeps through an injected sleeper — these tests record the sleeps and never sleep.
 *
 * <p>The bus drives a moving replay's checkpoint every 200 matching events or every 2 s (AMD-38),
 * so a replay that is still paging shows progress to this gate where today's fixed 1,500 polls
 * (~30 s) threw regardless.</p>
 */
@DisplayName("RegistryGate — REGISTRY-COLD-1 unit 2: the registry gate fails on NO PROGRESS, never"
        + " on a fixed count")
final class RegistryGateTest {

    /** The production value: today's ~30 s at 20 ms, now counted from the last movement. */
    private static final int STALL_POLLS = 1_500;

    private final List<Long> sleeps = new ArrayList<>();
    private final AtomicInteger polls = new AtomicInteger();

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RegistryGateTest() {
    }

    @Test
    @DisplayName("G-1: a snapshot frozen in REPLAY at checkpoint 42 → throws after exactly stallPolls"
            + " polls; the message names the mode and the checkpoint; one sleep between polls")
    void frozenReplay_throwsAfterExactlyStallPolls() {
        Supplier<Optional<SubscriberSnapshot>> frozen =
                counting(n -> snapshot(SubscriberMode.REPLAY, 42L));

        assertThatThrownBy(() -> RegistryGate.awaitLive(frozen, STALL_POLLS, sleeps::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry projection made no progress for ~30s during startup "
                        + "(mode=REPLAY, checkpoint=42)");

        assertThat(polls.get()).as("polls before the throw").isEqualTo(STALL_POLLS);
        assertThat(sleeps).hasSize(STALL_POLLS - 1).allMatch(ms -> ms == RegistryGate.POLL_MILLIS);
    }

    @Test
    @DisplayName("G-2: a checkpoint that moves once every 1,000 polls for 5,000 polls, then LIVE →"
            + " returns (today's gate would have thrown at 1,500)")
    void crawlingCheckpoint_isProgress_andTheGateWaits() {
        Supplier<Optional<SubscriberSnapshot>> crawling = counting(n -> n > 5_000
                ? snapshot(SubscriberMode.LIVE, 5L)
                : snapshot(SubscriberMode.REPLAY, (n - 1) / 1_000));

        assertThatCode(() -> RegistryGate.awaitLive(crawling, STALL_POLLS, sleeps::add))
                .doesNotThrowAnyException();

        assertThat(polls.get()).isEqualTo(5_001);
        assertThat(polls.get()).as("well past the fixed count today's gate threw at")
                .isGreaterThan(STALL_POLLS);
        assertThat(sleeps).hasSize(5_000);
    }

    @Test
    @DisplayName("G-2b: a mode change is progress too — REPLAY → TRANSITION at poll 1,400 resets the"
            + " stall count, LIVE at poll 2,800 returns")
    void modeChange_isProgress() {
        Supplier<Optional<SubscriberSnapshot>> flipping = counting(n -> {
            if (n >= 2_800) {
                return snapshot(SubscriberMode.LIVE, 7L);
            }
            return snapshot(n >= 1_400 ? SubscriberMode.TRANSITION : SubscriberMode.REPLAY, 7L);
        });

        assertThatCode(() -> RegistryGate.awaitLive(flipping, STALL_POLLS, sleeps::add))
                .doesNotThrowAnyException();

        assertThat(polls.get()).isEqualTo(2_800);
    }

    @Test
    @DisplayName("G-3: LIVE on the first poll → returns with zero sleeps")
    void liveAtOnce_returnsWithoutSleeping() {
        Supplier<Optional<SubscriberSnapshot>> live = counting(n -> snapshot(SubscriberMode.LIVE, 9L));

        RegistryGate.awaitLive(live, STALL_POLLS, sleeps::add);

        assertThat(polls.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Counts the polls and hands the 1-based poll number to the scripted snapshot. */
    private Supplier<Optional<SubscriberSnapshot>> counting(PollScript script) {
        return () -> Optional.of(script.at(polls.incrementAndGet()));
    }

    @FunctionalInterface
    private interface PollScript {
        SubscriberSnapshot at(int poll);
    }

    private static SubscriberSnapshot snapshot(SubscriberMode mode, long checkpoint) {
        return new SubscriberSnapshot(RegistryProjectionSubscriber.SUBSCRIBER_ID, mode, checkpoint,
                0, 0, 0, null);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * The registry projection's boot gate (REGISTRY-COLD-1 unit 2, IR-148): the composition
 * root's wait for the {@code registry_projection} subscriber to reach {@code LIVE}, failing
 * only when the replay STOPS MOVING — never after a fixed count.
 *
 * <p>Progress is the bus snapshot's {@code checkpoint} or {@code mode} differing from the
 * previous poll's. A moving replay shows it: the driver writes the checkpoint every 200
 * matching events or every 2 s during REPLAY (AMD-38), and the mode flips REPLAY →
 * TRANSITION → LIVE. The gate gives up after {@code stallPolls} consecutive polls without
 * progress — {@value #DEFAULT_STALL_POLLS} in production, today's ~30 s, now counted from the
 * last movement rather than from the start. There is no absolute ceiling here: the outer bound
 * is the packaged unit's start timeout.</p>
 *
 * <p>Polls the bus's snapshot every {@value #POLL_MILLIS} ms through an injected sleeper and
 * counts polls, never time — the arch rule reaches {@code System.nanoTime}, and a fixed test
 * clock must still terminate it. Production passes {@link #sleep}; tests pass a recorder.</p>
 */
final class RegistryGate {

    /** The poll interval — today's 20 ms. */
    static final long POLL_MILLIS = 20L;

    /** ~30 s at {@value #POLL_MILLIS} ms — today's number. */
    static final int DEFAULT_STALL_POLLS = 1_500;

    private RegistryGate() {
    }

    /**
     * Blocks until the snapshot reads {@code LIVE}; fails after {@code stallPolls}
     * consecutive polls without progress.
     *
     * @param snapshot   the registry subscriber's bus snapshot, empty before registration
     *                   (an absent snapshot is a poll without progress)
     * @param stallPolls the consecutive polls without progress after which the gate gives up
     * @param sleeper    receives {@value #POLL_MILLIS} between polls
     * @throws IllegalStateException after {@code stallPolls} consecutive polls with no
     *         progress — the message names the mode and the checkpoint last read
     */
    static void awaitLive(Supplier<Optional<SubscriberSnapshot>> snapshot, int stallPolls,
            LongConsumer sleeper) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(sleeper, "sleeper");
        if (stallPolls < 1) {
            throw new IllegalArgumentException("stallPolls must be >= 1, got " + stallPolls);
        }
        // Progress = the snapshot's checkpoint or mode differs from the previous poll's. The
        // first poll has no previous and counts as no progress, so a subscriber that never
        // moves fails after exactly stallPolls polls — today's window, measured from the
        // last movement instead of from the start.
        SubscriberMode previousMode = null;
        long previousCheckpoint = -1L;
        boolean observed = false;
        int stalled = 0;
        while (true) {
            Optional<SubscriberSnapshot> current = snapshot.get();
            SubscriberMode mode = current.map(SubscriberSnapshot::mode).orElse(null);
            long checkpoint = current.map(SubscriberSnapshot::checkpoint).orElse(-1L);
            if (mode == SubscriberMode.LIVE) {
                return;
            }
            boolean progress = observed
                    && (mode != previousMode || checkpoint != previousCheckpoint);
            stalled = progress ? 0 : stalled + 1;
            if (stalled >= stallPolls) {
                throw new IllegalStateException(message(stallPolls, mode, checkpoint));
            }
            previousMode = mode;
            previousCheckpoint = checkpoint;
            observed = true;
            sleeper.accept(POLL_MILLIS);
        }
    }

    /** The production sleeper: a real-time sleep; an interrupt ends the boot as it does today. */
    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while awaiting registry-projection LIVE", e);
        }
    }

    private static String message(int stallPolls, SubscriberMode mode, long checkpoint) {
        return "registry projection made no progress for ~" + (stallPolls * POLL_MILLIS / 1_000L)
                + "s during startup (mode=" + (mode == null ? "absent" : mode)
                + ", checkpoint=" + checkpoint + ")";
    }
}

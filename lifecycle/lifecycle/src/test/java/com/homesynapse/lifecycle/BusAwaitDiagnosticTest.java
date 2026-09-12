/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/**
 * FIX-2a (A) — the await-timeout diagnostic's text is a contract: the first line
 * is the {@code <failure message>} a red run leaves in the JUnit XML, the
 * subscriber lines are what the hub greps beside it. This test pins the exact
 * string on a hand-built snapshot list; nothing else about the diagnostic is
 * tested here (the render is pure — no bus, no store, no clock).
 *
 * <p>FIX-2b-i (A): the subscriber line carries {@code pending=<n>} — the
 * snapshot's {@code pendingDepth} — right after {@code dlq=<n>}, so a red reads
 * whether a position was offered and not consumed ({@code pending ≥ 1} with the
 * checkpoint below the head) or never offered ({@code pending=0}).</p>
 */
@DisplayName("BusAwaitDiagnostic — the timeout text names the head, the awaited position and every subscriber's mode/checkpoint/dlq/pending/behind (FIX-2a A, FIX-2b-i A)")
final class BusAwaitDiagnosticTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    BusAwaitDiagnosticTest() {
    }

    @Test
    @DisplayName("render: one header line + one line per snapshot in list order, \\n-joined, no trailing newline; pending= right after dlq=; awaited prints as the position or 'none'")
    void render_headerThenOneLinePerSnapshot_exactText() {
        // Arrange — three snapshots: one behind the head by 2 (a parked entry, two
        // positions offered and not consumed), the atomic-checkpoint projection
        // resting at 0, one LIVE at head with an empty queue.
        List<SubscriberSnapshot> snapshots = List.of(
                new SubscriberSnapshot("automation_engine", SubscriberMode.LIVE, 40L, 1, 2, 0,
                        Instant.parse("2026-01-01T00:00:00Z")),
                new SubscriberSnapshot("state_projection", SubscriberMode.REPLAY, 0L, 0, 0, 0,
                        null),
                new SubscriberSnapshot("command_dispatch_service", SubscriberMode.LIVE, 42L, 0, 0,
                        0, null));

        // Act
        String text = BusAwaitDiagnostic.render("the On frame reaching the scripted NCP",
                42L, OptionalLong.of(41L), snapshots);

        // Assert — the exact bytes, line by line.
        assertThat(text).isEqualTo(String.join("\n",
                "bus.await_timeout: what=the On frame reaching the scripted NCP"
                        + " store_head=42 awaited=41",
                "bus.await_subscriber: subscriber=automation_engine mode=LIVE"
                        + " checkpoint=40 dlq=1 pending=2 behind=2",
                "bus.await_subscriber: subscriber=state_projection mode=REPLAY"
                        + " checkpoint=0 dlq=0 pending=0 behind=42",
                "bus.await_subscriber: subscriber=command_dispatch_service mode=LIVE"
                        + " checkpoint=42 dlq=0 pending=0 behind=0"));

        // The no-position arm: awaited=none, and an empty list renders the header alone.
        assertThat(BusAwaitDiagnostic.render("the EZSP session", 0L, OptionalLong.empty(),
                List.of()))
                .isEqualTo("bus.await_timeout: what=the EZSP session store_head=0 awaited=none");
    }
}

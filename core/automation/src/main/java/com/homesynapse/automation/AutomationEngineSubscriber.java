/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.TriggerDurationCancelledEvent;
import com.homesynapse.event.TriggerDurationExpiredEvent;
import com.homesynapse.event.TriggerDurationStartedEvent;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.Ulid;

/**
 * The {@code automation_engine} event-bus subscriber (Doc 07 §3.2; SD-8) — the seam the
 * composition root wires to drive {@link StandardTriggerEvaluator} from the live event
 * stream. Package-private: it references {@code com.homesynapse.event.bus} types, which
 * stay off the exported automation API (the FIX-07 {@code requires event.bus} edge is
 * used only here).
 *
 * <p><strong>Replay (Doc 07 §3.10).</strong> While the bus delivers REPLAY-mode events,
 * the evaluator suppresses timer starts; this subscriber consumes the historical
 * {@code trigger_duration_*} events to reconstruct which duration timers were active at
 * the REPLAY→LIVE boundary, then re-derives them with their remaining duration on
 * {@link #onCaughtUp()} (re-derive-never-re-execute). No new events are produced during
 * replay.</p>
 */
final class AutomationEngineSubscriber implements Subscriber {

    private final StandardTriggerEvaluator evaluator;
    private final Map<StandardTriggerEvaluator.TimerKey, ReplayTimer> replayTimers =
            new LinkedHashMap<>();

    AutomationEngineSubscriber(StandardTriggerEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    @Override
    public void onEvent(EventEnvelope event) {
        if (evaluator.isReplayMode()) {
            accumulateReplayTimer(event);
        }
        // Trigger evaluation proceeds in both modes (§3.10): in REPLAY it has no side
        // effects (timers suppressed, nothing published); in LIVE it drives the timers
        // and the trigger-duration diagnostics. Run initiation is M7.2.
        evaluator.evaluate(event);
    }

    @Override
    public void setMode(SubscriberMode mode) {
        evaluator.setReplayMode(mode != SubscriberMode.LIVE);
    }

    @Override
    public void onCaughtUp() {
        // REPLAY→LIVE: re-derive the timers that were active at the boundary, then go live.
        for (Map.Entry<StandardTriggerEvaluator.TimerKey, ReplayTimer> entry : replayTimers.entrySet()) {
            ReplayTimer timer = entry.getValue();
            evaluator.rebuildTimer(entry.getKey().automationId(), entry.getKey().triggerIndex(),
                    timer.startingEventId, timer.subjectUlid, timer.forDuration, timer.startedAt,
                    timer.correlationId, timer.actorRef);
        }
        replayTimers.clear();
        evaluator.setReplayMode(false);
    }

    private void accumulateReplayTimer(EventEnvelope event) {
        switch (event.payload()) {
            case TriggerDurationStartedEvent started -> replayTimers.put(
                    new StandardTriggerEvaluator.TimerKey(started.automationId(), started.triggerIndex()),
                    new ReplayTimer(started.startingEventId(), started.entityRef().value(),
                            Duration.ofMillis(started.forDurationMs()),
                            event.eventTime() != null ? event.eventTime() : event.ingestTime(),
                            event.causalContext().correlationId(), event.actorRef()));
            case TriggerDurationCancelledEvent cancelled -> replayTimers.remove(
                    new StandardTriggerEvaluator.TimerKey(cancelled.automationId(),
                            cancelled.triggerIndex()));
            case TriggerDurationExpiredEvent expired -> replayTimers.remove(
                    new StandardTriggerEvaluator.TimerKey(expired.automationId(),
                            expired.triggerIndex()));
            default -> { /* not a duration-timer event — nothing to reconstruct */ }
        }
    }

    /** Reconstructed timer state from the replayed {@code trigger_duration_started} event. */
    private record ReplayTimer(
            EventId startingEventId,
            Ulid subjectUlid,
            Duration forDuration,
            Instant startedAt,
            Ulid correlationId,
            Ulid actorRef) {
    }
}

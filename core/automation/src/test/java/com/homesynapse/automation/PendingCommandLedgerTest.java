/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.Expectation;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.value.BooleanValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardPendingCommandLedger} — the command-outcome correlation FSM (Doc 07 §3.11.2):
 * {@code dispatched -> confirmed | unconfirmed | failed}. Exercises the five
 * {@link PendingStatus} transitions, confirmation matching, the deterministic
 * (clock-driven) timeout, the dual index, REPLAY rebuild + crash-recovery-by-idempotency-class,
 * the no-autonomous-retry invariant, and the {@code state_confirmed} /
 * {@code command_confirmation_timed_out} publish-count pins. All time is injected (§4c /
 * REC-156/167).
 */
@DisplayName("StandardPendingCommandLedger (M7.3)")
class PendingCommandLedgerTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final EntityId target = AutomationTestSupport.entityId();
    private final EventId commandEventId = AutomationTestSupport.eventId();

    private AutomationTestSupport.RecordingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private StandardPendingCommandLedger ledger(Clock clock) {
        return new StandardPendingCommandLedger(publisher,
                new AutomationTestSupport.StubEntityRegistry(List.of()), clock, DEFAULT_TIMEOUT_MS);
    }

    private PendingCommand dispatched(Instant deadline) {
        return dispatched(commandEventId, target, deadline);
    }

    private static PendingCommand dispatched(EventId id, EntityId entity, Instant deadline) {
        Expectation expectation = new ExactMatch(new BooleanValue(true));
        return new PendingCommand(id, entity, "turn_on", "on", expectation, deadline,
                CommandIdempotency.IDEMPOTENT, PendingStatus.DISPATCHED);
    }

    private EventEnvelope commandResult(String outcome) {
        return AutomationTestSupport.envelope(EventTypes.COMMAND_RESULT, SubjectRef.entity(target),
                new CommandResultEvent(target.value(), "turn_on", outcome, null));
    }

    private EventEnvelope stateReported(String value) {
        return AutomationTestSupport.envelope(EventTypes.STATE_REPORTED, SubjectRef.entity(target),
                new StateReportedEvent("on", value, null, null, null));
    }

    private EventEnvelope commandIssued(EntityId entity, CommandIdempotency idempotency) {
        return AutomationTestSupport.envelope(EventTypes.COMMAND_ISSUED, SubjectRef.entity(entity),
                new CommandIssuedEvent(entity.value(), "turn_on", "{}", 30_000, idempotency));
    }

    // ── FSM transitions ─────────────────────────────────────────────────────

    @Test
    @DisplayName("DISPATCHED -> ACKNOWLEDGED -> CONFIRMED: ack advances, a matching report confirms")
    void lifecycle_dispatchedAcknowledgedConfirmed() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.trackCommand(dispatched(farFuture()));

        assertThat(ledger.getCommand(commandEventId).orElseThrow().status())
                .isEqualTo(PendingStatus.DISPATCHED);

        ledger.onEvent(commandResult("acknowledged"));
        assertThat(ledger.getCommand(commandEventId).orElseThrow().status())
                .isEqualTo(PendingStatus.ACKNOWLEDGED);

        EventEnvelope report = stateReported("true");
        ledger.onEvent(report);

        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isEqualTo(1);
        StateConfirmedEvent confirmed =
                (StateConfirmedEvent) publisher.ofType(EventTypes.STATE_CONFIRMED).get(0).payload();
        assertThat(confirmed.commandEventId()).isEqualTo(commandEventId);
        assertThat(confirmed.reportEventId()).isEqualTo(report.eventId());
        assertThat(confirmed.attributeKey()).isEqualTo("on");
        assertThat(confirmed.actualValue()).isEqualTo("true");
        assertThat(confirmed.matchType()).isEqualTo("exact");
        assertThat(ledger.getCommand(commandEventId)).isEmpty();
        assertThat(ledger.pendingCount()).isZero();
    }

    @Test
    @DisplayName("DISPATCHED -> TIMED_OUT: stepping the clock past the deadline emits exactly one timeout")
    void lifecycle_dispatchedTimedOut() {
        AutomationTestSupport.MutableClock clock = AutomationTestSupport.mutableClock();
        StandardPendingCommandLedger ledger = ledger(clock);
        ledger.trackCommand(dispatched(clock.instant().plus(Duration.ofSeconds(30))));

        ledger.pollExpirations();   // not yet expired
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isZero();

        clock.advance(Duration.ofSeconds(31));
        ledger.pollExpirations();

        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isEqualTo(1);
        CommandConfirmationTimedOutEvent timedOut = (CommandConfirmationTimedOutEvent)
                publisher.ofType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT).get(0).payload();
        assertThat(timedOut.commandEventId()).isEqualTo(commandEventId);
        assertThat(ledger.pendingCount()).isZero();

        clock.advance(Duration.ofSeconds(10));
        ledger.pollExpirations();   // already terminal — no duplicate
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isEqualTo(1);
    }

    @Test
    @DisplayName("a rejected/timed_out command_result removes the entry and emits one timeout")
    void commandResult_rejected_emitsTimeoutOnce() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.trackCommand(dispatched(farFuture()));

        ledger.onEvent(commandResult("rejected"));

        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isEqualTo(1);
        assertThat(ledger.pendingCount()).isZero();

        ledger.pollExpirations();   // entry gone — no duplicate timeout
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isEqualTo(1);
    }

    // ── confirmation matching ───────────────────────────────────────────────

    @Test
    @DisplayName("a non-matching state_reported keeps the entry and emits nothing")
    void stateReported_nonMatching_retainsEntry() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.trackCommand(dispatched(farFuture()));

        ledger.onEvent(stateReported("false"));   // expected true

        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isZero();
        assertThat(ledger.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a state_reported on a different attribute is ignored")
    void stateReported_differentAttribute_ignored() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.trackCommand(dispatched(farFuture()));

        ledger.onEvent(AutomationTestSupport.envelope(EventTypes.STATE_REPORTED,
                SubjectRef.entity(target),
                new StateReportedEvent("brightness", "100", null, null, null)));

        assertThat(publisher.countOfType(EventTypes.STATE_CONFIRMED)).isZero();
        assertThat(ledger.pendingCount()).isEqualTo(1);
    }

    // ── dual index ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("dual index: getCommand(EventId) and getPendingForEntity(EntityId) both resolve")
    void dualIndex_bothLookupsResolve() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        PendingCommand command = dispatched(farFuture());
        ledger.trackCommand(command);

        assertThat(ledger.getCommand(commandEventId)).contains(command);
        assertThat(ledger.getPendingForEntity(target)).containsExactly(command);
        assertThat(ledger.getCommand(AutomationTestSupport.eventId())).isEmpty();
        assertThat(ledger.getPendingForEntity(AutomationTestSupport.entityId())).isEmpty();
    }

    // ── no autonomous retry ─────────────────────────────────────────────────

    @Test
    @DisplayName("no autonomous retry: the ledger never emits command_issued or command_dispatched")
    void noRetry_neverDispatches() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.trackCommand(dispatched(farFuture()));
        ledger.onEvent(commandResult("acknowledged"));
        ledger.onEvent(stateReported("true"));

        assertThat(publisher.ofType(EventTypes.COMMAND_ISSUED)).isEmpty();
        assertThat(publisher.ofType(EventTypes.COMMAND_DISPATCHED)).isEmpty();
    }

    // ── REPLAY rebuild + crash recovery ─────────────────────────────────────

    @Test
    @DisplayName("REPLAY: an IDEMPOTENT command in-flight at restart is re-offered (a signal), not re-issued")
    void replay_idempotent_reoffered() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.setMode(SubscriberMode.REPLAY);
        EventEnvelope issued = commandIssued(target, CommandIdempotency.IDEMPOTENT);
        ledger.onEvent(issued);
        ledger.onCaughtUp();

        assertThat(ledger.reissueOffered()).hasSize(1);
        assertThat(ledger.reissueOffered().get(0).commandEventId()).isEqualTo(issued.eventId());
        assertThat(ledger.adapterEvaluationOffered()).isEmpty();
        assertThat(ledger.pendingCount()).isZero();          // re-offered, not live-tracked
        assertThat(publisher.published()).isEmpty();          // never re-issues; no replay emission
    }

    @Test
    @DisplayName("REPLAY: a NOT_IDEMPOTENT command in-flight at restart expires (command_result expired_on_restart)")
    void replay_notIdempotent_expiredOnRestart() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.setMode(SubscriberMode.REPLAY);
        ledger.onEvent(commandIssued(target, CommandIdempotency.NOT_IDEMPOTENT));
        ledger.onCaughtUp();

        assertThat(publisher.countOfType(EventTypes.COMMAND_RESULT)).isEqualTo(1);
        CommandResultEvent result =
                (CommandResultEvent) publisher.ofType(EventTypes.COMMAND_RESULT).get(0).payload();
        assertThat(result.outcome()).isEqualTo("expired_on_restart");
        assertThat(result.targetEntityRef()).isEqualTo(target.value());
        assertThat(ledger.reissueOffered()).isEmpty();
        assertThat(ledger.pendingCount()).isZero();
    }

    @Test
    @DisplayName("REPLAY: a CONDITIONAL command in-flight at restart is offered to the adapter")
    void replay_conditional_adapterOffered() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.setMode(SubscriberMode.REPLAY);
        ledger.onEvent(commandIssued(target, CommandIdempotency.CONDITIONAL));
        ledger.onCaughtUp();

        assertThat(ledger.adapterEvaluationOffered()).hasSize(1);
        assertThat(ledger.reissueOffered()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("REPLAY pure-projection: a command confirmed in the log is not in-flight at restart")
    void replay_confirmedInLog_notReoffered() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.setMode(SubscriberMode.REPLAY);
        EventEnvelope issued = commandIssued(target, CommandIdempotency.IDEMPOTENT);
        ledger.onEvent(issued);
        ledger.onEvent(AutomationTestSupport.envelope(EventTypes.STATE_CONFIRMED,
                SubjectRef.entity(target),
                new StateConfirmedEvent(issued.eventId(), AutomationTestSupport.eventId(),
                        "on", "true", "true", "exact")));
        ledger.onCaughtUp();

        assertThat(ledger.reissueOffered()).isEmpty();
        assertThat(ledger.adapterEvaluationOffered()).isEmpty();
        assertThat(ledger.pendingCount()).isZero();
    }

    @Test
    @DisplayName("REPLAY pure-projection: a command rejected in the log is not in-flight at restart")
    void replay_rejectedInLog_notReoffered() {
        StandardPendingCommandLedger ledger = ledger(AutomationTestSupport.FIXED_CLOCK);
        ledger.setMode(SubscriberMode.REPLAY);
        ledger.onEvent(commandIssued(target, CommandIdempotency.IDEMPOTENT));
        ledger.onEvent(commandResult("rejected"));
        ledger.onCaughtUp();

        assertThat(ledger.reissueOffered()).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    private static Instant farFuture() {
        return AutomationTestSupport.FIXED_INSTANT.plus(Duration.ofHours(1));
    }
}

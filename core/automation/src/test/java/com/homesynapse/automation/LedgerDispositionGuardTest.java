/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.homesynapse.device.Entity;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SD-4 (Nick, 2026-07-04, v18 beat 5 — verbatim): "a test pinning every
 * self-published outcome string &isin; the {@code onCommandResult} guard set, so a
 * future disposition constant cannot silently reintroduce the loop-back class."
 *
 * <p>The ledger consumes {@code command_result} — the bus loops every publication
 * back. A DISPOSITION (a terminal report about an already-concluded command:
 * {@code superseded}/{@code expired_on_restart} from the ledger itself,
 * {@code unconfirmed} from an adapter's immediate honest verdict, {@code invalid}
 * from the dispatch service's Tier-1 rejection — the M9.4b grounding defect) must
 * NEVER terminal-match a tracked sibling via the (entity, command) fallback.
 * Genuine failure reports about the TRACKED command ({@code rejected},
 * {@code timed_out}, the router's {@code unsupported}/{@code handler_error}/
 * {@code integration_unavailable}) must NEVER be guard-swallowed.</p>
 *
 * <p><strong>Convention (SD-4):</strong> any NEW self-published disposition
 * constant must be added to {@code isDispositionOutcome} AND to BOTH halves of
 * this test.</p>
 */
@DisplayName("StandardPendingCommandLedger — disposition-guard membership (SD-4 / M9.4b §4)")
class LedgerDispositionGuardTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final EntityId entityId = AutomationTestSupport.entityId();

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private StandardPendingCommandLedger ledger;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        Entity entity = AutomationTestSupport.entityWith(entityId,
                AutomationTestSupport.deviceId(), StandardCapabilities.onOff());
        ledger = new StandardPendingCommandLedger(publisher,
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)),
                AutomationTestSupport.FIXED_CLOCK, DEFAULT_TIMEOUT_MS,
                parameters -> Map.of());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Issues turn_on (declared outcome — P41) and returns the tracked envelope. */
    private EventEnvelope trackTurnOn() {
        EventEnvelope issued = AutomationTestSupport.envelope(EventTypes.COMMAND_ISSUED,
                SubjectRef.entity(entityId),
                new CommandIssuedEvent(entityId.value(), "turn_on", "{}", 30_000,
                        CommandIdempotency.IDEMPOTENT));
        ledger.onEvent(issued);
        assertThat(ledger.getCommand(issued.eventId()))
                .as("fixture: turn_on tracks via its declared outcome")
                .isPresent();
        return issued;
    }

    private void deliverResult(String outcome) {
        ledger.onEvent(AutomationTestSupport.envelope(EventTypes.COMMAND_RESULT,
                SubjectRef.entity(entityId),
                new CommandResultEvent(entityId.value(), "turn_on", outcome,
                        "membership-test result")));
    }

    /** The guarded direction: the in-flight sibling SURVIVES the looped-back outcome. */
    private void assertGuarded(String outcome) {
        EventEnvelope issued = trackTurnOn();

        deliverResult(outcome);

        assertThat(ledger.getCommand(issued.eventId()))
                .as("'%s' is a disposition — it must never terminal-match a tracked sibling",
                        outcome)
                .isPresent();
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT))
                .isZero();
    }

    /** The terminal direction: the result concludes the tracked command, never swallowed. */
    private void assertTerminal(String outcome) {
        EventEnvelope issued = trackTurnOn();

        deliverResult(outcome);

        assertThat(ledger.getCommand(issued.eventId()))
                .as("'%s' reports the tracked command's fate — it must terminal-match",
                        outcome)
                .isEmpty();
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT))
                .isEqualTo(1);
    }

    // ── the guarded set (dispositions — the loop-back class) ────────────────

    @Test
    @DisplayName("guarded: superseded")
    void guarded_superseded() {
        assertGuarded("superseded");
    }

    @Test
    @DisplayName("guarded: expired_on_restart")
    void guarded_expiredOnRestart() {
        assertGuarded("expired_on_restart");
    }

    @Test
    @DisplayName("guarded: unconfirmed")
    void guarded_unconfirmed() {
        assertGuarded("unconfirmed");
    }

    @Test
    @DisplayName("guarded: invalid — the M9.4b grounding defect's regression pin "
            + "(a Tier-1 rejection reports a command that never entered dispatch)")
    void guarded_invalid() {
        assertGuarded("invalid");
    }

    // ── the terminal set (genuine reports about the tracked command) ────────

    @Test
    @DisplayName("terminal: acknowledged advances the entry to ACKNOWLEDGED")
    void terminal_acknowledged() {
        EventEnvelope issued = trackTurnOn();

        deliverResult("acknowledged");

        assertThat(ledger.getCommand(issued.eventId()))
                .isPresent()
                .get()
                .satisfies(command -> assertThat(command.status())
                        .isEqualTo(PendingStatus.ACKNOWLEDGED));
        assertThat(publisher.countOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT))
                .isZero();
    }

    @Test
    @DisplayName("terminal: rejected")
    void terminal_rejected() {
        assertTerminal("rejected");
    }

    @Test
    @DisplayName("terminal: timed_out")
    void terminal_timedOut() {
        assertTerminal("timed_out");
    }

    @Test
    @DisplayName("terminal: unsupported (the router's vocabulary)")
    void terminal_unsupported() {
        assertTerminal("unsupported");
    }

    @Test
    @DisplayName("terminal: handler_error (the router's vocabulary)")
    void terminal_handlerError() {
        assertTerminal("handler_error");
    }

    @Test
    @DisplayName("terminal: integration_unavailable (the router's vocabulary)")
    void terminal_integrationUnavailable() {
        assertTerminal("integration_unavailable");
    }

    // ── the cross-constant pin ──────────────────────────────────────────────

    @Test
    @DisplayName("the ledger's OUTCOME_INVALID and the dispatch service's OUTCOME_INVALID "
            + "are the same string — the two cannot drift")
    void invalidConstant_cannotDrift() {
        assertThat(StandardPendingCommandLedger.OUTCOME_INVALID)
                .isEqualTo(StandardCommandDispatchService.OUTCOME_INVALID)
                .isEqualTo("invalid");
    }
}

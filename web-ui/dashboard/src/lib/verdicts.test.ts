/*
 * The verdict vocabulary, locked. Ten outcomes, ZERO flattening: every value has
 * its own distinct label, the three charter classes render differently
 * ("honest-can't-know vs known-failed vs deliberately-superseded"), and the
 * recorded-reason recovery only fires on deterministic wire truth — no guessing.
 */
import { describe, it, expect } from 'vitest';
import {
  COMMAND_RESULT_OUTCOMES,
  actionVerdict,
  classifyRecordedReason,
  isActionSettled,
  isDoNothingRun,
  postureMeta,
  resultOutcomeMeta,
} from './verdicts';

describe('the ten-value command_result vocabulary (zero flattening)', () => {
  it('carries exactly the ten live outcomes (CommandResultEvent.java:22-27)', () => {
    expect([...COMMAND_RESULT_OUTCOMES].sort()).toEqual(
      [
        'acknowledged',
        'rejected',
        'timed_out',
        'invalid',
        'unsupported',
        'handler_error',
        'integration_unavailable',
        'superseded',
        'expired_on_restart',
        'unconfirmed',
      ].sort(),
    );
  });

  it('renders all ten outcomes DISTINCTLY — no two share a label', () => {
    const labels = COMMAND_RESULT_OUTCOMES.map((o) => resultOutcomeMeta(o).label);
    expect(new Set(labels).size).toBe(COMMAND_RESULT_OUTCOMES.length);
  });

  it('superseded is deliberately-superseded — calm intent-change, NEVER a failure', () => {
    const m = resultOutcomeMeta('superseded');
    expect(m.klass).toBe('deliberately-superseded');
    expect(m.tone).not.toBe('error');
    expect(m.tone).not.toBe('warn');
    expect(m.help).toMatch(/not a failure/i);
  });

  it('unconfirmed and timed_out are honest-can-not-know — calm amber, never error', () => {
    for (const o of ['unconfirmed', 'timed_out'] as const) {
      const m = resultOutcomeMeta(o);
      expect(m.klass).toBe('honest-cant-know');
      expect(m.tone).toBe('warn');
    }
  });

  it('the known-failed set renders as error with a recorded reason', () => {
    for (const o of ['rejected', 'invalid', 'unsupported', 'handler_error', 'integration_unavailable'] as const) {
      expect(resultOutcomeMeta(o).klass).toBe('known-failed');
      expect(resultOutcomeMeta(o).tone).toBe('error');
    }
  });

  it('expired_on_restart is honest restart accounting, not an alarm', () => {
    const m = resultOutcomeMeta('expired_on_restart');
    expect(m.klass).toBe('restart-accounting');
    expect(m.tone).not.toBe('error');
  });

  it('an acknowledgement is NOT presented as confirmation', () => {
    const m = resultOutcomeMeta('acknowledged');
    expect(m.klass).toBe('protocol-ack');
    expect(m.help).toMatch(/not the same as doing|not confirmation/i);
  });

  it('an unknown adapter-specific outcome gets the honest fallback, never a crash', () => {
    const m = resultOutcomeMeta('zigbee_default_response_0x86');
    expect(m.label).toBeTruthy();
    expect(m.tone).toBe('unknown');
  });
});

describe('recorded-reason recovery (deterministic wire truth only)', () => {
  it('recovers the ledger superseded disposition by its exact prefix (StandardPendingCommandLedger.java:912-916)', () => {
    expect(
      classifyRecordedReason(
        'superseded by a newer command on the same attribute; superseding command event 01ARZ3NDEKTSV4RRFFQ69G5FAV',
      ),
    ).toBe('superseded');
  });

  it('recovers expired_on_restart by its exact wire string (StandardPendingCommandLedger.java:922-924)', () => {
    expect(classifyRecordedReason('command was in-flight at restart and is not idempotent')).toBe('expired_on_restart');
  });

  it('recovers a bare outcome token (failureReason was null: reason = outcome)', () => {
    expect(classifyRecordedReason('superseded')).toBe('superseded');
    expect(classifyRecordedReason('unconfirmed')).toBe('unconfirmed');
    expect(classifyRecordedReason('rejected')).toBe('rejected');
  });

  it('NEVER guesses from variable text (zigbee profile-note reasons stay unclassified)', () => {
    expect(classifyRecordedReason('the device provides no authoritative report for identify (identify_ack_only)')).toBeNull();
    expect(classifyRecordedReason('Device unreachable — the command was rejected')).toBeNull();
    expect(classifyRecordedReason(null)).toBeNull();
    expect(classifyRecordedReason('')).toBeNull();
  });
});

describe('confirmation posture (a posture is not a verdict)', () => {
  it('best_effort renders calm — a posture, never a failure', () => {
    const m = postureMeta('best_effort');
    expect(m.tone).not.toBe('error');
    expect(m.tone).not.toBe('warn');
    expect(m.help).toMatch(/still works/i);
  });

  it('sleepy renders as designed behavior, not a fault', () => {
    expect(postureMeta('SLEEPY').help).toMatch(/normal/i);
  });

  it('an unknown posture is shown as recorded, never a crash', () => {
    expect(postureMeta('quantum').label).toContain('quantum');
  });
});

/* THE FIVE-MODES-DISTINCT LAW (Nick, 2026-07-25 — "the distinction IS the
 * product"): the five honest failure modes render DISTINCT, never collapsed —
 * each with its own LABEL + GLYPH (never hue alone; §3a ALL-USERS/CVD mandate).
 * Seeded to the PRIMARY table rows of the SKIP-VIS signature table. */
describe('the five honest failure modes render DISTINCT (the 2026-07-25 law)', () => {
  const MODES = {
    timedOut: { outcome: 'UNCONFIRMED', reason: 'confirmation timed out', resultOutcome: null, settled: true },
    superseded: { outcome: 'DISPATCHED', reason: null, resultOutcome: 'superseded', settled: true },
    ackedSilent: {
      outcome: 'UNCONFIRMED',
      reason: 'DefaultResponse SUCCESS +90 ms, then no report, ever',
      resultOutcome: 'unconfirmed',
      settled: true,
    },
    held: { outcome: 'DISPATCHED', reason: null, resultOutcome: null, settled: false },
    settledFailed: { outcome: 'FAILED', reason: 'device offline', resultOutcome: 'rejected', settled: true },
  } as const;

  it('the five modes are pairwise distinct on mode, label AND glyph', () => {
    const verdicts = Object.values(MODES).map((m) => actionVerdict(m));
    expect(new Set(verdicts.map((v) => v.mode)).size).toBe(5);
    expect(new Set(verdicts.map((v) => v.label)).size).toBe(5);
    expect(new Set(verdicts.map((v) => v.glyph)).size).toBe(5);
  });

  it('mode 1 (dispatched-and-timed-out) is calm honest-can-not-know, never error', () => {
    const v = actionVerdict(MODES.timedOut);
    expect(v.mode).toBe('timed-out');
    expect(v.tone).toBe('warn');
    expect(v.provisional).toBe(false);
  });

  it('mode 2 (superseded) renders as intent change — NEVER a failure, and settled', () => {
    const v = actionVerdict(MODES.superseded);
    expect(v.mode).toBe('superseded');
    expect(v.label).toBe('Replaced');
    expect(v.tone).not.toBe('error');
    expect(v.tone).not.toBe('warn');
    expect(v.provisional).toBe(false); // a superseded DISPATCHED is settled
  });

  it('mode 2 variant (superseded + timeout) still renders as intent change', () => {
    const v = actionVerdict({ outcome: 'UNCONFIRMED', reason: 'confirmation timed out', resultOutcome: 'superseded', settled: true });
    expect(v.mode).toBe('superseded');
    expect(v.tone).toBe('neutral');
  });

  it('mode 3 (acked-then-silent-forever) is distinct from timed-out; the reason renders verbatim elsewhere', () => {
    const v = actionVerdict(MODES.ackedSilent);
    expect(v.mode).toBe('acked-silent');
    expect(v.label).not.toBe(actionVerdict(MODES.timedOut).label);
    expect(v.tone).toBe('warn'); // same calm register — the label + glyph carry the distinction
    expect(v.glyph).not.toBe(actionVerdict(MODES.timedOut).glyph);
  });

  it('mode 4 (held-DISPATCHED) is PROVISIONAL — visibly unsettled in the label text itself', () => {
    const v = actionVerdict(MODES.held);
    expect(v.mode).toBe('held-dispatched');
    expect(v.provisional).toBe(true);
    expect(v.label.toLowerCase()).toContain('not settled'); // never rides styling alone
    expect(v.tone).not.toBe('error'); // calm, never alarm
  });

  it('mode 5 (settled-FAILED) keeps the known-failed register with the disposition sub-label', () => {
    const v = actionVerdict(MODES.settledFailed);
    expect(v.mode).toBe('settled-failed');
    expect(v.tone).toBe('error');
    expect(v.label).toBe('Rejected');
    expect(v.provisional).toBe(false);
  });

  it('an unknown adapter string on FAILED stays conservative FAILED (SD-7)', () => {
    const v = actionVerdict({ outcome: 'FAILED', reason: 'zcl weirdness', resultOutcome: 'zcl_weird_vendor_code', settled: true });
    expect(v.mode).toBe('settled-failed');
    expect(v.tone).toBe('error');
  });

  it('expired_on_restart renders as honest restart accounting, not an alarm', () => {
    const v = actionVerdict({ outcome: 'FAILED', reason: 'command was in-flight at restart and is not idempotent', resultOutcome: 'expired_on_restart', settled: true });
    expect(v.mode).toBe('expired-restart');
    expect(v.tone).not.toBe('error');
  });
});

/* The Q1b settled rule — client-side derivation IDENTICAL to the core formula
 * (SKIP-VIS DP-4: settled = !(DISPATCHED && (resultOutcome null || "acknowledged"))),
 * used only when the first-class field is absent (pre-v1.1.2 payloads). */
describe('provisional/settled (§5.9): field first, the core-identical derivation as fallback', () => {
  it('derives provisional for a bare DISPATCHED and an acked DISPATCHED (no field)', () => {
    expect(isActionSettled({ outcome: 'DISPATCHED', reason: null })).toBe(false);
    expect(isActionSettled({ outcome: 'DISPATCHED', reason: null, resultOutcome: 'acknowledged' })).toBe(false);
  });

  it('derives settled for superseded DISPATCHED and every non-DISPATCHED outcome', () => {
    expect(isActionSettled({ outcome: 'DISPATCHED', reason: null, resultOutcome: 'superseded' })).toBe(true);
    expect(isActionSettled({ outcome: 'UNCONFIRMED', reason: 'confirmation timed out' })).toBe(true);
    expect(isActionSettled({ outcome: 'CONFIRMED', reason: null })).toBe(true);
    expect(isActionSettled({ outcome: 'FAILED', reason: 'x' })).toBe(true);
  });

  it('the first-class settled field WINS over the derivation when present', () => {
    expect(isActionSettled({ outcome: 'DISPATCHED', reason: null, resultOutcome: null, settled: true })).toBe(true);
    expect(isActionSettled({ outcome: 'DISPATCHED', reason: null, resultOutcome: null, settled: false })).toBe(false);
  });

  it('a pre-v1.1.2 bare DISPATCHED (no keys at all) renders provisional', () => {
    const v = actionVerdict({ outcome: 'DISPATCHED', reason: null });
    expect(v.provisional).toBe(true);
    expect(v.recovered).toBe(false);
  });
});

/* D-4 RETIREMENT (2026-07-26): the first-class v1.1.2 resultOutcome is consumed
 * where present — the recorded-reason recovery survives ONLY as the pre-v1.1.2
 * fallback, including for the variable zigbee reasons D-4 could never classify. */
describe('D-4 retirement: first-class resultOutcome wins; recovery only for pre-v1.1.2 payloads', () => {
  it('the variable zigbee honest-unconfirmed class NOW renders honestly via the field', () => {
    // Pre-v1.1.2 this exact payload was D-4's recorded limitation (FAILED pill).
    const v = actionVerdict({
      outcome: 'UNCONFIRMED',
      reason: 'the device provides no authoritative report for identify (identify_ack_only)',
      resultOutcome: 'unconfirmed',
      settled: true,
    });
    expect(v.mode).toBe('acked-silent');
    expect(v.tone).toBe('warn');
  });

  it('recovery still fires on a pre-v1.1.2 flattened FAILED with the ledger string', () => {
    const v = actionVerdict({
      outcome: 'FAILED',
      reason: 'superseded by a newer command on the same attribute; superseding command event 01ARZ3NDEKTSV4RRFFQ69G5FAV',
    });
    expect(v.mode).toBe('superseded');
    expect(v.recovered).toBe(true);
    expect(v.tone).toBe('neutral');
  });

  it('the field wins over a contradictory reason (first-class truth outranks recovery)', () => {
    const v = actionVerdict({
      outcome: 'FAILED',
      reason: 'superseded', // a bare token the recovery WOULD have classified
      resultOutcome: 'rejected',
      settled: true,
    });
    expect(v.mode).toBe('settled-failed');
    expect(v.recovered).toBe(false);
    expect(v.resultOutcome).toBe('rejected');
  });

  it('recovery NEVER guesses from variable text on pre-v1.1.2 payloads (unchanged)', () => {
    const v = actionVerdict({
      outcome: 'FAILED',
      reason: 'the device provides no authoritative report for identify (identify_ack_only)',
    });
    expect(v.mode).toBe('settled-failed'); // stays conservative until the deploy
    expect(v.recovered).toBe(false);
  });
});

describe('the silent-skip run class (lawful Doc 07 §3.9 per-target skips)', () => {
  it('detects the actionCount-vs-actions[] disagreement', () => {
    expect(isDoNothingRun({ status: 'COMPLETED', actionCount: 2, commandCount: 0 }, 0)).toBe(true);
  });

  it('does not flag runs that actually did something or actually showed their steps', () => {
    expect(isDoNothingRun({ status: 'COMPLETED', actionCount: 1, commandCount: 1 }, 1)).toBe(false);
    expect(isDoNothingRun({ status: 'COMPLETED', actionCount: 1, commandCount: 0 }, 1)).toBe(false);
    expect(isDoNothingRun({ status: 'FAILED', actionCount: 2, commandCount: 0 }, 0)).toBe(false);
    expect(isDoNothingRun({ status: 'COMPLETED', actionCount: 0, commandCount: 0 }, 0)).toBe(false);
  });
});

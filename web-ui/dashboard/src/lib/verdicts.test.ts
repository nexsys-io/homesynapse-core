/*
 * The verdict vocabulary, locked. Ten outcomes, ZERO flattening: every value has
 * its own distinct label, the three charter classes render differently
 * ("honest-can't-know vs known-failed vs deliberately-superseded"), and the
 * recorded-reason recovery only fires on deterministic wire truth — no guessing.
 */
import { describe, it, expect } from 'vitest';
import {
  COMMAND_RESULT_OUTCOMES,
  classifyRecordedReason,
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

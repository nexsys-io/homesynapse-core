/*
 * HERO-1c C1 (2026-09-13) — the action step LINE per confirmation mode (SPEC §5 / §7
 * `explain.mode.*.line`; the HERO-1b audit's D6). The line itself carries the outcome: a
 * step that never confirmed never reads "Turned on …". RED at HEAD: CausalChain.tsx:213–:220
 * writes `actionPhrase(command) + target` ("Turned on Hallway Light.") for EVERY mode, so
 * eight of the nine mode lines fail at HEAD; the ninth — skipped with no target — is HEAD's
 * own SKIPPED_BEFORE_COMMAND sentence (hardening.test:265) and is the named preservation row.
 * The two HERO-1b null-target arms (unnamed · dangling) keep their sentences (charter §4).
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import { CausalChain } from './CausalChain';
import type { CausalChain as Chain, CausalAction, RunStatus } from '../lib/api/contract';
import { t } from '../lib/i18n';
import { UNNAMED_TARGET } from '../lib/format';
import type { ActionMode } from '../lib/verdicts';

afterEach(cleanup);

const AT = '2026-07-27T23:47:00Z';

function action(over: Partial<CausalAction> = {}): CausalAction {
  return {
    type: 'device_command',
    targetRef: { type: 'ENTITY', id: 'ent_hallway_light' },
    command: 'turn_on',
    params: {},
    outcome: 'CONFIRMED',
    reason: null,
    resultOutcome: null,
    settled: true,
    ...over,
  };
}

function chain(a: CausalAction, status: RunStatus = 'COMPLETED'): Chain {
  return {
    runId: 'run_modes',
    automationId: 'auto_evening',
    automationName: 'Evening Lights',
    trigger: { type: 'state_changed', subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' }, matchedAt: AT, firingValue: null },
    conditions: [],
    actions: [a],
    outcome: { status, reason: null, durationMs: 412, actionCount: 1, commandCount: a.command === null ? 0 : 1 },
    cascade: { parentRunId: null, depth: 0 },
  };
}

/** The action step's OWN text (line + pill + help) — a sentence elsewhere on the chain cannot satisfy a pin. */
function actionStep(a: CausalAction, status?: RunStatus) {
  const { container } = render(<CausalChain chain={chain(a, status)} />);
  const li = container.querySelector('li[data-kind="action"]')!;
  expect(li).toBeTruthy();
  return { text: li.textContent ?? '', li, container };
}

const HEAD_LINE = 'Turned on Hallway Light.'; // HEAD's one sentence for every mode (the D6 defect)
const statusFor = (o: CausalAction['outcome']): RunStatus => (o === 'SKIPPED' ? 'SKIPPED' : o === 'FAILED' ? 'FAILED' : 'COMPLETED');

/* mode → the wire fixture → the §7 key → the sentence at the sample slots (Hallway Light · turn_on). */
const NAMED_MODES: [ActionMode, Partial<CausalAction>, string, string][] = [
  ['confirmed', { outcome: 'CONFIRMED' }, 'explain.mode.confirmed.line', 'Hallway Light turned on.'],
  ['held-dispatched', { outcome: 'DISPATCHED', settled: false }, 'explain.mode.heldDispatched.line', 'Hallway Light was asked to turn on; waiting for it to confirm.'],
  ['timed-out', { outcome: 'UNCONFIRMED', reason: 'confirmation timed out' }, 'explain.mode.timedOut.line', 'Hallway Light was asked to turn on; no reply came within its window.'],
  ['superseded', { outcome: 'DISPATCHED', resultOutcome: 'superseded' }, 'explain.mode.superseded.line', 'Hallway Light was asked to turn on, then a newer command replaced it.'],
  ['acked-silent', { outcome: 'UNCONFIRMED', resultOutcome: 'unconfirmed', reason: 'DefaultResponse SUCCESS, then no report' }, 'explain.mode.ackedSilent.line', 'Hallway Light accepted the command to turn on, but never reported acting.'],
  ['settled-failed', { outcome: 'FAILED', resultOutcome: 'rejected', reason: 'device offline' }, 'explain.mode.settledFailed.line', 'The command to turn on Hallway Light failed — device offline.'],
  ['expired-restart', { outcome: 'FAILED', resultOutcome: 'expired_on_restart', reason: 'command was in-flight at restart and is not idempotent' }, 'explain.mode.expiredRestart.line', 'Hallway Light was asked to turn on; the hub restarted before it could confirm.'],
  ['not-recorded', { outcome: null as unknown as CausalAction['outcome'] }, 'explain.mode.notRecorded.line', "What happened to Hallway Light isn't recorded."],
];

describe("HERO-1c C1 — the action step line is the mode's §7 sentence", () => {
  it.each(NAMED_MODES)('%s → %s, never HEAD\'s "Turned on Hallway Light."', (_mode, over, key, expected) => {
    const { text } = actionStep(action(over), statusFor(over.outcome as CausalAction['outcome']));
    expect(text).toContain(expected);
    expect(text).not.toContain(HEAD_LINE);
    expect(text).not.toMatch(/\bnull\b/);
    expect(key).toMatch(/^explain\.mode\./); // the key named in the row is a §7 mode line
  });

  it('skipped, no target (command null, targetRef null) → explain.mode.skipped.line [GREEN at HEAD — SKIPPED_BEFORE_COMMAND, hardening.test:265; the named preservation row]', () => {
    const { text } = actionStep(action({ outcome: 'SKIPPED', command: null, targetRef: null, reason: 'Condition not met' }), 'SKIPPED');
    expect(text).toContain('Skipped before any command was sent.');
    expect(text).toContain(t('explain.mode.skipped.line'));
    expect(text).not.toContain('Ran an unrecorded command on');
  });

  it('skipped with a named target (command null, targetRef present) → explain.mode.skipped.lineNamed', () => {
    const { text } = actionStep(action({ outcome: 'SKIPPED', command: null, reason: 'target unavailable' }), 'SKIPPED');
    expect(text).toContain('Nothing was sent to Hallway Light — this step was skipped.');
    expect(text).not.toContain('Skipped before any command was sent.');
  });

  it('settled-failed with no recorded reason → the line without a reason clause', () => {
    const { text } = actionStep(action({ outcome: 'FAILED', resultOutcome: 'rejected', reason: null }), 'FAILED');
    expect(text).toContain('The command to turn on Hallway Light failed.');
    expect(text).not.toContain('failed —');
  });

  it('held-DISPATCHED on a colour-class command: the mode line, the slow-confirm hint beneath it, the provisional suffix for screen readers', () => {
    const { text, li } = actionStep(action({ outcome: 'DISPATCHED', settled: false, command: 'set_color_temperature' }));
    expect(text).toContain('Hallway Light was asked to run "set_color_temperature"; waiting for it to confirm.');
    expect(text).toMatch(/confirm slowly/); // pendingHint, still appended to the help — not the line
    expect(Array.from(li.querySelectorAll('.sr-only')).map((n) => n.textContent).join(' | ')).toContain(t('explain.a11y.provisional'));
  });

  it("the unnamed-target arm keeps HERO-1b's sentence [GREEN at HEAD — hardening.test:281–:290; preservation]", () => {
    const { text } = actionStep(action({ outcome: 'FAILED', resultOutcome: 'rejected', targetRef: null }), 'FAILED');
    expect(text).toContain(`Turned on ${UNNAMED_TARGET}.`);
  });

  it('a11y is intact: each step keeps its explain.a11y.step text and the polite role="status" region is present [GREEN at HEAD — SPEC §8; preservation]', () => {
    const { li, container } = actionStep(action());
    expect(Array.from(li.querySelectorAll('.sr-only')).map((n) => n.textContent).join(' | ')).toContain('Step 2 of 3: action — Confirmed.');
    expect(container.querySelector('[role="status"]')?.getAttribute('aria-live')).toBe('polite');
  });
});

/* ---- HERO-1c C3 (2026-09-13) — the retired format.ts command-outcome map's open-vocabulary pin, at the
 * render: an outcome string this build does not know is the not-recorded mode on the chain
 * (SPEC §5's last row — the `explain.mode.unknownOutcome.label` pill, the notRecorded line),
 * never success, never "Failed". RED before C3's verdicts.ts edit: the unknown string took the
 * conservative FAILED arm (the "Failed" pill and, after C1, the settledFailed line). ---- */
describe('HERO-1c C3 — an outcome string this build does not know renders in the honest register on the chain', () => {
  it('PARTIALLY_APPLIED → the not-recorded line and the "Recorded as" pill — never "Failed", never "Turned on"', () => {
    const { text } = actionStep(action({ outcome: 'PARTIALLY_APPLIED' as unknown as CausalAction['outcome'] }));
    expect(text).toContain("What happened to Hallway Light isn't recorded.");
    expect(text).toContain('Recorded as "PARTIALLY_APPLIED"');
    expect(text).not.toMatch(/\bFailed\b/);
    expect(text).not.toContain(HEAD_LINE);
  });
});

/* ---- HERO-1c correction D2 (2026-09-13) — SPEC §7 :281 `explain.mode.settledFailed.lineNoCommand`:
 * a FAILED step that never issued a command (:776 — `command` null, a named target) says so.
 * RED at the HERO-1c tree: the settledFailed line rendered the SPEC's null verb — "The command to
 * act Hallway Light failed — …". ---- */
describe('HERO-1c D2 — a FAILED step with no command and a named target', () => {
  it('renders explain.mode.settledFailed.lineNoCommand with the reason clause — never "to act"', () => {
    const { text } = actionStep(action({ outcome: 'FAILED', command: null, resultOutcome: 'rejected', reason: 'target unavailable' }), 'FAILED');
    expect(text).toContain('No command was sent to Hallway Light — this step failed — target unavailable.');
    expect(text).not.toContain('to act');
  });

  it('and without a recorded reason the clause is empty', () => {
    const { text } = actionStep(action({ outcome: 'FAILED', command: null, resultOutcome: null, reason: null }), 'FAILED');
    expect(text).toContain('No command was sent to Hallway Light — this step failed.');
  });
});

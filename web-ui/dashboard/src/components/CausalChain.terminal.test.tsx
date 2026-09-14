/*
 * HERO-1d D1 (2026-09-13) — `terminalLine` is keyed: every arm of the terminal step's line is a
 * SPEC §7 `explain.terminal.*` row read through heroCopy(), byte-identical to the literal it
 * replaced (charter §0: this lane moves sentences, it changes none). RED at HEAD: the keyless
 * arms — outcome null (`.unrecorded`) · the no-duration arms (`.completed.noTime`,
 * `.completed.nothing.noTime`, `.completed.open.noTime`, `.completed.open.one.noTime`) · the
 * singular open arm (`.completed.open.one`) · INTERRUPTED and a status this build does not know
 * (`.status`, HEAD's `${runStatusMeta(s).label}.` tail) — read an undefined key, so heroCopy()
 * yields ''. Preservation rows (GREEN at HEAD, named): the arms whose existing key already
 * matched HEAD's literal byte for byte — `.completed` · `.completed.open` (count > 1) ·
 * `.completed.nothing` · `.skipped` (both reason arms) · `.failed` (both) · `.cancelled` — and
 * the HERO-1b honesty row (a missing duration is omitted, never "0.0s"). Every row ALSO pins the
 * literal itself, so the catalog cannot drift from the screen.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import { CausalChain } from './CausalChain';
import type { CausalChain as Chain, CausalAction, RunStatus } from '../lib/api/contract';
import { heroCopy, NOT_RECORDED, runStatusMeta } from '../lib/format';
import type { MessageKey } from '../lib/i18n';

afterEach(cleanup);

const AT = '2026-07-27T23:47:00Z';
const SECS = 412; // → "0.4s"

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
/** A held-DISPATCHED step: provisional — the §5.9 "has not settled yet" arm. */
const held = () => action({ outcome: 'DISPATCHED', settled: false });

type Outcome = Chain['outcome'];
function chain(outcome: Outcome | null, actions: CausalAction[] = [action()]): Chain {
  return {
    runId: 'run_terminal',
    automationId: 'auto_evening',
    automationName: 'Evening Lights',
    trigger: { type: 'state_changed', subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' }, matchedAt: AT, firingValue: null },
    conditions: [],
    actions,
    outcome: outcome as Outcome,
    cascade: { parentRunId: null, depth: 0 },
  };
}
const done = (over: Partial<Outcome> = {}): Outcome => ({ status: 'COMPLETED', reason: null, durationMs: SECS, actionCount: 1, commandCount: 1, ...over });
/** The live wire's missing duration — guarded at CausalChain.tsx `secs` (HERO-1b: omitted, never "0.0s"). */
const NO_TIME = null as unknown as number;

/** The terminal step's LINE alone — the first span of its step line; never the sr-only text, never the pill. */
function terminalText(c: Chain): string {
  const { container } = render(<CausalChain chain={c} />);
  const li = container.querySelector('li[data-kind="outcome"]')!;
  expect(li).toBeTruthy();
  return li.querySelector('div > div > span')?.textContent ?? '';
}
const k = (key: string, slots: Record<string, string> = {}) => heroCopy(key as MessageKey, slots);

type Row = [name: string, build: () => Chain, key: string, slots: Record<string, string>, literal: string];
const KEYLESS_AT_HEAD: Row[] = [
  ['outcome null → explain.terminal.unrecorded at {notRecorded}', () => chain(null), 'explain.terminal.unrecorded', { notRecorded: NOT_RECORDED }, 'Outcome not recorded.'],
  ['COMPLETED, no duration → .completed.noTime', () => chain(done({ durationMs: NO_TIME })), 'explain.terminal.completed.noTime', {}, 'Done.'],
  ['COMPLETED do-nothing, no duration → .completed.nothing.noTime', () => chain(done({ durationMs: NO_TIME, actionCount: 2, commandCount: 0 }), []), 'explain.terminal.completed.nothing.noTime', {}, 'Finished, but nothing was changed.'],
  ['COMPLETED, one open outcome → .completed.open.one at {secs}', () => chain(done(), [held()]), 'explain.terminal.completed.open.one', { secs: '0.4' }, 'Done in 0.4s — one outcome has not settled yet.'],
  ['COMPLETED, one open outcome, no duration → .completed.open.one.noTime', () => chain(done({ durationMs: NO_TIME }), [held()]), 'explain.terminal.completed.open.one.noTime', {}, 'Done — one outcome has not settled yet.'],
  ['COMPLETED, two open outcomes, no duration → .completed.open.noTime at {count}', () => chain(done({ durationMs: NO_TIME, actionCount: 2, commandCount: 2 }), [held(), held()]), 'explain.terminal.completed.open.noTime', { count: '2' }, 'Done — 2 outcomes have not settled yet.'],
  ['INTERRUPTED → .status at the recorded label (HEAD\'s "Interrupted.")', () => chain(done({ status: 'INTERRUPTED' })), 'explain.terminal.status', { label: 'Interrupted' }, 'Interrupted.'],
  ['a status this build does not know → .status, shown as recorded', () => chain(done({ status: 'PAUSED' as RunStatus })), 'explain.terminal.status', { label: runStatusMeta('PAUSED').label }, 'Recorded as "PAUSED".'],
];
const PRESERVATION: Row[] = [
  ['COMPLETED with a duration → .completed at {secs}', () => chain(done()), 'explain.terminal.completed', { secs: '0.4' }, 'Done in 0.4s.'],
  ['COMPLETED, two open outcomes → .completed.open at {secs}·{count}', () => chain(done({ actionCount: 2, commandCount: 2 }), [held(), held()]), 'explain.terminal.completed.open', { secs: '0.4', count: '2' }, 'Done in 0.4s — 2 outcomes have not settled yet.'],
  ['COMPLETED do-nothing (the silent skip) → .completed.nothing at {secs}', () => chain(done({ actionCount: 3, commandCount: 0 }), []), 'explain.terminal.completed.nothing', { secs: '0.4' }, 'Finished in 0.4s, but nothing was changed.'],
  ['SKIPPED with a reason → .skipped at {reasonClause}', () => chain(done({ status: 'SKIPPED', reason: 'Condition not met' })), 'explain.terminal.skipped', { reasonClause: ' — Condition not met' }, 'Skipped — Condition not met.'],
  ['SKIPPED without a reason → .skipped, the empty clause', () => chain(done({ status: 'SKIPPED' })), 'explain.terminal.skipped', { reasonClause: '' }, 'Skipped.'],
  ['FAILED with a reason → .failed at {reasonClause}', () => chain(done({ status: 'FAILED', reason: 'device offline' })), 'explain.terminal.failed', { reasonClause: ' — device offline' }, 'Failed — device offline.'],
  ['FAILED without a reason → .failed, the empty clause', () => chain(done({ status: 'FAILED' })), 'explain.terminal.failed', { reasonClause: '' }, 'Failed.'],
  ['CANCELLED → .cancelled', () => chain(done({ status: 'CANCELLED' })), 'explain.terminal.cancelled', {}, 'Cancelled.'],
];

describe('HERO-1d D1 — terminalLine is the catalog', () => {
  it.each(KEYLESS_AT_HEAD)('%s [RED at HEAD — the key is undefined]', (_name, build, key, slots, literal) => {
    const line = terminalText(build());
    expect(line).toBe(literal); // the screen is byte-identical to HEAD's literal
    expect(line).toBe(k(key, slots)); // and it is the catalog's row
  });

  it.each(PRESERVATION)('%s [GREEN at HEAD — the existing key already matched the literal; preservation]', (_name, build, key, slots, literal) => {
    const line = terminalText(build());
    expect(line).toBe(literal);
    expect(line).toBe(k(key, slots));
  });

  it('a missing duration is OMITTED, never "0.0s" [GREEN at HEAD — the HERO-1b honesty row; preservation]', () => {
    expect(terminalText(chain(done({ durationMs: NO_TIME })))).not.toMatch(/\d\.\ds/);
    expect(terminalText(chain(done({ durationMs: NO_TIME }), [held()]))).not.toMatch(/\d\.\ds/);
    expect(terminalText(chain(done({ durationMs: NO_TIME, actionCount: 2, commandCount: 0 }), []))).not.toMatch(/\d\.\ds/);
  });

  it('the {count} slot is the OPEN count, never the action count [GREEN at HEAD by construction; preservation]', () => {
    expect(terminalText(chain(done({ actionCount: 3, commandCount: 3 }), [held(), held(), action()]))).toBe('Done in 0.4s — 2 outcomes have not settled yet.');
  });

  it('no sentence changed: INTERRUPTED keeps HEAD\'s recorded label — `explain.terminal.interrupted` ("Cut off before it finished.") is a §7 row this lane does not consume', () => {
    const line = terminalText(chain(done({ status: 'INTERRUPTED' })));
    expect(line).toBe('Interrupted.');
    expect(line).not.toBe(k('explain.terminal.interrupted'));
  });
});

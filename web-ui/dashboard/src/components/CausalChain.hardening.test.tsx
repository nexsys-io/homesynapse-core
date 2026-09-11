/*
 * FE-LIVE-V112 item 1 — the causal-chain render vs the LIVE wire (RED-FIRST).
 * ---------------------------------------------------------------------------
 * The live wire serves optionals PRESENT-BUT-NULL beside populated siblings
 * (`resultOutcome: null` with `settled: true`; `reason`, `trigger.type`,
 * `automationName`, `firingValue` all observed null — the 2026-07-27
 * devtools-chain-glance return). The mocks only ever emitted populated-or-ABSENT,
 * so a fixture-green `.toLowerCase()` crashed the chain render on live data and
 * the crash killed the view's polling loop. The real seam is TRI-STATE
 * (absent / null / value), not binary.
 *
 * These tests feed the render the shapes production actually emits — a
 * present-but-null row for EVERY nullable key — plus the genuinely-empty chain
 * and the era-skeleton. House law: written RED before the fix; the honest-state
 * copy is locked here so it cannot quietly regress into a blank or a lie.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import axe from 'axe-core';
import { CausalChain } from './CausalChain';
import type { CausalChain as Chain, CausalAction } from '../lib/api/contract';
import { validators } from '../lib/api/shapes';
import { SCENARIOS } from '../lib/api/mock/scenarios';
import { createMockTransport } from '../lib/api/mock/mockTransport';
import {
  CASCADE_PARENT_UNRECORDED,
  clockTimeWithDate,
  EMPTY_CHAIN_NOTE,
  NO_READING_YET,
  NOT_RECORDED,
  NULL_NAME_NOTE,
  SKIPPED_BEFORE_COMMAND,
  UNNAMED_TARGET,
  UNRESOLVED_REF_PILL,
  unrecordedTriggerLine,
} from '../lib/format';
import { makeRefResolver } from '../lib/registry';
import { causalChains } from '../lib/api/mock/mockData';

afterEach(cleanup);

/* ---- The live shape, built exactly as observed (WCAP-2 + the chain glance):
 * every nullable key PRESENT and null, beside populated siblings. ---- */
function liveNullAction(over: Partial<CausalAction> = {}): CausalAction {
  return {
    type: 'device_command',
    targetRef: { type: 'ENTITY', id: 'ent_livingroom_lamp' },
    command: 'turn_on',
    params: {},
    outcome: 'CONFIRMED',
    reason: null,
    resultOutcome: null, // observed: null beside settled: true
    settled: true,
    ...over,
  };
}

function liveNullChain(over: Partial<Chain> = {}): Chain {
  return {
    runId: 'run_live_nulls',
    automationId: 'auto_rotated',
    automationName: null, // observed (identity rotation)
    trigger: {
      type: null, // observed (identity rotation)
      subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' },
      matchedAt: '2026-07-27T23:47:00Z',
      firingValue: null, // observed in ALL eras — the .toLowerCase() crash field
    },
    conditions: [],
    actions: [liveNullAction()],
    outcome: { status: 'COMPLETED', reason: null, durationMs: 412, actionCount: 1, commandCount: 1 },
    cascade: { parentRunId: null, depth: 0 },
    ...over,
  };
}

describe('present-but-null (the live tri-state seam) — the render must not throw', () => {
  it('renders the full observed present-but-null chain without throwing', () => {
    expect(() => render(<CausalChain chain={liveNullChain()} />)).not.toThrow();
  });

  // The corpus sweep: one test row per nullable key, so no single-occurrence
  // patch can go green while a sibling site still throws.
  const rows: { key: string; chain: () => Chain }[] = [
    { key: 'trigger.firingValue', chain: () => liveNullChain({ automationName: 'Named', trigger: { type: 'StateChangeTrigger', subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' }, matchedAt: '2026-07-27T23:47:00Z', firingValue: null } }) },
    { key: 'trigger.type', chain: () => liveNullChain({ trigger: { type: null, subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' }, matchedAt: '2026-07-27T23:47:00Z', firingValue: 'motion = detected' } }) },
    { key: 'automationName', chain: () => liveNullChain({ automationName: null }) },
    { key: 'actions[].reason', chain: () => liveNullChain({ actions: [liveNullAction({ reason: null })] }) },
    { key: 'actions[].resultOutcome', chain: () => liveNullChain({ actions: [liveNullAction({ resultOutcome: null, settled: true })] }) },
    { key: 'outcome.reason', chain: () => liveNullChain({ outcome: { status: 'FAILED', reason: null, durationMs: 90, actionCount: 1, commandCount: 1 } }) },
    { key: 'cascade.parentRunId', chain: () => liveNullChain({ cascade: { parentRunId: null, depth: 0 } }) },
  ];
  for (const row of rows) {
    it(`renders with ${row.key} = null, and never prints "null" as if it were data`, () => {
      const { container } = render(<CausalChain chain={row.chain()} />);
      expect(container.textContent).not.toMatch(/\bnull\b/);
    });
  }

  it('a null firingValue renders the honest "not recorded" marker, never an invented value', () => {
    const { container } = render(<CausalChain chain={liveNullChain()} />);
    const text = container.textContent ?? '';
    expect(text).toContain(NOT_RECORDED);
    expect(text).not.toContain('(null)');
  });

  it('a null automationName still renders the calm prior-instance note', () => {
    const { container } = render(<CausalChain chain={liveNullChain()} />);
    expect(container.textContent).toContain(NULL_NAME_NOTE);
  });

  it('null resultOutcome beside settled:true renders the outcome pill honestly (no lie, no crash)', () => {
    const { container } = render(
      <CausalChain chain={liveNullChain({ actions: [liveNullAction({ outcome: 'UNCONFIRMED', resultOutcome: null, settled: true })] })} />,
    );
    // Honest-can't-know register — never a fake success, never a bare crash.
    expect(container.textContent).toContain('Sent — no reply');
  });

  it('the hardened present-but-null chain passes the axe structural rules', async () => {
    const { container } = render(<CausalChain chain={liveNullChain()} />);
    const res = await axe.run(container as HTMLElement, {
      rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    });
    expect(res.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });
});

describe('the honest empty state — a real, successful, genuinely empty chain', () => {
  const emptyChain = (): Chain =>
    liveNullChain({
      automationName: 'Quiet automation',
      conditions: [],
      actions: [],
      outcome: { status: 'COMPLETED', reason: null, durationMs: 12, actionCount: 0, commandCount: 0 },
    });

  it('renders the explicit empty note — never a silent blank, never an error posture', () => {
    const { container } = render(<CausalChain chain={emptyChain()} />);
    const text = container.textContent ?? '';
    expect(text).toContain(EMPTY_CHAIN_NOTE);
    // Nothing failed: the empty state must not borrow the error register.
    expect(text).not.toMatch(/went wrong|failed/i);
  });

  it('the era-skeleton (actionCount > 0, empty actions[]) still never reads as clean success', () => {
    const skeleton = liveNullChain({
      actions: [],
      outcome: { status: 'COMPLETED', reason: null, durationMs: 41, actionCount: 9, commandCount: 0 },
    });
    const { container } = render(<CausalChain chain={skeleton} />);
    const text = container.textContent ?? '';
    expect(text).toContain('nothing was changed');
    expect(text).not.toMatch(/Done in [\d.]+s\.\s*$/);
  });
});

describe('the live-nulls mock fixtures (the shape that actually broke, now fixture-covered)', () => {
  const scenario = () => SCENARIOS.find((s) => s.id === 'live-nulls');

  it('the scenario exists and its every chain validates against the frozen contract', () => {
    const s = scenario();
    expect(s).toBeDefined();
    const data = s!.build();
    const chains = Object.values(data.causalChains);
    expect(chains.length).toBeGreaterThanOrEqual(2);
    for (const c of chains) {
      expect(() =>
        validators['B3:causalChain']({ data: c, meta: { viewPosition: 1, timestamp: 't' } }),
      ).not.toThrow();
    }
  });

  it('carries a present-but-null row for every nullable chain key', () => {
    const data = scenario()!.build();
    const nulls = data.causalChains['run_ln_nulls']!;
    expect(nulls.automationName).toBeNull();
    expect(nulls.trigger.type).toBeNull();
    expect(nulls.trigger.firingValue).toBeNull();
    expect(nulls.actions.some((a) => a.reason === null)).toBe(true);
    expect(nulls.actions.some((a) => a.resultOutcome === null && a.settled === true)).toBe(true);
    expect(nulls.outcome.reason).toBeNull();
  });

  it('carries a genuinely empty chain and an error case (run listed, chain 404s)', async () => {
    const data = scenario()!.build();
    const empty = data.causalChains['run_ln_empty']!;
    expect(empty.actions).toEqual([]);
    expect(empty.conditions).toEqual([]);
    expect(empty.outcome.actionCount).toBe(0);
    // The error fixture: the run is listed but no chain exists — the mock serves
    // the honest 404 problem, which the view must surface as a retryable error.
    expect(data.runs.some((r) => r.runId === 'run_ln_missing')).toBe(true);
    expect(data.causalChains['run_ln_missing']).toBeUndefined();
  });

  it('every chain in EVERY scenario renders without throwing (the fixture-blindness sweep)', () => {
    for (const s of SCENARIOS) {
      for (const [id, chain] of Object.entries(s.build().causalChains)) {
        expect(() => render(<CausalChain chain={chain} />), `${s.id}/${id}`).not.toThrow();
        cleanup();
      }
    }
  });

  it('the mock transport 404s the missing chain with the honest problem shape', async () => {
    const transport = createMockTransport(() => 'test-token');
    const res = await transport.send({ method: 'GET', path: '/api/v1/runs/run_no_such/causal-chain' });
    expect(res.status).toBe(404);
  });
});

/* ---- FE-NULL-1 (2026-09-10): the four REQUIRED-NULLABLE chain keys + the F4 row render the
 * honest sentence HERO-0 wrote (context/research/2026-09-06_HERO-0_null-census_v1.1.3_return.md §1).
 * The wire (core 39c8dd3): `trigger.subjectRef` null when the triggering event is outside the
 * run's correlation (StandardExplanationService:644–:649); `observedState[].value` null when the
 * entity had no value at evaluation (RunExplanation:137); `actions[].command` + `targetRef` null
 * for a SKIPPED/FAILED action that never issued a command (:771/:776); `cascade.parentRunId`
 * ALWAYS null in V1 (RunExplanation:213–:219) — so depth > 0 must say so, never render nothing.
 * Red-first: each arm's sentence is RED at HEAD (the copy constants do not exist; the fixtures
 * are forbidden by HEAD's types); the depth-0 and the non-null-run rows are GREEN-BY-CONSTRUCTION
 * regression guards — disclosed. A null NEVER renders as "null", and a null ref accuses nothing:
 * no dangling pill even under a COMPLETE registry census (a null is not an unresolvable id). */
describe("FE-NULL-1 — the chain's null arms render the honest sentence, never a blank, never an accusation", () => {
  const REGISTRY = [
    { entityId: 'ent_hallway_motion', availability: 'AVAILABLE' as const, stale: false },
    { entityId: 'ent_hallway_light', name: 'Hallway Light', availability: 'AVAILABLE' as const, stale: false },
    { entityId: 'sys_sun', availability: 'AVAILABLE' as const, stale: false },
    { entityId: 'ent_livingroom_lamp', name: 'Living Room Lamp', availability: 'AVAILABLE' as const, stale: false },
  ];
  const census = () => makeRefResolver(REGISTRY, true);
  const AT = '2026-07-27T23:47:00Z';

  it('trigger.subjectRef null → "Something set it off at {time} — what isn\'t recorded." (matchedAt kept; no pill, no label)', () => {
    const chain = liveNullChain({ automationName: 'Named', trigger: { type: 'state_changed', subjectRef: null, matchedAt: AT, firingValue: null } });
    const { container } = render(<CausalChain chain={chain} resolveRef={census()} />);
    const text = container.textContent ?? '';
    expect(text).toContain(`Something set it off at ${clockTimeWithDate(AT)} — what isn't recorded.`);
    expect(text).toContain(unrecordedTriggerLine(clockTimeWithDate(AT)));
    expect(text).not.toMatch(/\bnull\b/);
    expect(text).not.toContain(UNRESOLVED_REF_PILL); // a null ref is not a dangling ref — nothing is accused
    expect(text).not.toContain('Something not on record'); // the refLabel fallback is a label, not this sentence
    // The trigger step still discloses the type / firingValue detail.
    expect(text).toContain(`value ${NOT_RECORDED}`);
  });

  it('observedState[].value null → "{entity} {attr} had no reading yet." in the "At the time" detail (never "= null")', () => {
    const chain = liveNullChain({
      automationName: 'Named',
      conditions: [{ expression: 'time is after sunset', evaluated: true, result: true, observedState: [{ entityId: 'sys_sun', attribute: 'elevation', value: null }] }],
    });
    const { container } = render(<CausalChain chain={chain} />);
    const text = container.textContent ?? '';
    expect(text).toContain(`Sun elevation ${NO_READING_YET}`);
    expect(text).toContain('had no reading yet.');
    expect(text).not.toMatch(/=\s*null|\bnull\b/);
  });

  it('actions[].command null → "Skipped before any command was sent." (not the actionPhrase fallback); the Command detail says not recorded', () => {
    const chain = liveNullChain({
      automationName: 'Named',
      actions: [liveNullAction({ command: null, targetRef: null, params: {}, outcome: 'SKIPPED', reason: 'Condition not met', resultOutcome: null, settled: true })],
      outcome: { status: 'SKIPPED', reason: 'Condition not met', durationMs: 38, actionCount: 1, commandCount: 0 },
    });
    const { container } = render(<CausalChain chain={chain} resolveRef={census()} />);
    const text = container.textContent ?? '';
    expect(text).toContain(SKIPPED_BEFORE_COMMAND);
    expect(text).toContain('Skipped before any command was sent.');
    expect(text).not.toContain('Ran an unrecorded command on');
    expect(text).not.toContain(UNRESOLVED_REF_PILL);
    expect(text).not.toMatch(/\bnull\b/);
    expect(text).toContain(NOT_RECORDED); // the Command detail (CausalChain.tsx `a.command ?? NOT_RECORDED`) is kept
  });

  it('actions[].targetRef null (command present) → the line ends "…a device the run didn\'t name." — no pill under a complete census', () => {
    const chain = liveNullChain({ automationName: 'Named', actions: [liveNullAction({ targetRef: null, command: 'turn_on', outcome: 'FAILED', resultOutcome: 'rejected', settled: true })] });
    const { container } = render(<CausalChain chain={chain} resolveRef={census()} />);
    const text = container.textContent ?? '';
    expect(text).toContain(`Turned on ${UNNAMED_TARGET}.`);
    expect(text).toContain("a device the run didn't name.");
    expect(text).not.toContain(UNRESOLVED_REF_PILL);
    expect(text).not.toContain('Something not on record');
    expect(text).not.toMatch(/\bnull\b/);
  });

  it('F4: cascade depth > 0 with parentRunId null → "Started by another run — which one isn\'t recorded." and NO link', () => {
    const chain = liveNullChain({ automationName: 'Named', cascade: { parentRunId: null, depth: 1 } });
    const { container } = render(<CausalChain chain={chain} />);
    const text = container.textContent ?? '';
    expect(text).toContain(CASCADE_PARENT_UNRECORDED);
    expect(text).toContain("Started by another run — which one isn't recorded.");
    expect(container.querySelector('a[href*="/explain/run/"]')).toBeNull();
    expect(text).not.toMatch(/\bnull\b/);
  });

  it('depth 0 still renders no cascade line at all [GREEN at HEAD by construction — the regression guard]', () => {
    const { container } = render(<CausalChain chain={liveNullChain({ automationName: 'Named', cascade: { parentRunId: null, depth: 0 } })} />);
    const text = container.textContent ?? '';
    expect(text).not.toContain(CASCADE_PARENT_UNRECORDED);
    expect(container.querySelector('a[href*="/explain/run/"]')).toBeNull();
  });

  it('a non-null run renders exactly as before — none of the four sentences appear on the happy path [GREEN at HEAD by construction]', () => {
    const { container } = render(<CausalChain chain={causalChains['run_eh_001']!} resolveRef={census()} />);
    const text = container.textContent ?? '';
    expect(text).toMatch(/Hallway Motion detected motion at /);
    expect(text).toContain('Turned on Hallway Light.');
    expect(text).toContain('Sun elevation = -6.2°');
    expect(text).not.toContain(SKIPPED_BEFORE_COMMAND);
    expect(text).not.toContain(UNNAMED_TARGET);
    expect(text).not.toContain(CASCADE_PARENT_UNRECORDED);
    expect(text).not.toContain(NO_READING_YET);
    expect(text).not.toContain('Something set it off');
  });

  it("the default mock's SKIPPED run (both nulls + a null trigger subject) renders every arm honestly and passes axe", async () => {
    const { container } = render(<CausalChain chain={causalChains['run_eh_003']!} resolveRef={census()} />);
    const text = container.textContent ?? '';
    expect(text).toContain(SKIPPED_BEFORE_COMMAND);
    expect(text).toContain('Something set it off at ');
    expect(text).not.toContain(UNRESOLVED_REF_PILL);
    expect(text).not.toMatch(/\bnull\b/);
    const res = await axe.run(container as HTMLElement, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
    expect(res.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });
});

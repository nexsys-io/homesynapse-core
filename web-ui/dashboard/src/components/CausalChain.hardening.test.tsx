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
  heroCopy,
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
import { t, type MessageKey } from '../lib/i18n';

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
    // HERO-1b B4 (SPEC §4): this fixture carries `trigger.type: null` — the prior-instance
    // class — so it is the ERA BOUNDARY and renders the "no detail recorded" body; the
    // current-instance empty chain (type on record) keeps EMPTY_CHAIN_NOTE. Two facts.
    const { container } = render(<CausalChain chain={emptyChain()} />);
    const text = container.textContent ?? '';
    expect(text).toContain('It happened before the current automations were loaded, so the run was kept but not its steps.');
    const current = emptyChain();
    current.trigger = { ...current.trigger, type: 'state_changed' };
    expect(render(<CausalChain chain={current} />).container.textContent).toContain(EMPTY_CHAIN_NOTE);
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
    expect(text).toContain('Hallway Light turned on.'); // HERO-1c C1: the confirmed mode's own line (flipped from HEAD's 'Turned on Hallway Light.')
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

/* ---- HERO-1b B4 (2026-09-12): the chain's empties split (SPEC §4, design/hero-v1/SPEC.md:88)
 * and the trigger step's reading-not-recorded arm (SPEC.md:90). RED at HEAD: CausalChain.tsx
 * :67–:68 has ONE `genuinelyEmpty` state (EMPTY_CHAIN_NOTE) for both facts, and :105 writes the
 * trigger line as "{Trigger} changed at {time}." when firingValue is null. ---- */
describe('HERO-1b B4 — two empty facts, two sentences (the era boundary vs a current automation that planned nothing)', () => {
  const AT = '2026-07-27T23:47:00Z';
  const skeleton = (over: Partial<Chain> = {}) =>
    liveNullChain({
      conditions: [],
      actions: [],
      outcome: { status: 'COMPLETED', reason: null, durationMs: 12, actionCount: 0, commandCount: 0 },
      ...over,
    });

  it('the era skeleton (automationName null) renders the "no detail recorded" title as the headline and the body as one step — never "recorded no steps"', () => {
    const { container } = render(<CausalChain chain={skeleton()} />);
    const text = container.textContent ?? '';
    expect(text).toContain("This run is on record, but its steps aren't.");
    expect(text).toContain('It happened before the current automations were loaded, so the run was kept but not its steps. Records are never removed.');
    expect(text).not.toContain(EMPTY_CHAIN_NOTE);
    // FE-114 D7: narrowed by one token to the headline fact it guards (`explain.headline.completed.none` ends
    // "…and recorded no steps.") — the terminal step now reads SPEC §4's "Done, recorded no steps." for this
    // skeleton (SPEC.md:88, the `explain.terminal.noSteps` row). Old: not.toContain('recorded no steps').
    expect(text).not.toContain('and recorded no steps');
    expect(text).toContain(NULL_NAME_NOTE); // the name note still explains the null name
    expect(container.querySelector('li[data-kind="outcome"]')).toBeTruthy(); // the terminal step stays
  });

  it('the era skeleton by trigger.type null alone (name on record) renders the same two sentences', () => {
    const { container } = render(<CausalChain chain={skeleton({ automationName: 'Old hallway rule', trigger: { type: null, subjectRef: null, matchedAt: AT, firingValue: null } })} />);
    const text = container.textContent ?? '';
    expect(text).toContain("This run is on record, but its steps aren't.");
    expect(text).not.toContain('Old hallway rule ran when');
  });

  it('a CURRENT automation that planned nothing renders the completed.none headline — not the era card', () => {
    const chain = skeleton({ automationName: 'Quiet automation', trigger: { type: 'state_changed', subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' }, matchedAt: AT, firingValue: null } });
    const { container } = render(<CausalChain chain={chain} />);
    const text = container.textContent ?? '';
    expect(text).toContain(`Quiet automation ran when Hallway Motion changed at ${clockTimeWithDate(AT)} and recorded no steps.`);
    expect(text).not.toContain("This run is on record, but its steps aren't.");
    expect(text).not.toContain('It happened before the current automations were loaded');
  });

  it('the trigger step: firingValue null → "{Trigger} set it off at {time} — the reading wasn\'t recorded." as the LINE (Q6), the L2 says value not recorded', () => {
    const { container } = render(<CausalChain chain={liveNullChain({ automationName: 'Evening Lights', trigger: { type: 'state_changed', subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' }, matchedAt: AT, firingValue: null } })} />);
    const text = container.textContent ?? '';
    expect(text).toContain(`Hallway Motion set it off at ${clockTimeWithDate(AT)} — the reading wasn't recorded.`);
    const stepLine = container.querySelector('li[data-kind="trigger"]')?.textContent ?? '';
    expect(stepLine).not.toContain(`Hallway Motion changed at ${clockTimeWithDate(AT)}.`); // the headline's because-slot may say "changed"; the STEP line says the reading is unrecorded
    expect(text).toContain('value not recorded');
  });

  it('the trigger step: subjectRef null AND firingValue null → the one unrecorded sentence, no reading marker', () => {
    const { container } = render(<CausalChain chain={liveNullChain({ trigger: { type: 'state_changed', subjectRef: null, matchedAt: AT, firingValue: null } })} />);
    const text = container.textContent ?? '';
    expect(text).toContain(unrecordedTriggerLine(clockTimeWithDate(AT)));
    expect(text).not.toContain("the reading wasn't recorded");
  });

  it('the trigger step with a firing value keeps the verb line [GREEN at HEAD by construction]', () => {
    const { container } = render(<CausalChain chain={causalChains['run_eh_001']!} />);
    expect(container.textContent).toMatch(/Hallway Motion detected motion at /);
  });
});

/* ---- HERO-1b B7 (2026-09-12): the `hero-states` scenario — one run per SPEC §3 row today's
 * emitter can produce (COMPLETED × each outcome · the silent skip · the era skeleton · one
 * each of SKIPPED / FAILED / CANCELLED / INTERRUPTED · a replaced command for §10 sentence 6),
 * carrying the wire's null arms as they are: `firingValue` null everywhere (all eras, 2026-07-27),
 * `resultOutcome` null beside CONFIRMED (the R-4b record F-R4b-H — the zigbee handler publishes
 * command_result only on failure). Each run stays in ONE era (audit O1). RED at HEAD: no such
 * scenario exists (SCENARIOS is 14 entries, scenarios.ts:912–:927). ---- */
describe('HERO-1b B7 — the hero-states scenario', () => {
  const scenario = () => SCENARIOS.find((s) => s.id === 'hero-states');

  it('exists, and every chain passes the tri-state validator', () => {
    expect(scenario()).toBeTruthy();
    const ds = scenario()!.build();
    for (const c of Object.values(ds.causalChains)) {
      expect(() => validators['B3:causalChain']({ data: c, meta: { viewPosition: 1, timestamp: 't' } })).not.toThrow();
    }
    for (const nf of Object.values(ds.nonFiring)) {
      expect(() => validators['B3:nonFiring']({ data: nf, meta: { viewPosition: 1, timestamp: 't' } })).not.toThrow();
    }
  });

  it('carries the twelve rows, one run each, in one era each', () => {
    const ds = scenario()!.build();
    const chains = Object.values(ds.causalChains);
    expect(chains.length).toBe(12);
    expect(ds.runs.length).toBe(12);
    const cell = (c: Chain) => `${c.outcome.status}×${c.actions[0]?.outcome ?? (c.outcome.actionCount > 0 ? 'silentSkip' : c.automationName === null ? 'era' : 'none')}${c.actions[0]?.resultOutcome ? '/' + c.actions[0].resultOutcome : ''}`;
    expect(new Set(chains.map(cell))).toEqual(
      new Set([
        'COMPLETED×CONFIRMED', 'COMPLETED×DISPATCHED', 'COMPLETED×UNCONFIRMED', 'COMPLETED×FAILED/rejected', 'COMPLETED×SKIPPED',
        'COMPLETED×silentSkip', 'COMPLETED×era',
        'SKIPPED×SKIPPED', 'FAILED×FAILED/rejected', 'CANCELLED×CONFIRMED', 'INTERRUPTED×FAILED/expired_on_restart',
        'COMPLETED×DISPATCHED/superseded',
      ]),
    );
    for (const c of chains) {
      expect(c.trigger.firingValue, c.runId).toBeNull(); // today's wire: never a reading
      for (const a of c.actions) {
        if (a.outcome === 'CONFIRMED') expect(a.resultOutcome, c.runId).toBeNull(); // F-R4b-H: confirmed by the device's own report, no verdict row
        if (a.outcome === 'SKIPPED') expect(a.command, c.runId).toBeNull(); // :776 — never issued
      }
    }
  });

  it('every chain renders without throwing, and no headline claims a reading or a delivery', () => {
    const ds = scenario()!.build();
    for (const c of Object.values(ds.causalChains)) {
      const { container } = render(<CausalChain chain={c} />);
      const text = container.textContent ?? '';
      expect(text, c.runId).not.toMatch(/\bnull\b/);
      expect(text, c.runId).not.toContain('detected motion'); // firingValue is null: the verb is "changed"
      expect(text, c.runId).not.toMatch(/\bdelivered\b/i);
      cleanup();
    }
  });

  it('the default mock no longer carries the H8 false value: CONFIRMED actions have resultOutcome null', () => {
    for (const c of Object.values(causalChains)) {
      for (const a of c.actions) if (a.outcome === 'CONFIRMED') expect(a.resultOutcome, c.runId).toBeNull();
    }
  });
});

/* ---- HERO-1d D2 (2026-09-13): the four chain sentences that still lived in the component are
 * catalog rows — the condition line (`explain.condition.line`, a key HERO-1b minted and the
 * component never read), the cascade link (`explain.cascade.parent`, the same), the do-nothing
 * step (`explain.step.nothing.one` / `.many` and its hint `.hint`) and the L2 recovered-reason
 * suffix (`explain.action.detail.outcome.recovered`). Byte-identical on screen (charter §0).
 * RED at HEAD: the four NEW keys are undefined (heroCopy → ''); the condition line and the
 * cascade link already matched their existing rows byte for byte, so those rows are GREEN at
 * HEAD — named preservation (the charter predicted them red; the catalog text equals the
 * literal, so the assertion cannot tell the two apart — disclosed). ---- */
describe('HERO-1d D2 — the condition line, the cascade link, the do-nothing step and the recovered suffix are the catalog', () => {
  const lineOf = (li: Element | null) => li?.querySelector('div > div > span')?.textContent ?? '';
  const key = (k: string, slots: Record<string, string> = {}) => heroCopy(k as MessageKey, slots);

  it('the condition line → explain.condition.line at {condition}·{verdict} [GREEN at HEAD — byte-identical; preservation]', () => {
    const chain = liveNullChain({ automationName: 'Named', conditions: [{ expression: 'time is after sunset', evaluated: true, result: true, observedState: [] }] });
    const { container } = render(<CausalChain chain={chain} />);
    const line = lineOf(container.querySelector('li[data-kind="condition"]'));
    expect(line).toBe('The rule "time is after sunset" was true.');
    expect(line).toBe(key('explain.condition.line', { condition: 'time is after sunset', verdict: 'was true' }));
  });

  it('the condition line, expression null and not evaluated → the NOT_RECORDED slot, "was not checked" [GREEN at HEAD; preservation]', () => {
    const chain = liveNullChain({ automationName: 'Named', conditions: [{ expression: null as unknown as string, evaluated: false, result: false, observedState: [] }] });
    const { container } = render(<CausalChain chain={chain} />);
    const line = lineOf(container.querySelector('li[data-kind="condition"]'));
    expect(line).toBe(`The rule "${NOT_RECORDED}" was not checked.`);
    expect(line).toBe(key('explain.condition.line', { condition: NOT_RECORDED, verdict: 'was not checked' }));
  });

  it('the cascade link → explain.cascade.parent [GREEN at HEAD — byte-identical; preservation]', () => {
    const { container } = render(<CausalChain chain={liveNullChain({ automationName: 'Named', cascade: { parentRunId: 'run_parent', depth: 1 } })} />);
    const a = container.querySelector('a[href*="/explain/run/run_parent"]')!;
    expect(a).toBeTruthy();
    expect(a.textContent).toBe('← See what triggered this run');
    expect(a.textContent).toBe(key('explain.cascade.parent'));
  });

  const doNothing = (actionCount: number) =>
    liveNullChain({ automationName: 'Named', actions: [], outcome: { status: 'COMPLETED', reason: null, durationMs: 41, actionCount, commandCount: 0 } });

  it('the do-nothing step, one planned step → explain.step.nothing.one', () => {
    const { container } = render(<CausalChain chain={doNothing(1)} />);
    const line = lineOf(container.querySelector('li[data-kind="action"]'));
    expect(line).toBe('Nothing was changed: the planned step ended without sending a command.');
    expect(line).toBe(key('explain.step.nothing.one'));
  });

  it('the do-nothing step, several planned steps → explain.step.nothing.many at {count}', () => {
    const { container } = render(<CausalChain chain={doNothing(3)} />);
    const line = lineOf(container.querySelector('li[data-kind="action"]'));
    expect(line).toBe('Nothing was changed: all 3 planned steps ended without sending a command.');
    expect(line).toBe(key('explain.step.nothing.many', { count: '3' }));
  });

  it('the do-nothing hint → explain.step.nothing.hint, the paragraph whole (JSX collapsed its source line break to one space)', () => {
    const { container } = render(<CausalChain chain={doNothing(2)} />);
    const hint = container.querySelector('li[data-kind="action"] p')?.textContent ?? '';
    expect(hint).toBe('This usually means the devices this automation targets were unavailable, so each was skipped by design. The step-by-step record of these skips is not kept yet.');
    expect(hint).toBe(key('explain.step.nothing.hint'));
  });

  it('the L2 recovered-reason suffix → explain.action.detail.outcome.recovered (a pre-v1.1.2 payload: resultOutcome ABSENT, the reason classifiable; the leading space belongs to the row)', () => {
    const legacy = liveNullAction({ outcome: 'FAILED', reason: 'rejected' });
    delete (legacy as Partial<CausalAction>).resultOutcome; // ABSENT, not null — verdicts.ts actionVerdict's recovery path
    const chain = liveNullChain({ automationName: 'Named', actions: [legacy], outcome: { status: 'FAILED', reason: null, durationMs: 90, actionCount: 1, commandCount: 1 } });
    const { container } = render(<CausalChain chain={chain} />);
    const outcomeDetail = Array.from(container.querySelectorAll('li[data-kind="action"] details')).find(
      (d) => d.querySelector('summary')?.textContent === t('explain.action.detail.outcome'),
    )!;
    expect(outcomeDetail).toBeTruthy();
    const body = outcomeDetail.querySelector('div')?.textContent ?? '';
    expect(body).toBe('rejected (recovered from the recorded reason — this record predates the current hub software)');
    expect(body).toBe(`rejected${key('explain.action.detail.outcome.recovered')}`);
  });
});

/* ---- FE-115 D1 (2026-09-19) — the v1.1.5 `conditions[].definition` renders as a sentence UNDER the observed
 * state (a hint line after the "At the time" detail), through the ONE pure function `definitionSentence`; the L1
 * condition line is byte-identical to HEAD (the wire's `expression` is still the type). THE TRI-STATE on screen:
 * key ABSENT (a pre-v1.1.5 hub) → nothing added, HEAD's bytes; PRESENT-null → the not-recorded sentence;
 * PRESENT-object → the sentence. RED at HEAD: no sentence exists for any arm. ---- */
describe('FE-115 D1 — the condition row shows the definition sentence under the observed state (tri-state)', () => {
  const lineOf = (li: Element | null) => li?.querySelector('div > div > span')?.textContent ?? '';
  const hintsOf = (li: Element | null) => Array.from(li?.querySelectorAll(':scope > div > div > p') ?? []).map((p) => p.textContent ?? '');
  const cond = (over: Partial<Chain['conditions'][number]> = {}): Chain['conditions'][number] => ({
    expression: 'StateCondition',
    evaluated: true,
    result: true,
    observedState: [{ entityId: 'ent_hallway_light', attribute: 'power', value: 'on' }],
    ...over,
  });
  const def = {
    type: 'StateCondition',
    selector: 'ent_hallway_light',
    attribute: 'power',
    value: 'on',
    above: null,
    below: null,
    after: null,
    before: null,
    children: [] as never[],
  };
  const chainWith = (c: Chain['conditions'][number]) => liveNullChain({ automationName: 'Named', conditions: [c] });

  it('PRESENT-object → the sentence, after the "At the time" detail; the L1 line unchanged', () => {
    const { container } = render(<CausalChain chain={chainWith(cond({ definition: def }))} />);
    const li = container.querySelector('li[data-kind="condition"]')!;
    expect(lineOf(li)).toBe('The rule "StateCondition" was true.');
    expect(hintsOf(li)).toEqual(['It checks that Hallway Light power is "on".']);
    // order: the detail first, the sentence under it
    const kids = Array.from(li.querySelector(':scope > div > div:nth-child(2)')!.children).map((e) => e.tagName.toLowerCase());
    expect(kids).toEqual(['details', 'p']);
  });
  it('PRESENT-null → the keyed not-recorded sentence (the projection could not vouch; nothing invented)', () => {
    const { container } = render(<CausalChain chain={chainWith(cond({ definition: null }))} />);
    const li = container.querySelector('li[data-kind="condition"]')!;
    expect(hintsOf(li)).toEqual([t('explain.condition.def.notRecorded' as MessageKey)]);
    expect(hintsOf(li)).toEqual(["What this rule checked isn't on record for this run."]);
  });
  it('ABSENT (a pre-v1.1.5 hub) → no sentence at all: the row is HEAD\'s bytes [the honesty law: absent ≠ null]', () => {
    const c = cond();
    expect('definition' in c).toBe(false);
    const { container } = render(<CausalChain chain={chainWith(c)} />);
    const li = container.querySelector('li[data-kind="condition"]')!;
    expect(hintsOf(li)).toEqual([]);
    expect(li.textContent).not.toContain('It checks that');
    expect(li.textContent).not.toContain("isn't on record for this run");
  });
  it('an UNKNOWN type renders the raw type as recorded — never a throw', () => {
    const { container } = render(<CausalChain chain={chainWith(cond({ definition: { ...def, type: 'PresenceCondition' } }))} />);
    const li = container.querySelector('li[data-kind="condition"]')!;
    expect(hintsOf(li)).toEqual(['This is a rule of kind "PresenceCondition" — this dashboard can\'t describe it yet.']);
  });
  it('a compound renders recursively; the sentence sits under the row even when there is no observed state (no detail → the sentence alone)', () => {
    const time = { ...def, type: 'TimeCondition', selector: null, attribute: null, value: null, after: '18:00', before: null };
    const { container } = render(<CausalChain chain={chainWith(cond({ observedState: [], definition: { ...def, type: 'AndCondition', selector: null, attribute: null, value: null, children: [def, time] as never[] } }))} />);
    const li = container.querySelector('li[data-kind="condition"]')!;
    expect(li.querySelector('details')).toBeNull();
    expect(hintsOf(li)).toEqual(['It checks that Hallway Light power is "on" and the time is after 18:00.']);
  });
  it('a ULID selector goes through the registry census: named when resolved, LOUD when dangling (the observed-state rule), as recorded for a group selector', () => {
    const ulid = '01KX1PB9AAB4VB3E10BD477TVX';
    const resolve = makeRefResolver([{ entityId: 'ent_hallway_light', name: 'Hall Light', availability: 'AVAILABLE', stale: false }], true);
    const named = render(<CausalChain chain={chainWith(cond({ definition: def }))} resolveRef={resolve} />);
    expect(hintsOf(named.container.querySelector('li[data-kind="condition"]'))).toEqual(['It checks that Hall Light power is "on".']);
    cleanup();
    const loud = render(<CausalChain chain={chainWith(cond({ definition: { ...def, selector: ulid } }))} resolveRef={resolve} />);
    expect(hintsOf(loud.container.querySelector('li[data-kind="condition"]'))).toEqual([`It checks that ${ulid} (not in this hub’s registry) power is "on".`]);
    cleanup();
    const group = render(<CausalChain chain={chainWith(cond({ definition: { ...def, selector: 'area:kitchen/PRIMARY' } }))} resolveRef={resolve} />);
    expect(hintsOf(group.container.querySelector('li[data-kind="condition"]'))).toEqual(['It checks that area:kitchen/PRIMARY power is "on".']);
  });
  it('the v115-keys scenario renders every chain without throwing (the fixture-blindness sweep, extended)', () => {
    const d = SCENARIOS.find((s) => s.id === 'v115-keys')!.build();
    for (const chain of Object.values(d.causalChains)) {
      expect(() => render(<CausalChain chain={chain} />)).not.toThrow();
      cleanup();
    }
  });
});

/* ---- FE-115 D4 (2026-09-19) — actionPhrase's three verbs are §7 rows (`explain.action.phrase.*`), byte-identical:
 * the unnamed-target line and the loud dangling-target line read them. GREEN at HEAD on the bytes (preservation,
 * disclosed); RED at HEAD on the key half (no such key). ---- */
describe('FE-115 D4 — the action phrase verbs read the catalog; the lines are HEAD\'s bytes', () => {
  const lineOf = (li: Element | null) => li?.querySelector('div > div > span')?.textContent ?? '';
  const unnamed = (command: string) =>
    liveNullChain({ automationName: 'Named', actions: [liveNullAction({ command, targetRef: null, outcome: 'UNCONFIRMED', settled: true })] });
  it.each([
    ['turn_on', 'explain.action.phrase.turnedOn', 'Turned on'],
    ['turn_off', 'explain.action.phrase.turnedOff', 'Turned off'],
    ['dim', 'explain.action.phrase.dimmed', 'Dimmed'],
  ])('%s → the unnamed-target line starts with the keyed verb (%s = "%s")', (command, key, literal) => {
    const { container } = render(<CausalChain chain={unnamed(command)} />);
    const line = lineOf(container.querySelector('li[data-kind="action"]'));
    expect(line).toBe(`${literal} ${UNNAMED_TARGET}.`);
    expect(line).toBe(`${t(key as MessageKey)} ${UNNAMED_TARGET}.`);
  });
  it('the loud dangling-target line keeps its bytes with the keyed verb', () => {
    const ulid = '01KX1PB9AAB4VB3E10BD477TVX';
    const resolve = makeRefResolver([{ entityId: 'ent_hallway_light', availability: 'AVAILABLE', stale: false }], true);
    const chain = liveNullChain({ automationName: 'Named', actions: [liveNullAction({ command: 'turn_off', targetRef: { type: 'ENTITY', id: ulid } })] });
    const { container } = render(<CausalChain chain={chain} resolveRef={resolve} />);
    expect(lineOf(container.querySelector('li[data-kind="action"]'))).toBe(`Turned off entity ${ulid} — not in this hub’s registry.`);
  });
});

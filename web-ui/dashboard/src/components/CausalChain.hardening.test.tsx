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
import { EMPTY_CHAIN_NOTE, NOT_RECORDED, NULL_NAME_NOTE } from '../lib/format';

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

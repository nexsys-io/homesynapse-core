/*
 * Contract conformance test (the CI guard, dispatch §4).
 * Every mock fixture must validate against the frozen-contract shape. If a shape
 * drifts, this fails in CI — not the demo. When real endpoints land, the same
 * validators can run against live responses to catch Core drift early.
 */
import { describe, it, expect } from 'vitest';
import { createMockTransport } from './mock/mockTransport';
import { ENDPOINT_IDS, validateAgainstContract, validators, CONTRACT_VERSION, type EndpointId } from './shapes';
import { PROBLEM_TYPE_URI_PREFIX, problemSlug, type ProblemDetail } from './contract';
import { ApiProblem } from './client';
import { SCENARIOS } from './mock/scenarios';
import { setToken } from '../auth';

setToken('test-token');
const transport = createMockTransport(() => 'test-token');

const REQUESTS: Record<EndpointId, string> = {
  'A1:entities': '/api/v1/entities',
  'A2:entity': '/api/v1/entities/ent_hallway_light',
  'A3:entityState': '/api/v1/entities/ent_hallway_light/state',
  'A4:projection': '/internal/projection',
  'A5:dlq': '/internal/dlq',
  'B1:events': '/api/v1/events',
  'B2:health': '/api/v1/health',
  'B3:runs': '/api/v1/runs',
  'B3:causalChain': '/api/v1/runs/run_eh_001/causal-chain',
  'B3:nonFiring': '/api/v1/automations/auto_evening_hallway/non-firing',
  'B3:automations': '/api/v1/automations',
};

describe('frozen read-API contract', () => {
  it('pins the contract version', () => {
    // v1.1.4 (EXPLAIN-114a/b, landed core-side 2026-09-12/13; the freeze doc's amendment date is the
    // 13th): seven additive keys + FIRED_CONFIRMED (FE-114 D0 — the first named flip:
    // 'v1.1.3-2026-09-06' → 'v1.1.4-2026-09-13'). This pin, scripts/contract-check.mjs and
    // v113-additive.test.ts's pin move together.
    expect(CONTRACT_VERSION).toBe('v1.1.5-2026-09-19');
  });

  it('has a validator for every canonical endpoint', () => {
    for (const id of ENDPOINT_IDS) expect(typeof validators[id]).toBe('function');
    expect(Object.keys(REQUESTS).sort()).toEqual([...ENDPOINT_IDS].sort());
  });

  for (const id of ENDPOINT_IDS) {
    it(`${id} mock conforms to the frozen shape`, async () => {
      const res = await transport.send({ method: 'GET', path: REQUESTS[id] });
      expect(res.status).toBe(200);
      expect(() => validateAgainstContract(id, res.body)).not.toThrow();
    });
  }

  it('rejects a body that violates the shape', () => {
    expect(() => validateAgainstContract('A1:entities', { data: [{ entityId: 'x', availability: 'BOGUS', stale: false }], meta: { viewPosition: 1, timestamp: 't' } })).toThrow();
  });
});

/* v1.1.1 (2026-07-02, DRIFT-2 adjudication): the wire problem `type` is the URI form
   `https://homesynapse.local/problems/<slug>` (Locked Doc 09 §3.8 / ProblemType.TYPE_URI_PREFIX);
   clients key on the SLUG SUFFIX, never the whole URI byte-for-byte. These tests pin
   (a) the ratified prefix, (b) slug derivation incl. bare-slug tolerance, (c) that the
   MOCK emits the URI form exactly as Core does (mock === wire), and (d) that every
   slug-keyed detection fires on a URI-form type. */
describe('problem `type` is URI-form; clients key on the slug suffix (v1.1.1)', () => {
  it('pins the ratified prefix (Doc 09 §3.8 / ProblemType.TYPE_URI_PREFIX)', () => {
    expect(PROBLEM_TYPE_URI_PREFIX).toBe('https://homesynapse.local/problems/');
  });

  it('problemSlug strips the URI prefix and tolerates a bare slug', () => {
    expect(problemSlug(`${PROBLEM_TYPE_URI_PREFIX}state-store-replaying`)).toBe('state-store-replaying');
    expect(problemSlug('network-unreachable')).toBe('network-unreachable'); // client-minted, never on the wire
    expect(problemSlug(undefined)).toBe('');
    // An unknown URI form is NOT silently slugged — only the ratified prefix strips.
    expect(problemSlug('https://elsewhere.example/problems/not-found')).not.toBe('not-found');
  });

  it('the mock 401 carries the URI-form type and trips isAuthRequired via the slug', async () => {
    const anon = createMockTransport(() => null);
    const res = await anon.send({ method: 'GET', path: '/api/v1/entities' });
    expect(res.status).toBe(401);
    const p = new ApiProblem(res.body as ProblemDetail);
    expect(p.type).toBe(`${PROBLEM_TYPE_URI_PREFIX}authentication-required`);
    expect(p.slug).toBe('authentication-required');
    expect(p.isAuthRequired).toBe(true);
  });

  it('the mock 404 carries the URI-form type; not-found detection keys on the slug', async () => {
    const res = await transport.send({ method: 'GET', path: '/api/v1/unmapped' });
    expect(res.status).toBe(404);
    const p = new ApiProblem(res.body as ProblemDetail);
    expect(p.type).toBe(`${PROBLEM_TYPE_URI_PREFIX}not-found`);
    expect(p.slug).toBe('not-found'); // the EventsView "not served yet" honest-state key
  });

  it('isReplaying / isForbidden fire on URI-form types (the live 503/403 shapes)', () => {
    const replaying = new ApiProblem({
      type: `${PROBLEM_TYPE_URI_PREFIX}state-store-replaying`,
      title: 'Starting up',
      status: 503,
    });
    expect(replaying.isReplaying).toBe(true);
    // The sibling 503 must NOT read as replaying (the FE1_GO_LIVE distinction).
    const unhealthy = new ApiProblem({
      type: `${PROBLEM_TYPE_URI_PREFIX}integration-unhealthy`,
      title: 'Integration unhealthy',
      status: 503,
    });
    expect(unhealthy.isReplaying).toBe(false);
    const forbidden = new ApiProblem({
      type: `${PROBLEM_TYPE_URI_PREFIX}forbidden`,
      title: 'Forbidden',
      status: 403,
    });
    expect(forbidden.isForbidden).toBe(true);
  });

  it('the client-minted offline problem stays bare and still detects', () => {
    const offline = new ApiProblem({ type: 'network-unreachable', title: 'Cannot reach your home', status: 0 });
    expect(offline.slug).toBe('network-unreachable');
    expect(offline.isOffline).toBe(true);
  });
});

/* v1.1.1 (2026-07-02, DRIFT-1 adjudication): A4/A5 are enveloped `{data, meta}` at the
   frozen shapes + the ruled additive extras (M7.5c-a). These pin the RATIFIED element
   shapes — notably A5 parkedSubscribers = subscriber IDS (strings), which replaces the
   mirror's pre-ratification object guess. */
describe('A4/A5 enveloped at the frozen v1.1.1 shapes (M7.5c-a)', () => {
  const META = { viewPosition: 7, timestamp: '2026-07-02T00:00:00Z' };

  it('A4 accepts the live shape: frozen four + additive entityCount/ready', () => {
    const live = {
      data: { mode: 'LIVE', viewPosition: 7, lagEvents: 0, projectionVersion: 5, entityCount: 0, ready: true },
      meta: META,
    };
    expect(() => validateAgainstContract('A4:projection', live)).not.toThrow();
    // Additive extras are type-checked when present.
    const badReady = { data: { ...live.data, ready: 'yes' }, meta: META };
    expect(() => validateAgainstContract('A4:projection', badReady)).toThrow();
  });

  it('A5 accepts the live shape: parked IDS + additive subscribers[] detail', () => {
    const live = {
      data: {
        depth: 3,
        parkedSubscribers: ['automation_engine'],
        subscribers: [
          { subscriberId: 'state_projection', mode: 'LIVE', dlqDepth: 0, crashCount: 0, oldestParkedAt: null },
          { subscriberId: 'automation_engine', mode: 'LIVE', dlqDepth: 3, crashCount: 1, oldestParkedAt: '2026-07-02T00:00:05Z' },
        ],
      },
      meta: META,
    };
    expect(() => validateAgainstContract('A5:dlq', live)).not.toThrow();
  });

  it('A5 rejects the pre-ratification object guess for parkedSubscribers', () => {
    const oldGuess = {
      data: { depth: 1, parkedSubscribers: [{ subscriberId: 'automation_engine', reason: 'parked' }] },
      meta: META,
    };
    expect(() => validateAgainstContract('A5:dlq', oldGuess)).toThrow();
  });

  it('A4/A5 bare bodies (the pre-M7.5c-a wire) FAIL the envelope contract', () => {
    // No tolerance, no shim: the frozen §0 "every read carries meta" anchor is load-bearing
    // (the poll cursor). A bare body must never validate.
    expect(() => validateAgainstContract('A4:projection', { mode: 'LIVE', viewPosition: 0, entityCount: 0, ready: true })).toThrow();
    expect(() => validateAgainstContract('A5:dlq', { subscribers: [] })).toThrow();
  });
});

/* OBSERVED LIVE NULLABILITY (field-evidenced 2026-07-18; folded 2026-07-19 by the
   explainability-UX lane): prior-instance runs arrive with automationName = null
   (B3:runs, B3:causalChain) and trigger.type = null — automation instance ULIDs
   re-mint per YAML load, and StandardExplanationService serves registry-miss names
   as null. The v1.1 freeze text does not annotate these nullable; the clarification
   ask is recorded in the 2026-07-19 lane return. These pins hold the tolerance to
   EXACTLY null (absence and wrong types still fail). */
describe('prior-instance null-name tolerance (observed live wire)', () => {
  const META = { viewPosition: 9, timestamp: '2026-07-19T00:00:00Z' };

  it('B3:runs accepts automationName: null, rejects absence and non-strings', () => {
    const run = (automationName: unknown) => ({
      data: [{ runId: 'r', automationId: 'a', automationName, triggeredAt: 't', status: 'COMPLETED', terminalReason: null }],
      meta: META,
    });
    expect(() => validateAgainstContract('B3:runs', run(null))).not.toThrow();
    expect(() => validateAgainstContract('B3:runs', run('Named'))).not.toThrow();
    expect(() => validateAgainstContract('B3:runs', run(42))).toThrow();
    const absent = { data: [{ runId: 'r', automationId: 'a', triggeredAt: 't', status: 'COMPLETED' }], meta: META };
    expect(() => validateAgainstContract('B3:runs', absent)).toThrow();
  });

  it('B3:causalChain accepts automationName: null and trigger.type: null', () => {
    const chain = SCENARIOS.find((s) => s.id === 'field-evidence')!.build().causalChains['run_fe_nullname']!;
    expect(chain.automationName).toBeNull();
    expect(chain.trigger.type).toBeNull();
    expect(() => validateAgainstContract('B3:causalChain', { data: chain, meta: META })).not.toThrow();
  });
});

/* v1.1.2 (ratified 2026-07-22 Nick ruling 1; landed core-side 2026-07-26, SKIP-VIS,
   DP-4 GO): the three ADDITIVE keys. Absence is lawful — the DEPLOYED read surface
   predates the landing until the deploy completes, so a pre-v1.1.2 payload without
   the keys must still validate; presence is validated strictly. noCommandsIssued
   serializes as true or null, NEVER false (the additive-nullable idiom — the core
   constructs only Boolean.TRUE or null). */
describe('v1.1.2 additive keys (SKIP-VIS DP-1/DP-2/DP-4)', () => {
  const META = { viewPosition: 11, timestamp: '2026-07-26T00:00:00Z' };
  const chainWith = (action: Record<string, unknown>) => ({
    data: {
      runId: 'r',
      automationId: 'a',
      automationName: 'Named',
      trigger: { type: 'state_changed', subjectRef: { type: 'ENTITY', id: 'e' }, matchedAt: 't', firingValue: 'v' },
      conditions: [],
      actions: [{ type: 'device_command', targetRef: { type: 'ENTITY', id: 'e' }, command: 'turn_on', params: {}, outcome: 'DISPATCHED', reason: null, ...action }],
      outcome: { status: 'COMPLETED', reason: null, durationMs: 1, actionCount: 1, commandCount: 1 },
      cascade: { parentRunId: null, depth: 0 },
    },
    meta: META,
  });

  it('causal-chain actions accept resultOutcome string|null and settled boolean when present', () => {
    expect(() => validateAgainstContract('B3:causalChain', chainWith({ resultOutcome: 'superseded', settled: true }))).not.toThrow();
    expect(() => validateAgainstContract('B3:causalChain', chainWith({ resultOutcome: null, settled: false }))).not.toThrow();
  });

  it('a pre-v1.1.2 action WITHOUT the keys still validates (the deployed wire until the deploy)', () => {
    expect(() => validateAgainstContract('B3:causalChain', chainWith({}))).not.toThrow();
  });

  it('rejects wrong types on the additive keys', () => {
    expect(() => validateAgainstContract('B3:causalChain', chainWith({ resultOutcome: 42 }))).toThrow();
    expect(() => validateAgainstContract('B3:causalChain', chainWith({ settled: 'yes' }))).toThrow();
  });

  const nf = (extra: Record<string, unknown>) => ({
    data: {
      automationId: 'a',
      automationName: 'Named',
      enabled: true,
      verdict: 'ACTED_BUT_UNCONFIRMED',
      lastRelevantRunId: 'r',
      explanation: 'x',
      triggerSummary: 'y',
      lastEvaluation: { at: null, conditionsResult: null },
      ...extra,
    },
    meta: META,
  });

  it('non-firing accepts noCommandsIssued true and null; absence stays lawful', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ noCommandsIssued: true }))).not.toThrow();
    expect(() => validateAgainstContract('B3:nonFiring', nf({ noCommandsIssued: null }))).not.toThrow();
    expect(() => validateAgainstContract('B3:nonFiring', nf({}))).not.toThrow();
  });

  it('non-firing REJECTS noCommandsIssued false — never-false is wire truth', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ noCommandsIssued: false }))).toThrow();
    expect(() => validateAgainstContract('B3:nonFiring', nf({ noCommandsIssued: 'true' }))).toThrow();
  });

  it('the five-modes scenario carries the ruled wire signatures row-exact', () => {
    const d = SCENARIOS.find((s) => s.id === 'five-modes')!.build();
    const chain = d.causalChains['run_fm_all']!;
    const sig = chain.actions.map((a) => `${a.outcome}|${String(a.resultOutcome)}|${String(a.reason)}`);
    // Pairwise distinct (the law: the distinction IS the product).
    expect(new Set(sig).size).toBe(chain.actions.length);
    expect(sig).toContain('UNCONFIRMED|null|confirmation timed out'); // mode 1
    expect(sig).toContain('DISPATCHED|superseded|null'); // mode 2
    expect(sig).toContain('UNCONFIRMED|unconfirmed|DefaultResponse SUCCESS +90 ms, then no report, ever'); // mode 3
    expect(sig).toContain('DISPATCHED|null|null'); // mode 4 (provisional)
    expect(sig).toContain('FAILED|rejected|device offline'); // mode 5
    // The DP-3 VALUE correction carried by the mock: triggeredAt ≡ matchedAt.
    expect(d.runs[0]!.triggeredAt).toBe(chain.trigger.matchedAt);
    // Mode 4 is the only unsettled action.
    expect(chain.actions.filter((a) => a.settled === false)).toHaveLength(1);
  });

  it('the pre-v1.1.2 scenario carries NO v1.1.2 keys (a true pre-fix payload)', () => {
    const d = SCENARIOS.find((s) => s.id === 'verdict-vocabulary')!.build();
    for (const chain of Object.values(d.causalChains)) {
      for (const a of chain.actions) {
        expect('resultOutcome' in a).toBe(false);
        expect('settled' in a).toBe(false);
      }
    }
  });

  it('the field-evidence silent-skip now reports ACTED_BUT_UNCONFIRMED + the marker (DP-2)', () => {
    const d = SCENARIOS.find((s) => s.id === 'field-evidence')!.build();
    const nfe = d.nonFiring['auto_fe']!;
    expect(nfe.verdict).toBe('ACTED_BUT_UNCONFIRMED');
    expect(nfe.noCommandsIssued).toBe(true);
    expect(nfe.explanation).toContain('issued no device commands');
    expect(nfe.explanation).not.toContain('fired and confirmed'); // the dead sentence
  });
});

/* T1.2: every scenario the mock can serve must be contract-shaped — so FE-4 verifies real
   shapes and live-integration (FE-1) meets nothing the UI hasn't already faced. */
describe('every mock scenario is contract-shaped', () => {
  const META = { viewPosition: 1, timestamp: new Date().toISOString() };
  const env = (data: unknown) => ({ data, meta: META });

  for (const s of SCENARIOS) {
    it(`scenario "${s.id}" conforms to the frozen contract`, () => {
      const d = s.build();
      expect(() => validateAgainstContract('A1:entities', env(d.entities))).not.toThrow();
      for (const v of Object.values(d.entityDetail)) expect(() => validateAgainstContract('A2:entity', env(v))).not.toThrow();
      for (const v of Object.values(d.entityState)) expect(() => validateAgainstContract('A3:entityState', env(v))).not.toThrow();
      expect(() => validateAgainstContract('A4:projection', env(d.projection))).not.toThrow();
      expect(() => validateAgainstContract('A5:dlq', env(d.dlq))).not.toThrow();
      expect(() => validateAgainstContract('B1:events', env(d.events))).not.toThrow();
      expect(() => validateAgainstContract('B2:health', env(d.health))).not.toThrow();
      expect(() => validateAgainstContract('B3:runs', env(d.runs))).not.toThrow();
      for (const v of Object.values(d.causalChains)) expect(() => validateAgainstContract('B3:causalChain', env(v))).not.toThrow();
      for (const v of Object.values(d.nonFiring)) expect(() => validateAgainstContract('B3:nonFiring', env(v))).not.toThrow();
      expect(() => validateAgainstContract('B3:automations', env(d.automations))).not.toThrow();
    });
  }
});

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
    expect(CONTRACT_VERSION).toBe('v1.1.1-2026-07-02');
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

/*
 * Contract conformance test (the CI guard, dispatch §4).
 * Every mock fixture must validate against the frozen-contract shape. If a shape
 * drifts, this fails in CI — not the demo. When real endpoints land, the same
 * validators can run against live responses to catch Core drift early.
 */
import { describe, it, expect } from 'vitest';
import { createMockTransport } from './mock/mockTransport';
import { ENDPOINT_IDS, validateAgainstContract, validators, CONTRACT_VERSION, type EndpointId } from './shapes';
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
    expect(CONTRACT_VERSION).toBe('v1.1-2026-06-21');
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

/*
 * Contract conformance test (the CI guard, dispatch §4).
 * Every mock fixture must validate against the frozen-contract shape. If a shape
 * drifts, this fails in CI — not the demo. When real endpoints land, the same
 * validators can run against live responses to catch Core drift early.
 */
import { describe, it, expect } from 'vitest';
import { createMockTransport } from './mock/mockTransport';
import { ENDPOINT_IDS, validateAgainstContract, validators, CONTRACT_VERSION, type EndpointId } from './shapes';
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

import { describe, it, expect } from 'vitest';
import { parseRoute } from './router';

describe('hash router', () => {
  it('defaults to overview', () => {
    expect(parseRoute('')).toEqual({ name: 'overview', params: {} });
    expect(parseRoute('#/')).toEqual({ name: 'overview', params: {} });
  });

  it('routes the device list and detail', () => {
    expect(parseRoute('#/devices')).toEqual({ name: 'devices', params: {} });
    expect(parseRoute('#/devices/ent_hallway_light')).toEqual({ name: 'device', params: { id: 'ent_hallway_light' } });
  });

  it('routes both hero halves', () => {
    expect(parseRoute('#/explain')).toEqual({ name: 'explain', params: {} });
    expect(parseRoute('#/explain/runs')).toEqual({ name: 'explain-runs', params: {} });
    expect(parseRoute('#/explain/run/run_eh_001')).toEqual({ name: 'explain-run', params: { runId: 'run_eh_001' } });
    expect(parseRoute('#/explain/why-not')).toEqual({ name: 'explain-why-not', params: {} });
    expect(parseRoute('#/explain/why-not/auto_x')).toEqual({ name: 'explain-why-not', params: { automationId: 'auto_x' } });
  });
});

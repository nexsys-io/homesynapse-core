/*
 * HomeSynapse — Mock control state (T1.2). The active DATA scenario (scenarios.ts) + the active
 * TRANSPORT condition that drive the mock transport. Dev/demo only.
 *
 * Initialized from URL params (?scenario=…&mock=…) so a demo link opens straight into a state;
 * mutated live by the DevPanel. Subscribers (the panel) re-render on change; a monotonic
 * `generation` lets the transport issue stable-yet-invalidating ETags so the 304 path is
 * demonstrable AND a scenario switch always refreshes.
 */
import { DEFAULT_SCENARIO_ID, resolveScenario, SCENARIOS, type MockDataset } from './scenarios';

export type TransportCondition =
  | 'normal'
  | 'replaying'
  | 'offline'
  | 'slow'
  | 'auth-required'
  | 'forbidden'
  | 'etag';

export const TRANSPORT_CONDITIONS: { id: TransportCondition; label: string; blurb: string }[] = [
  { id: 'normal', label: 'Normal', blurb: 'Healthy responses.' },
  { id: 'replaying', label: 'Starting up · 503', blurb: 'state-store-replaying → the calm catch-up state.' },
  { id: 'offline', label: 'Offline', blurb: 'Backend unreachable → honest degraded state.' },
  { id: 'slow', label: 'Slow · 1.5s', blurb: 'Injected latency → loading skeletons.' },
  { id: 'auth-required', label: '401', blurb: 'authentication-required.' },
  { id: 'forbidden', label: '403', blurb: 'Token rejected → drops to the gate.' },
  { id: 'etag', label: 'ETag · 304', blurb: 'Revalidation served from cache.' },
];

const listeners = new Set<() => void>();

const state = {
  scenarioId: DEFAULT_SCENARIO_ID,
  condition: 'normal' as TransportCondition,
  dataset: resolveScenario(DEFAULT_SCENARIO_ID),
  generation: 1,
};

function readUrl(): void {
  try {
    const p = new URLSearchParams(location.search);
    const s = p.get('scenario');
    if (s && SCENARIOS.some((x) => x.id === s)) {
      state.scenarioId = s;
      state.dataset = resolveScenario(s);
    }
    const c = p.get('mock');
    if (c && TRANSPORT_CONDITIONS.some((x) => x.id === c)) state.condition = c as TransportCondition;
  } catch {
    /* no window (test/SSR) — keep defaults */
  }
}
readUrl();

function syncUrl(): void {
  try {
    const p = new URLSearchParams(location.search);
    if (state.scenarioId === DEFAULT_SCENARIO_ID) p.delete('scenario');
    else p.set('scenario', state.scenarioId);
    if (state.condition === 'normal') p.delete('mock');
    else p.set('mock', state.condition);
    const qs = p.toString();
    history.replaceState(null, '', `${location.pathname}${qs ? `?${qs}` : ''}${location.hash}`);
  } catch {
    /* no window */
  }
}

export function getDataset(): MockDataset {
  return state.dataset;
}
export function getScenarioId(): string {
  return state.scenarioId;
}
export function getCondition(): TransportCondition {
  return state.condition;
}
/** Latency the transport should inject for the active condition. */
export function latencyMs(): number {
  return state.condition === 'slow' ? 1500 : 0;
}
/** Bumped on any change; the transport folds it into ETags so a switch invalidates the cache. */
export function getGeneration(): number {
  return state.generation;
}

export function setScenario(id: string): void {
  if (state.scenarioId === id) return;
  state.scenarioId = id;
  state.dataset = resolveScenario(id);
  state.generation++;
  syncUrl();
  listeners.forEach((l) => l());
}
export function setCondition(c: TransportCondition): void {
  if (state.condition === c) return;
  state.condition = c;
  state.generation++;
  syncUrl();
  listeners.forEach((l) => l());
}

export function subscribeMock(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

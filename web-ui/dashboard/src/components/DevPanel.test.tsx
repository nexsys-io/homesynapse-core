/*
 * HERO-U3 R7 (2026-10-11) — the DevPanel toggles the v1.1.7 recovery record LIVE / not LIVE: its two chips select
 * `recovery-live` and `recovery-catching-up`, and the mock transport's entity read follows — a numeric
 * meta.recoveryViewPosition AFTER timestamp with the record's keys carrying values, then null with every key null (the
 * not-LIVE rule's input; SPEC §3, AMD-103 §3), and back. design/recovery-card-v1/states.html is the design; this is the
 * build's demonstration without the Pi (the Pi serves v1.1.7 only from BC10, Sat Oct 17).
 * RED at HEAD (2a54145): neither scenario exists — no chip to press, and the default home's meta has no recoveryViewPosition.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup, fireEvent } from '@testing-library/preact';
import { DevPanel } from './DevPanel';
import { getScenarioId, setScenario } from '../lib/api/mock/mockState';
import { createMockTransport } from '../lib/api/mock/mockTransport';
import { SCENARIOS } from '../lib/api/mock/scenarios';

/** The eleven v1.1.7 keys in the emitter's order (EntityStateJson.java:62–:65), written out. */
const KEYS = ['probeAnsweredAt', 'probeWindowStart', 'probesAnswered', 'probesMissed', 'availabilityClass', 'reportIntervalSeconds', 'silenceLimitSeconds', 'missesToDark', 'recordIntervalSeconds', 'recordDueBy', 'recordOverdueSince'];
type Body = { data: Record<string, unknown>[]; meta: Record<string, unknown> };
const list = async (): Promise<Body> => (await createMockTransport(() => 'demo-token').send({ method: 'GET', path: '/api/v1/entities' })).body as Body;

afterEach(() => {
  cleanup();
  setScenario('default');
});

describe('HERO-U3 R7 — the DevPanel toggles the recovery record LIVE / not LIVE', () => {
  it('one chip per state; LIVE → a numeric recoveryViewPosition after timestamp and keys on record; catching up → null and every key null; and back', async () => {
    const live = SCENARIOS.find((s) => s.id === 'recovery-live');
    const dark = SCENARIOS.find((s) => s.id === 'recovery-catching-up');
    expect(live).toBeTruthy();
    expect(dark).toBeTruthy();
    const { getByRole } = render(<DevPanel />);
    fireEvent.click(getByRole('button', { name: 'Demo scenarios' }));

    fireEvent.click(getByRole('radio', { name: live!.label }));
    expect(getScenarioId()).toBe('recovery-live');
    expect(getByRole('radio', { name: live!.label }).getAttribute('aria-checked')).toBe('true');
    let body = await list();
    expect(Object.keys(body.meta)).toEqual(['viewPosition', 'timestamp', 'recoveryViewPosition']);
    expect(typeof body.meta.recoveryViewPosition).toBe('number');
    expect(body.data.some((r) => KEYS.some((k) => r[k] !== null))).toBe(true);

    fireEvent.click(getByRole('radio', { name: dark!.label }));
    expect(getScenarioId()).toBe('recovery-catching-up');
    body = await list();
    expect(body.meta.recoveryViewPosition).toBeNull();
    for (const r of body.data) for (const k of KEYS) {
      expect(k in r, k).toBe(true);
      expect(r[k], k).toBeNull();
    }

    fireEvent.click(getByRole('radio', { name: live!.label }));
    expect(typeof (await list()).meta.recoveryViewPosition).toBe('number');
  });
});

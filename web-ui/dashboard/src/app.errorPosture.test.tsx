/*
 * NEW-2 — THE ERROR-POSTURE LAW, ENFORCED APP-WIDE (RED-FIRST).
 * ---------------------------------------------------------------------------
 * The defect class (G1 rehearsal §6.3, DX-15): ErrorBoundary is documented
 * LOAD-BEARING ("neither is ever a silent blank or an eternal spinner") yet was
 * mounted in exactly ONE of nine views (RunChainView). The 2026-07-27 incident
 * — an uncontained render throw killing the view AND its polling loop —
 * reproduced verbatim on WhyNotView at the 2026-08-16 rehearsal because the
 * fix had been applied to the surface, not the class (arc-discipline 25).
 *
 * These tests prove, for EVERY view, that a render throw inside that view:
 *   1. degrades to the honest render-failure card (never a blank, never a
 *      stuck "Loading…", never an app crash);
 *   2. leaves the shell (nav, status) alive so the user can navigate away;
 *   3. does NOT kill the global projection poll loop.
 *
 * The throw is injected by resolving the view's data fetch with a Proxy that
 * throws on ANY property access — strictly worse than any real payload, so
 * containment is proven independent of per-view null guards. House law:
 * written RED before the fix (each case crashed uncontained at baseline
 * d26777c, except RunChainView — its pre-existing inner boundary makes that
 * case green-by-construction, disclosed as the preservation fixture).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, cleanup, act, fireEvent } from '@testing-library/preact';
import { App } from './app';
import { api } from './lib/api';
import { setToken, clearToken } from './lib/auth';
import { RENDER_ERROR_TITLE } from './components/ErrorBoundary';
import { t } from './lib/i18n';

/** Data that throws on ANY property access during render — the contained-crash
 *  stand-in (the poll.survival Bomb, generalized to arrive AS the payload). */
function bombPayload(): unknown {
  return new Proxy(
    {},
    {
      get(_t, prop) {
        throw new TypeError(`render bomb: property "${String(prop)}" accessed while drawing the view`);
      },
    },
  );
}

const META = { viewPosition: 1, timestamp: '2026-08-17T12:00:00.000Z' };

type BombableMethod =
  | 'listRuns'
  | 'listEntities'
  | 'listEvents'
  | 'getDlq'
  | 'listAutomations'
  | 'getCausalChain'
  | 'getNonFiring';

/** One case per view of app.tsx's renderView() switch (nine views; WhyNot has
 *  two data modes, both covered). `method` is the fetch whose payload bombs. */
const CASES: { view: string; hash: string; method: BombableMethod; baselineContained?: true }[] = [
  { view: 'OverviewView', hash: '#/overview', method: 'listRuns' },
  { view: 'DevicesView', hash: '#/devices', method: 'listEntities' },
  { view: 'EventsView', hash: '#/events', method: 'listEvents' },
  { view: 'HealthView', hash: '#/health', method: 'getDlq' },
  { view: 'AutomationsView', hash: '#/automations', method: 'listAutomations' },
  { view: 'ExplainHubView', hash: '#/explain', method: 'listAutomations' },
  { view: 'RunsView', hash: '#/explain/runs', method: 'listRuns' },
  // Pre-existing inner boundary (RunChainView.tsx) — green-by-construction at
  // baseline; kept as the preservation fixture for the reference pattern.
  { view: 'RunChainView', hash: '#/explain/run/run_eh_001', method: 'getCausalChain', baselineContained: true },
  { view: 'WhyNotView (picker)', hash: '#/explain/why-not', method: 'listAutomations' },
  // The 2026-08-16 incident surface, generalized.
  { view: 'WhyNotView (detail)', hash: '#/explain/why-not/auto_evening_hallway', method: 'getNonFiring' },
];

beforeEach(() => {
  vi.useFakeTimers();
  setToken('test-token');
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  clearToken();
  window.location.hash = '';
});

describe('the error-posture law holds on every view (render throw → honest card; app + poll survive)', () => {
  for (const c of CASES) {
    it(`${c.view}: a render throw degrades to the honest card, the shell stays alive, the poll loop survives`, async () => {
      const projSpy = vi.spyOn(api, 'getProjection'); // passthrough — the real loop, observed
      vi.spyOn(api, c.method).mockResolvedValue({ data: bombPayload(), meta: META } as never);

      window.location.hash = c.hash;
      const { container } = render(<App />);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2500);
      });

      const text = container.textContent ?? '';
      // 1. The honest render-failure card — named, retryable; never a blank.
      expect(text).toContain(RENDER_ERROR_TITLE);
      expect(text).toMatch(/Try again/);
      // 2. The eternal spinner is unreachable past a thrown render.
      expect(text).not.toContain(t('ui.loading')); // HERO-1c C5 + D3: the spinner's default is the app row (this pin was the literal 'Loading…')
      // 3. The shell chrome survived — the user can still navigate away.
      expect(text).toContain('Automations');
      expect(text).toContain('Health');

      // 4. THE PROOF the 2026-07-27 class is dead: the projection poll keeps
      //    ticking after the contained throw.
      const before = projSpy.mock.calls.length;
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3200);
      });
      expect(projSpy.mock.calls.length).toBeGreaterThan(before);
    });
  }

  /* HERO-1c correction D3 (2026-09-13): the state primitives default to the app's generic pair
     (`ui.loading` / `ui.error.*`); the hero views pass the `explain.*` rows through Resource's
     `labels`. RED at the HERO-1c tree: Loading's default was `explain.loading` on EVERY page. */
  it('D3: the DEVICES page loads with the app row ui.loading — never the hero\'s "Loading this run…"', async () => {
    vi.spyOn(api, 'listEntities').mockReturnValue(new Promise(() => {}) as never); // never resolves: the loading state stands
    window.location.hash = '#/devices';
    const { container } = render(<App />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1600);
    });
    const text = container.textContent ?? '';
    expect(text).toContain(t('ui.loading'));
    expect(text).not.toContain(t('explain.loading'));
  });

  it('D3: the run page loads with the hero row explain.loading through Resource labels [GREEN at the HERO-1c tree by construction — it was then the default]', async () => {
    vi.spyOn(api, 'getCausalChain').mockReturnValue(new Promise(() => {}) as never);
    window.location.hash = '#/explain/run/run_eh_001';
    const { container } = render(<App />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1600);
    });
    expect(container.textContent ?? '').toContain(t('explain.loading'));
  });

  it('"Try again" on the app-level card remounts the view and refetches (recovery, not a dead end)', async () => {
    let blow = true;
    vi.spyOn(api, 'getDlq').mockImplementation(async () => {
      if (blow) return { data: bombPayload(), meta: META } as never;
      return { data: { depth: 0, parkedSubscribers: [] }, meta: META } as never;
    });
    window.location.hash = '#/health';
    const { container, getByText } = render(<App />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1600);
    });
    expect(container.textContent ?? '').toContain(RENDER_ERROR_TITLE);

    blow = false;
    fireEvent.click(getByText('Try again'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1600);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1600);
    });
    const text = container.textContent ?? '';
    expect(text).not.toContain(RENDER_ERROR_TITLE);
    expect(text).toContain('All clear'); // the view remounted and refetched honestly
  });

  it('navigating to another view resets a tripped boundary (a crash never follows the user)', async () => {
    vi.spyOn(api, 'listAutomations').mockResolvedValue({ data: bombPayload(), meta: META } as never);
    window.location.hash = '#/automations';
    const { container } = render(<App />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1600);
    });
    expect(container.textContent ?? '').toContain(RENDER_ERROR_TITLE);

    window.location.hash = '#/health';
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1600);
    });
    const text = container.textContent ?? '';
    expect(text).not.toContain(RENDER_ERROR_TITLE);
    expect(text).toContain('System health');
  });
});

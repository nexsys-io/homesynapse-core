/*
 * FE-LIVE-V112 item 1 — the polling loop must SURVIVE a render throw (RED-FIRST).
 * ---------------------------------------------------------------------------
 * Field evidence (2026-07-27 devtools-chain-glance return): the chain render
 * threw on a live present-but-null field and the crash killed the view's
 * refetch loop — a 41-s network window with ZERO causal-chain requests while
 * projection polling continued; the "Updated" stamp frozen ~19 min. The loop
 * dying is what turned a cosmetic defect into an apparently-hung application.
 *
 * These tests PROVE (not assume) that with the error boundary in place:
 *   1. a render throw inside the chain panel is CONTAINED (honest card, app alive);
 *   2. the global projection poll keeps ticking after the throw;
 *   3. the view's own refetch-on-cursor-advance keeps fetching after the throw;
 *   4. "Try again" resets the boundary and re-renders the children.
 */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { render, cleanup, fireEvent, act } from '@testing-library/preact';
import { PollProvider, useApi } from './poll';
import { api } from './api';
import { setToken } from './auth';
import { Resource } from '../components/Resource';
import { ErrorBoundary, RENDER_ERROR_TITLE } from '../components/ErrorBoundary';

setToken('test-token');

/* A component that throws during render — the contained-crash stand-in. It
 * throws unconditionally, which is STRICTLY WORSE than the fixed chain render,
 * so surviving it proves the containment property independent of any guard. */
function Bomb({ label }: { label?: string }): never {
  throw new TypeError(`render bomb${label ? `: ${label}` : ''}`);
}

/** Mimics RunChainView's structure: useApi + Resource + boundary around the renderer. */
function Panel({ fetcher, renderData }: { fetcher: () => Promise<{ data: unknown; meta: { viewPosition: number; timestamp: string } }>; renderData: (d: unknown) => preact.JSX.Element }) {
  const state = useApi(fetcher as Parameters<typeof useApi>[0]);
  return (
    <ErrorBoundary onRetry={state.reload}>
      <Resource state={state}>{(d) => renderData(d)}</Resource>
    </ErrorBoundary>
  );
}

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('the error boundary contains a render throw', () => {
  it('renders the honest render-error card instead of crashing, and offers retry', () => {
    const { container, getByRole } = render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>,
    );
    const text = container.textContent ?? '';
    expect(text).toContain(RENDER_ERROR_TITLE);
    // The card is honest about WHAT failed: the display, not the request/data.
    expect(getByRole('alert')).toBeTruthy();
    expect(text).toMatch(/Try again/);
  });

  it('"Try again" resets the boundary and re-renders the children', () => {
    let blow = true;
    function Sometimes() {
      if (blow) throw new TypeError('first render only');
      return <p>recovered content</p>;
    }
    const { container, getByText } = render(
      <ErrorBoundary>
        <Sometimes />
      </ErrorBoundary>,
    );
    expect(container.textContent).toContain(RENDER_ERROR_TITLE);
    blow = false;
    fireEvent.click(getByText('Try again'));
    expect(container.textContent).toContain('recovered content');
    expect(container.textContent).not.toContain(RENDER_ERROR_TITLE);
  });
});

describe('the polling loop provably survives a contained render throw', () => {
  it('projection polling AND the view refetch loop both keep running after the throw', async () => {
    const projectionSpy = vi.spyOn(api, 'getProjection');
    let fetches = 0;
    const fetcher = async () => {
      fetches++;
      return {
        data: { ok: true },
        meta: { viewPosition: fetches, timestamp: new Date().toISOString() },
      };
    };

    render(
      <PollProvider intervalMs={500}>
        <Panel fetcher={fetcher} renderData={() => <Bomb label="chain render" />} />
      </PollProvider>,
    );

    // Let several poll ticks elapse: each tick advances the mock projection
    // cursor, which re-triggers the view's fetch, whose successful data then
    // THROWS in render — contained by the boundary every time.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });

    const projCallsAtCrash = projectionSpy.mock.calls.length;
    const fetchesAtCrash = fetches;
    expect(projCallsAtCrash).toBeGreaterThanOrEqual(2); // loop ran, crash already contained
    expect(fetchesAtCrash).toBeGreaterThanOrEqual(1); // data fetched, render threw

    // THE PROOF: after the contained throw, both loops keep going.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(projectionSpy.mock.calls.length).toBeGreaterThan(projCallsAtCrash);
    expect(fetches).toBeGreaterThan(fetchesAtCrash);
  });

  it('the contained throw renders the honest card while the rest of the tree stays live', async () => {
    const { container } = render(
      <PollProvider intervalMs={500}>
        <p>sibling surface</p>
        <ErrorBoundary>
          <Bomb />
        </ErrorBoundary>
      </PollProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1200);
    });
    const text = container.textContent ?? '';
    expect(text).toContain('sibling surface'); // the app did not go down with the view
    expect(text).toContain(RENDER_ERROR_TITLE);
  });
});

/*
 * HomeSynapse — Polling (the no-WebSocket model, D-OPEN-3 firm).
 * ---------------------------------------------------------------------------
 * ONE coalesced poll loop drives a global projection cursor (meta.viewPosition).
 * Views refetch only when the cursor advances — cheap change-detection without a
 * push channel, and no per-view polling storms (Doc 13 §performance; contract §0).
 * The loop:
 *   - polls A4 /internal/projection every 1-2s (always present, cheap);
 *   - treats 503 state-store-replaying as a first-class "catching up" phase with
 *     backoff, NOT an error toast;
 *   - pauses while the tab is hidden (C13-07);
 *   - kicks immediately when the session token changes.
 */
import { createContext, type ComponentChildren } from 'preact';
import { useContext, useEffect, useRef, useState, useCallback } from 'preact/hooks';
import { api, ApiProblem } from './api';
import type { ApiResult } from './api/client';
import { onTokenChange } from './auth';

export type Phase = 'starting' | 'live' | 'replaying' | 'error' | 'auth';

interface PollState {
  viewPosition: number;
  phase: Phase;
}

const PollCtx = createContext<PollState>({ viewPosition: 0, phase: 'starting' });

export function PollProvider({
  intervalMs = 1500,
  children,
}: {
  intervalMs?: number;
  children: ComponentChildren;
}) {
  const [state, setState] = useState<PollState>({ viewPosition: 0, phase: 'starting' });

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let backoff = intervalMs;

    const schedule = (ms: number) => {
      clearTimeout(timer);
      timer = setTimeout(tick, ms);
    };

    async function tick() {
      if (!active || document.hidden) return;
      try {
        const res = await api.getProjection();
        if (!active) return;
        backoff = intervalMs;
        setState((prev) => {
          const vp = res.meta.viewPosition;
          const phase: Phase = res.data.mode === 'LIVE' ? 'live' : 'replaying';
          if (prev.viewPosition === vp && prev.phase === phase) return prev; // no churn
          return { viewPosition: vp, phase };
        });
        schedule(intervalMs);
      } catch (e) {
        if (!active) return;
        if (e instanceof ApiProblem && e.isReplaying) {
          setState((p) => (p.phase === 'replaying' ? p : { ...p, phase: 'replaying' }));
          backoff = Math.min(backoff * 1.5, 5000);
          schedule(backoff);
        } else if (e instanceof ApiProblem && (e.isAuthRequired || e.isForbidden)) {
          setState((p) => (p.phase === 'auth' ? p : { ...p, phase: 'auth' }));
          schedule(intervalMs * 2);
        } else {
          setState((p) => (p.phase === 'error' ? p : { ...p, phase: 'error' }));
          backoff = Math.min(backoff * 1.5, 5000);
          schedule(backoff);
        }
      }
    }

    const onVis = () => {
      if (!document.hidden) schedule(0);
    };
    const offToken = onTokenChange(() => schedule(0));
    document.addEventListener('visibilitychange', onVis);
    schedule(0);

    return () => {
      active = false;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVis);
      offToken();
    };
  }, [intervalMs]);

  return <PollCtx.Provider value={state}>{children}</PollCtx.Provider>;
}

export function usePollCursor(): PollState {
  return useContext(PollCtx);
}

/* ---- Per-view data loading, coalesced on the global cursor ---- */

export type LoadStatus = 'loading' | 'ok' | 'error' | 'replaying' | 'auth';
export interface ApiState<T> {
  status: LoadStatus;
  data?: T;
  meta?: ApiResult<T>['meta'];
  error?: ApiProblem | Error;
  reload: () => void;
}

/**
 * Fetch via the typed API and refetch whenever the global projection cursor
 * advances. `fetcher` may be an inline closure (kept in a ref to avoid stale
 * captures). Returns a discriminated load state for the view to render.
 */
export function useApi<T>(fetcher: () => Promise<ApiResult<T>>): ApiState<T> {
  const { viewPosition } = usePollCursor();
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const [s, setS] = useState<Omit<ApiState<T>, 'reload'>>({ status: 'loading' });

  const load = useCallback(async () => {
    try {
      const res = await fetcherRef.current();
      setS({ status: 'ok', data: res.data, meta: res.meta });
    } catch (e) {
      if (e instanceof ApiProblem && e.isReplaying) setS({ status: 'replaying', error: e });
      else if (e instanceof ApiProblem && (e.isAuthRequired || e.isForbidden)) setS({ status: 'auth', error: e });
      else setS({ status: 'error', error: e as Error });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [viewPosition, load]);

  return { ...s, reload: load };
}

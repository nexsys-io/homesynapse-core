/*
 * HomeSynapse — API entry point (THE switch).
 * ---------------------------------------------------------------------------
 * One place decides real vs mock transport. Flip with the `VITE_USE_MOCKS` env
 * (`true`/`false`); default = mock in dev, real in prod. As Core delivers each
 * B-class endpoint, no view changes — only this wiring and the per-endpoint
 * routing change.
 */
import { createClient, type ApiClient, ApiProblem } from './client';
import { createRealTransport } from './realTransport';
import { createMockTransport, mockControls } from './mock/mockTransport';
import { makeApi, type Api } from './endpoints';
import { getToken, clearToken, setAuthError } from '../auth';

function resolveMode(): 'mock' | 'real' {
  const flag = import.meta.env.VITE_USE_MOCKS as string | undefined;
  if (flag === 'true') return 'mock';
  if (flag === 'false') return 'real';
  return import.meta.env.DEV ? 'mock' : 'real';
}

export const API_MODE = resolveMode();

const baseUrl = typeof window !== 'undefined' ? window.location.origin : 'http://127.0.0.1:7070';

const transport =
  API_MODE === 'mock' ? createMockTransport(getToken) : createRealTransport(baseUrl, getToken);

const client: ApiClient = createClient({
  transport,
  onAuthError: (p: ApiProblem) => {
    // 403 (invalid/expired) drops the session token so the shell shows the gate.
    if (p.isForbidden) {
      setAuthError('That token was rejected — it may be invalid or expired. Check it and try again.');
      clearToken();
    }
  },
});

export const api: Api = makeApi(client);
export { ApiProblem, mockControls };

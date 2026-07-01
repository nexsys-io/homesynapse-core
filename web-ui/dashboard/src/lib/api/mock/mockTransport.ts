/*
 * HomeSynapse — Mock transport (T1.2 scenario-driven).
 * Implements the same Transport interface as the real fetch transport, so the app swaps
 * real<->mock with one switch (index.ts). It serves the ACTIVE scenario's dataset (mockState +
 * scenarios) and injects the ACTIVE transport condition — so every value the contract allows and
 * every transport condition (503 / offline / slow / 401 / 403 / ETag-304) is one control away.
 */
import type { RawRequest, RawResponse, Transport } from '../client';
import type { Envelope, PaginationMeta, ProblemDetail, ProjectionStatus } from '../contract';
import { getCondition, getDataset, getGeneration, latencyMs } from './mockState';

let vp = 48_500;
const meta = () => ({ viewPosition: ++vp, timestamp: new Date().toISOString() });
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function envelope<T>(data: T, pagination?: PaginationMeta): Envelope<T> {
  return pagination ? { data, pagination, meta: meta() } : { data, meta: meta() };
}
function problem(p: ProblemDetail): RawResponse {
  return { status: p.status, headers: {}, body: p };
}
function notFound(detail: string): RawResponse {
  return problem({ type: 'not-found', title: 'Not found', status: 404, detail });
}
const listPagination = (limit = 50): PaginationMeta => ({ nextCursor: null, hasMore: false, limit });

/** Stable within a generation, invalidated when the scenario/condition changes. Includes query. */
function etagFor(req: RawRequest): string {
  const q = req.query ? JSON.stringify(req.query) : '';
  return `"mock-g${getGeneration()}-${req.path}-${q}"`;
}
/** Wrap a 200 body, applying ETag/304 semantics for the `etag` condition. */
function respond(req: RawRequest, body: unknown): RawResponse {
  if (getCondition() === 'etag') {
    const tag = etagFor(req);
    if (req.ifNoneMatch === tag) return { status: 304, headers: {}, body: null };
    return { status: 200, headers: { etag: tag }, body };
  }
  return { status: 200, headers: { etag: `"mock-${Math.random().toString(36).slice(2, 8)}"` }, body };
}

export function createMockTransport(getToken: () => string | null): Transport {
  return {
    async send(req: RawRequest): Promise<RawResponse> {
      const condition = getCondition();

      const lat = latencyMs();
      if (lat) await sleep(lat);

      // Offline: mimic a fetch() network failure — the transport rejects (client wraps it).
      if (condition === 'offline') throw new TypeError('Failed to fetch (mock: offline)');

      // AB-1 auth. `auth-required` forces 401 regardless; a missing token is 401; `forbidden`
      // (or the sentinel 'invalid' token) is 403.
      const token = getToken();
      if (condition === 'auth-required' || !token) {
        return problem({
          type: 'authentication-required',
          title: 'Authentication required',
          status: 401,
          detail: 'Paste the pairing token from config/initial_api_token.',
        });
      }
      if (condition === 'forbidden' || token === 'invalid') {
        return problem({ type: 'forbidden', title: 'Forbidden', status: 403, detail: 'Token invalid or expired.' });
      }

      const data = getDataset();

      // Boot/catch-up: the projection reports REPLAY, and the /api reads return 503 (first-class
      // "starting up", not an error toast — Doc 13 §0). /internal/dlq still answers.
      if (condition === 'replaying') {
        if (req.path === '/internal/projection') {
          const replaying: ProjectionStatus = { ...data.projection, mode: 'REPLAY', lagEvents: 128 };
          return respond(req, envelope(replaying));
        }
        if (req.path.startsWith('/api/')) {
          return problem({
            type: 'state-store-replaying',
            title: 'Starting up',
            status: 503,
            detail: 'The projection is catching up. Retrying shortly.',
          });
        }
      }

      const path = req.path;
      const q = req.query ?? {};

      // ---- A-class ----
      if (path === '/api/v1/entities') return respond(req, envelope(data.entities, listPagination(Number(q.limit) || 50)));

      let m = path.match(/^\/api\/v1\/entities\/([^/]+)\/state$/);
      if (m) {
        const s = data.entityState[decodeURIComponent(m[1]!)];
        return s ? respond(req, envelope(s)) : notFound(`No state for entity ${m[1]}`);
      }
      m = path.match(/^\/api\/v1\/entities\/([^/]+)$/);
      if (m) {
        const id = decodeURIComponent(m[1]!);
        const found = data.entities.find((e) => e.entityId === id);
        const d = data.entityDetail[id] ?? (found ? { entityId: id, availability: found.availability, attributes: {} } : undefined);
        return d ? respond(req, envelope(d)) : notFound(`No entity ${id}`);
      }
      if (path === '/internal/projection') return respond(req, envelope(data.projection));
      if (path === '/internal/dlq') return respond(req, envelope(data.dlq));

      // ---- B-class ----
      if (path === '/api/v1/events') {
        let evs = data.events;
        if (q.subjectId) evs = evs.filter((e) => e.subjectRef.id === q.subjectId);
        if (q.type) evs = evs.filter((e) => e.type === q.type);
        return respond(req, envelope(evs, listPagination(Number(q.limit) || 50)));
      }
      if (path === '/api/v1/health') return respond(req, envelope(data.health));
      if (path === '/api/v1/runs') {
        let rs = data.runs;
        if (q.automationId) rs = rs.filter((r) => r.automationId === q.automationId);
        return respond(req, envelope(rs, listPagination(Number(q.limit) || 50)));
      }
      m = path.match(/^\/api\/v1\/runs\/([^/]+)\/causal-chain$/);
      if (m) {
        const c = data.causalChains[decodeURIComponent(m[1]!)];
        return c ? respond(req, envelope(c)) : notFound(`No causal chain for run ${m[1]}`);
      }
      m = path.match(/^\/api\/v1\/automations\/([^/]+)\/non-firing$/);
      if (m) {
        const nf = data.nonFiring[decodeURIComponent(m[1]!)];
        return nf ? respond(req, envelope(nf)) : notFound(`No non-firing explanation for ${m[1]}`);
      }
      if (path === '/api/v1/automations') return respond(req, envelope(data.automations, listPagination()));

      return notFound(`Unmapped mock path: ${path}`);
    },
  };
}

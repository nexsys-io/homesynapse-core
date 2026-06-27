/*
 * HomeSynapse — Mock transport.
 * Implements the same Transport interface as the real fetch transport, so the
 * app swaps real<->mock with one switch (index.ts). Simulates AB-1 auth, the
 * {data, pagination, meta} envelope, problem+json errors, and (optionally) the
 * 503 state-store-replaying boot state.
 */
import type { RawRequest, RawResponse, Transport } from '../client';
import type { Envelope, PaginationMeta, ProblemDetail } from '../contract';
import * as fx from './mockData';

/** Test/demo controls. Flip `replaying` to exercise the catch-up boot state. */
export const mockControls = { replaying: false, latencyMs: 0 };

function envelope<T>(data: T, pagination?: PaginationMeta): Envelope<T> {
  return pagination ? { data, pagination, meta: fx.meta() } : { data, meta: fx.meta() };
}
function ok(body: unknown): RawResponse {
  return { status: 200, headers: { etag: `"mock-${Math.random().toString(36).slice(2, 8)}"` }, body };
}
function problem(p: ProblemDetail): RawResponse {
  return { status: p.status, headers: {}, body: p };
}
function notFound(detail: string): RawResponse {
  return problem({ type: 'not-found', title: 'Not found', status: 404, detail });
}
const listPagination = (limit = 50): PaginationMeta => ({ nextCursor: null, hasMore: false, limit });

export function createMockTransport(getToken: () => string | null): Transport {
  return {
    async send(req: RawRequest): Promise<RawResponse> {
      if (mockControls.latencyMs) await new Promise((r) => setTimeout(r, mockControls.latencyMs));

      // AB-1 auth simulation.
      const token = getToken();
      if (!token) {
        return problem({
          type: 'authentication-required',
          title: 'Authentication required',
          status: 401,
          detail: 'Paste the pairing token from config/initial_api_token.',
        });
      }
      if (token === 'invalid') {
        return problem({ type: 'forbidden', title: 'Forbidden', status: 403, detail: 'Token invalid or expired.' });
      }

      // Boot/catch-up state (Doc 13 §0; first-class, not an error toast).
      if (mockControls.replaying && req.path.startsWith('/api/')) {
        return problem({
          type: 'state-store-replaying',
          title: 'Starting up',
          status: 503,
          detail: 'The projection is catching up. Retrying shortly.',
        });
      }

      const path = req.path;
      const q = req.query ?? {};

      // ---- A-class ----
      if (path === '/api/v1/entities') return ok(envelope(fx.entities, listPagination(Number(q.limit) || 50)));

      let m = path.match(/^\/api\/v1\/entities\/([^/]+)\/state$/);
      if (m) {
        const s = fx.entityState[decodeURIComponent(m[1]!)];
        return s ? ok(envelope(s)) : notFound(`No state for entity ${m[1]}`);
      }
      m = path.match(/^\/api\/v1\/entities\/([^/]+)$/);
      if (m) {
        const id = decodeURIComponent(m[1]!);
        const d =
          fx.entityDetail[id] ??
          (fx.entities.find((e) => e.entityId === id)
            ? { entityId: id, availability: fx.entities.find((e) => e.entityId === id)!.availability, attributes: {} }
            : undefined);
        return d ? ok(envelope(d)) : notFound(`No entity ${id}`);
      }
      if (path === '/internal/projection') return ok(envelope(fx.projection));
      if (path === '/internal/dlq') return ok(envelope(fx.dlq));

      // ---- B-class ----
      if (path === '/api/v1/events') {
        let data = fx.events;
        if (q.subjectId) data = data.filter((e) => e.subjectRef.id === q.subjectId);
        if (q.type) data = data.filter((e) => e.type === q.type);
        return ok(envelope(data, listPagination(Number(q.limit) || 50)));
      }
      if (path === '/api/v1/health') return ok(envelope(fx.health));
      if (path === '/api/v1/runs') {
        let data = fx.runs;
        if (q.automationId) data = data.filter((r) => r.automationId === q.automationId);
        return ok(envelope(data, listPagination(Number(q.limit) || 50)));
      }
      m = path.match(/^\/api\/v1\/runs\/([^/]+)\/causal-chain$/);
      if (m) {
        const c = fx.causalChains[decodeURIComponent(m[1]!)];
        return c ? ok(envelope(c)) : notFound(`No causal chain for run ${m[1]}`);
      }
      m = path.match(/^\/api\/v1\/automations\/([^/]+)\/non-firing$/);
      if (m) {
        const nf = fx.nonFiring[decodeURIComponent(m[1]!)];
        return nf ? ok(envelope(nf)) : notFound(`No non-firing explanation for ${m[1]}`);
      }
      if (path === '/api/v1/automations') return ok(envelope(fx.automations, listPagination()));

      return notFound(`Unmapped mock path: ${path}`);
    },
  };
}

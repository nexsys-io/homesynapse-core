/*
 * NEW-7 (a) — THE UNSERVED-ENDPOINT TEACHING STATE, keyed on the observed wire
 * discriminator (RED-FIRST where the baseline could run it; disclosed below).
 * ---------------------------------------------------------------------------
 * The live fact (sitting record 2026-08-20 §6 row 5): the Activity nav's
 * `GET /api/v1/events?sort=DESC&limit=50` answers a ROUTER-level 404 —
 * `Content-Type: application/json`, NOT `application/problem+json`, 159 B. The
 * body was not captured and is not needed: `/api/v1/events` has NO route in this
 * release, so no handler exists to author a 404 there — a 404 on that path can
 * only be the router saying "no such route"; the media type excludes Core's
 * exception-path problems (problem+json — RestFilters.java:551) and non-JSON
 * 404s. (Core's endpoint-level problems go out as application/json with a
 * problem-shaped body — EndpointResponses.java — which is exactly why the PATH
 * set, not the media type, carries the premise, and why a path leaves the set
 * the moment Core ships it.) EventsView already SHIPPED the calm teaching
 * copy for exactly this (i18n `events.notServedYet.*`) but keyed it on the
 * problem slug `not-found` — an inference the live wire refuted (the rehearsal
 * 404 rendered the generic card). This suite pins the corrected keying:
 *   - the discriminator fires ONLY for path ∧ 404 ∧ application/json;
 *   - it never reads the body (null / unknown JSON / text all classify alike);
 *   - a problem+json 404 — on the same path or any other — keeps the honest
 *     generic card + Try again (the design working, per the ruling's posture).
 *
 * Baseline behavior (captured against a pristine 7c9e4fa copy, see the return):
 * the observed shape rendered the GENERIC card (the live incident, in-fixture)
 * and a problem+json `not-found` rendered the TEACHING card (the refuted
 * inference). Both flip here. The discriminator's own tests cannot run at
 * baseline (the export did not exist) — disclosed, not rounded up.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { EventsView } from './EventsView';
import { api } from '../lib/api';
import { BRAND, t } from '../lib/i18n';
import {
  ApiProblem,
  createClient,
  isUnservedEndpoint404,
  mediaType,
  UNSERVED_ENDPOINT_SLUG,
  type RawResponse,
  type Transport,
} from '../lib/api/client';
import { PROBLEM_TYPE_URI_PREFIX } from '../lib/api/contract';
import {
  WIRE_20260820_EVENTS_404_HEADERS_ONLY as HEADERS_404,
  WIRE_20260820_EVENTS_404_REQUEST as REQ_404,
} from '../lib/api/fixtures/wire-2026-08-20-events-404-headers';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/* ---- The discriminator, as a pure function ---- */

describe('isUnservedEndpoint404 — fires ONLY for path ∧ 404 ∧ application/json (never problem+json)', () => {
  it('fires for the recorded headers on the recorded path', () => {
    expect(isUnservedEndpoint404(REQ_404.path, HEADERS_404.status, HEADERS_404.headers['content-type'])).toBe(true);
  });

  it('tolerates media-type parameters (a charset would not change the verdict)', () => {
    expect(mediaType('application/json; charset=utf-8')).toBe('application/json');
    expect(mediaType('Application/JSON')).toBe('application/json');
    expect(isUnservedEndpoint404('/api/v1/events', 404, 'application/json; charset=utf-8')).toBe(true);
  });

  it('does NOT fire for application/problem+json — a Core-authored 404 means the endpoint exists', () => {
    expect(isUnservedEndpoint404('/api/v1/events', 404, 'application/problem+json')).toBe(false);
  });

  it('does NOT fire for a missing or non-JSON content type (never a guess)', () => {
    expect(isUnservedEndpoint404('/api/v1/events', 404, undefined)).toBe(false);
    expect(isUnservedEndpoint404('/api/v1/events', 404, 'text/plain')).toBe(false);
    expect(isUnservedEndpoint404('/api/v1/events', 404, 'text/html')).toBe(false);
  });

  it('does NOT fire for any status but 404', () => {
    for (const status of [200, 304, 400, 401, 403, 500, 503]) {
      expect(isUnservedEndpoint404('/api/v1/events', status, 'application/json')).toBe(false);
    }
  });

  it('does NOT fire off the observed path — exact match, not a prefix', () => {
    for (const path of ['/api/v1/events/abc', '/api/v1/events/', '/api/v1/runs', '/api/v1/health', '/internal/projection']) {
      expect(isUnservedEndpoint404(path, 404, 'application/json')).toBe(false);
    }
  });
});

/* ---- Through the client: the minted problem, and body-independence ---- */

function transportReturning(res: RawResponse): Transport {
  return { send: async () => res };
}

async function rejectionFor(res: RawResponse): Promise<ApiProblem> {
  const client = createClient({ transport: transportReturning(res) });
  try {
    await client.get('B1:events', REQ_404.path, REQ_404.query);
  } catch (e) {
    if (e instanceof ApiProblem) return e;
    throw e;
  }
  throw new Error('expected the client to reject');
}

describe('the client mints the unserved-endpoint problem from the headers alone', () => {
  it('the HEADERS-ON-RECORD fixture (body NOT captured) → isUnservedEndpoint, status 404, the client slug', async () => {
    const p = await rejectionFor(HEADERS_404);
    expect(p.isUnservedEndpoint).toBe(true);
    expect(p.status).toBe(404);
    expect(p.slug).toBe(UNSERVED_ENDPOINT_SLUG);
    expect(p.isAuthRequired).toBe(false);
    expect(p.isOffline).toBe(false);
    expect(p.isReplaying).toBe(false);
  });

  it('the verdict is IDENTICAL whatever the body carried — null, an unknown JSON shape, or text (never body-keyed)', async () => {
    const bodies: unknown[] = [
      null,
      { title: 'Endpoint GET /api/v1/events not found', status: 404, type: 'https://javalin.io/documentation#error-responses', details: {} },
      { anything: 'at all' },
      'Endpoint GET /api/v1/events not found',
      '',
      // Even a problem-SHAPED body under application/json classifies by the headers:
      // on an UNROUTED path no handler exists to have authored it, and the path
      // leaves FROZEN_UNBUILT_PATHS the moment Core routes it (client.ts).
      { type: `${PROBLEM_TYPE_URI_PREFIX}not-found`, title: 'Not found', status: 404 },
    ];
    for (const body of bodies) {
      const p = await rejectionFor({ ...HEADERS_404, body });
      expect(p.isUnservedEndpoint).toBe(true);
      expect(p.slug).toBe(UNSERVED_ENDPOINT_SLUG);
    }
  });

  it('a problem+json 404 on the SAME path is the ratified not-found — NOT the teaching state', async () => {
    const p = await rejectionFor({
      status: 404,
      headers: { 'content-type': 'application/problem+json' },
      body: { type: `${PROBLEM_TYPE_URI_PREFIX}not-found`, title: 'Not found', status: 404, detail: 'No events match' },
    });
    expect(p.isUnservedEndpoint).toBe(false);
    expect(p.slug).toBe('not-found');
    expect(p.problem.title).toBe('Not found');
  });

  it('the minted problem carries calm, name-light fallback copy (for any surface that shows it generically)', async () => {
    const p = await rejectionFor(HEADERS_404);
    expect(p.problem.title).toBe('This part of the dashboard is not in this release yet');
    expect(p.problem.detail).toContain('Nothing is wrong');
    expect(p.problem.title + (p.problem.detail ?? '')).not.toContain(BRAND.productName);
  });
});

/* ---- The surface: EventsView renders the teaching card vs the generic card ---- */

async function renderEventsRejecting(problem: ApiProblem) {
  vi.spyOn(api, 'listEvents').mockRejectedValue(problem);
  const utils = render(<EventsView />);
  await act(async () => {}); // flush the rejected fetch and the rerender
  return utils;
}

describe('EventsView — the teaching card for the discriminator; the honest generic card for every other 404', () => {
  it('the observed router 404 renders the calm teaching card (no alert, no Try again)', async () => {
    const problem = await rejectionFor(HEADERS_404);
    const { container } = await renderEventsRejecting(problem);
    const text = container.textContent ?? '';
    expect(text).toContain(t('events.notServedYet.title'));
    expect(text).toContain(t('events.notServedYet.hint'));
    expect(container.querySelector('[role="alert"]')).toBeNull();
    expect(text).not.toContain('Try again');
    expect(text).not.toContain('Loading…');
  });

  it('a problem+json not-found on the same path renders the GENERIC honest card + Try again (the fallback for every OTHER 404)', async () => {
    const problem = new ApiProblem({
      type: `${PROBLEM_TYPE_URI_PREFIX}not-found`,
      title: 'Not found',
      status: 404,
      detail: 'No events match',
    });
    const { container } = await renderEventsRejecting(problem);
    const text = container.textContent ?? '';
    expect(container.querySelector('[role="alert"]')).not.toBeNull();
    expect(text).toContain('Not found');
    expect(text).toContain('Try again');
    expect(text).not.toContain(t('events.notServedYet.title'));
  });

  it('a non-problem 404 that does NOT match the discriminator (text/html) renders the generic card — no teaching by status alone', async () => {
    const problem = await rejectionFor({ status: 404, headers: { 'content-type': 'text/html' }, body: '<h1>Not Found</h1>' });
    expect(problem.isUnservedEndpoint).toBe(false);
    const { container } = await renderEventsRejecting(problem);
    expect(container.querySelector('[role="alert"]')).not.toBeNull();
    expect(container.textContent ?? '').not.toContain(t('events.notServedYet.title'));
  });
});

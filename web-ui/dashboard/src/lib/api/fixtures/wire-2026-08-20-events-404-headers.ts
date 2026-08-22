/*
 * REAL-WIRE FIXTURE — HEADERS-ON-RECORD, BODY NOT CAPTURED (H8 tier 1, partial).
 * ---------------------------------------------------------------------------
 * CAPTURE PROVENANCE — these are REAL response headers, transcribed from the
 * DevTools Network pane; the response BODY was NOT copied and is NOT on record:
 *   Captured : 2026-08-20 evening CT, midweek FE-deploy sitting (the Activity nav
 *              click; "exactly the two expected 404 XHRs" in the console)
 *   Request  : GET /api/v1/events?sort=DESC&limit=50 (the client's own query shape —
 *              EventsView → api.listEvents({ sort: 'DESC', limit: 50 }))
 *   Response : 404 · Content-Type: application/json (NOT application/problem+json) ·
 *              Content-Length: 159 · ETag not captured · timing not captured
 *   Body     : NOT CAPTURED — modelled here as `null` (what realTransport yields for an
 *              empty text). Nothing in this fixture, and nothing in the detector it
 *              feeds, depends on what those 159 bytes said. (Adjudicated NOT NEEDED:
 *              sitting record §6 row 5 — "the discriminator for NEW-7's (a) teaching
 *              state exists without it".) If the body is ever captured, file it as a
 *              SEPARATE fixture; do not retrofit it here.
 *   Record   : nexsys-hivemind/context/audits/2026-08-20_midweek-FE-deploy_sitting-record.md
 *              §2 row 5 + §6 ("Row 5 / NEW-7 — CLOSED-SUFFICIENT")
 *   Build    : the deployed bundle at capture was index-C95CAnmp.js — core c091f7c.
 *
 * WHY IT EXISTS: the router-level not-found on a FROZEN-UNBUILT path is a TEACHING
 * state ("the activity feed is not in this release yet"), not an error — and the
 * only honest way to recognize it without the body is the wire discriminator
 * (path ∧ 404 ∧ media type application/json): the PATH carries the premise (no
 * route exists, so no handler could have authored this 404), the media type
 * excludes Core's exception-path problems (application/problem+json —
 * RestFilters.java:551) and non-JSON 404s. This fixture pins the headers the
 * detector keys on; the paired suite (EventsView.unserved404.test.tsx) proves the
 * detector fires for exactly this shape and for NO problem+json 404.
 *
 * Size note (hypothesis, NOT evidence — the body stays uncaptured and un-keyed):
 * Javalin's default 404 document for this route, pretty-printed at 4 spaces, is
 * 158 B; 159 with a trailing newline. Consistent with the record; not proven.
 *
 * The header map is lower-cased because realTransport lower-cases every response
 * header key before the client reads it (realTransport.ts).
 */
import type { RawRequest, RawResponse } from '../client';

export const WIRE_20260820_EVENTS_404_REQUEST: RawRequest = {
  method: 'GET',
  path: '/api/v1/events',
  query: { sort: 'DESC', limit: 50 },
};

/** HEADERS-ON-RECORD, BODY NOT CAPTURED. */
export const WIRE_20260820_EVENTS_404_HEADERS_ONLY: RawResponse = {
  status: 404,
  headers: {
    'content-type': 'application/json',
    'content-length': '159',
  },
  body: null,
};

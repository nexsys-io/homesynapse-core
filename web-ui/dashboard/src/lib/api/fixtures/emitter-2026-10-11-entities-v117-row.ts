/*
 * EMITTER FIXTURE (H8: MIRRORED, not VERIFIED) — the v1.1.7 entity row, its envelope meta, and the emitter's other legs.
 * ---------------------------------------------------------------------------
 * PROVENANCE — from the emitter's test at 2a54145; re-pinned against the Pi's wire at BC10 (Sat Oct 17).
 * No Pi serves v1.1.7 yet (it runs 37f05a9 until BC10), so this file is NOT a capture: it is named `emitter-`, never
 * `wire-`. Every value is a LITERAL of api/rest-api/src/test/java/com/homesynapse/api/rest/EntityStateJsonV117Test.java
 * at core 2a54145 (md5 bb3ff1f50618a307da0eb7c07b00e0bc) — copied from its assertions and golden strings, never typed
 * from the Java record's component names (IR-89):
 *   - the frozen v1.1.6 part of the A1 row = GOLDEN_V116_LIST's row (:230), byte for byte;
 *   - the eleven v1.1.7 keys in the wire's order (V117_KEYS :65–:68 = EntityStateJson.RECOVERY_KEYS :62–:65), valued as
 *     elevenKeysInOrder_renderedFromTheRecord asserts them (:107–:120): the newest answer 11:30 with its window from
 *     10:30 (55 answered, 1 missed), the mains-metered contract (600 · 660 · 2 · 3600), recordDueBy
 *     2026-10-11T12:41:10.050Z, recordOverdueSince null — appended after `link` on the list row (:203–:206);
 *   - the meta: viewPosition 12, timestamp = the fixed clock (NOW :51), recoveryViewPosition 9 AFTER timestamp
 *     (threeReads_keysAndMeta :192–:197 with assertMeta :301–:304; the golden test's "recoveryViewPosition":9 :277; I-14);
 *   - the A2/A3 record = GOLDEN_V116_RECORD's data (:233) + the same eleven keys (the Get reads' 23 keys, R-3).
 * THE NOT-LIVE LEG: the same row with every v1.1.7 key PRESENT and null and meta.recoveryViewPosition null
 * (notLive_everyKeyNull :127–:138; threeReads with the `dark` service :194–:197; the golden test's :254–:256).
 * THE R6 LEG: overdueSince_whenNowIsPastDueBy (:174–:182) — at now 2026-10-11T12:41:10.051Z the core serves
 * recordOverdueSince "2026-10-11T11:30:00Z" (the newest evidence instant). Its meta is COMPOSED, not asserted there:
 * the threeReads positions (12 · 9) and timestamp = that same `now` — one clock instant stamps the meta and feeds the
 * bound (GetEntityEndpoint.java:153–:164).
 * VERBATIM — key order and values are the emitter's; do not "clean up" these objects.
 */
import type { EntitySummary } from '../contract';

export const EMITTER_20261011_V117_ROW: EntitySummary = {
  entityId: '01H8000000000000000000000A',
  availability: 'AVAILABLE',
  stale: false,
  deviceId: '01H8000000000000000000000D',
  lastReported: '2026-10-11T11:00:00Z',
  availabilityReason: 'first_contact',
  lastSeenAt: '2026-10-11T11:00:00Z',
  link: { lqi: 200, rssiDbm: -45, at: '2026-10-11T11:00:00Z' },
  probeAnsweredAt: '2026-10-11T11:30:00Z',
  probeWindowStart: '2026-10-11T10:30:00Z',
  probesAnswered: 55,
  probesMissed: 1,
  availabilityClass: 'mains-metered',
  reportIntervalSeconds: 600,
  silenceLimitSeconds: 660,
  missesToDark: 2,
  recordIntervalSeconds: 3600,
  recordDueBy: '2026-10-11T12:41:10.050Z',
  recordOverdueSince: null,
};

/** The LIVE read's meta, verbatim — recoveryViewPosition AFTER timestamp (I-14). */
export const EMITTER_20261011_V117_META = { viewPosition: 12, timestamp: '2026-10-11T12:00:00Z', recoveryViewPosition: 9 } as const;

/** The A2 / A3 `data` object of the same LIVE read: the golden v1.1.6 record (:233) + the eleven keys. */
export const EMITTER_20261011_V117_RECORD = {
  entityId: '01H8000000000000000000000A',
  attributes: {},
  availability: 'AVAILABLE',
  stateVersion: 4,
  lastChanged: '2026-10-11T10:59:00Z',
  lastUpdated: '2026-10-11T11:00:00Z',
  lastReported: '2026-10-11T11:00:00Z',
  staleAfter: null,
  stale: false,
  availabilityReason: 'first_contact',
  lastSeenAt: '2026-10-11T11:00:00Z',
  link: { lqi: 200, rssiDbm: -45, at: '2026-10-11T11:00:00Z' },
  probeAnsweredAt: '2026-10-11T11:30:00Z',
  probeWindowStart: '2026-10-11T10:30:00Z',
  probesAnswered: 55,
  probesMissed: 1,
  availabilityClass: 'mains-metered',
  reportIntervalSeconds: 600,
  silenceLimitSeconds: 660,
  missesToDark: 2,
  recordIntervalSeconds: 3600,
  recordDueBy: '2026-10-11T12:41:10.050Z',
  recordOverdueSince: null,
};

/** The not-LIVE leg: the projection catching up — every v1.1.7 key PRESENT and null, never absent. */
export const EMITTER_20261011_V117_NOT_LIVE_ROW: EntitySummary = {
  entityId: '01H8000000000000000000000A',
  availability: 'AVAILABLE',
  stale: false,
  deviceId: '01H8000000000000000000000D',
  lastReported: '2026-10-11T11:00:00Z',
  availabilityReason: 'first_contact',
  lastSeenAt: '2026-10-11T11:00:00Z',
  link: { lqi: 200, rssiDbm: -45, at: '2026-10-11T11:00:00Z' },
  probeAnsweredAt: null,
  probeWindowStart: null,
  probesAnswered: null,
  probesMissed: null,
  availabilityClass: null,
  reportIntervalSeconds: null,
  silenceLimitSeconds: null,
  missesToDark: null,
  recordIntervalSeconds: null,
  recordDueBy: null,
  recordOverdueSince: null,
};

/** The not-LIVE read's meta: recoveryViewPosition null. */
export const EMITTER_20261011_V117_NOT_LIVE_META = { viewPosition: 12, timestamp: '2026-10-11T12:00:00Z', recoveryViewPosition: null } as const;

/** The R6 leg: the same row read once the core's clock is past recordDueBy — recordOverdueSince is the evidence instant. */
export const EMITTER_20261011_V117_OVERDUE_ROW: EntitySummary = {
  entityId: '01H8000000000000000000000A',
  availability: 'AVAILABLE',
  stale: false,
  deviceId: '01H8000000000000000000000D',
  lastReported: '2026-10-11T11:00:00Z',
  availabilityReason: 'first_contact',
  lastSeenAt: '2026-10-11T11:00:00Z',
  link: { lqi: 200, rssiDbm: -45, at: '2026-10-11T11:00:00Z' },
  probeAnsweredAt: '2026-10-11T11:30:00Z',
  probeWindowStart: '2026-10-11T10:30:00Z',
  probesAnswered: 55,
  probesMissed: 1,
  availabilityClass: 'mains-metered',
  reportIntervalSeconds: 600,
  silenceLimitSeconds: 660,
  missesToDark: 2,
  recordIntervalSeconds: 3600,
  recordDueBy: '2026-10-11T12:41:10.050Z',
  recordOverdueSince: '2026-10-11T11:30:00Z',
};

/** The R6 leg's meta (COMPOSED — see the header): timestamp = the `now` the bound was read at. */
export const EMITTER_20261011_V117_OVERDUE_META = { viewPosition: 12, timestamp: '2026-10-11T12:41:10.051Z', recoveryViewPosition: 9 } as const;

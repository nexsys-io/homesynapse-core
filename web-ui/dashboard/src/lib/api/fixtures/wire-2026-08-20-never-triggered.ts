/*
 * REAL-PAYLOAD FIXTURE (H8 tier 1 — the live-wire verification rule).
 * ---------------------------------------------------------------------------
 * CAPTURE PROVENANCE — this is a REAL wire body, not an authored mock:
 *   Captured : 2026-08-20 ≈23:59:59 GMT (≈18:59:59 CT — "seconds before the 19:00 CT
 *              wave-trigger"; the instant is the body's own meta.timestamp, the only
 *              clock the record carries), midweek FE-deploy sitting, DevTools →
 *              Network → Response tab, pasted as text (bodies carry no token — L3).
 *   Request  : GET /api/v1/automations/01M0GPZFVANYA5TZMZSXRCV063/non-firing
 *              (host not captured — the browser reached the Pi's :7070 through an
 *              SSH tunnel; the record carries the path only)
 *   Response : 200 OK · 396 B · timing not captured · ETag not captured ·
 *              X-HomeSynapse-View-Position not captured
 *              (396 B is the record's size for the 200 class on this path — §2 row 2;
 *              re-derived: this object serializes compactly to exactly 396 bytes, as
 *              the 2026-08-16 fixture does to its recorded 395. The header values
 *              for THIS response were not transcribed; the body's own
 *              meta.viewPosition is 104220.)
 *   Record   : nexsys-hivemind/context/audits/2026-08-20_midweek-FE-deploy_sitting-record.md
 *              §6 (closure addendum — row 2 CLOSED-FULL, "the null arm CONFIRMED at the wire")
 *   Build    : the deployed bundle at capture was index-C95CAnmp.js — core c091f7c,
 *              the NEW-2/3 build (sitting record §1 Block 2). The dashboard tree at
 *              this checkout's baseline (7c9e4fa) is unchanged since c091f7c.
 *
 * WHY IT EXISTS — STABILITY, not a new arm: the 2026-08-16 fixture ALREADY carries
 * this arm of the `lastEvaluation` tri-state (bench-hero · NEVER_TRIGGERED ·
 * lastEvaluation: null · noCommandsIssued: null). This body is the SECOND real-wire
 * capture of the SAME null arm from a DIFFERENT deployment — the post-NEW-2/3
 * bundle, the re-provisioned automation id, a later view position, a
 * nanosecond-precision timestamp string. Its evidentiary value is
 * CROSS-DEPLOYMENT DIALECT STABILITY: the non-firing surface's wire shape did not
 * drift between the gate-day build (index-B9CmxYDm.js) and the NEW-2/3 build
 * (index-C95CAnmp.js) — exactly the class of silent drift the STATE-DIALECT
 * finding (F-S2) proves is possible on the /state surface. The paired detector,
 * `fixtures.stability.test.ts`, asserts the two captures agree on every key and
 * every null, and differ in VALUE only at {automationId, meta.viewPosition,
 * meta.timestamp}; any other difference is drift and fails the suite.
 *
 * VERBATIM — do not "clean up" this object. It mirrors the captured JSON
 * byte-for-byte (key order and values), envelope included.
 */
import type { Envelope, NonFiringExplanation } from '../contract';

export const WIRE_20260820_NEVER_TRIGGERED_BENCH_HERO: Envelope<NonFiringExplanation> = {
  data: {
    automationId: '01M0GPZFVANYA5TZMZSXRCV063',
    automationName: 'bench-hero',
    enabled: true,
    verdict: 'NEVER_TRIGGERED',
    lastRelevantRunId: null,
    explanation: "Automation 'bench-hero' has not been triggered; it fires on state change.",
    triggerSummary: 'state change',
    lastEvaluation: null,
    noCommandsIssued: null,
  },
  meta: { viewPosition: 104220, timestamp: '2026-08-20T23:59:59.684724880Z' },
};

/*
 * REAL-PAYLOAD FIXTURE (H8 tier 1 — the live-wire verification rule).
 * ---------------------------------------------------------------------------
 * CAPTURE PROVENANCE — this is a REAL wire body, not an authored mock:
 *   Captured : 2026-08-16 05:22:35 GMT, G1 rehearsal, DevTools → Network
 *   Request  : GET http://localhost:7070/api/v1/automations/01M028WEHCN64AFM2K0ZBSD5Z3/non-firing
 *   Response : 200 OK · 395 B · 17 ms · ETag W/"91229" · X-HomeSynapse-View-Position: 91229
 *   Record   : nexsys-hivemind/context/audits/2026-08-16_G1_rehearsal_complete-record_and_gate-day-brief.md §4.5
 *   Build    : the deployed bundle at capture was index-B9CmxYDm.js — byte-identical
 *              to this checkout's baseline build (verified 2026-08-17, FE lane).
 *
 * WHY IT EXISTS: this exact body crashed WhyNotView at the rehearsal
 * (`TypeError: can't access property "at", n.lastEvaluation is null`) because
 * contract.ts declared `lastEvaluation` non-nullable and every mock populated
 * it — the manufactured false type (DX-16, H8's origin exhibit). The fixture
 * pins the LIVE shape so the tri-state (absent / null / value) stays
 * fixture-covered instead of fixture-hidden.
 *
 * VERBATIM — do not "clean up" this object. It mirrors the captured JSON
 * byte-for-byte (key order and values), envelope included.
 */
import type { Envelope, NonFiringExplanation } from '../contract';

export const WIRE_20260816_NONFIRING_BENCH_HERO: Envelope<NonFiringExplanation> = {
  data: {
    automationId: '01M028WEHCN64AFM2K0ZBSD5Z3',
    automationName: 'bench-hero',
    enabled: true,
    verdict: 'NEVER_TRIGGERED',
    lastRelevantRunId: null,
    explanation: "Automation 'bench-hero' has not been triggered; it fires on state change.",
    triggerSummary: 'state change',
    lastEvaluation: null,
    noCommandsIssued: null,
  },
  meta: { viewPosition: 91229, timestamp: '2026-08-16T05:44:17.103484856Z' },
};

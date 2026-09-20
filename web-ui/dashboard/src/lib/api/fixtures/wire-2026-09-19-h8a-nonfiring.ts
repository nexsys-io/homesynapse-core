/*
 * REAL-PAYLOAD FIXTURE (H8 tier 1 — the live-wire verification rule).
 * ---------------------------------------------------------------------------
 * CAPTURE PROVENANCE — this is a REAL wire body, not an authored mock:
 *   Captured : 2026-09-19 17:30:55Z (12:30 CT) at H8-a block B2-1 (the third GET; the body's
 *              own meta.timestamp is 17:30:55.049Z — the automation id was resolved by name
 *              from the automations body first), on the rig (hs-fresh) against the loopback
 *              :7070; copied to the desktop and hashed (B2-3, attempt 2).
 *   Request  : GET /api/v1/automations/01M2XBCYM6K6ZW0W6WYBA84KNB/non-firing
 *              (Bearer token sent; NOT in this record — Bearer 0)
 *   Response : 200 OK · 581 B · headers not captured (the record carries the body only)
 *   Body hash: sha256 01bf6f29112eebb836a7031457bdea677ff21ab43085d0c549422c1a22efa46b
 *              (the file `nonfiring.json`, 581 bytes; JSON.stringify of this object is the
 *              same 581 bytes — fixtures.stability.test.ts pins both)
 *   Record   : nexsys-hivemind/context/audits/2026-09-06_H8a_real-wire_operator-record.md
 *              §0 THE CAPTURE (C) · B2-1 · B2-2 (K4: `triggerRef` present, matches the list's
 *              trigger ref) · B2-3
 *   Bodies   : nexsys-hivemind/context/audits/2026-09-06_H8a_v113-wire-capture/nonfiring.json
 *   Build    : the SHIPPED artifact 6bd8508 (install-smoke run #56) — a v1.1.4 emitter.
 *
 * WHY IT EXISTS — THE THIRD REAL CAPTURE OF THE NEVER_TRIGGERED NULL ARM, now on a v1.1.4
 * wire: the same automation name (bench-hero, re-provisioned a third time — a third id), the
 * same three nulls the two 2026-08 captures carry (lastEvaluation · noCommandsIssued ·
 * lastRelevantRunId), PLUS the v1.1.3 `triggerRef` as an object (FE-113's K4 → VERIFIED) and
 * the v1.1.4 trio in order — `disabledAt` null · `disabledReason` null (a non-DISABLED
 * verdict) · `definitionKey` the 64-hex string, equal to the automations body's key for the
 * same definition (DP-5). It extends the cross-deployment drift detector by ONE import: the
 * key set GROWS by exactly the four additive keys across the 08-16 → 08-20 → 09-19 sequence,
 * and every key the older captures carry is byte-identical in kind and null-ness here.
 * The verdict-sentence `explanation` is the hub's own wording, unchanged since 08-16.
 *
 * VERBATIM — do not "clean up" this object. It mirrors the captured JSON
 * byte-for-byte (key order and values), envelope included.
 */
import type { Envelope, NonFiringExplanation } from '../contract';

export const WIRE_20260919_H8A_NONFIRING_BENCH_HERO: Envelope<NonFiringExplanation> = {
  data: {
    automationId: '01M2XBCYM6K6ZW0W6WYBA84KNB',
    automationName: 'bench-hero',
    enabled: true,
    verdict: 'NEVER_TRIGGERED',
    lastRelevantRunId: null,
    explanation: "Automation 'bench-hero' has not been triggered; it fires on state change.",
    triggerSummary: 'state change',
    lastEvaluation: null,
    noCommandsIssued: null,
    triggerRef: { type: 'entity', id: '01M1PRQN03X8H4MNEZQ62F76F1' },
    disabledAt: null,
    disabledReason: null,
    definitionKey: 'ec52920629ee95fdf5a8d65f3e5d8c213a28a9b1b6f099ec5484017bf958b833',
  },
  meta: { viewPosition: 411, timestamp: '2026-09-19T17:30:55.049221541Z' },
};

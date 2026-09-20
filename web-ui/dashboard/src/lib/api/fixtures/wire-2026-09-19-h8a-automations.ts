/*
 * REAL-PAYLOAD FIXTURE (H8 tier 1 — the live-wire verification rule).
 * ---------------------------------------------------------------------------
 * CAPTURE PROVENANCE — this is a REAL wire body, not an authored mock:
 *   Captured : 2026-09-19 17:30:54Z (12:30 CT) at H8-a block B2-1 (the second of the three
 *              GETs; the body's own meta.timestamp is 17:30:54.997Z), on the rig (hs-fresh)
 *              against the loopback :7070; copied to the desktop and hashed (B2-3, attempt 2).
 *   Request  : GET /api/v1/automations  (Bearer token sent; NOT in this record — Bearer 0)
 *   Response : 200 OK · 629 B · headers not captured (the record carries the body only)
 *   Body hash: sha256 6fc36639059337f5303ad478cc597405c2388f81f7b713178098f193d3ff22c4
 *              (the file `automations.json`, 629 bytes; JSON.stringify of this object is the
 *              same 629 bytes — fixtures.stability.test.ts pins both)
 *   Record   : nexsys-hivemind/context/audits/2026-09-06_H8a_real-wire_operator-record.md
 *              §0 THE CAPTURE (C) · B2-1 · B2-2 (K3: `ref` on every component, 2 of 3 non-null,
 *              each exactly {type:"entity", id:<26>}; R16: sys_* refs 0) · B2-3
 *   Bodies   : nexsys-hivemind/context/audits/2026-09-06_H8a_v113-wire-capture/automations.json
 *   Build    : the SHIPPED artifact 6bd8508 (install-smoke run #56) — a v1.1.4 emitter.
 *
 * WHY IT EXISTS — THE FIRST REAL v1.1.3 AND v1.1.4 AUTOMATIONS BODY: `components[].ref` is
 * PRESENT on every component — an object on the trigger and the command action, JSON null on
 * the delay action (the R1 rule: a ref iff exactly ONE entity by identity) — the first real
 * capture of BOTH arms of that tri-state on one wire (FE-113's K3 → VERIFIED). And the v1.1.4
 * `definitionKey` (6th, after lastRunId) is on the wire as a 64-hex SHA-256 — the first real
 * v1.1.4 body in the corpus (FE-114's mirror of this key: VALIDATED at the wire; its non-firing
 * twin below carries the SAME key for the same definition — DP-5's equality, real). The
 * component `type` strings are the live vocabulary (StateChangeTrigger · DelayAction ·
 * CommandAction), the `summary` strings the hub's own lowercase phrases — shown as recorded.
 * `lastRunId` is null: bench-hero had never run on this install. Envelope: `pagination`
 * present (nextCursor null · hasMore false · limit 50).
 *
 * VERBATIM — do not "clean up" this object. It mirrors the captured JSON
 * byte-for-byte (key order and values), envelope included.
 */
import type { AutomationSummary, Envelope } from '../contract';

export const WIRE_20260919_H8A_AUTOMATIONS: Envelope<AutomationSummary[]> = {
  data: [
    {
      automationId: '01M2XBCYM6K6ZW0W6WYBA84KNB',
      name: 'bench-hero',
      enabled: true,
      components: [
        { type: 'StateChangeTrigger', summary: 'state change trigger', ref: { type: 'entity', id: '01M1PRQN03X8H4MNEZQ62F76F1' } },
        { type: 'DelayAction', summary: 'delay action', ref: null },
        { type: 'CommandAction', summary: 'command action', ref: { type: 'entity', id: '01M1PRQN03X8H4MNEZQ62F76F1' } },
      ],
      lastRunId: null,
      definitionKey: 'ec52920629ee95fdf5a8d65f3e5d8c213a28a9b1b6f099ec5484017bf958b833',
    },
  ],
  pagination: { nextCursor: null, hasMore: false, limit: 50 },
  meta: { viewPosition: 411, timestamp: '2026-09-19T17:30:54.997928360Z' },
};

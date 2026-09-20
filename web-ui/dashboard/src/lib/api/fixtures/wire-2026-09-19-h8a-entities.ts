/*
 * REAL-PAYLOAD FIXTURE (H8 tier 1 — the live-wire verification rule).
 * ---------------------------------------------------------------------------
 * CAPTURE PROVENANCE — this is a REAL wire body, not an authored mock:
 *   Captured : 2026-09-19 17:30:54Z (12:30 CT) at H8-a block B2-1, by the operator's
 *              scripted GET on the rig (hs-fresh) against the loopback :7070 — three
 *              bodies saved to files, copied to the desktop and hashed (B2-3, attempt 2).
 *              The instant is the body's own meta.timestamp; the paste-back's stamp
 *              reads 17:30:54Z.
 *   Request  : GET /api/v1/entities  (Bearer token sent; NOT in this record — Bearer 0)
 *   Response : 200 OK · 759 B · headers not captured (the record carries the body only)
 *   Body hash: sha256 29e04def1dd6cb3c317a665829284d61bb9700dd543dd80145193344ec005afe
 *              (the file `entities.json`, 759 bytes; JSON.stringify of this object is the
 *              same 759 bytes — fixtures.stability.test.ts pins both)
 *   Record   : nexsys-hivemind/context/audits/2026-09-06_H8a_real-wire_operator-record.md
 *              §0 THE CAPTURE (C) · B2-1 (the three GETs) · B2-2 (the asserts: K1 deviceId,
 *              K2 lastReported on every row) · B2-3 (the capture home + hashes)
 *   Bodies   : nexsys-hivemind/context/audits/2026-09-06_H8a_v113-wire-capture/entities.json
 *   Build    : the SHIPPED artifact 6bd8508 (install-smoke run #56; arm64 .deb
 *              homesynapse_0.1.0+git20260914.115803.g6bd8508_arm64, origin sha256
 *              2fba0325…09bd8) — a v1.1.4 emitter (EXPLAIN-114a/b on main; 114c is not in it).
 *
 * WHY IT EXISTS — THE FIRST REAL v1.1.3 LIST BODY (FE-113's K1/K2 → VERIFIED): every row
 * carries `deviceId` (a 26-char ULID) and `lastReported` (ISO-8601 with micros, `Z`) as
 * VALUES — the null arm is NOT on this wire (R-4c D-9's class: the desk pre-registered ≥ 1
 * null and the fleet had none), so this body is evidence for the VALUE arm only; the
 * present-null arm of these two keys stays fixture-covered by the mock, not by a capture.
 * Two rows are UNAVAILABLE with a six-day-old lastReported (the S31 pair, offline since
 * 09-13) beside two AVAILABLE rows reported within the last five minutes — the
 * evidence-with-age rendering's real input. NOTE the envelope: this list body carries NO
 * `pagination` key (the automations body beside it does) — recorded as observed, not
 * "fixed" (a §3 observation for the hub; the mirror's Envelope.pagination is optional).
 *
 * VERBATIM — do not "clean up" this object. It mirrors the captured JSON
 * byte-for-byte (key order and values), envelope included.
 */
import type { Envelope, EntitySummary } from '../contract';

export const WIRE_20260919_H8A_ENTITIES: Envelope<EntitySummary[]> = {
  data: [
    {
      entityId: '01M19RHWXYZYJMM26SX0E41HXN',
      availability: 'UNAVAILABLE',
      stale: false,
      deviceId: '01M19RHWWZXKD4MWM66KAW8MSR',
      lastReported: '2026-09-13T14:45:07.132090Z',
    },
    {
      entityId: '01M19XN7NNQQ8S3JJF09T6YKKY',
      availability: 'UNAVAILABLE',
      stale: false,
      deviceId: '01M19XN7MXFBA3P5BT4VDY0BM6',
      lastReported: '2026-09-13T14:44:31.278087Z',
    },
    {
      entityId: '01M1PRQN03X8H4MNEZQ62F76F1',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01M1PRQMZHFV4SAWT1E96B9BQ2',
      lastReported: '2026-09-19T17:28:15.976273Z',
    },
    {
      entityId: '01M2DKJWVSJCHB6TTAJSW3D880',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01M2DKJWVDDHRF8ZX9HQ5B94KX',
      lastReported: '2026-09-19T17:25:21.811743Z',
    },
  ],
  meta: { viewPosition: 411, timestamp: '2026-09-19T17:30:54.933013931Z' },
};

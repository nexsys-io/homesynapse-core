/*
 * REAL-PAYLOAD FIXTURE (H8 tier 1 — the live-wire verification rule). ONE ROW.
 * ---------------------------------------------------------------------------
 * CAPTURE PROVENANCE — a REAL wire row, not an authored mock (IR-89: never typed from
 * the Java record's component names; copied byte for byte from the captured JSON):
 *   Captured : 2026-10-10 00:10:34.867841587Z (Fri 2026-10-09 19:10 CT) by Nick's
 *              `~/bench.sh entities` on the Pi at core df2bc62 (J1 on the wire), before
 *              BC9a. The instant is the body's own meta.timestamp.
 *   Request  : GET /api/v1/entities  (Bearer token sent; NOT in this record)
 *   Response : 200 OK · body 2,627 B · 10 rows · headers not captured (body only)
 *   Body     : _scratch/v102/b4/entities_df2bc62_raw.json (md5 c39ef0f65776c4e8e8c5731874a8a504)
 *   This row : data[7] — compact JSON 322 B, md5 ebce431508f8ac9c9ba789aed1a7c60c;
 *              v116-additive.test.ts pins JSON.stringify(row) to those bytes.
 *   Build    : the Pi's df2bc62 — the J1 emitter (LINK-READ-2, landed 2026-10-03):
 *              ListEntitiesEndpoint.java:213–:216 appends availabilityReason · lastSeenAt ·
 *              link after the frozen keys; AvailabilityReason lower-cased at
 *              ZigbeeIntegrationAdapter.java:2081; link via linkJson {lqi, rssiDbm, at}.
 *
 * WHY THIS ROW — the VALUE arm of all three J1 keys on one real row: `availabilityReason`
 * is the LOWER-CASED token (`frame_received`); `lastSeenAt` is Instant.toString() with
 * NINE fractional digits (nanos) where `lastReported` carries six (micros) — two
 * precisions on one row, both ISO-8601 `Z`; `link` is {lqi, rssiDbm, at} in the wire's
 * key order. NOTE what the row shows: `lastSeenAt` (10-08) is OLDER than `lastReported`
 * (10-10) — lastSeenAt moves on an availability TRANSITION, not on every frame (SPEC
 * correction 2). The body's other arms are on the same wire and are NOT copied here (one
 * row by the charter §2): seven rows (data[1..6]) carry all three keys PRESENT-NULL beside
 * AVAILABLE and a moving lastReported (IR-133's healthy device — the common case), and
 * data[0] is UNAVAILABLE · null · null with a 2026-07-19 last report — the fifth state's
 * real exhibit at S2 (SPEC §3 R5). No `name` key on this wire (unset → omitted, C8).
 *
 * VERBATIM — do not "clean up" this object. It mirrors the captured row byte-for-byte
 * (key order and values).
 */
import type { EntitySummary } from '../contract';

export const WIRE_20261009_J1_ENTITY_ROW: EntitySummary = {
  entityId: '01M3DPGF6Y4YXNXDHBW38ZEX2G',
  availability: 'AVAILABLE',
  stale: false,
  deviceId: '01M3DPGF69FPJ6DVZCBJQMMYEA',
  lastReported: '2026-10-10T00:10:32.767199Z',
  availabilityReason: 'frame_received',
  lastSeenAt: '2026-10-08T06:55:37.106667040Z',
  link: { lqi: 248, rssiDbm: -38, at: '2026-10-08T06:55:37.106667040Z' },
};

/** The capture's envelope meta, verbatim — so a lawful body can be rebuilt around the row. */
export const WIRE_20261009_J1_META = { viewPosition: 1596602, timestamp: '2026-10-10T00:10:34.867841587Z' } as const;

/*
 * REAL-PAYLOAD FIXTURE (H8 tier 1 — the live-wire verification rule). THE WHOLE BODY: 10 ROWS + META.
 * ---------------------------------------------------------------------------
 * CAPTURE PROVENANCE — a REAL wire body, not an authored mock (IR-89: never typed from the Java record's
 * component names; emitted from the captured JSON by script, key order and values byte for byte):
 *   Captured : 2026-10-17 15:21:13.110460353Z (Sat 2026-10-17 10:21 CT) by Nick's `~/bench.sh entities` on
 *              hs-dev-1 (the Pi) at core 176adad (J3 T-B) during BC10 Part E. The instant is the body's own
 *              meta.timestamp.
 *   Request  : GET /api/v1/entities  (Bearer token sent; NOT in this record)
 *   Response : 200 OK · body 5,638 B (5,639 on disk with the trailing LF) · 10 rows · headers not captured (body only)
 *   Body     : _scratch/v111/bc10/wire-2026-10-17-entities_raw.json (md5 559ac60dc338068ad00313d57288115f)
 *   Embedded : JSON.stringify(WIRE_20261017_ENTITIES) re-serialises to the body's 5,638 compact bytes (md5
 *              3dfb721e0c4b2b7aa7df41b00f468f25); the record file is those bytes plus the shell's trailing LF —
 *              5,639 B, md5 559ac60dc338068ad00313d57288115f. v117-additive.test.ts and
 *              fixtures.stability.test.ts pin both.
 *   Build    : the Pi's 176adad — the v1.1.7 emitter (AVAIL-API-1 T-B): EntityStateJson.java RECOVERY_KEYS
 *              appends the eleven record keys after `link`; ListEntitiesEndpoint.java:212 appends
 *              meta.recoveryViewPosition after timestamp (I-14). The read is LIVE: recoveryViewPosition 2318441.
 *
 * WHY THIS BODY — the FIRST v1.1.7 capture: the emitter fixture (emitter-2026-10-11-entities-v117-row.ts,
 * MIRRORED from the emitter's test) meets the wire here, and the H8 note turns VERIFIED. What the wire shows,
 * as it is (one boot's view — never "fixed"): every row carries all eleven keys PRESENT (typed or null) in the
 * emitter's order; 9 AVAILABLE, 1 UNAVAILABLE — data[0], the Hue 01KX1PA4HSJ581GASYB7DHE40F (mains-floor;
 * reason · lastSeenAt · link all null; last report 2026-07-19: the seeded-dark version-1 device, SPEC §3 R5 at
 * S3); classes 5 passive (silenceLimitSeconds 90000 — IR-121's 25 h; recordIntervalSeconds null — never asked),
 * 3 mains-metered (600 · 660 · 2 · 3600), 2 mains-floor (null · 60 · 2 · 3600); ONE answered check on record
 * (data[4]: answered 15:17:00.416638676Z in the window from 15:16:28.269545141Z, 1 answered · 0 missed — and
 * its lastReported 15:18:00 is AFTER the answer, so SPEC §3 R1 at S3, not R2); the three J1 keys null on 7
 * rows and valued on 3 (frame_received; lastSeenAt 10-08 · 10-09 · 10-11 — OLDER than lastReported, SPEC
 * correction 2; data[7] is the j1-row fixture's entity one transition later); recordDueBy on every row and
 * recordOverdueSince null on every row (the read fell inside every bound — V-6); nine-digit nanos beside
 * six-digit micros. No `name` key (unset → omitted, C8).
 *
 * VERBATIM — do not "clean up" this object. It mirrors the captured body byte for byte (key order and values).
 */
import type { Envelope, EntitySummary } from '../contract';

export const WIRE_20261017_ENTITIES: Envelope<EntitySummary[]> = {
  data: [
    // data[0]
    {
      entityId: '01KX1PA4HSJ581GASYB7DHE40F',
      availability: 'UNAVAILABLE',
      stale: false,
      deviceId: '01KX1PA4GRZHY2GD37B5CFVQHY',
      lastReported: '2026-07-19T00:49:15.787079Z',
      availabilityReason: null,
      lastSeenAt: null,
      link: null,
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'mains-floor',
      reportIntervalSeconds: null,
      silenceLimitSeconds: 60,
      missesToDark: 2,
      recordIntervalSeconds: 3600,
      recordDueBy: '2026-10-17T16:18:18.322873Z',
      recordOverdueSince: null,
    },
    // data[1]
    {
      entityId: '01KX1PB9AAB4VB3E10BD477TV3',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01KX1PB9A5931A8G0F0X03QXT2',
      lastReported: '2026-10-17T15:10:32.406313Z',
      availabilityReason: null,
      lastSeenAt: null,
      link: null,
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'passive',
      reportIntervalSeconds: null,
      silenceLimitSeconds: 90000,
      missesToDark: 2,
      recordIntervalSeconds: null,
      recordDueBy: '2026-10-18T16:16:28.322873Z',
      recordOverdueSince: null,
    },
    // data[2]
    {
      entityId: '01KXW0157SP56CCSGJCNDCSQNG',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01KXW0156Z1GJ3WCV2G516AKWS',
      lastReported: '2026-10-17T15:21:03.894943Z',
      availabilityReason: null,
      lastSeenAt: null,
      link: null,
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'passive',
      reportIntervalSeconds: null,
      silenceLimitSeconds: 90000,
      missesToDark: 2,
      recordIntervalSeconds: null,
      recordDueBy: '2026-10-18T16:21:03.944943Z',
      recordOverdueSince: null,
    },
    // data[3]
    {
      entityId: '01KXW13WF0D6TYGN13WXHTG87K',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01KXW13WEGRCT5C0XSQT8WZBG9',
      lastReported: '2026-10-17T14:10:30.933069Z',
      availabilityReason: null,
      lastSeenAt: null,
      link: null,
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'passive',
      reportIntervalSeconds: null,
      silenceLimitSeconds: 90000,
      missesToDark: 2,
      recordIntervalSeconds: null,
      recordDueBy: '2026-10-18T16:16:28.322873Z',
      recordOverdueSince: null,
    },
    // data[4]
    {
      entityId: '01KXW1W1SBJZERC9MBAMV2DWKE',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01KXW1W1RR66GV98D9QDPB4VXY',
      lastReported: '2026-10-17T15:18:00.024006Z',
      availabilityReason: null,
      lastSeenAt: null,
      link: null,
      probeAnsweredAt: '2026-10-17T15:17:00.416638676Z',
      probeWindowStart: '2026-10-17T15:16:28.269545141Z',
      probesAnswered: 1,
      probesMissed: 0,
      availabilityClass: 'mains-floor',
      reportIntervalSeconds: null,
      silenceLimitSeconds: 60,
      missesToDark: 2,
      recordIntervalSeconds: 3600,
      recordDueBy: '2026-10-17T16:19:50.074006Z',
      recordOverdueSince: null,
    },
    // data[5]
    {
      entityId: '01KY12MQW954E4XYNKH0Y5H8VX',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01KY12MQVQ204M1VP39F1ZDM33',
      lastReported: '2026-10-17T15:01:44.367763Z',
      availabilityReason: null,
      lastSeenAt: null,
      link: null,
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'passive',
      reportIntervalSeconds: null,
      silenceLimitSeconds: 90000,
      missesToDark: 2,
      recordIntervalSeconds: null,
      recordDueBy: '2026-10-18T16:16:28.322873Z',
      recordOverdueSince: null,
    },
    // data[6]
    {
      entityId: '01M3DM74SGEY7RXVDYSM4PK2XA',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01M3DM74RKEQ9H05PAATBMW2MW',
      lastReported: '2026-10-17T15:19:48.786832Z',
      availabilityReason: null,
      lastSeenAt: null,
      link: null,
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'mains-metered',
      reportIntervalSeconds: 600,
      silenceLimitSeconds: 660,
      missesToDark: 2,
      recordIntervalSeconds: 3600,
      recordDueBy: '2026-10-17T16:31:38.836832Z',
      recordOverdueSince: null,
    },
    // data[7]
    {
      entityId: '01M3DPGF6Y4YXNXDHBW38ZEX2G',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01M3DPGF69FPJ6DVZCBJQMMYEA',
      lastReported: '2026-10-17T15:21:09.818063Z',
      availabilityReason: 'frame_received',
      lastSeenAt: '2026-10-11T02:27:52.334584811Z',
      link: { lqi: 236, rssiDbm: -41, at: '2026-10-11T02:27:52.334584811Z' },
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'mains-metered',
      reportIntervalSeconds: 600,
      silenceLimitSeconds: 660,
      missesToDark: 2,
      recordIntervalSeconds: 3600,
      recordDueBy: '2026-10-17T16:32:59.868063Z',
      recordOverdueSince: null,
    },
    // data[8]
    {
      entityId: '01M3DPKN9WD9B88Q4SVDMSBJVS',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01M3DPKN9SX4K6D7D1PKDP17S3',
      lastReported: '2026-10-17T15:21:10.332403Z',
      availabilityReason: 'frame_received',
      lastSeenAt: '2026-10-09T11:35:38.667676404Z',
      link: { lqi: 252, rssiDbm: -37, at: '2026-10-09T11:35:38.667676404Z' },
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'mains-metered',
      reportIntervalSeconds: 600,
      silenceLimitSeconds: 660,
      missesToDark: 2,
      recordIntervalSeconds: 3600,
      recordDueBy: '2026-10-17T16:33:00.382403Z',
      recordOverdueSince: null,
    },
    // data[9]
    {
      entityId: '01M3Y4YA6YSYQWVE9ET8FT4HWQ',
      availability: 'AVAILABLE',
      stale: false,
      deviceId: '01M3Y4YA6KMH7JDEF5YMMJ3YND',
      lastReported: '2026-10-17T15:21:08.285382Z',
      availabilityReason: 'frame_received',
      lastSeenAt: '2026-10-08T22:10:38.556395459Z',
      link: { lqi: 240, rssiDbm: -40, at: '2026-10-08T22:10:38.556395459Z' },
      probeAnsweredAt: null,
      probeWindowStart: null,
      probesAnswered: null,
      probesMissed: null,
      availabilityClass: 'passive',
      reportIntervalSeconds: null,
      silenceLimitSeconds: 90000,
      missesToDark: 2,
      recordIntervalSeconds: null,
      recordDueBy: '2026-10-18T16:21:08.335382Z',
      recordOverdueSince: null,
    },
  ],
  meta: { viewPosition: 2318763, timestamp: '2026-10-17T15:21:13.110460353Z', recoveryViewPosition: 2318441 },
};

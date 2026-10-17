/*
 * HERO-U3 R1 + R7 (2026-10-11) — the v1.1.7 additive keys (AVAIL-API-1 T-B, core 2a54145) under the tri-state law: on
 * HAND-BUILT bodies, on the EMITTER's row, on the seven preserved wire fixtures, and on the mock.
 * ---------------------------------------------------------------------------
 * v1.1.7 APPENDS eleven keys after `link` on the THREE entity reads (EntityStateJson.java:62–:65 RECOVERY_KEYS, written
 * by appendRecoveryKeys :124–:155 — the Get reads through `render` :107–:112, the list through `summarise`) and
 * `meta.recoveryViewPosition` AFTER `timestamp` (GetEntityEndpoint.java:155–:158 · GetEntityStateEndpoint.java:148–:151
 * · ListEntitiesEndpoint.java:209–:212 — the AVAIL-API-1 return's I-14):
 *   probeAnsweredAt · probeWindowStart — Instant.toString() | null (the newest probe_answered: its instant, its window)
 *   probesAnswered · probesMissed — int ≥ 0 | null (that ONE record's counts, never summed)
 *   availabilityClass — mains-metered · mains-floor · passive | null — an OPEN string vocabulary to this mirror
 *   reportIntervalSeconds · silenceLimitSeconds · missesToDark · recordIntervalSeconds — int | null (the contract record)
 *   recordDueBy · recordOverdueSince — Instant.toString() | null — R-G's bound, COMPUTED BY THE CORE (Q10 (a))
 *   meta.recoveryViewPosition — long | null — the recovery-card projection's position; null while it is not LIVE
 * Every key is PRESENT on a v1.1.7 read (null while not LIVE, with no record, with no contract — never absent); a
 * pre-v1.1.7 hub omits them (lawful). THE TRI-STATE: absent passes · null passes · a present key must be typed.
 *
 * RED at HEAD (2a54145): shapes.ts reads the three entity bodies (A1 :156–:184 · A2 :185–:194 · A3 :195–:212) and meta
 * (:141–:145) by the keys it knows and IGNORES the rest, so every PRESENT-wrong-type row below PASSES at HEAD — those
 * rows are the red (the manufactured-tolerance class); the pin is red on its value, RECOVERY_KEYS on the missing export,
 * the R7 rows on the missing scenarios. The ABSENT / PRESENT-null / PRESENT-typed rows are GREEN at HEAD by construction
 * (nothing inspects the keys) and are disclosed as PRESERVATION — the v115 / v116 form.
 *
 * THE ROW: fixtures/emitter-2026-10-11-entities-v117-row.ts — from the emitter's test at 2a54145; re-pinned against the
 * Pi's wire at BC10 (Sat Oct 17). Not a capture: no Pi serves v1.1.7 before BC10. The golden strings and the eleven
 * values below were extracted from EntityStateJsonV117Test.java by script, not typed (IR-89).
 *
 * HERO-U4 R1 (2026-10-17): H8 → VERIFIED. BC10's REAL v1.1.7 body at core 176adad (fixtures/wire-2026-10-17-entities.ts; the
 * record _scratch/v111/bc10/wire-2026-10-17-entities_raw.json, md5 559ac60dc338068ad00313d57288115f) validates under these arms
 * with ZERO ContractError, every row carrying the eleven keys as the emitter writes them; the emitter row's shape IS the wire's.
 * The MIRRORED note on the emitter fixture is superseded by this exercise (its file stays untouched, P3).
 */
import { describe, it, expect, afterAll } from 'vitest';
import { createHash } from 'node:crypto';
import { validateAgainstContract, ContractError, type EndpointId } from './shapes';
import { CONTRACT_VERSION, RECOVERY_KEYS, type EntitySummary, type ResponseMeta } from './contract';
import { SCENARIOS, resolveScenario } from './mock/scenarios';
import { createMockTransport } from './mock/mockTransport';
import { setScenario } from './mock/mockState';
import { WIRE_20260816_NONFIRING_BENCH_HERO } from './fixtures/wire-2026-08-16-nonfiring';
import { WIRE_20260820_EVENTS_404_HEADERS_ONLY } from './fixtures/wire-2026-08-20-events-404-headers';
import { WIRE_20260820_NEVER_TRIGGERED_BENCH_HERO } from './fixtures/wire-2026-08-20-never-triggered';
import { WIRE_20260919_H8A_AUTOMATIONS } from './fixtures/wire-2026-09-19-h8a-automations';
import { WIRE_20260919_H8A_ENTITIES } from './fixtures/wire-2026-09-19-h8a-entities';
import { WIRE_20260919_H8A_NONFIRING_BENCH_HERO } from './fixtures/wire-2026-09-19-h8a-nonfiring';
import { WIRE_20261009_J1_ENTITY_ROW, WIRE_20261009_J1_META } from './fixtures/wire-2026-10-09-entities-j1-row';
import {
  EMITTER_20261011_V117_META as LIVE_META,
  EMITTER_20261011_V117_NOT_LIVE_META as NOT_LIVE_META,
  EMITTER_20261011_V117_NOT_LIVE_ROW as NOT_LIVE_ROW,
  EMITTER_20261011_V117_OVERDUE_META as OVERDUE_META,
  EMITTER_20261011_V117_OVERDUE_ROW as OVERDUE_ROW,
  EMITTER_20261011_V117_RECORD as RECORD,
  EMITTER_20261011_V117_ROW as ROW,
} from './fixtures/emitter-2026-10-11-entities-v117-row';
/* The seven captures' SOURCE, as Vite's `?raw` serves it (vite/client types it) — md5'd below, no file-system path involved. */
import RAW_0 from './fixtures/wire-2026-08-16-nonfiring.ts?raw';
import RAW_1 from './fixtures/wire-2026-08-20-events-404-headers.ts?raw';
import RAW_2 from './fixtures/wire-2026-08-20-never-triggered.ts?raw';
import RAW_3 from './fixtures/wire-2026-09-19-h8a-automations.ts?raw';
import RAW_4 from './fixtures/wire-2026-09-19-h8a-entities.ts?raw';
import RAW_5 from './fixtures/wire-2026-09-19-h8a-nonfiring.ts?raw';
import RAW_6 from './fixtures/wire-2026-10-09-entities-j1-row.ts?raw';
/* HERO-U4 R1: the BC10 real body (176adad) — the whole A1 envelope, and its SOURCE for the md5 pin. */
import { WIRE_20261017_ENTITIES } from './fixtures/wire-2026-10-17-entities';
import RAW_7 from './fixtures/wire-2026-10-17-entities.ts?raw';

/** The emitter's order, LITERALLY (EntityStateJsonV117Test.java:65–:68 V117_KEYS = EntityStateJson.RECOVERY_KEYS :62–:65). */
const EMITTER_KEYS = ['probeAnsweredAt', 'probeWindowStart', 'probesAnswered', 'probesMissed', 'availabilityClass', 'reportIntervalSeconds', 'silenceLimitSeconds', 'missesToDark', 'recordIntervalSeconds', 'recordDueBy', 'recordOverdueSince'] as const;
type Key = (typeof EMITTER_KEYS)[number];
/** The list read's eight v1.1.6 keys (GOLDEN_V116_LIST) and the Get reads' twelve (V116_KEYS :62–:64). */
const V116_LIST_KEYS = ['entityId', 'availability', 'stale', 'deviceId', 'lastReported', 'availabilityReason', 'lastSeenAt', 'link'];
const V116_RECORD_KEYS = ['entityId', 'attributes', 'availability', 'stateVersion', 'lastChanged', 'lastUpdated', 'lastReported', 'staleAfter', 'stale', 'availabilityReason', 'lastSeenAt', 'link'];
/** The emitter's golden v1.1.6 bytes (EntityStateJsonV117Test.java:230 and :233), verbatim. */
const GOLDEN_V116_LIST = '{"data":[{"entityId":"01H8000000000000000000000A","availability":"AVAILABLE","stale":false,"deviceId":"01H8000000000000000000000D","lastReported":"2026-10-11T11:00:00Z","availabilityReason":"first_contact","lastSeenAt":"2026-10-11T11:00:00Z","link":{"lqi":200,"rssiDbm":-45,"at":"2026-10-11T11:00:00Z"}}],"meta":{"viewPosition":12,"timestamp":"2026-10-11T12:00:00Z"}}';
const GOLDEN_V116_RECORD = '{"data":{"entityId":"01H8000000000000000000000A","attributes":{},"availability":"AVAILABLE","stateVersion":4,"lastChanged":"2026-10-11T10:59:00Z","lastUpdated":"2026-10-11T11:00:00Z","lastReported":"2026-10-11T11:00:00Z","staleAfter":null,"stale":false,"availabilityReason":"first_contact","lastSeenAt":"2026-10-11T11:00:00Z","link":{"lqi":200,"rssiDbm":-45,"at":"2026-10-11T11:00:00Z"}},"meta":{"viewPosition":12,"timestamp":"2026-10-11T12:00:00Z"}}';
/** The eleven values elevenKeysInOrder_renderedFromTheRecord asserts on the LIVE read (:108–:120). */
const EMITTER_ELEVEN: Record<Key, unknown> = {
  probeAnsweredAt: "2026-10-11T11:30:00Z",
  probeWindowStart: "2026-10-11T10:30:00Z",
  probesAnswered: 55,
  probesMissed: 1,
  availabilityClass: "mains-metered",
  reportIntervalSeconds: 600,
  silenceLimitSeconds: 660,
  missesToDark: 2,
  recordIntervalSeconds: 3600,
  recordDueBy: "2026-10-11T12:41:10.050Z",
  recordOverdueSince: null,
};

const META116 = { viewPosition: LIVE_META.viewPosition, timestamp: LIVE_META.timestamp }; // a pre-v1.1.7 meta
/** A lawful body around ONE row / record; loosened ONLY here (the mirror's types forbid the wrong-type fixtures for good). */
const listBody = (row: Record<string, unknown>, meta: Record<string, unknown> = META116) => ({ data: [row], meta });
const oneBody = (data: Record<string, unknown>, meta: Record<string, unknown> = META116) => ({ data, meta });
const without = (o: object, keys: readonly string[]): Record<string, unknown> =>
  Object.fromEntries(Object.entries(o).filter(([k]) => !keys.includes(k)));
/** One key of a row, record or body by name (a typed row is an interface: no index signature to cast to). */
const field = (o: object, k: string): unknown => (o as Record<string, unknown>)[k];
/** Every v1.1.7 key PRESENT and null — the not-LIVE shape. */
const NULLS: Record<string, null> = Object.fromEntries(EMITTER_KEYS.map((k) => [k, null]));

type Kind = 'instant' | 'count' | 'class' | 'int';
const KIND: Record<Key, Kind> = {
  probeAnsweredAt: 'instant',
  probeWindowStart: 'instant',
  probesAnswered: 'count',
  probesMissed: 'count',
  availabilityClass: 'class',
  reportIntervalSeconds: 'int',
  silenceLimitSeconds: 'int',
  missesToDark: 'int',
  recordIntervalSeconds: 'int',
  recordDueBy: 'instant',
  recordOverdueSince: 'instant',
};
const TYPED: Record<Kind, unknown[]> = {
  instant: ['2026-10-11T11:30:00Z', '2026-10-11T12:41:10.050Z', '2026-10-10T00:10:32.767199123Z'],
  count: [0, 1, 55],
  class: ['mains-metered', 'mains-floor', 'passive', 'mains-hybrid'], // the last: a token this mirror does not know — OPEN
  int: [2, 60, 660, 3600, 90_000],
};
const WRONG: Record<Kind, unknown[]> = {
  instant: [1791726600, 1791726600.05, true, { epochSecond: 1791726600 }, ['2026-10-11T11:30:00Z']], // epoch seconds = the 1970 class
  count: ['55', 55.5, -1, true, {}, [55]],
  class: [3, true, ['passive'], { token: 'passive' }],
  int: ['600', 600.5, true, {}, [600]],
};
const READS: { id: EndpointId; base: () => Record<string, unknown>; body: (o: Record<string, unknown>) => unknown }[] = [
  { id: 'A1:entities', base: () => without(ROW, EMITTER_KEYS), body: (o) => listBody(o) },
  { id: 'A2:entity', base: () => without(RECORD, EMITTER_KEYS), body: (o) => oneBody(o) },
  { id: 'A3:entityState', base: () => without(RECORD, EMITTER_KEYS), body: (o) => oneBody(o) },
];

/* ---- R1: the eleven keys and the meta key, tri-state, on all three reads ---- */
describe('A1 · A2 · A3: the eleven v1.1.7 keys under the tri-state law', () => {
  for (const k of EMITTER_KEYS) {
    it(`${k} (${KIND[k]}) — ABSENT passes · PRESENT-null passes (alone, and beside every key null) · PRESENT-typed passes, on all three reads [preservation: GREEN at HEAD by construction]`, () => {
      for (const r of READS) {
        const absent = r.base();
        expect(k in absent, r.id).toBe(false);
        expect(() => validateAgainstContract(r.id, r.body(absent)), `${r.id} absent`).not.toThrow();
        expect(() => validateAgainstContract(r.id, r.body({ ...absent, [k]: null })), `${r.id} null`).not.toThrow();
        expect(() => validateAgainstContract(r.id, r.body({ ...absent, ...NULLS })), `${r.id} all null`).not.toThrow();
        for (const v of TYPED[KIND[k]]) {
          expect(() => validateAgainstContract(r.id, r.body({ ...absent, ...NULLS, [k]: v })), `${r.id} ${String(v)}`).not.toThrow();
        }
      }
    });
    it(`${k} (${KIND[k]}) — PRESENT-wrong-type THROWS ContractError on all three reads [RED at HEAD: no arm reads the key]`, () => {
      for (const r of READS) {
        for (const v of WRONG[KIND[k]]) {
          expect(() => validateAgainstContract(r.id, r.body({ ...r.base(), ...NULLS, [k]: v })), `${r.id} ${JSON.stringify(v)}`).toThrow(ContractError);
        }
      }
    });
  }
  it('meta.recoveryViewPosition — ABSENT (a pre-v1.1.7 meta) · PRESENT-null (not LIVE) · PRESENT-number (LIVE) pass on all three reads [preservation]', () => {
    for (const r of READS) {
      const o = { ...r.base(), ...NULLS };
      for (const meta of [META116, { ...META116, recoveryViewPosition: null }, { ...META116, recoveryViewPosition: 0 }, { ...META116, recoveryViewPosition: 9 }]) {
        const body = r.id === 'A1:entities' ? listBody(o, meta) : oneBody(o, meta);
        expect(() => validateAgainstContract(r.id, body), `${r.id} ${JSON.stringify(meta)}`).not.toThrow();
      }
    }
  });
  it('meta.recoveryViewPosition — a quoted number, a fraction, a negative, a boolean, an object THROW [RED at HEAD: meta() reads viewPosition and timestamp only]', () => {
    for (const r of READS) {
      const o = { ...r.base(), ...NULLS };
      for (const bad of ['9', 9.5, -1, true, { position: 9 }]) {
        const meta = { ...META116, recoveryViewPosition: bad };
        const body = r.id === 'A1:entities' ? listBody(o, meta) : oneBody(o, meta);
        expect(() => validateAgainstContract(r.id, body), `${r.id} ${JSON.stringify(bad)}`).toThrow(ContractError);
      }
    }
  });
  it('the keys are read BY NAME, never by position: the eleven reversed, or set before `link`, still validate; a wrong type is caught wherever it sits [RED at HEAD on the wrong-type arm]', () => {
    const reversed = { ...without(ROW, EMITTER_KEYS), ...Object.fromEntries([...EMITTER_KEYS].reverse().map((k) => [k, field(ROW, k)])) };
    expect(() => validateAgainstContract('A1:entities', listBody(reversed, LIVE_META))).not.toThrow();
    const early = { entityId: ROW.entityId, ...Object.fromEntries(EMITTER_KEYS.map((k) => [k, field(ROW, k)])), ...without(ROW, ['entityId', ...EMITTER_KEYS]) };
    expect(() => validateAgainstContract('A1:entities', listBody(early, LIVE_META))).not.toThrow();
    expect(() => validateAgainstContract('A1:entities', listBody({ ...early, probesMissed: '1' }, LIVE_META))).toThrow(ContractError);
  });
  it('the four frozen and v1.1.3 / v1.1.6 checks stand beside the new arms (additive only: no existing field, casing or nesting changed)', () => {
    const o = { ...without(ROW, EMITTER_KEYS), ...NULLS };
    expect(() => validateAgainstContract('A1:entities', listBody({ ...o, availability: 'BOGUS' }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', listBody({ ...o, lastReported: 1791726600 }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', listBody({ ...o, link: { lqi: 200, rssiDbm: -45 } }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A3:entityState', oneBody({ ...without(RECORD, EMITTER_KEYS), ...NULLS, stateVersion: '4' }))).toThrow(ContractError);
  });
});

/* ---- P5: the emitter's row, byte for byte ---- */
describe('THE EMITTER\'S ROW (2a54145) — the fixture is the emitter\'s literals, byte for byte (P5)', () => {
  it('stripped of the eleven keys and recoveryViewPosition, the LIVE A1 body IS the golden v1.1.6 list bytes (:230) and the A2/A3 body the golden record bytes (:233) — the emitter\'s own golden test, in this mirror', () => {
    expect(JSON.stringify({ data: [without(ROW, EMITTER_KEYS)], meta: without(LIVE_META, ['recoveryViewPosition']) })).toBe(GOLDEN_V116_LIST);
    expect(JSON.stringify({ data: without(RECORD, EMITTER_KEYS), meta: without(LIVE_META, ['recoveryViewPosition']) })).toBe(GOLDEN_V116_RECORD);
  });
  it('the eleven values are the ones elevenKeysInOrder_renderedFromTheRecord asserts (:108–:120) — on the row and on the record alike; no probeLink (Q12 (a))', () => {
    expect(Object.fromEntries(EMITTER_KEYS.map((k) => [k, field(ROW, k)]))).toEqual(EMITTER_ELEVEN);
    expect(Object.fromEntries(EMITTER_KEYS.map((k) => [k, field(RECORD, k)]))).toEqual(EMITTER_ELEVEN);
    expect('probeLink' in ROW).toBe(false);
    expect('probeLink' in RECORD).toBe(false);
  });
  it('the key order: the eight v1.1.6 list keys, then the eleven in the emitter\'s order (19, the R-3 list pin); the record\'s twelve, then the same eleven (23)', () => {
    expect(Object.keys(ROW)).toEqual([...V116_LIST_KEYS, ...EMITTER_KEYS]);
    expect(Object.keys(RECORD)).toEqual([...V116_RECORD_KEYS, ...EMITTER_KEYS]);
    expect(Object.keys(ROW).length).toBe(19);
    expect(Object.keys(RECORD).length).toBe(23);
    for (const r of [NOT_LIVE_ROW, OVERDUE_ROW]) expect(Object.keys(r)).toEqual(Object.keys(ROW));
  });
  it('contract.ts RECOVERY_KEYS mirrors the emitter\'s order exactly [RED at HEAD: no such export]', () => {
    expect(RECOVERY_KEYS).toEqual([...EMITTER_KEYS]);
  });
  it('the LIVE body validates as A1, A2 and A3; its meta is viewPosition · timestamp · recoveryViewPosition — AFTER timestamp (I-14) [preservation]', () => {
    expect(() => validateAgainstContract('A1:entities', { data: [ROW], meta: LIVE_META })).not.toThrow();
    expect(() => validateAgainstContract('A2:entity', { data: RECORD, meta: LIVE_META })).not.toThrow();
    expect(() => validateAgainstContract('A3:entityState', { data: RECORD, meta: LIVE_META })).not.toThrow();
    expect(Object.keys(LIVE_META)).toEqual(['viewPosition', 'timestamp', 'recoveryViewPosition']);
  });
  it('the not-LIVE leg: every key PRESENT and null, recoveryViewPosition null — validates [preservation]', () => {
    for (const k of EMITTER_KEYS) {
      expect(k in NOT_LIVE_ROW, k).toBe(true);
      expect(field(NOT_LIVE_ROW, k), k).toBeNull();
    }
    expect(NOT_LIVE_META.recoveryViewPosition).toBeNull();
    expect(without(NOT_LIVE_ROW, EMITTER_KEYS)).toEqual(without(ROW, EMITTER_KEYS));
    expect(() => validateAgainstContract('A1:entities', { data: [NOT_LIVE_ROW], meta: NOT_LIVE_META })).not.toThrow();
  });
  it('V-6, the core\'s rule, on both legs: recordOverdueSince is null while the read is before recordDueBy, and is the evidence instant once the read is past it', () => {
    const at = (iso: string | null | undefined) => Date.parse(String(iso));
    expect(at(LIVE_META.timestamp)).toBeLessThan(at(ROW.recordDueBy));
    expect(ROW.recordOverdueSince).toBeNull();
    expect(at(OVERDUE_META.timestamp)).toBeGreaterThan(at(OVERDUE_ROW.recordDueBy));
    expect(OVERDUE_ROW.recordOverdueSince).toBe('2026-10-11T11:30:00Z');
    expect(without(OVERDUE_ROW, ['recordOverdueSince'])).toEqual(without(ROW, ['recordOverdueSince']));
    expect(() => validateAgainstContract('A1:entities', { data: [OVERDUE_ROW], meta: OVERDUE_META })).not.toThrow();
  });
});

/* ---- P3: the seven wire fixtures, untouched ---- */
const WIRE_RAW: Record<string, string> = {
  'wire-2026-08-16-nonfiring.ts': RAW_0,
  'wire-2026-08-20-events-404-headers.ts': RAW_1,
  'wire-2026-08-20-never-triggered.ts': RAW_2,
  'wire-2026-09-19-h8a-automations.ts': RAW_3,
  'wire-2026-09-19-h8a-entities.ts': RAW_4,
  'wire-2026-09-19-h8a-nonfiring.ts': RAW_5,
  'wire-2026-10-09-entities-j1-row.ts': RAW_6,
  'wire-2026-10-17-entities.ts': RAW_7,
};
const WIRE_MD5: Record<string, string> = {
  'wire-2026-08-16-nonfiring.ts': '896f861d70026487bc4e8df1496a42f8',
  'wire-2026-08-20-events-404-headers.ts': '6f502c7b39fb2e0a57d3634cf3aee320',
  'wire-2026-08-20-never-triggered.ts': '05b02fbcc2fa6a74f6996cc4470839ee',
  'wire-2026-09-19-h8a-automations.ts': '8983c148b2aeb13f818cca16fafa34b2',
  'wire-2026-09-19-h8a-entities.ts': 'bc58bc411130c8bd5439b6f32eebd538',
  'wire-2026-09-19-h8a-nonfiring.ts': 'c9d414b7297aed78214c99188634ee17',
  'wire-2026-10-09-entities-j1-row.ts': '88eb7965cf97bd65c07699df68b9b809',
  'wire-2026-10-17-entities.ts': '6b8dbcc09960aa39b9baa452fb8a5f06', // HERO-U4 R1 — pinned at its birth (the BC10 body; the provenance block is part of the record)
};
describe('the eight wire-*.ts fixtures are untouched and still validate (the preservation fixtures, P3; HERO-U4 R1 adds the BC10 body — 7 → 8, the named flip)', () => {
  it('each file\'s md5 is its md5 at 2a54145 — the BC10 body\'s at its birth (the real captures are never "cleaned up")', () => {
    expect(Object.keys(WIRE_MD5).length).toBe(8);
    for (const [file, md5] of Object.entries(WIRE_MD5)) {
      expect(typeof WIRE_RAW[file], file).toBe('string');
      expect(createHash('md5').update(WIRE_RAW[file]!, 'utf8').digest('hex'), file).toBe(md5);
    }
  });
  it('the six bodies validate under the v1.1.7 validators with ZERO ContractError, and none carries a v1.1.7 key (the ABSENT arm, on the real wire); the 404 record is still headers-only', () => {
    expect(() => validateAgainstContract('A1:entities', WIRE_20260919_H8A_ENTITIES)).not.toThrow();
    expect(() => validateAgainstContract('A1:entities', { data: [WIRE_20261009_J1_ENTITY_ROW], meta: WIRE_20261009_J1_META })).not.toThrow();
    expect(() => validateAgainstContract('B3:automations', WIRE_20260919_H8A_AUTOMATIONS)).not.toThrow();
    for (const nf of [WIRE_20260816_NONFIRING_BENCH_HERO, WIRE_20260820_NEVER_TRIGGERED_BENCH_HERO, WIRE_20260919_H8A_NONFIRING_BENCH_HERO]) {
      expect(() => validateAgainstContract('B3:nonFiring', nf)).not.toThrow();
    }
    for (const e of [...WIRE_20260919_H8A_ENTITIES.data, WIRE_20261009_J1_ENTITY_ROW]) for (const k of EMITTER_KEYS) expect(k in e, k).toBe(false);
    for (const m of [WIRE_20260919_H8A_ENTITIES.meta, WIRE_20261009_J1_META]) expect('recoveryViewPosition' in m).toBe(false);
    expect(WIRE_20260820_EVENTS_404_HEADERS_ONLY.status).toBe(404);
  });
});

/* ---- HERO-U4 R1 (2026-10-17): THE REAL WIRE — BC10's v1.1.7 body at core 176adad (H8 → VERIFIED) ---- */
describe('HERO-U4 R1 — THE REAL WIRE (BC10, core 176adad): the first v1.1.7 body, byte for byte [RED at HEAD: the fixture module does not exist]', () => {
  const BODY = WIRE_20261017_ENTITIES;
  const rows = BODY.data;
  const JSON_BYTES = JSON.stringify(BODY);
  const md5 = (s: string) => createHash('md5').update(s, 'utf8').digest('hex');
  it('the embedded body IS the record: 5,638 compact UTF-8 bytes (md5 3dfb721e…) — plus the file\'s trailing LF, 5,639 B, md5 559ac60dc338068ad00313d57288115f; 10 rows; no Bearer', () => {
    expect(new TextEncoder().encode(JSON_BYTES).length).toBe(5638);
    expect(md5(JSON_BYTES)).toBe('3dfb721e0c4b2b7aa7df41b00f468f25');
    expect(md5(`${JSON_BYTES}\n`)).toBe('559ac60dc338068ad00313d57288115f');
    expect(rows.length).toBe(10);
    expect(JSON_BYTES).not.toContain('Bearer');
  });
  it('P2 — the whole body validates as A1 under v1.1.7 with ZERO ContractError, and so does every row alone', () => {
    expect(() => validateAgainstContract('A1:entities', BODY)).not.toThrow();
    for (const r of rows) expect(() => validateAgainstContract('A1:entities', { data: [r], meta: BODY.meta }), r.entityId).not.toThrow();
  });
  it('P2 — every row carries the eleven keys PRESENT after the eight v1.1.6 list keys, in the emitter\'s order (19 keys), each null or typed as the emitter writes it', () => {
    for (const r of rows) {
      expect(Object.keys(r), r.entityId).toEqual([...V116_LIST_KEYS, ...EMITTER_KEYS]);
      for (const k of EMITTER_KEYS) {
        const v = field(r, k);
        const at = `${r.entityId}.${k}`;
        if (v === null) continue;
        if (KIND[k] === 'instant' || KIND[k] === 'class') expect(typeof v, at).toBe('string');
        if (KIND[k] === 'instant') expect(Number.isNaN(Date.parse(v as string)), at).toBe(false);
        if (KIND[k] === 'count' || KIND[k] === 'int') expect(Number.isInteger(v) && (v as number) >= 0, at).toBe(true);
      }
    }
  });
  it('the meta is viewPosition · timestamp · recoveryViewPosition — AFTER timestamp (I-14); the read is LIVE and the instant is the capture\'s own', () => {
    expect(Object.keys(BODY.meta)).toEqual(['viewPosition', 'timestamp', 'recoveryViewPosition']);
    expect(BODY.meta).toEqual({ viewPosition: 2318763, timestamp: '2026-10-17T15:21:13.110460353Z', recoveryViewPosition: 2318441 });
  });
  it('the wire as it is, never "fixed": ONE UNAVAILABLE row — data[0], the Hue, mains-floor with reason · lastSeenAt · link null since a 2026-07-19 report; 5 passive · 3 metered · 2 floor with their contracts; ONE answered check on record; recordOverdueSince null on every row', () => {
    expect(rows.filter((r) => r.availability === 'UNAVAILABLE').map((r) => r.entityId)).toEqual(['01KX1PA4HSJ581GASYB7DHE40F']);
    expect(rows[0]).toMatchObject({ entityId: '01KX1PA4HSJ581GASYB7DHE40F', availabilityClass: 'mains-floor', availabilityReason: null, lastSeenAt: null, link: null, lastReported: '2026-07-19T00:49:15.787079Z' });
    const by = (c: string) => rows.filter((r) => r.availabilityClass === c);
    const contract = (r: EntitySummary) => [r.reportIntervalSeconds, r.silenceLimitSeconds, r.missesToDark, r.recordIntervalSeconds];
    expect([by('passive').length, by('mains-metered').length, by('mains-floor').length]).toEqual([5, 3, 2]);
    for (const r of by('passive')) expect(contract(r), r.entityId).toEqual([null, 90000, 2, null]);
    for (const r of by('mains-metered')) expect(contract(r), r.entityId).toEqual([600, 660, 2, 3600]);
    for (const r of by('mains-floor')) expect(contract(r), r.entityId).toEqual([null, 60, 2, 3600]);
    expect(rows.filter((r) => r.probeAnsweredAt !== null).map((r) => r.entityId)).toEqual(['01KXW1W1SBJZERC9MBAMV2DWKE']);
    expect(rows[4]).toMatchObject({ probeAnsweredAt: '2026-10-17T15:17:00.416638676Z', probeWindowStart: '2026-10-17T15:16:28.269545141Z', probesAnswered: 1, probesMissed: 0 });
    for (const r of rows) {
      expect(typeof r.recordDueBy, r.entityId).toBe('string');
      expect(r.recordOverdueSince, r.entityId).toBeNull();
    }
  });
  it('the emitter fixture\'s MIRRORED shape IS the wire\'s — the same 19 keys in the same order, the same meta key order (H8 VERIFIED on this body); data[7] is the j1-row fixture\'s entity one J1 transition later', () => {
    for (const r of rows) expect(Object.keys(r), r.entityId).toEqual(Object.keys(ROW));
    expect(Object.keys(BODY.meta)).toEqual(Object.keys(LIVE_META));
    expect(rows[7]!.entityId).toBe(WIRE_20261009_J1_ENTITY_ROW.entityId);
    expect(Date.parse(rows[7]!.lastSeenAt!)).toBeGreaterThan(Date.parse(WIRE_20261009_J1_ENTITY_ROW.lastSeenAt!));
  });
  it('V-6 on the real wire: every recordDueBy is AFTER the read instant, so every recordOverdueSince is null — the core\'s rule, observed', () => {
    const read = Date.parse(BODY.meta.timestamp);
    for (const r of rows) expect(Date.parse(r.recordDueBy!), r.entityId).toBeGreaterThan(read);
  });
});

/* ---- the pin and the mirror ---- */
describe('the pin and the mirror', () => {
  it('CONTRACT_VERSION is the v1.1.7 pin — the day T-B landed on main [RED at HEAD: v1.1.6-2026-10-09]', () => {
    expect(CONTRACT_VERSION).toBe('v1.1.7-2026-10-11');
  });
  it('a v1.1.7 row, a present-null row and a pre-v1.1.7 row are all assignable to EntitySummary; ResponseMeta takes a position, null, or nothing (tsc is the gate)', () => {
    const typed: EntitySummary = { ...ROW };
    const nulled: EntitySummary = { ...NOT_LIVE_ROW };
    const pre: EntitySummary = { entityId: ROW.entityId, availability: ROW.availability, stale: ROW.stale };
    const metas: ResponseMeta[] = [{ ...LIVE_META }, { ...NOT_LIVE_META }, { ...META116 }];
    expect([typed, nulled, pre].length + metas.length).toBe(6);
  });
});

/* ---- R7: the mock serves a v1.1.7 fleet, LIVE and catching up ---- */
type Body = { data: unknown; meta: Record<string, unknown> };
const send = async (path: string): Promise<Body> => (await createMockTransport(() => 'demo-token').send({ method: 'GET', path })).body as Body;
describe('HERO-U3 R7 — the mock serves a v1.1.7 fleet, LIVE and catching up [RED at HEAD: neither scenario exists]', () => {
  afterAll(() => setScenario('default'));
  const scenario = (id: string) => SCENARIOS.find((s) => s.id === id);
  it('`recovery-live`: every row carries the v1.1.6 triple and the eleven keys PRESENT; recoveryViewPosition is a number; the A1 body and every A3 record validate', () => {
    expect(scenario('recovery-live')).toBeTruthy();
    const d = resolveScenario('recovery-live');
    expect(typeof d.recoveryViewPosition).toBe('number');
    expect(d.entities.length).toBeGreaterThanOrEqual(6);
    for (const e of d.entities) for (const k of ['availabilityReason', 'lastSeenAt', 'link', ...EMITTER_KEYS]) expect(k in e, `${e.entityId}.${k}`).toBe(true);
    expect(() => validateAgainstContract('A1:entities', { data: d.entities, meta: { ...META116, recoveryViewPosition: d.recoveryViewPosition } })).not.toThrow();
    for (const e of d.entities) {
      const s = d.entityState[e.entityId];
      expect(s, e.entityId).toBeTruthy();
      expect(() => validateAgainstContract('A3:entityState', { data: s, meta: { ...META116, recoveryViewPosition: d.recoveryViewPosition } }), e.entityId).not.toThrow();
      for (const k of EMITTER_KEYS) expect(field(s!, k), `${e.entityId}.${k}`).toEqual(field(e, k));
    }
  });
  it('carries a mains-metered device with a FULL record · a mains-floor device with its contract and null probe* · a passive device (recordIntervalSeconds null) · a row past R-G\'s bound · and at least one null per key (the H8 false-type rule)', () => {
    const rows = resolveScenario('recovery-live').entities;
    const has = (f: (e: EntitySummary) => boolean) => rows.some(f);
    expect(has((e) => e.availabilityClass === 'mains-metered' && EMITTER_KEYS.filter((k) => k !== 'recordOverdueSince').every((k) => e[k] !== null))).toBe(true);
    expect(has((e) => e.availabilityClass === 'mains-floor' && e.reportIntervalSeconds === null && e.silenceLimitSeconds === 60 && e.probeAnsweredAt === null && e.probeWindowStart === null && e.probesAnswered === null && e.probesMissed === null)).toBe(true);
    expect(has((e) => e.availabilityClass === 'passive' && e.recordIntervalSeconds === null)).toBe(true);
    expect(has((e) => e.availability === 'AVAILABLE' && typeof e.recordOverdueSince === 'string')).toBe(true);
    for (const k of EMITTER_KEYS) expect(rows.some((e) => e[k] === null), k).toBe(true);
    for (const k of EMITTER_KEYS) expect(rows.some((e) => e[k] !== null), k).toBe(true);
  });
  it('V-6 in the mock: every recordOverdueSince the mock core serves sits beside a recordDueBy earlier than the read (never an overdue before its due instant)', () => {
    expect(scenario('recovery-live')).toBeTruthy();
    const now = Date.now();
    for (const e of resolveScenario('recovery-live').entities) {
      if (e.recordOverdueSince != null) {
        expect(typeof e.recordDueBy, e.entityId).toBe('string');
        expect(Date.parse(e.recordDueBy!), e.entityId).toBeLessThan(now);
      }
    }
  });
  it('`recovery-catching-up` is the same fleet NOT LIVE: the same entities, every key PRESENT and null, recoveryViewPosition null; validates', () => {
    expect(scenario('recovery-catching-up')).toBeTruthy();
    const dark = resolveScenario('recovery-catching-up');
    const lit = resolveScenario('recovery-live');
    expect(dark.recoveryViewPosition).toBeNull();
    expect(dark.entities.map((e) => e.entityId)).toEqual(lit.entities.map((e) => e.entityId));
    for (const e of dark.entities) for (const k of EMITTER_KEYS) {
      expect(k in e, `${e.entityId}.${k}`).toBe(true);
      expect(e[k], `${e.entityId}.${k}`).toBeNull();
    }
    expect(() => validateAgainstContract('A1:entities', { data: dark.entities, meta: { ...META116, recoveryViewPosition: null } })).not.toThrow();
  });
  it('the mock transport appends recoveryViewPosition AFTER timestamp on the three entity reads of a v1.1.7 dataset — and omits it for a pre-v1.1.7 hub (the default home) and on every other read', async () => {
    setScenario('recovery-live');
    const list = await send('/api/v1/entities');
    expect(Object.keys(list.meta)).toEqual(['viewPosition', 'timestamp', 'recoveryViewPosition']);
    expect(typeof list.meta.recoveryViewPosition).toBe('number');
    expect(() => validateAgainstContract('A1:entities', list)).not.toThrow();
    const id = (list.data as EntitySummary[])[0]!.entityId;
    for (const path of [`/api/v1/entities/${id}`, `/api/v1/entities/${id}/state`]) {
      const b = await send(path);
      expect(Object.keys(b.meta), path).toEqual(['viewPosition', 'timestamp', 'recoveryViewPosition']);
      for (const k of EMITTER_KEYS) expect(k in (b.data as Record<string, unknown>), `${path} ${k}`).toBe(true);
    }
    expect('recoveryViewPosition' in (await send('/api/v1/health')).meta).toBe(false);
    setScenario('recovery-catching-up');
    expect((await send('/api/v1/entities')).meta.recoveryViewPosition).toBeNull();
    setScenario('default');
    expect(Object.keys((await send('/api/v1/entities')).meta)).toEqual(['viewPosition', 'timestamp']);
  });
});

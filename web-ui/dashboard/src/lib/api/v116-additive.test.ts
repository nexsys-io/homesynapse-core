/*
 * HERO-U2b R1 (2026-10-09) — the v1.1.6 additive keys, honesty-tested on HAND-BUILT bodies, on ONE REAL
 * wire row and on the mock.
 * ---------------------------------------------------------------------------
 * v1.1.6 (J1 LINK-READ-2; landed on main 2026-10-03; on the Pi's wire at df2bc62) adds THREE keys on ONE
 * read — the A1 entity row — APPENDED after `lastReported` (ListEntitiesEndpoint.java:213–:216):
 *   `availabilityReason: string | null` — the reason name LOWER-CASED (ZigbeeIntegrationAdapter.java:2081),
 *     an OPEN vocabulary to this mirror (first_contact · ping_success · frame_received · ping_timeout ·
 *     silence_timeout · leave today — AvailabilityReason.java); null until the first transition (IR-133) or
 *     for a version-1 event;
 *   `lastSeenAt: string | null` — Instant.toString() (nanos when present), NEVER epoch seconds;
 *   `link: {lqi: number, rssiDbm: number, at: string} | null` — linkJson, all three fields written.
 * THE TRI-STATE IDIOM (MODULE_CONTEXT §Gotchas): absent passes (a pre-J1 hub) · null passes ("nothing on
 * record") · a present key must be typed — a wrong type is ContractError, never "optional".
 *
 * RED at HEAD (2b4be09): shapes.ts:145–:163 validates the A1 row with NO arm for the three keys, so every
 * PRESENT-wrong-type row below PASSES at HEAD (the manufactured-tolerance class) — those rows are the red;
 * the §D2 mock rows are red because mockData's fleet carries none of the keys; the pin row is red on the
 * value. The ABSENT / PRESENT-null / PRESENT-typed rows are GREEN at HEAD by construction (nothing inspected
 * the keys) and are disclosed as preservation — the v115 form.
 *
 * THE REAL ROW: fixtures/wire-2026-10-09-entities-j1-row.ts — data[7] of Nick's `~/bench.sh entities` capture
 * at df2bc62 (IR-89: from a real payload, never from the Java record's component names). The VALUE arm of
 * all three keys is VERIFIED on that body; the null arm is REAL on the same wire (seven rows) and is
 * fixture-covered here by the mock and by hand-built bodies.
 */
import { describe, it, expect } from 'vitest';
import { validateAgainstContract, ContractError } from './shapes';
import { CONTRACT_VERSION, type EntitySummary } from './contract';
import { entities } from './mock/mockData';
import { SCENARIOS, resolveScenario } from './mock/scenarios';
import { WIRE_20260919_H8A_ENTITIES as PRE_J1_BODY } from './fixtures/wire-2026-09-19-h8a-entities';
import { WIRE_20261009_J1_ENTITY_ROW as REAL_ROW, WIRE_20261009_J1_META as REAL_META } from './fixtures/wire-2026-10-09-entities-j1-row';

const META = { viewPosition: 1596602, timestamp: '2026-10-10T00:10:34.867841587Z' };
/** A lawful A1 body around ONE row; the row is loosened ONLY here (the mirror's types forbid the wrong-type fixtures for good). */
const a1 = (row: Record<string, unknown>) => ({ data: [row], meta: META });
const base = (): Record<string, unknown> => ({
  entityId: '01M3DPGF6Y4YXNXDHBW38ZEX2G',
  availability: 'AVAILABLE',
  stale: false,
  deviceId: '01M3DPGF69FPJ6DVZCBJQMMYEA',
  lastReported: '2026-10-10T00:10:32.767199Z',
});
/** The null arm as the wire serves it (data[1..6] of the capture): all three keys PRESENT, JSON null. */
const nulls = (): Record<string, unknown> => ({ ...base(), availabilityReason: null, lastSeenAt: null, link: null });
const LINK = { lqi: 248, rssiDbm: -38, at: '2026-10-08T06:55:37.106667040Z' };
const REASONS = ['first_contact', 'ping_success', 'frame_received', 'ping_timeout', 'silence_timeout', 'leave'] as const;

/* ---- A1: the three J1 keys, tri-state ---- */
describe('A1 entities: availabilityReason / lastSeenAt / link tri-state (v1.1.6)', () => {
  it('ABSENT passes — a pre-J1 hub omits all three (the real 2026-09-19 body does) [GREEN at HEAD by construction; preservation]', () => {
    for (const e of PRE_J1_BODY.data) {
      expect('availabilityReason' in e).toBe(false);
      expect('lastSeenAt' in e).toBe(false);
      expect('link' in e).toBe(false);
    }
    expect(() => validateAgainstContract('A1:entities', PRE_J1_BODY)).not.toThrow();
    expect(() => validateAgainstContract('A1:entities', a1(base()))).not.toThrow();
  });
  it('PRESENT-null passes on all three — "nothing on record" (IR-133: the healthy device until its first transition) [preservation]', () => {
    expect(() => validateAgainstContract('A1:entities', a1(nulls()))).not.toThrow();
    // each key null on its own, the others valued — the nulls are independent facts
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), availabilityReason: 'frame_received' }))).not.toThrow();
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), lastSeenAt: LINK.at }))).not.toThrow();
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: LINK }))).not.toThrow();
  });
  it('PRESENT-typed passes: the REAL df2bc62 row, verbatim, inside its own envelope meta [preservation]', () => {
    expect(() => validateAgainstContract('A1:entities', { data: [REAL_ROW], meta: REAL_META })).not.toThrow();
  });
  it('PRESENT-typed passes on every known reason token, lower-cased, and on a token this mirror does not know (an OPEN vocabulary) [preservation]', () => {
    for (const r of REASONS) {
      expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), availabilityReason: r })), r).not.toThrow();
    }
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), availabilityReason: 'some_future_reason' }))).not.toThrow();
  });

  it('PRESENT-wrong-type THROWS: availabilityReason as a number, a boolean, an object, an array [RED at HEAD]', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), availabilityReason: 3 }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), availabilityReason: true }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), availabilityReason: { name: 'PING_TIMEOUT' } }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), availabilityReason: ['ping_timeout'] }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: lastSeenAt as epoch SECONDS (the 1970 misread class), a boolean, an object [RED at HEAD]', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), lastSeenAt: 1759906537 }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), lastSeenAt: 1759906537.106 }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), lastSeenAt: false }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), lastSeenAt: { epochSecond: 1759906537 } }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: link as a string, a number, a boolean, an array [RED at HEAD]', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: 'lqi 248' }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: 248 }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: true }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: [248, -38] }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: a link object MISSING a field linkJson always writes (lqi · rssiDbm · at) [RED at HEAD]', () => {
    for (const k of ['lqi', 'rssiDbm', 'at'] as const) {
      const l: Record<string, unknown> = { ...LINK };
      delete l[k];
      expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: l })), k).toThrow(ContractError);
    }
  });
  it('PRESENT-wrong-type THROWS: a link field of the wrong type — lqi / rssiDbm as strings or null, at as epoch seconds or null [RED at HEAD]', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: { ...LINK, lqi: '248' } }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: { ...LINK, lqi: null } }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: { ...LINK, rssiDbm: '-38' } }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: { ...LINK, rssiDbm: null } }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: { ...LINK, at: 1759906537 } }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), link: { ...LINK, at: null } }))).toThrow(ContractError);
  });
  it('the four frozen + v1.1.3 checks are untouched beside the new arms (additive-only: existing field/casing/nesting unchanged)', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), availability: 'BOGUS' }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), lastReported: 1759906537 }))).toThrow(ContractError);
    expect(() => validateAgainstContract('A1:entities', a1({ ...nulls(), deviceId: 42 }))).toThrow(ContractError);
  });
});

/* ---- The REAL row: the bytes, the key order, the two precisions ---- */
describe('the REAL df2bc62 row (data[7]) is the wire, byte for byte', () => {
  it('JSON.stringify of the fixture is the capture\'s compact row (322 B) — key order and values', () => {
    const s = JSON.stringify(REAL_ROW);
    expect(s).toBe(
      '{"entityId":"01M3DPGF6Y4YXNXDHBW38ZEX2G","availability":"AVAILABLE","stale":false,"deviceId":"01M3DPGF69FPJ6DVZCBJQMMYEA","lastReported":"2026-10-10T00:10:32.767199Z","availabilityReason":"frame_received","lastSeenAt":"2026-10-08T06:55:37.106667040Z","link":{"lqi":248,"rssiDbm":-38,"at":"2026-10-08T06:55:37.106667040Z"}}',
    );
    expect(new TextEncoder().encode(s).length).toBe(322);
  });
  it('the eight wire keys in the endpoint\'s order (summarise: frozen five, then the three J1 keys), no `name`', () => {
    expect(Object.keys(REAL_ROW)).toEqual(['entityId', 'availability', 'stale', 'deviceId', 'lastReported', 'availabilityReason', 'lastSeenAt', 'link']);
    expect(Object.keys(REAL_ROW.link!)).toEqual(['lqi', 'rssiDbm', 'at']);
  });
  it('the reason is LOWER-CASED on the wire; the instants are ISO-8601 Z strings at two precisions (nanos vs micros); never epoch seconds', () => {
    expect(REAL_ROW.availabilityReason).toBe('frame_received');
    expect(REAL_ROW.availabilityReason).toBe(REAL_ROW.availabilityReason!.toLowerCase());
    expect(REAL_ROW.lastSeenAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{9}Z$/);
    expect(REAL_ROW.lastReported).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/);
    expect(typeof REAL_ROW.link!.lqi).toBe('number');
    expect(typeof REAL_ROW.link!.rssiDbm).toBe('number');
    expect(REAL_ROW.link!.at).toBe(REAL_ROW.lastSeenAt);
    // the transition instant is OLDER than the last report — lastSeenAt is not "the last frame"
    expect(new Date(REAL_ROW.lastSeenAt!).getTime()).toBeLessThan(new Date(REAL_ROW.lastReported!).getTime());
  });
});

/* ---- The mirror and the pin ---- */
describe('the TypeScript mirror declares the three keys optional-nullable (additive, no rename); CONTRACT_VERSION is the v1.1.6 pin', () => {
  it('a J1 row, a pre-J1 row and a present-null row are all assignable; the pin reads v1.1.6 [RED at HEAD: the pin is v1.1.5]', () => {
    const typed: EntitySummary = { ...REAL_ROW };
    const nulled: EntitySummary = { ...REAL_ROW, availabilityReason: null, lastSeenAt: null, link: null };
    const pre: EntitySummary = { entityId: REAL_ROW.entityId, availability: REAL_ROW.availability, stale: REAL_ROW.stale };
    expect([typed, nulled, pre].length).toBe(3);
    // D0: the pin's homes (contract.ts · contract.test.ts · v113 · v114 · v115 · this file · scripts/contract-check.mjs)
    // move together; this file asserts the value through the module.
    expect(CONTRACT_VERSION).toMatch(/^v1\.1\.6-/);
  });
});

/* ---- D2: the default mock is a J1 hub on the A1 read that does NOT always populate (H8) ---- */
describe('HERO-U2b D2 — the default mock fleet carries the three keys PRESENT, with the null arm and the six tokens [RED at HEAD: absent]', () => {
  it('every row carries availabilityReason / lastSeenAt / link PRESENT (a J1 hub never omits them)', () => {
    expect(entities.length).toBeGreaterThanOrEqual(6);
    for (const e of entities) {
      expect('availabilityReason' in e, e.entityId).toBe(true);
      expect('lastSeenAt' in e, e.entityId).toBe(true);
      expect('link' in e, e.entityId).toBe(true);
    }
  });
  it('at least one AVAILABLE row carries all three null beside a moving lastReported — IR-133\'s healthy device, the common case', () => {
    const healthyNull = entities.filter(
      (e) => e.availability === 'AVAILABLE' && e.availabilityReason === null && e.lastSeenAt === null && e.link === null && typeof e.lastReported === 'string',
    );
    expect(healthyNull.length).toBeGreaterThanOrEqual(1);
  });
  it('each of the six reason tokens is carried by some row, LOWER-CASED, and no row carries an upper-cased token', () => {
    const carried = new Set(entities.map((e) => e.availabilityReason).filter((r): r is string => typeof r === 'string'));
    for (const r of REASONS) expect(carried.has(r), r).toBe(true);
    for (const r of carried) expect(r).toBe(r.toLowerCase());
  });
  it('the reasons are consistent with the flag they explain (a transition INTO the row\'s availability): AVAILABLE ← first_contact / ping_success / frame_received / null; UNAVAILABLE ← ping_timeout / silence_timeout / leave / null', () => {
    for (const e of entities) {
      if (e.availability === 'AVAILABLE') expect([null, 'first_contact', 'ping_success', 'frame_received'], e.entityId).toContain(e.availabilityReason);
      if (e.availability === 'UNAVAILABLE') expect([null, 'ping_timeout', 'silence_timeout', 'leave'], e.entityId).toContain(e.availabilityReason);
    }
  });
  it('a ping_success row beside AVAILABLE has lastSeenAt AFTER lastReported — the R2 Quiet edge is reachable from the default home', () => {
    const edge = entities.find((e) => e.availability === 'AVAILABLE' && e.availabilityReason === 'ping_success');
    expect(edge).toBeTruthy();
    expect(typeof edge!.lastSeenAt).toBe('string');
    expect(typeof edge!.lastReported).toBe('string');
    expect(new Date(edge!.lastSeenAt!).getTime()).toBeGreaterThan(new Date(edge!.lastReported!).getTime());
  });
  it('link is a value on at least one row and null on at least one row that still carries a reason (the nulls are independent)', () => {
    expect(entities.some((e) => e.link !== null && typeof e.link === 'object')).toBe(true);
    expect(entities.some((e) => e.link === null && typeof e.availabilityReason === 'string')).toBe(true);
  });
  it('the default fleet, read through the validator, is a lawful J1 body', () => {
    expect(() => validateAgainstContract('A1:entities', { data: entities, meta: META })).not.toThrow();
  });
});

/* ---- D2: the DevPanel scenario per §3 cell, and the ABSENT arm kept reachable ---- */
describe('HERO-U2b D2 — the `recovery-states` scenario is one J1 hub with a row per S2 cell of SPEC §3 [RED at HEAD: no such scenario]', () => {
  it('exists in the registry; every row carries the three keys PRESENT and the dataset validates as an A1 body', () => {
    expect(SCENARIOS.some((s) => s.id === 'recovery-states')).toBe(true);
    const d = resolveScenario('recovery-states');
    expect(d.entities.length).toBeGreaterThanOrEqual(8);
    for (const e of d.entities) for (const k of ['availabilityReason', 'lastSeenAt', 'link']) expect(k in e, `${e.entityId}.${k}`).toBe(true);
    expect(() => validateAgainstContract('A1:entities', { data: d.entities, meta: META })).not.toThrow();
  });
  it('carries: the IR-133 null row · the ping_success EDGE · a ping_timeout row · a passive silence_timeout row · an UNKNOWN row · a `leave` row · the version-1 null UNAVAILABLE row (the fifth state at S2) · an open-vocabulary reason — each with its device named', () => {
    const rows = resolveScenario('recovery-states').entities;
    const has = (f: (e: EntitySummary) => boolean) => rows.some(f);
    expect(has((e) => e.availability === 'AVAILABLE' && e.availabilityReason === null && e.lastSeenAt === null && e.link === null && typeof e.lastReported === 'string')).toBe(true);
    expect(has((e) => e.availability === 'AVAILABLE' && e.availabilityReason === 'ping_success' && typeof e.lastSeenAt === 'string' && typeof e.lastReported === 'string' && new Date(e.lastSeenAt).getTime() > new Date(e.lastReported).getTime())).toBe(true);
    expect(has((e) => e.availability === 'UNAVAILABLE' && e.availabilityReason === 'ping_timeout' && typeof e.lastSeenAt === 'string')).toBe(true);
    expect(has((e) => e.availability === 'UNAVAILABLE' && e.availabilityReason === 'silence_timeout')).toBe(true);
    expect(has((e) => e.availability === 'UNKNOWN')).toBe(true);
    expect(has((e) => e.availability === 'UNAVAILABLE' && e.availabilityReason === 'leave')).toBe(true);
    expect(has((e) => e.availability === 'UNAVAILABLE' && e.availabilityReason === null && e.lastSeenAt === null && e.link === null)).toBe(true);
    expect(has((e) => typeof e.availabilityReason === 'string' && !REASONS.includes(e.availabilityReason as (typeof REASONS)[number]))).toBe(true);
    for (const e of rows) expect(typeof e.name, e.entityId).toBe('string');
  });
  it('`legacy-hub` strips the three J1 keys from its entities too — the ABSENT arm (SPEC §3\'s S1 column) stays reachable by hand', () => {
    for (const e of resolveScenario('legacy-hub').entities) for (const k of ['availabilityReason', 'lastSeenAt', 'link']) expect(k in e, `${e.entityId}.${k}`).toBe(false);
    expect(() => validateAgainstContract('A1:entities', { data: resolveScenario('legacy-hub').entities, meta: META })).not.toThrow();
  });
});

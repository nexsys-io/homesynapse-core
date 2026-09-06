/*
 * v1.1.3 ADDITIVE KEYS — the tri-state honesty tests (FE-113, the CG-123 mirror).
 * ---------------------------------------------------------------------------
 * The wire, as CG-123 landed it (core f25291b, 2026-09-06; the hub's audit
 * `2026-09-05_CG-123_intake_two-layer-audit_v65-b5.md` §0/§6 fixed the literals):
 *   A1 `GET /api/v1/entities` row        += `deviceId: string|null`, `lastReported: string|null`
 *                                           (`Instant.toString()` — ISO-8601 UTC, nanos when
 *                                           present; NEVER epoch seconds)
 *   B3 `…/{id}/non-firing` data         += `triggerRef: {type: "entity", id} | null`
 *   B3 `GET /api/v1/automations` comp.  += `ref: {type: "entity", id} | null`
 * Every new key is PRESENT in every v1.1.3 payload (JSON null when unknown); a
 * v1.1.2 hub omits them entirely — lawful, rendered as ABSENCE.
 *
 * THE HONESTY LAW (FE-HONEST-1 §10-H/§10-I) at the validator seam: ABSENT and
 * NULL are two different facts; a PRESENT key must be TYPED (the
 * `noCommandsIssued` idiom, shapes.ts) — "optional" is never a licence for a
 * wrong type to pass. The H8 false-type class is closed at the MOCK too: a mock
 * that always populates a nullable key hides the null — so the default mock is
 * pinned to carry ≥1 null per new key and ONE dangling ref (the LOUD exhibit).
 *
 * Red-first (house law): every "throws" below is RED at HEAD — the v1.1.2
 * validators ignore unknown keys, so a wrong type PASSED before this WU. The
 * "absent passes" rows and the two recorded-fixture rows are green-by-
 * construction at HEAD (disclosed; they are the P2 regression guard: a new
 * validator that rejects a recorded measurement is wrong, not the fixture).
 */
import { describe, it, expect } from 'vitest';
import { validateAgainstContract, ContractError } from './shapes';
import type { AutomationSummary, EntitySummary, NonFiringExplanation, SubjectRef } from './contract';
import { entities, nonFiring, automations } from './mock/mockData';
import { WIRE_20260816_NONFIRING_BENCH_HERO as AUG16 } from './fixtures/wire-2026-08-16-nonfiring';
import { WIRE_20260820_NEVER_TRIGGERED_BENCH_HERO as AUG20 } from './fixtures/wire-2026-08-20-never-triggered';

const META = { viewPosition: 120001, timestamp: '2026-09-06T02:45:29.123456Z' };
const ULID = '01M0GPZFVANYA5TZMZSXRCV063'; // a real registry-shaped id (the 08-20 capture's automation ULID form)
const ISO_NANOS = '2026-09-06T02:45:29.123456Z'; // the audit's `Instant.toString()` exhibit

/* ---- A1: entities[].deviceId / lastReported ---- */
const a1 = (row: Record<string, unknown>) => ({
  data: [{ entityId: 'ent_x', availability: 'AVAILABLE', stale: false, ...row }],
  meta: META,
});

describe('A1 entities: deviceId / lastReported tri-state (v1.1.3)', () => {
  it('PRESENT-typed passes (ULID string · ISO-8601 with nanos)', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ deviceId: ULID, lastReported: ISO_NANOS }))).not.toThrow();
  });
  it('PRESENT-null passes — "this hub has nothing on record" is a lawful v1.1.3 value', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ deviceId: null, lastReported: null }))).not.toThrow();
  });
  it('ABSENT passes — a v1.1.2 hub omits both keys (the C8 `name` idiom)', () => {
    expect(() => validateAgainstContract('A1:entities', a1({}))).not.toThrow();
  });
  it('PRESENT-wrong-type THROWS: deviceId 42', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ deviceId: 42 }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: lastReported as epoch seconds (never a number — the 1970-misread class)', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ lastReported: 1725580000 }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: lastReported as an object', () => {
    expect(() => validateAgainstContract('A1:entities', a1({ lastReported: { epochSecond: 1725580000 } }))).toThrow(ContractError);
  });
});

/* ---- B3 non-firing: triggerRef ---- */
const nf = (extra: Record<string, unknown>) => ({
  data: {
    automationId: 'a',
    automationName: 'Named',
    enabled: true,
    verdict: 'CONDITION_NOT_MET',
    lastRelevantRunId: 'r',
    explanation: 'x',
    triggerSummary: 'y',
    lastEvaluation: { at: null, conditionsResult: null },
    noCommandsIssued: null,
    ...extra,
  },
  meta: META,
});

describe('B3 non-firing: triggerRef tri-state (v1.1.3)', () => {
  it('PRESENT-object passes, with the wire literal lowercase "entity" (RunExplanation.java:95; never normalized)', () => {
    const body = nf({ triggerRef: { type: 'entity', id: ULID } });
    expect(() => validateAgainstContract('B3:nonFiring', body)).not.toThrow();
    expect((body.data as { triggerRef?: unknown }).triggerRef).toEqual({ type: 'entity', id: ULID });
  });
  it('PRESENT-null passes (a trigger that names no single entity)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ triggerRef: null }))).not.toThrow();
  });
  it('ABSENT passes (a v1.1.2 payload)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({}))).not.toThrow();
  });
  it('PRESENT-wrong-type THROWS: triggerRef "ent_x" (a bare string is not a ref)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ triggerRef: 'ent_x' }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-shape THROWS: triggerRef without id', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ triggerRef: { type: 'entity' } }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-shape THROWS: triggerRef with a non-string id', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ triggerRef: { type: 'entity', id: 7 } }))).toThrow(ContractError);
  });
});

/* ---- B3 automations: components[].ref ---- */
const autos = (component: Record<string, unknown>) => ({
  data: [
    {
      automationId: 'a',
      name: 'Named',
      enabled: true,
      components: [{ type: 'trigger', summary: 'When something changes', ...component }],
      lastRunId: null,
    },
  ],
  meta: META,
});

describe('B3 automations: components[].ref tri-state (v1.1.3)', () => {
  it('PRESENT-object passes with the lowercase "entity" literal', () => {
    expect(() => validateAgainstContract('B3:automations', autos({ ref: { type: 'entity', id: ULID } }))).not.toThrow();
  });
  it('PRESENT-null passes (a component that names no single entity — the hub R1 rule)', () => {
    expect(() => validateAgainstContract('B3:automations', autos({ ref: null }))).not.toThrow();
  });
  it('ABSENT passes (a v1.1.2 payload)', () => {
    expect(() => validateAgainstContract('B3:automations', autos({}))).not.toThrow();
  });
  it('PRESENT-wrong-shape THROWS: ref {type:"entity"} without id', () => {
    expect(() => validateAgainstContract('B3:automations', autos({ ref: { type: 'entity' } }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: ref as a bare string', () => {
    expect(() => validateAgainstContract('B3:automations', autos({ ref: 'ent_x' }))).toThrow(ContractError);
  });
});

/* ---- P2: the two recorded measurements validate UNCHANGED (absence lawful) ---- */
describe('the recorded v1.1.2 fixtures validate unchanged under the v1.1.3 validators (P2)', () => {
  it('2026-08-16 (gate-day build) — no triggerRef key, still lawful', () => {
    expect('triggerRef' in AUG16.data).toBe(false);
    expect(() => validateAgainstContract('B3:nonFiring', AUG16)).not.toThrow();
  });
  it('2026-08-20 (NEW-2/3 build) — no triggerRef key, still lawful', () => {
    expect('triggerRef' in AUG20.data).toBe(false);
    expect(() => validateAgainstContract('B3:nonFiring', AUG20)).not.toThrow();
  });
});

/* ---- R4: the default mock carries the v1.1.3 hub shape HONESTLY (the H8 class) ---- */
describe('the default mock is a v1.1.3 hub that does NOT always populate (H8: a mock that always populates hides the null)', () => {
  it('every entity row carries BOTH keys; ≥2 real values; ≥1 deviceId null; ≥1 lastReported null; never epoch seconds', () => {
    for (const e of entities) {
      expect('deviceId' in e).toBe(true);
      expect('lastReported' in e).toBe(true);
      if (e.lastReported != null) expect(typeof e.lastReported).toBe('string');
      if (e.deviceId != null) expect(typeof e.deviceId).toBe('string');
    }
    expect(entities.filter((e) => typeof e.deviceId === 'string').length).toBeGreaterThanOrEqual(2);
    expect(entities.filter((e) => typeof e.lastReported === 'string').length).toBeGreaterThanOrEqual(2);
    expect(entities.filter((e) => e.deviceId === null).length).toBeGreaterThanOrEqual(1);
    expect(entities.filter((e) => e.lastReported === null).length).toBeGreaterThanOrEqual(1);
  });

  it('triggerRef is PRESENT on every non-firing entry: an object on CONDITION_NOT_MET/DISABLED, null on NEVER_TRIGGERED', () => {
    const entries = Object.values(nonFiring);
    expect(entries.length).toBeGreaterThanOrEqual(3);
    for (const e of entries) {
      expect('triggerRef' in e).toBe(true);
      if (e.verdict === 'NEVER_TRIGGERED') expect(e.triggerRef).toBeNull();
      if (e.verdict === 'CONDITION_NOT_MET' || e.verdict === 'DISABLED') {
        expect(e.triggerRef).not.toBeNull();
        expect(e.triggerRef?.type).toBe('entity'); // the wire literal, lowercase
        expect(typeof e.triggerRef?.id).toBe('string');
      }
    }
  });

  it('components[].ref is PRESENT on every component, with ≥1 null', () => {
    const comps = automations.flatMap((a) => a.components);
    expect(comps.length).toBeGreaterThan(0);
    for (const c of comps) expect('ref' in c).toBe(true);
    expect(comps.filter((c) => c.ref === null).length).toBeGreaterThanOrEqual(1);
    for (const c of comps) if (c.ref) expect(c.ref.type).toBe('entity');
  });

  it('exactly the LOUD exhibit: ≥1 ref points at an id ABSENT from the mock registry (a dangling ref), and it is named', () => {
    const registry = new Set(entities.map((e) => e.entityId));
    const refs: SubjectRef[] = [
      ...automations.flatMap((a) => a.components.map((c) => c.ref)).filter((r): r is SubjectRef => !!r),
      ...Object.values(nonFiring).map((n) => n.triggerRef).filter((r): r is SubjectRef => !!r),
    ];
    const dangling = refs.filter((r) => !registry.has(r.id)).map((r) => r.id);
    expect(dangling.length).toBeGreaterThanOrEqual(1);
    expect(dangling).toContain('01KX1PB9AAB4VB3E10BD477TVX'); // the R-4 §10-J exhibit's target ULID, verbatim
    // …and the resolvable refs really resolve (the exhibit is the exception, not the rule).
    expect(refs.filter((r) => registry.has(r.id)).length).toBeGreaterThanOrEqual(3);
  });

  it('the default mock validates as a v1.1.3 hub payload on all three reads', () => {
    expect(() => validateAgainstContract('A1:entities', { data: entities, meta: META })).not.toThrow();
    for (const n of Object.values(nonFiring)) expect(() => validateAgainstContract('B3:nonFiring', { data: n, meta: META })).not.toThrow();
    expect(() => validateAgainstContract('B3:automations', { data: automations, meta: META })).not.toThrow();
  });
});

/* ---- The typed mirror carries the keys as OPTIONAL-NULLABLE (compile-level; tsc runs in `verify`) ---- */
describe('the TypeScript mirror declares the four keys optional-nullable (additive, no rename)', () => {
  it('a v1.1.3 row, a v1.1.2 row, and a present-null row are all assignable', () => {
    const v113: EntitySummary = { entityId: 'e', availability: 'AVAILABLE', stale: false, deviceId: ULID, lastReported: ISO_NANOS };
    const v112: EntitySummary = { entityId: 'e', availability: 'AVAILABLE', stale: false };
    const nulls: EntitySummary = { entityId: 'e', availability: 'AVAILABLE', stale: false, deviceId: null, lastReported: null };
    const nfx: NonFiringExplanation = { ...AUG16.data, triggerRef: { type: 'entity', id: ULID } };
    const nfn: NonFiringExplanation = { ...AUG16.data, triggerRef: null };
    const auto: AutomationSummary = {
      automationId: 'a',
      name: 'n',
      enabled: true,
      components: [{ type: 'trigger', summary: 's', ref: null }, { type: 'action', summary: 's', ref: { type: 'entity', id: ULID } }, { type: 'condition', summary: 's' }],
      lastRunId: null,
    };
    expect([v113, v112, nulls, nfx, nfn, auto].length).toBe(6);
  });
});

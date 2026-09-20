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
 * `noCommandsIssued` idiom, shapes.ts) — "optional" is never a license for a
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
import { validateAgainstContract, ContractError, CONTRACT_VERSION } from './shapes';
import type { AutomationSummary, CausalChain, EntitySummary, NonFiringExplanation, SubjectRef } from './contract';
import { entities, nonFiring, automations, causalChains } from './mock/mockData';
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

/* ---- FE-NULL-1 (2026-09-10): the causal chain's REQUIRED-NULLABLE arms — v1.1 base keys, NO bump ----
 * Four B3 causal-chain keys the emitter serves as JSON null on the v1.1 wire that the
 * mirror at d192d17 typed non-null (HERO-0 F2, 2026-09-06 — the F2/F4 rows):
 *   trigger.subjectRef            StandardExplanationService:644–:649 — `.orElse(null)` when the
 *                                 triggering event is outside the run's correlation
 *   conditions[].observedState[].value   RunExplanation:137 — "or null if unreported"
 *   actions[].command · actions[].targetRef   StandardExplanationService:771/:776 — a SKIPPED/FAILED
 *                                 action that never issued a command (`new ActionView(type, targetRef|null, null, …)`)
 *   cascade.parentRunId           RunExplanation:213–:219 — ALWAYS null in V1 (already `string|null`)
 * These keys are REQUIRED on every v1.1 payload (the emitter always writes them): MISSING is a
 * ContractError. The OPTIONAL `'key' in o` idiom of the v1.1.2/.3 additive keys above is
 * deliberately NOT copied — two idioms, one table (MODULE_CONTEXT.md, the FE-NULL-1 beat).
 *
 * Red-first register, row by row, at HEAD 39c8dd3 (the hub re-reads these against HEAD — R2):
 *   subjectRef  PRESENT-null: RED (shapes.ts:256 `subjectRef()` throws "must be object") AND the type
 *               forbids the fixture. MISSING / wrong-type: GREEN-BY-CONSTRUCTION (req()/subjectRef()
 *               already threw at HEAD) — disclosed; they are regression guards, not red rows.
 *   command     PRESENT-null: RED (shapes.ts:272 `isStr` throws). MISSING / wrong-type: GREEN-BY-
 *               CONSTRUCTION (req()/isStr already threw) — disclosed.
 *   targetRef   NOT VALIDATED AT ALL at HEAD: PRESENT-null PASSES the validator but the TYPE forbids
 *               the fixture (tsc RED); MISSING and wrong-shape are RED (nothing threw at HEAD).
 *   value       nothing inside observedState[] was checked at HEAD (shapes.ts:265 array-only):
 *               PRESENT-null passes the validator but the TYPE forbids it (tsc RED); MISSING and
 *               wrong-type are RED (nothing threw at HEAD).
 */
/** A full, valid v1.1 chain body (the default mock's happy path, deep-cloned per test). */
const chainBase = (): CausalChain => structuredClone(causalChains['run_eh_001']!);
const chainBody = (d: CausalChain) => ({ data: d, meta: META });
/** The wrong-type / missing-key fixtures are forbidden by the mirror's TYPES for good — the
 *  cast lives ONLY here so a test can hand the validator what a drifted wire would carry. */
const loosen = (d: CausalChain) => d as unknown as { trigger: Record<string, unknown>; conditions: Record<string, unknown>[]; actions: Record<string, unknown>[] };

describe("FE-NULL-1 — the chain's null arms: trigger.subjectRef (REQUIRED, null-or-{type,id})", () => {
  it('PRESENT-typed passes (the v1.1 shape as before)', () => {
    expect(() => validateAgainstContract('B3:causalChain', chainBody(chainBase()))).not.toThrow();
  });
  it('PRESENT-null passes — the triggering event is outside the run\'s correlation (:644–:649) [RED at HEAD: throws + type]', () => {
    const c = chainBase();
    c.trigger.subjectRef = null;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('MISSING throws — a REQUIRED v1.1 key, never "optional" [GREEN at HEAD by construction: req() threw]', () => {
    const c = chainBase();
    delete loosen(c).trigger.subjectRef;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type throws: subjectRef "ent_x" (a bare string is not a ref) [GREEN at HEAD by construction]', () => {
    const c = chainBase();
    loosen(c).trigger.subjectRef = 'ent_x';
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
});

describe("FE-NULL-1 — the chain's null arms: conditions[].observedState[].value (REQUIRED, string-or-null)", () => {
  it('PRESENT-typed passes, and every entry key is now checked (entityId · attribute · value)', () => {
    expect(() => validateAgainstContract('B3:causalChain', chainBody(chainBase()))).not.toThrow();
  });
  it('PRESENT-null passes — the entity had no value for the attribute at evaluation (RunExplanation:137) [RED at HEAD: type forbids]', () => {
    const c = chainBase();
    c.conditions[0]!.observedState[0]!.value = null;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('MISSING throws — `value` is a required entry key [RED at HEAD: nothing inside the array was checked]', () => {
    const c = chainBase();
    delete (loosen(c).conditions[0]!.observedState as Record<string, unknown>[])[0]!.value;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('MISSING throws — `entityId` / `attribute` are required entry keys too [RED at HEAD]', () => {
    const c = chainBase();
    delete (loosen(c).conditions[0]!.observedState as Record<string, unknown>[])[0]!.entityId;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
    const d = chainBase();
    delete (loosen(d).conditions[0]!.observedState as Record<string, unknown>[])[0]!.attribute;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(d))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type throws: value 42 (the wire serves strings, never numbers) [RED at HEAD]', () => {
    const c = chainBase();
    (loosen(c).conditions[0]!.observedState as Record<string, unknown>[])[0]!.value = 42;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('a non-object observedState entry throws [RED at HEAD]', () => {
    const c = chainBase();
    (loosen(c).conditions[0]!.observedState as unknown[])[0] = 'sys_sun';
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
});

describe("FE-NULL-1 — the chain's null arms: actions[].command (REQUIRED, string-or-null)", () => {
  it('PRESENT-typed passes', () => {
    expect(() => validateAgainstContract('B3:causalChain', chainBody(chainBase()))).not.toThrow();
  });
  it('PRESENT-null passes — a SKIPPED action that never issued a command (:776) [RED at HEAD: shapes.ts:272 isStr throws + type]', () => {
    const c = chainBase();
    c.actions[0]!.command = null;
    c.actions[0]!.outcome = 'SKIPPED';
    c.actions[0]!.resultOutcome = null;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('MISSING throws — a REQUIRED v1.1 key [GREEN at HEAD by construction: req() threw]', () => {
    const c = chainBase();
    delete loosen(c).actions[0]!.command;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type throws: command 7 [GREEN at HEAD by construction: isStr threw]', () => {
    const c = chainBase();
    loosen(c).actions[0]!.command = 7;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
});

describe("FE-NULL-1 — the chain's null arms: actions[].targetRef (REQUIRED, null-or-{type,id}; never validated before)", () => {
  it('PRESENT-typed passes', () => {
    expect(() => validateAgainstContract('B3:causalChain', chainBody(chainBase()))).not.toThrow();
  });
  it('PRESENT-null passes — a non-dispatched action with no target refs (:771) [RED at HEAD: type forbids; the validator never looked]', () => {
    const c = chainBase();
    c.actions[0]!.targetRef = null;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('MISSING throws — a REQUIRED v1.1 key [RED at HEAD: targetRef was not validated at all]', () => {
    const c = chainBase();
    delete loosen(c).actions[0]!.targetRef;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('PRESENT-wrong-shape throws: targetRef {type:"entity"} without id [RED at HEAD]', () => {
    const c = chainBase();
    loosen(c).actions[0]!.targetRef = { type: 'entity' };
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type throws: targetRef as a bare string [RED at HEAD]', () => {
    const c = chainBase();
    loosen(c).actions[0]!.targetRef = 'ent_hallway_light';
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
});

/* ---- R4: the default mock carries the four null arms + the depth-1 chain (the H8 class) ---- */
describe('FE-NULL-1 — the default mock carries every null arm the v1.1 emitter serves (H8: an always-populated mock hides the null)', () => {
  const chains = () => Object.values(causalChains);
  it("the SKIPPED run's action carries command: null AND targetRef: null (the :776 shape), resultOutcome null, settled true", () => {
    const skipped = chains().flatMap((c) => c.actions).filter((a) => a.outcome === 'SKIPPED');
    expect(skipped.length).toBeGreaterThanOrEqual(1);
    expect(skipped.some((a) => a.command === null && a.targetRef === null && a.resultOutcome === null && a.settled === true)).toBe(true);
    // …and no SKIPPED action still carries the H8 false type (a command it never sent).
    expect(skipped.every((a) => a.command === null)).toBe(true);
  });
  it('ONE condition carries an observedState entry with value: null', () => {
    const entries = chains().flatMap((c) => c.conditions).flatMap((k) => k.observedState);
    expect(entries.filter((e) => e.value === null).length).toBeGreaterThanOrEqual(1);
    expect(entries.filter((e) => typeof e.value === 'string').length).toBeGreaterThanOrEqual(3);
  });
  it('ONE chain carries trigger.subjectRef: null (the oldest run — its triggering event outside its correlation)', () => {
    const nulls = chains().filter((c) => c.trigger.subjectRef === null);
    expect(nulls.length).toBe(1);
    expect(nulls[0]!.runId).toBe('run_eh_003');
    expect(chains().filter((c) => c.trigger.subjectRef !== null).length).toBeGreaterThanOrEqual(3);
  });
  it('ONE chain carries cascade { parentRunId: null, depth: 1 } (F4 — V1 never carries a parent id)', () => {
    const deep = chains().filter((c) => c.cascade.depth > 0);
    expect(deep.length).toBe(1);
    expect(deep[0]!.cascade.parentRunId).toBeNull();
    expect(chains().filter((c) => c.cascade.depth === 0).length).toBeGreaterThanOrEqual(3);
  });
  it('every default-mock chain validates under the FE-NULL-1 validators', () => {
    for (const c of chains()) expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
});

/* ---- The typed mirror carries the four keys as REQUIRED-NULLABLE (compile-level; tsc runs in `verify`) ---- */
describe('the TypeScript mirror declares the four chain keys required-nullable (no rename, no bump)', () => {
  it('a present-null arm on each key is assignable; CONTRACT_VERSION is untouched', () => {
    const c = chainBase();
    c.trigger.subjectRef = null;
    c.conditions[0]!.observedState[0]!.value = null;
    c.actions[0]!.command = null;
    c.actions[0]!.targetRef = null;
    c.cascade = { parentRunId: null, depth: 1 };
    expect(c.trigger.subjectRef).toBeNull();
    // FE-114 D0: the third home of the version pin (the hub's census named two). Moved with them:
    // 'v1.1.3-2026-09-06' → 'v1.1.4-2026-09-13'. FE-NULL-1's fact stands: ITS rows carried no bump.
    expect(CONTRACT_VERSION).toBe('v1.1.5-2026-09-19');
  });
});

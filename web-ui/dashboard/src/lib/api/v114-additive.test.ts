/*
 * v1.1.4 ADDITIVE KEYS — the tri-state honesty tests (FE-114 D0, the EXPLAIN-114a mirror).
 * ---------------------------------------------------------------------------
 * The wire, as EXPLAIN-114a landed it (core 5f918c7 → fed99e8, 2026-09-12/13; the table
 * `api/rest-api/MODULE_CONTEXT.md` §EXPLAIN-114a fixes the literals and the null rules):
 *   B3 `…/{runId}/causal-chain` actions[]  += `settledAt: string|null` (the classifying envelope's
 *                                             instant) · `confirmedAt: string|null` (the
 *                                             `state_confirmed` instant) — `Instant.toString()`
 *   B3 causal-chain data                  += `definitionKey: string|null` (the run's definition hash)
 *   B3 `…/{id}/non-firing` data           += `disabledAt: string|null` · `disabledReason: string|null`
 *                                             · `definitionKey: string|null`
 *   B3 non-firing `data.verdict`          += the value `FIRED_CONFIRMED` (the enum grows LAST)
 *   B3 `GET /api/v1/automations` data[]   += `definitionKey: string|null`
 * Every new key is PRESENT in every v1.1.4 payload (JSON null when the log carries no value); a
 * pre-v1.1.4 hub omits them entirely — lawful, rendered as ABSENCE (the FE-113 idiom).
 *
 * THE TRI-STATE at the validator seam (MODULE_CONTEXT.md, the FE-113 beat): absent passes, null
 * passes, a PRESENT key must be TYPED — a number where an instant should be is the epoch-seconds
 * misread class, never "optional".
 *
 * NO LIVE v1.1.4 CAPTURE EXISTS in the corpus (charter §4): every body below is HAND-BUILT from the
 * rest-api table; the surface is MIRRORED, not VERIFIED, until H8's real-wire read lands.
 *
 * Red-first (house law), at HEAD 6bd8508: every "throws" row is RED (the v1.1.3 validators ignore
 * unknown keys, so a wrong type PASSED); `FIRED_CONFIRMED` is RED (`oneOf` rejects it); the version
 * pin is RED; the "passes" rows and the two recorded-fixture rows are GREEN-BY-CONSTRUCTION
 * (disclosed — they are the regression guard); the assignability block is RED at `tsc` only.
 */
import { describe, it, expect } from 'vitest';
import { validateAgainstContract, ContractError, CONTRACT_VERSION } from './shapes';
import type { AutomationSummary, CausalAction, CausalChain, NonFiringExplanation } from './contract';
// FE-115 D2 (2026-09-19): the default mock became a v1.1.5 hub (the keys PRESENT — v115-additive.test.ts pins it), so
// the pre-v1.1.4 dataset these absence rows describe is now the `legacy-hub` scenario — the ONE scenario that keeps the
// absent arm reachable. The assertions below are unchanged; only their dataset moved (the import), so "a pre-v1.1.4
// hub omits the key" is still asserted against a dataset that IS one.
import { resolveScenario } from './mock/scenarios';
const { causalChains, nonFiring, automations } = resolveScenario('legacy-hub');
import { WIRE_20260816_NONFIRING_BENCH_HERO as AUG16 } from './fixtures/wire-2026-08-16-nonfiring';
import { WIRE_20260820_NEVER_TRIGGERED_BENCH_HERO as AUG20 } from './fixtures/wire-2026-08-20-never-triggered';

const META = { viewPosition: 120001, timestamp: '2026-09-13T02:45:29.123456Z' };
/** The rest-api pins' exhibits: `"2026-01-01T00:00:03Z"` (causalChain_v114KeysLastInOrder_iso8601OrNull),
 *  `"2026-01-01T00:00:11Z"` + `"repeated_failure"` (nonFiring_v114DisabledKeysOnWire). */
const ISO_3S = '2026-01-01T00:00:03Z';
const ISO_11S = '2026-01-01T00:00:11Z';
const ISO_NANOS = '2026-09-13T02:45:29.123456Z'; // `Instant.toString()` with nanos
/** A SHA-256 hex string of the `DefinitionHashes` shape (64 hex chars). */
const KEY = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

/* ---- B3 causal-chain: a full, valid chain body (the default mock's happy path, deep-cloned per test) ---- */
const chainBase = (): CausalChain => structuredClone(causalChains['run_eh_001']!);
const chainBody = (d: CausalChain) => ({ data: d, meta: META });
/** The wrong-type fixtures are forbidden by the mirror's TYPES for good — the cast lives ONLY here. */
const loosen = (d: CausalChain) => d as unknown as Record<string, unknown> & { actions: Record<string, unknown>[] };

describe('B3 causal-chain: actions[].settledAt / confirmedAt tri-state (v1.1.4)', () => {
  it('PRESENT-typed passes — ISO-8601 on both (the settled-and-confirmed action)', () => {
    const c = chainBase();
    c.actions[0]!.settledAt = ISO_3S;
    c.actions[0]!.confirmedAt = ISO_3S;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('PRESENT-null passes on both — a bare or acknowledged DISPATCHED (no classifying event; no state_confirmed)', () => {
    const c = chainBase();
    c.actions[0]!.settledAt = null;
    c.actions[0]!.confirmedAt = null;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('settled: true beside confirmedAt: null is LAWFUL — a FAILED or UNCONFIRMED action settles without a confirmation (charter §4)', () => {
    const c = chainBase();
    c.actions[0]!.outcome = 'UNCONFIRMED';
    c.actions[0]!.resultOutcome = 'unconfirmed';
    c.actions[0]!.settled = true;
    c.actions[0]!.settledAt = ISO_NANOS;
    c.actions[0]!.confirmedAt = null;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('ABSENT passes — a pre-v1.1.4 hub omits both keys (the `legacy-hub` scenario does; the default mock is a v1.1.5 hub since FE-115)', () => {
    const c = chainBase();
    expect('settledAt' in c.actions[0]!).toBe(false);
    expect('confirmedAt' in c.actions[0]!).toBe(false);
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('PRESENT-wrong-type THROWS: settledAt as epoch seconds (never a number — the 1970-misread class) [RED at HEAD]', () => {
    const c = chainBase();
    loosen(c).actions[0]!.settledAt = 1767225603;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: confirmedAt as an object [RED at HEAD]', () => {
    const c = chainBase();
    loosen(c).actions[0]!.confirmedAt = { epochSecond: 1767225603 };
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: confirmedAt true (a boolean is not an instant) [RED at HEAD]', () => {
    const c = chainBase();
    loosen(c).actions[0]!.confirmedAt = true;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
});

describe('B3 causal-chain: data.definitionKey tri-state (v1.1.4)', () => {
  it('PRESENT-typed passes (the SHA-256 hex string)', () => {
    const c = chainBase();
    c.definitionKey = KEY;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('PRESENT-null passes — the log carries no definition hash for this run', () => {
    const c = chainBase();
    c.definitionKey = null;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('ABSENT passes (a pre-v1.1.4 payload)', () => {
    const c = chainBase();
    expect('definitionKey' in c).toBe(false);
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).not.toThrow();
  });
  it('PRESENT-wrong-type THROWS: definitionKey 42 [RED at HEAD]', () => {
    const c = chainBase();
    loosen(c).definitionKey = 42;
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: definitionKey as an object [RED at HEAD]', () => {
    const c = chainBase();
    loosen(c).definitionKey = { sha256: KEY };
    expect(() => validateAgainstContract('B3:causalChain', chainBody(c))).toThrow(ContractError);
  });
});

/* ---- B3 non-firing: disabledAt / disabledReason / definitionKey + the FIRED_CONFIRMED value ---- */
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
    triggerRef: null,
    ...extra,
  },
  meta: META,
});

describe('B3 non-firing: disabledAt / disabledReason / definitionKey tri-state (v1.1.4)', () => {
  it('PRESENT-typed passes — the DISABLED verdict with the auto-disable marker on the log (the T14 exhibit)', () => {
    const body = nf({ verdict: 'DISABLED', enabled: false, lastRelevantRunId: null, disabledAt: ISO_11S, disabledReason: 'repeated_failure', definitionKey: KEY });
    expect(() => validateAgainstContract('B3:nonFiring', body)).not.toThrow();
  });
  it('PRESENT-typed passes — disabledReason "configuration" (DP-6: DISABLED with no marker on the log; disabledAt null)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ verdict: 'DISABLED', enabled: false, disabledAt: null, disabledReason: 'configuration', definitionKey: KEY }))).not.toThrow();
  });
  it('PRESENT-null passes on all three — any non-DISABLED verdict serves disabledAt / disabledReason null (definitionKey null only for a fixture)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ disabledAt: null, disabledReason: null, definitionKey: null }))).not.toThrow();
  });
  it('ABSENT passes (a pre-v1.1.4 payload)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({}))).not.toThrow();
  });
  it('PRESENT-wrong-type THROWS: disabledAt as epoch seconds [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ disabledAt: 1767225611 }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: disabledReason 7 [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ disabledReason: 7 }))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: definitionKey as an object [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ definitionKey: { sha256: KEY } }))).toThrow(ContractError);
  });
});

describe('B3 non-firing: the verdict value FIRED_CONFIRMED (v1.1.4 — the enum grows LAST)', () => {
  it('FIRED_CONFIRMED passes the verdict domain, with the non-null lastRelevantRunId the wire carries for it [RED at HEAD: oneOf rejects it]', () => {
    expect(() => validateAgainstContract('B3:nonFiring', nf({ verdict: 'FIRED_CONFIRMED', lastRelevantRunId: 'r', disabledAt: null, disabledReason: null, definitionKey: KEY }))).not.toThrow();
  });
  it('the four v1.1 verdicts still pass, and a verdict outside the five still throws [GREEN at HEAD by construction]', () => {
    for (const v of ['CONDITION_NOT_MET', 'NEVER_TRIGGERED', 'ACTED_BUT_UNCONFIRMED', 'DISABLED']) {
      expect(() => validateAgainstContract('B3:nonFiring', nf({ verdict: v }))).not.toThrow();
    }
    expect(() => validateAgainstContract('B3:nonFiring', nf({ verdict: 'PAUSED' }))).toThrow(ContractError);
  });
});

/* ---- B3 automations: data[].definitionKey ---- */
const autos = (extra: Record<string, unknown>) => ({
  data: [{ automationId: 'a', name: 'Named', enabled: true, components: [{ type: 'trigger', summary: 'When something changes', ref: null }], lastRunId: null, ...extra }],
  meta: META,
});

describe('B3 automations: data[].definitionKey tri-state (v1.1.4)', () => {
  it('PRESENT-typed passes (never null in production — the registry answered)', () => {
    expect(() => validateAgainstContract('B3:automations', autos({ definitionKey: KEY }))).not.toThrow();
  });
  it('PRESENT-null passes (a fixture arm the table allows)', () => {
    expect(() => validateAgainstContract('B3:automations', autos({ definitionKey: null }))).not.toThrow();
  });
  it('ABSENT passes (a pre-v1.1.4 payload — the `legacy-hub` scenario)', () => {
    expect(() => validateAgainstContract('B3:automations', autos({}))).not.toThrow();
    for (const a of automations) expect('definitionKey' in a).toBe(false);
  });
  it('PRESENT-wrong-type THROWS: definitionKey 42 [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:automations', autos({ definitionKey: 42 }))).toThrow(ContractError);
  });
});

/* ---- P6: the two recorded measurements carry none of the v1.1.4 keys and validate UNCHANGED ---- */
describe('the recorded v1.1.2 fixtures validate unchanged under the v1.1.4 validators (P6: absence lawful)', () => {
  it('2026-08-16 (gate-day build) — no v1.1.4 key, still lawful', () => {
    for (const k of ['disabledAt', 'disabledReason', 'definitionKey']) expect(k in AUG16.data, k).toBe(false);
    expect(() => validateAgainstContract('B3:nonFiring', AUG16)).not.toThrow();
  });
  it('2026-08-20 (NEW-2/3 build) — no v1.1.4 key, still lawful', () => {
    for (const k of ['disabledAt', 'disabledReason', 'definitionKey']) expect(k in AUG20.data, k).toBe(false);
    expect(() => validateAgainstContract('B3:nonFiring', AUG20)).not.toThrow();
  });
  it('the `legacy-hub` scenario is a pre-v1.1.4 hub on the non-firing read (absence, not null — the default mock carries the keys since FE-115)', () => {
    for (const n of Object.values(nonFiring)) {
      expect('disabledAt' in n).toBe(false);
      expect(() => validateAgainstContract('B3:nonFiring', { data: n, meta: META })).not.toThrow();
    }
  });
});

/* ---- The typed mirror carries the keys as OPTIONAL-NULLABLE (compile-level; tsc runs in `verify`) ---- */
describe('the TypeScript mirror declares the seven keys optional-nullable and the fifth verdict (additive, no rename)', () => {
  it('a v1.1.4 object, a pre-v1.1.4 object and a present-null object are all assignable on every read; CONTRACT_VERSION is the freeze doc amendment date', () => {
    const base = chainBase();
    const settled: CausalAction = { ...base.actions[0]!, settledAt: ISO_3S, confirmedAt: ISO_3S };
    const nulls: CausalAction = { ...base.actions[0]!, settledAt: null, confirmedAt: null };
    const pre: CausalAction = { ...base.actions[0]! };
    const keyed: CausalChain = { ...base, definitionKey: KEY };
    const keyedNull: CausalChain = { ...base, definitionKey: null };
    const fired: NonFiringExplanation = { ...AUG16.data, verdict: 'FIRED_CONFIRMED', lastRelevantRunId: 'r', disabledAt: null, disabledReason: null, definitionKey: KEY };
    const disabled: NonFiringExplanation = { ...AUG16.data, verdict: 'DISABLED', disabledAt: ISO_11S, disabledReason: 'repeated_failure', definitionKey: KEY };
    const auto: AutomationSummary = { automationId: 'a', name: 'n', enabled: true, components: [], lastRunId: null, definitionKey: KEY };
    const autoNull: AutomationSummary = { automationId: 'a', name: 'n', enabled: true, components: [], lastRunId: null, definitionKey: null };
    expect([settled, nulls, pre, keyed, keyedNull, fired, disabled, auto, autoNull].length).toBe(9);
    // D0: the three pins move together (contract.ts · contract.test.ts · scripts/contract-check.mjs) — plus the
    // v113-additive.test.ts pin the FE-NULL-1 lane added (four homes, one value).
    expect(CONTRACT_VERSION).toBe('v1.1.5-2026-09-19');
  });
});

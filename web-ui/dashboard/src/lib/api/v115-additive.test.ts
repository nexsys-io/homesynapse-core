/*
 * FE-115 (2026-09-19) — the v1.1.5 additive key, honesty-tested on HAND-BUILT bodies and on the mock.
 * ---------------------------------------------------------------------------
 * v1.1.5 (EXPLAIN-114c, core e56f555; the freeze doc's stamp) adds ONE key on ONE read: the B3 causal-chain
 * `conditions[].definition` — the condition as structured data, `{type, selector, attribute, value, above,
 * below, after, before, children:[…]}` in exactly that order, recursive through `children` (`[]` for a leaf,
 * never null), or JSON null when the projection cannot vouch for it. PRESENT in every v1.1.5 payload; a
 * pre-v1.1.5 hub omits it. THE TRI-STATE IDIOM (MODULE_CONTEXT :69–:70): absent passes · null passes · a
 * present key must be the typed object — a wrong type is ContractError, never "optional".
 *
 * RED at HEAD (d1c2cbc): shapes.ts:261–:281 validates conditions[] with NO `definition` arm, so every
 * wrong-type row below PASSES at HEAD (the manufactured-tolerance class) — those rows are the red. The
 * absent / null / typed-object rows are GREEN at HEAD by construction (nothing inspected the key) and are
 * disclosed as preservation. §D2 pins the mock as a v1.1.5 hub (RED at HEAD: the keys are absent).
 *
 * NO LIVE v1.1.5 CAUSAL-CHAIN BODY EXISTS in the corpus (the H8-a capture is entities / automations /
 * non-firing) — this key is MIRRORED against the emitter's source, not VERIFIED (H8); the live-wire bar is owed.
 */
import { describe, it, expect } from 'vitest';
import { validateAgainstContract, ContractError } from './shapes';
import { CONTRACT_VERSION, type CausalChain, type CausalCondition, type ConditionDefinition } from './contract';
import { causalChains, nonFiring, automations } from './mock/mockData';
import { SCENARIOS, resolveScenario } from './mock/scenarios';

const META = { viewPosition: 1, timestamp: '2026-09-19T17:30:54.933013931Z' };
const body = (d: CausalChain) => ({ data: d, meta: META });
const chainBase = (): CausalChain => structuredClone(causalChains['run_eh_001']!);
/** The wrong-type fixtures are forbidden by the mirror's TYPES for good — the cast lives ONLY here. */
const loosen = (c: CausalCondition) => c as unknown as Record<string, unknown>;

/* ---- The emitter's leaves and compounds, in the wire's key order (GetRunCausalChainEndpoint.definitionMap) ---- */
const leaf = (over: Partial<ConditionDefinition> = {}): ConditionDefinition => ({
  type: 'StateCondition',
  selector: '01M1PRQN03X8H4MNEZQ62F76F1',
  attribute: 'power',
  value: 'on',
  above: null,
  below: null,
  after: null,
  before: null,
  children: [],
  ...over,
});
const numeric = (): ConditionDefinition => leaf({ type: 'NumericCondition', value: null, above: 10, below: 80.5 });
const time = (): ConditionDefinition => leaf({ type: 'TimeCondition', selector: null, attribute: null, value: null, after: '18:00', before: '23:30' });
const compound = (type: string, children: ConditionDefinition[]): ConditionDefinition =>
  leaf({ type, selector: null, attribute: null, value: null, children });
const withDef = (definition: unknown): CausalChain => {
  const c = chainBase();
  (loosen(c.conditions[0]!) as { definition: unknown }).definition = definition;
  return c;
};
const nest = (depth: number): ConditionDefinition => (depth <= 1 ? leaf() : compound('NotCondition', [nest(depth - 1)]));

describe('B3 causal-chain: conditions[].definition tri-state (v1.1.5)', () => {
  it('ABSENT passes — a pre-v1.1.5 hub omits the key [GREEN at HEAD by construction; preservation]', () => {
    const c = chainBase();
    delete (c.conditions[0] as Partial<CausalCondition>).definition;
    expect('definition' in c.conditions[0]!).toBe(false);
    expect(() => validateAgainstContract('B3:causalChain', body(c))).not.toThrow();
  });
  it('PRESENT-null passes — the projection could not vouch for the definition (hash mismatch, automation gone, index out of range) [preservation]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(null)))).not.toThrow();
  });
  it('PRESENT-typed passes on every permit: a state leaf, a numeric leaf (numbers), a time leaf (HH:MM strings), and/or/not compounds, a zone leaf [preservation]', () => {
    for (const d of [
      leaf(),
      numeric(),
      time(),
      compound('AndCondition', [leaf(), time()]),
      compound('OrCondition', [leaf(), numeric(), time()]),
      compound('NotCondition', [leaf()]),
      compound('AndCondition', [compound('OrCondition', [leaf(), leaf()]), compound('NotCondition', [time()])]),
      leaf({ type: 'ZoneCondition', selector: null, attribute: null, value: null }),
    ]) {
      expect(() => validateAgainstContract('B3:causalChain', body(withDef(d)))).not.toThrow();
    }
  });
  it('a `type` this mirror does not know PASSES the validator — an open vocabulary; the renderer says "as recorded" [preservation]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(leaf({ type: 'PresenceCondition' }))))).not.toThrow();
  });
  it('a compound with ZERO children passes — the wire decides the arity, the mirror renders what it carries [preservation]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(compound('AndCondition', []))))).not.toThrow();
  });

  it('PRESENT-wrong-type THROWS: definition as a string (a sentence is not the shape) [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef('someone is home')))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: definition as an array [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef([leaf()])))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: `type` missing (the one field the emitter never nulls) [RED at HEAD]', () => {
    const { type: _t, ...rest } = leaf();
    void _t;
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(rest)))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: `type` null [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...leaf(), type: null })))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: `above` / `below` as strings (the wire carries Double) [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...numeric(), above: '10' })))).toThrow(ContractError);
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...numeric(), below: '80.5' })))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: `value` / `after` / `before` / `selector` / `attribute` as numbers or booleans [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...leaf(), value: 1 })))).toThrow(ContractError);
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...time(), after: 1800 })))).toThrow(ContractError);
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...time(), before: true })))).toThrow(ContractError);
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...leaf(), selector: 7 })))).toThrow(ContractError);
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...leaf(), attribute: false })))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: a field the emitter always writes is MISSING (selector · value · above · after · children) [RED at HEAD]', () => {
    for (const k of ['selector', 'value', 'above', 'after', 'children'] as const) {
      const d: Record<string, unknown> = { ...leaf() };
      delete d[k];
      expect(() => validateAgainstContract('B3:causalChain', body(withDef(d))), k).toThrow(ContractError);
    }
  });
  it('PRESENT-wrong-type THROWS: `children` null or not an array (the emitter writes [] for a leaf, never null) [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...leaf(), children: null })))).toThrow(ContractError);
    expect(() => validateAgainstContract('B3:causalChain', body(withDef({ ...leaf(), children: {} })))).toThrow(ContractError);
  });
  it('PRESENT-wrong-type THROWS: a malformed CHILD (the recursion validates every level) [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(compound('AndCondition', [leaf(), 'x' as unknown as ConditionDefinition]))))).toThrow(ContractError);
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(compound('OrCondition', [{ ...leaf(), above: 'deep' } as unknown as ConditionDefinition]))))).toThrow(ContractError);
  });
  it('the recursion is BOUNDED: 8 levels pass, a 9th is ContractError (charter D1) [RED at HEAD]', () => {
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(nest(8))))).not.toThrow();
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(nest(9))))).toThrow(ContractError);
    expect(() => validateAgainstContract('B3:causalChain', body(withDef(nest(9))))).toThrow(/depth/);
  });
});

describe('the TypeScript mirror declares conditions[].definition optional-nullable and ConditionDefinition recursive readonly (additive, no rename)', () => {
  it('a v1.1.5 condition, a pre-v1.1.5 condition and a present-null condition are all assignable; CONTRACT_VERSION is the v1.1.5 pin', () => {
    const base = chainBase().conditions[0]!;
    const typed: CausalCondition = { ...base, definition: compound('AndCondition', [leaf(), time()]) };
    const nulled: CausalCondition = { ...base, definition: null };
    const pre: CausalCondition = { expression: base.expression, evaluated: base.evaluated, result: base.result, observedState: base.observedState };
    const children: readonly ConditionDefinition[] = typed.definition!.children;
    expect([typed, nulled, pre].length).toBe(3);
    expect(children.length).toBe(2);
    // D0: the pin has five homes (contract.ts · contract.test.ts · v113-additive.test.ts · v114-additive.test.ts ·
    // scripts/contract-check.mjs); this file asserts the value through the module, not a sixth literal home.
    expect(CONTRACT_VERSION).toMatch(/^v1\.1\.5-/);
  });
});

/* ---- D2: the default mock is a v1.1.5 hub on the four hero reads; `legacy-hub` keeps the absent arm ---- */
describe('FE-115 D2 — the default mock is a v1.1.5 hub that does NOT always populate (H8: an always-populated mock hides the null)', () => {
  const chains = () => Object.values(causalChains);
  it('every causal-chain condition carries `definition` PRESENT, and at least one is null and at least one an object [RED at HEAD: absent]', () => {
    const conds = chains().flatMap((c) => c.conditions);
    expect(conds.length).toBeGreaterThanOrEqual(3);
    for (const c of conds) expect('definition' in c).toBe(true);
    expect(conds.filter((c) => c.definition === null).length).toBeGreaterThanOrEqual(1);
    expect(conds.filter((c) => c.definition !== null && typeof c.definition === 'object').length).toBeGreaterThanOrEqual(1);
  });
  it('the definition objects carry the emitter\'s NINE keys in the wire order, recursively (the fixture law mirrors byte order)', () => {
    const ORDER = ['type', 'selector', 'attribute', 'value', 'above', 'below', 'after', 'before', 'children'];
    const check = (d: ConditionDefinition) => {
      expect(Object.keys(d)).toEqual(ORDER);
      d.children.forEach(check);
    };
    for (const c of chains().flatMap((x) => x.conditions)) if (c.definition) check(c.definition);
  });
  it('every action carries settledAt / confirmedAt PRESENT (confirmedAt ≥1 null, ≥1 value; settledAt a value on every SETTLED action — the default home holds no bare DISPATCHED, so its null arm lives in `v115-keys`); every chain carries definitionKey PRESENT (≥1 null) [RED at HEAD: absent]', () => {
    const acts = chains().flatMap((c) => c.actions);
    for (const a of acts) {
      expect('settledAt' in a).toBe(true);
      expect('confirmedAt' in a).toBe(true);
      if (a.settled !== false) expect(typeof a.settledAt, `${a.outcome} settledAt`).toBe('string');
    }
    expect(acts.filter((a) => a.confirmedAt === null).length).toBeGreaterThanOrEqual(1);
    expect(acts.filter((a) => typeof a.confirmedAt === 'string').length).toBeGreaterThanOrEqual(1);
    for (const c of chains()) expect('definitionKey' in c).toBe(true);
    expect(chains().filter((c) => c.definitionKey === null).length).toBeGreaterThanOrEqual(1);
    expect(chains().filter((c) => typeof c.definitionKey === 'string').length).toBeGreaterThanOrEqual(1);
  });
  it('every non-firing entry carries disabledAt / disabledReason / definitionKey PRESENT; the DISABLED entry carries a disabledAt value; the rest null [RED at HEAD: absent]', () => {
    const entries = Object.values(nonFiring);
    for (const n of entries) for (const k of ['disabledAt', 'disabledReason', 'definitionKey']) expect(k in n, k).toBe(true);
    const disabled = entries.filter((n) => n.verdict === 'DISABLED');
    expect(disabled.length).toBeGreaterThanOrEqual(1);
    for (const n of disabled) expect(typeof n.disabledAt).toBe('string');
    for (const n of entries.filter((x) => x.verdict !== 'DISABLED')) {
      expect(n.disabledAt).toBeNull();
      expect(n.disabledReason).toBeNull();
    }
  });
  it('every automation carries definitionKey PRESENT and typed (never null in production — the registry answered) [RED at HEAD: absent]', () => {
    for (const a of automations) {
      expect('definitionKey' in a).toBe(true);
      expect(typeof a.definitionKey).toBe('string');
    }
  });
  it('the default dataset, read through the validators, is a lawful v1.1.5 hub on the four reads', () => {
    for (const c of chains()) expect(() => validateAgainstContract('B3:causalChain', body(c))).not.toThrow();
    for (const n of Object.values(nonFiring)) expect(() => validateAgainstContract('B3:nonFiring', { data: n, meta: META })).not.toThrow();
    expect(() => validateAgainstContract('B3:automations', { data: automations, meta: META })).not.toThrow();
  });
});

describe('FE-115 D2 — `legacy-hub` is the ONE pre-v1.1.4 scenario (the absent arm keeps a reachable state) [RED at HEAD: no such scenario]', () => {
  it('exists in the registry and strips every v1.1.4 / v1.1.5 key from the four reads', () => {
    expect(SCENARIOS.some((s) => s.id === 'legacy-hub')).toBe(true);
    const d = resolveScenario('legacy-hub');
    for (const c of Object.values(d.causalChains)) {
      expect('definitionKey' in c).toBe(false);
      for (const cond of c.conditions) expect('definition' in cond).toBe(false);
      for (const a of c.actions) {
        expect('settledAt' in a).toBe(false);
        expect('confirmedAt' in a).toBe(false);
      }
    }
    for (const n of Object.values(d.nonFiring)) for (const k of ['disabledAt', 'disabledReason', 'definitionKey']) expect(k in n, k).toBe(false);
    for (const a of d.automations) expect('definitionKey' in a).toBe(false);
  });
  it('and still validates (absence is lawful — the tri-state) and keeps the v1.1.3 keys (a v1.1.3 hub, not a v1.1 one)', () => {
    const d = resolveScenario('legacy-hub');
    for (const c of Object.values(d.causalChains)) expect(() => validateAgainstContract('B3:causalChain', body(c))).not.toThrow();
    for (const n of Object.values(d.nonFiring)) {
      expect(() => validateAgainstContract('B3:nonFiring', { data: n, meta: META })).not.toThrow();
      expect('triggerRef' in n).toBe(true);
    }
    expect(() => validateAgainstContract('B3:automations', { data: d.automations, meta: META })).not.toThrow();
  });
});

describe('FE-115 D2 — one DevPanel scenario per key state (the v1.1.4 / v1.1.5 sentences reachable by hand) [RED at HEAD: no such scenario]', () => {
  it('`v115-keys` carries: a confirmedAt value + null, a definition per permit (state · numeric · time · and · or · not · zone) + null + an unknown type, a DISABLED with disabledAt, a FIRED_CONFIRMED', () => {
    expect(SCENARIOS.some((s) => s.id === 'v115-keys')).toBe(true);
    const d = resolveScenario('v115-keys');
    const conds = Object.values(d.causalChains).flatMap((c) => c.conditions);
    const types = new Set(conds.map((c) => c.definition?.type).filter((x): x is string => typeof x === 'string'));
    for (const ty of ['StateCondition', 'NumericCondition', 'TimeCondition', 'AndCondition', 'OrCondition', 'NotCondition', 'ZoneCondition']) {
      expect(types.has(ty), ty).toBe(true);
    }
    expect(conds.some((c) => c.definition === null)).toBe(true);
    expect(conds.some((c) => c.definition && !['StateCondition', 'NumericCondition', 'TimeCondition', 'AndCondition', 'OrCondition', 'NotCondition', 'ZoneCondition'].includes(c.definition.type))).toBe(true);
    const acts = Object.values(d.causalChains).flatMap((c) => c.actions);
    expect(acts.some((a) => typeof a.confirmedAt === 'string')).toBe(true);
    expect(acts.some((a) => a.confirmedAt === null)).toBe(true);
    // the held mode: a bare DISPATCHED — settled false, settledAt null, confirmedAt null (the settledAt null arm's home)
    expect(acts.some((a) => a.outcome === 'DISPATCHED' && a.settled === false && a.settledAt === null && a.confirmedAt === null)).toBe(true);
    const nfs = Object.values(d.nonFiring);
    expect(nfs.some((n) => n.verdict === 'DISABLED' && typeof n.disabledAt === 'string')).toBe(true);
    expect(nfs.some((n) => n.verdict === 'FIRED_CONFIRMED' && typeof n.lastRelevantRunId === 'string')).toBe(true);
    for (const c of Object.values(d.causalChains)) expect(() => validateAgainstContract('B3:causalChain', body(c))).not.toThrow();
  });
});

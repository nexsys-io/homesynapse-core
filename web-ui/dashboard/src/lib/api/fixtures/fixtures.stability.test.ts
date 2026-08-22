/*
 * CROSS-DEPLOYMENT DIALECT STABILITY — the non-firing wire, two real captures.
 * ---------------------------------------------------------------------------
 * The H8 tier-1 discipline applied honestly: a second real capture of the same
 * shape is evidence of STABILITY, not a new arm. The 2026-08-16 body (gate-day
 * build, index-B9CmxYDm.js) and the 2026-08-20 body (NEW-2/3 build,
 * index-C95CAnmp.js) are both NEVER_TRIGGERED · lastEvaluation: null ·
 * noCommandsIssued: null for the same automation. This suite is the DRIFT
 * DETECTOR for that claim: the two captures must agree on every key (recursive,
 * envelope included), agree on null-ness per key, and differ in VALUE only at
 * the enumerated set {data.automationId, meta.viewPosition, meta.timestamp}.
 * Anything else differing FAILS — that is the STATE-DIALECT class (F-S2) caught
 * on the surface where it has NOT happened.
 *
 * Red-first disclosure (#18): on a stable wire these assertions are green by
 * construction. The detector proves its teeth below by deliberately mutated
 * copies that MUST fail the named assertion (arc-discipline 10 — the
 * false-verdict boundary is fixture-paired).
 */
import { describe, it, expect } from 'vitest';
import { WIRE_20260816_NONFIRING_BENCH_HERO as AUG16 } from './wire-2026-08-16-nonfiring';
import { WIRE_20260820_NEVER_TRIGGERED_BENCH_HERO as AUG20 } from './wire-2026-08-20-never-triggered';
import { validateAgainstContract } from '../shapes';

/* ---- The detector (pure; exported for nothing — fixtures only) ---- */

type Kind = 'null' | 'object' | 'array' | 'value';

function kindOf(v: unknown): Kind {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  if (typeof v === 'object') return 'object';
  return 'value';
}

/** Every path in the tree (recursive through plain objects), with its kind and
 *  leaf value. Arrays are leaves here: neither capture carries one, and a
 *  future capture that does will show up as a KIND change, not silently. */
function walk(v: unknown, prefix: string, out: Map<string, { kind: Kind; value: unknown }>): void {
  const kind = kindOf(v);
  if (prefix) out.set(prefix, { kind, value: v });
  if (kind === 'object') {
    for (const [k, child] of Object.entries(v as Record<string, unknown>)) {
      walk(child, prefix ? `${prefix}.${k}` : k, out);
    }
  }
}

function paths(v: unknown): Map<string, { kind: Kind; value: unknown }> {
  const out = new Map<string, { kind: Kind; value: unknown }>();
  walk(v, '', out);
  return out;
}

/** Key ORDER per object, as the fixture law mirrors it byte-for-byte. */
function keyOrders(v: unknown, prefix = '', out: Map<string, string[]> = new Map()): Map<string, string[]> {
  if (kindOf(v) === 'object') {
    const keys = Object.keys(v as Record<string, unknown>);
    out.set(prefix || '<root>', keys);
    for (const k of keys) keyOrders((v as Record<string, unknown>)[k], prefix ? `${prefix}.${k}` : k, out);
  }
  return out;
}

interface Verdict {
  /** Paths present in exactly one capture. */
  keySetDiff: string[];
  /** Paths present in both whose null-ness (or kind) differs. */
  nullnessDiff: string[];
  /** Leaf paths present in both, same kind, whose VALUES differ. */
  valueDiff: string[];
  /** Objects (by path) whose key ORDER differs. */
  orderDiff: string[];
}

function compareCaptures(a: unknown, b: unknown): Verdict {
  const pa = paths(a);
  const pb = paths(b);
  const keySetDiff = [...new Set([...pa.keys(), ...pb.keys()])].filter((p) => !(pa.has(p) && pb.has(p))).sort();
  const nullnessDiff: string[] = [];
  const valueDiff: string[] = [];
  for (const [p, ea] of pa) {
    const eb = pb.get(p);
    if (!eb) continue;
    if (ea.kind !== eb.kind) {
      nullnessDiff.push(p);
      continue;
    }
    if (ea.kind === 'value' && ea.value !== eb.value) valueDiff.push(p);
  }
  const oa = keyOrders(a);
  const ob = keyOrders(b);
  const orderDiff = [...oa.keys()]
    .filter((p) => ob.has(p) && (oa.get(p) ?? []).join('|') !== (ob.get(p) ?? []).join('|'))
    .sort();
  return { keySetDiff, nullnessDiff: nullnessDiff.sort(), valueDiff: valueDiff.sort(), orderDiff };
}

/** The brief's enumerated set — the ONLY lawful value differences between the
 *  two captures. Sorted, so the assertion is order-free. */
const LAWFUL_VALUE_DIFF = ['data.automationId', 'meta.timestamp', 'meta.viewPosition'];

/** The stability assertion, as ONE function so the teeth tests below can prove
 *  each arm fails on the right mutation with a NAMED reason. */
function assertStable(a: unknown, b: unknown): void {
  const v = compareCaptures(a, b);
  if (v.keySetDiff.length) throw new Error(`key set differs: ${v.keySetDiff.join(', ')}`);
  if (v.nullnessDiff.length) throw new Error(`null-ness differs: ${v.nullnessDiff.join(', ')}`);
  const unlawful = v.valueDiff.filter((p) => !LAWFUL_VALUE_DIFF.includes(p));
  if (unlawful.length) throw new Error(`value differs outside the lawful set: ${unlawful.join(', ')}`);
}

/** Compact UTF-8 byte length — the wire's Content-Length for a Jackson-compact
 *  body with the captured key order (the 2026-08-16 record: 395 B; 2026-08-20: 396 B). */
function wireBytes(v: unknown): number {
  return new TextEncoder().encode(JSON.stringify(v)).length;
}

/* ---- (b) The stability test — green-by-construction on a stable wire (disclosed) ---- */

describe('the non-firing wire is dialect-stable across the two deployments (2026-08-16 → 2026-08-20)', () => {
  it('IDENTICAL key sets — recursive, envelope included', () => {
    expect(compareCaptures(AUG16, AUG20).keySetDiff).toEqual([]);
  });

  it('IDENTICAL null-ness per key (lastEvaluation null in both; noCommandsIssued null in both; lastRelevantRunId null in both)', () => {
    const v = compareCaptures(AUG16, AUG20);
    expect(v.nullnessDiff).toEqual([]);
    // The null arm, named: both captures serve the three nulls the client guards for.
    for (const p of ['data.lastEvaluation', 'data.noCommandsIssued', 'data.lastRelevantRunId']) {
      expect(paths(AUG16).get(p)?.kind).toBe('null');
      expect(paths(AUG20).get(p)?.kind).toBe('null');
    }
  });

  it('the differing VALUES are EXACTLY {data.automationId, meta.viewPosition, meta.timestamp} — nothing more, nothing less', () => {
    expect(compareCaptures(AUG16, AUG20).valueDiff).toEqual(LAWFUL_VALUE_DIFF);
    expect(() => assertStable(AUG16, AUG20)).not.toThrow();
  });

  it('IDENTICAL key ORDER per object (a stricter pin than the brief — the fixture law mirrors byte order; disclosed as such)', () => {
    expect(compareCaptures(AUG16, AUG20).orderDiff).toEqual([]);
  });

  it('both captures validate against the contract mirror (object-OR-null is the recorded wire truth, twice)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', AUG16)).not.toThrow();
    expect(() => validateAgainstContract('B3:nonFiring', AUG20)).not.toThrow();
  });

  it('byte-complete against the recorded sizes: 395 B (2026-08-16) and 396 B (2026-08-20) — the +1 is the sixth digit of viewPosition', () => {
    expect(wireBytes(AUG16)).toBe(395);
    expect(wireBytes(AUG20)).toBe(396);
    expect(String(AUG20.meta.viewPosition).length - String(AUG16.meta.viewPosition).length).toBe(1);
  });

  it('the automation identity re-minted between deployments but the name did not (the re-provisioned bench-hero)', () => {
    expect(AUG20.data.automationId).not.toBe(AUG16.data.automationId);
    expect(AUG20.data.automationName).toBe(AUG16.data.automationName);
    expect(AUG20.meta.viewPosition).toBeGreaterThan(AUG16.meta.viewPosition);
  });
});

/* ---- The detector's TEETH — deliberately mutated copies MUST fail the named arm ---- */

describe('the drift detector has teeth (red proof by mutation — the false-verdict boundary, fixture-paired)', () => {
  it('an EXTRA key fails the key-set assertion (the brief\'s own mutation)', () => {
    const mutated = { ...AUG20, data: { ...AUG20.data, extra: 1 } };
    expect(compareCaptures(AUG16, mutated).keySetDiff).toEqual(['data.extra']);
    expect(() => assertStable(AUG16, mutated)).toThrow(/key set differs: data\.extra/);
  });

  it('a MISSING key fails the key-set assertion', () => {
    const { noCommandsIssued: _dropped, ...rest } = AUG20.data;
    void _dropped;
    const mutated = { ...AUG20, data: rest };
    expect(() => assertStable(AUG16, mutated)).toThrow(/key set differs: data\.noCommandsIssued/);
  });

  it('a key flipping null → value fails the null-ness assertion (the key set is unchanged, so only THIS arm fires)', () => {
    const mutated = { ...AUG20, data: { ...AUG20.data, noCommandsIssued: true } };
    expect(compareCaptures(AUG16, mutated).keySetDiff).toEqual([]);
    expect(() => assertStable(AUG16, mutated)).toThrow(/null-ness differs: data\.noCommandsIssued/);
  });

  it('lastEvaluation flipping null → object fails the null-ness assertion AND surfaces its new keys', () => {
    const mutated = { ...AUG20, data: { ...AUG20.data, lastEvaluation: { at: null, conditionsResult: null } } };
    const v = compareCaptures(AUG16, mutated);
    expect(v.nullnessDiff).toEqual(['data.lastEvaluation']);
    expect(v.keySetDiff).toEqual(['data.lastEvaluation.at', 'data.lastEvaluation.conditionsResult']);
    expect(() => assertStable(AUG16, mutated)).toThrow(/key set differs/);
  });

  it('a VALUE drifting outside the lawful set fails the enumerated-set assertion (a one-character copy change is enough)', () => {
    const mutated = { ...AUG20, data: { ...AUG20.data, triggerSummary: 'state change ' } };
    expect(compareCaptures(AUG16, mutated).valueDiff).toEqual([...LAWFUL_VALUE_DIFF, 'data.triggerSummary'].sort());
    expect(() => assertStable(AUG16, mutated)).toThrow(/value differs outside the lawful set: data\.triggerSummary/);
  });

  it('a re-ORDERED object fails the order pin while passing every other arm (so the pins are independent)', () => {
    const { automationId, ...restOfData } = AUG20.data;
    const mutated = { ...AUG20, data: { ...restOfData, automationId } }; // same keys, automationId moved last
    const v = compareCaptures(AUG16, mutated);
    expect(v.keySetDiff).toEqual([]);
    expect(v.nullnessDiff).toEqual([]);
    expect(v.valueDiff).toEqual(LAWFUL_VALUE_DIFF);
    expect(v.orderDiff).toEqual(['data']);
  });

  it('a "cleaned up" fixture changes the byte count (the size pin is itself a detector)', () => {
    const mutated = { ...AUG20, data: { ...AUG20.data, extra: 1 } };
    expect(wireBytes(mutated)).not.toBe(396);
  });
});

/*
 * E5 — the MEASURED confirmation-rendering semantics (AMD-97, ratified 2026-07-01).
 * ---------------------------------------------------------------------------
 * Locks the four honest-state behaviors the bench measured on real silicon
 * (nexsys-bench/corpus/devices/philips-hue-white-a19.md) as UI-lane truths:
 *   1. color confirms slowly + legitimately — calm pending inside the window,
 *      pending→confirmed transition at the measured timing, NO client-side timeout;
 *   2. idempotent commands confirm-from-cache/readback — honest terminal state,
 *      never a forever-spinner;
 *   3. effect/identify class renders honest UNCONFIRMED immediately — an ACK is
 *      not confirmation (AMD-97-INV-01: never a false CONFIRMED);
 *   4. superseded commands expire — no stranded stale pending chip.
 * The per-capability timeout VALUES live in Doc 08 §3.6 + the bench corpus and are
 * consumed by the BACKEND; these tests also pin that the UI copy carries no
 * hardcoded numbers (pointer-not-copy, SK-INV-01).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveScenario, SCENARIOS } from './mock/scenarios';
import { commandKind, pendingHint, unconfirmableHint, outcomeMeta } from '../format';

describe('E5 scenario — the four measured confirmation semantics', () => {
  beforeEach(() => {
    vi.useFakeTimers({ now: new Date('2026-07-02T20:23:00Z') });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('is registered as a one-click scenario (T1.2 pattern)', () => {
    expect(SCENARIOS.some((s) => s.id === 'e5-confirmation')).toBe(true);
  });

  it('1: color-temperature confirms slowly — pending inside the measured window, confirmed after', () => {
    const d = resolveScenario('e5-confirmation');
    const chain = d.causalChains['run_e5_ct']!;
    const action = () => chain.actions[0]!;

    // Inside the honest window: calm pending, no failure states.
    expect(action().outcome).toBe('DISPATCHED');
    vi.advanceTimersByTime(6_000); // measured lower sample was 6.7s — still legitimately pending
    expect(action().outcome).toBe('DISPATCHED');

    // Past the measured upper command→report sample (8.4s): the honest confirm arrives.
    vi.advanceTimersByTime(3_000);
    expect(action().outcome).toBe('CONFIRMED');
    expect(action().reason).toMatch(/reported/i);
  });

  it('1b: the pending state renders calm (info tone), never failure-anxiety', () => {
    const m = outcomeMeta('DISPATCHED');
    expect(m.tone).toBe('info'); // not warn/error inside the window
    expect(pendingHint('set_color_temperature')).toMatch(/slowly/i);
    expect(pendingHint('turn_on')).toBeNull(); // fast-confirming class gets no slow-hint
  });

  it('2: idempotent command renders the honest confirmed-from-cache state — never a spinner', () => {
    const d = resolveScenario('e5-confirmation');
    const a = d.causalChains['run_e5_idempotent']!.actions[0]!;
    expect(a.outcome).toBe('CONFIRMED'); // terminal, backend-attested — nothing left to wait on
    expect(a.reason).toMatch(/already on/i);
    expect(a.reason).toMatch(/no change/i);
  });

  it('3: effect/identify class is honestly UNCONFIRMED immediately (an ACK is not confirmation)', () => {
    const d = resolveScenario('e5-confirmation'); // t = activation: NO waiting period elapsed
    const a = d.causalChains['run_e5_effect']!.actions[0]!;
    expect(a.outcome).toBe('UNCONFIRMED'); // immediate — never DISPATCHED-pretending-progress
    expect(a.outcome).not.toBe('CONFIRMED'); // AMD-97-INV-01
    expect(commandKind('identify')).toBe('effect');
    expect(unconfirmableHint('identify')).toMatch(/never reported/i);
    expect(unconfirmableHint('turn_on')).toBeNull();
  });

  it('4: a superseded command expires honestly — the newer command confirms, no stale pending chip', () => {
    const d = resolveScenario('e5-confirmation');
    const acts = d.causalChains['run_e5_superseded']!.actions;
    expect(acts).toHaveLength(2);
    expect(acts[0]!.outcome).toBe('UNCONFIRMED'); // expired, not false-failed, not false-confirmed
    expect(acts[0]!.reason).toMatch(/superseded/i);
    expect(acts[1]!.outcome).toBe('CONFIRMED'); // the coalesced report carries the final value
    // No action is left pending — nothing for a chip to strand on.
    expect(acts.every((a) => a.outcome !== 'DISPATCHED')).toBe(true);
  });

  it('color_loop classifies as effect (unconfirmable-by-report), not color', () => {
    // The measured write-only honesty proof: SUCCESS ACK, zero effect-state reports.
    expect(commandKind('color_loop')).toBe('effect');
    expect(commandKind('set_color_temperature')).toBe('color');
    expect(commandKind('set_temperature')).toBe('other'); // a thermostat is not a color
  });

  it('UI copy hardcodes NO timeout numbers — the window is the backend’s (pointer-not-copy)', () => {
    for (const text of [
      pendingHint('set_color_temperature'),
      unconfirmableHint('identify'),
      outcomeMeta('DISPATCHED').help,
      outcomeMeta('UNCONFIRMED').help,
    ]) {
      expect(text ?? '').not.toMatch(/\d/);
    }
  });
});

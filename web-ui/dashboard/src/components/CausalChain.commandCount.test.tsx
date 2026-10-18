/*
 * HERO-U4 R3 (2026-10-17) — IR-207's rider on the hero. GATE-2 F5: on the rig's copy 1,108 of 1,596 `automation_completed` rows are
 * COMPLETED with action_count ≥ 1 and command_count = 0 — a hero that reads COMPLETED as acted would lie. Wherever the run's FINAL
 * STATUS renders as a label — the terminal step and its pill (CausalChain.tsx `status`) — `outcome.commandCount` renders beside it
 * ("Completed · 0 commands") through two §7 rows (`explain.terminal.commands` · `.commands.one`, filed for the hero SPEC's §7
 * amendment); a count the wire did not carry as an integer ≥ 0 appends nothing (the honesty law). RED at HEAD: the keys are absent
 * and the pill reads the bare label. The runs LIST (RunSummary) carries no commandCount on the wire, so its pill stays bare — a
 * cross-lane ask, filed. P5 is adjudicated below on the SILENT-SKIP mock run (scenarios.ts:678–:684) [GREEN at HEAD; disclosed].
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import { CausalChain } from './CausalChain';
import type { CausalChain as Chain } from '../lib/api/contract';
import { causalChains } from '../lib/api/mock/mockData';
import { resolveScenario } from '../lib/api/mock/scenarios';
import { t, type MessageKey } from '../lib/i18n';

afterEach(cleanup);

/** The whole render's text, the terminal step's text and its PILL's text (the span after the line span). */
function rendered(c: Chain) {
  const { container } = render(<CausalChain chain={c} />);
  const li = container.querySelector('li[data-kind="outcome"]')!;
  expect(li).toBeTruthy();
  const spans = li.querySelectorAll('div > div > span');
  return { text: container.textContent ?? '', step: li.textContent ?? '', pill: spans[1]?.textContent ?? '' };
}
const confirmedRun = () => structuredClone(causalChains['run_eh_001']!); // COMPLETED · actionCount 1 · commandCount 1 · CONFIRMED
const silentSkipRun = () => structuredClone(resolveScenario('field-evidence').causalChains['run_fe_skip']!); // COMPLETED · 2 · 0 · actions []

describe('HERO-U4 R3 — IR-207\'s rider: commandCount beside the final status; a COMPLETED run with no command never reads as acted', () => {
  it('the two §7 rows resolve [RED at HEAD: absent]', () => {
    expect(t('explain.terminal.commands' as MessageKey)).toBe('{label} · {count} commands');
    expect(t('explain.terminal.commands.one' as MessageKey)).toBe('{label} · 1 command');
  });
  it('P5 — the SILENT-SKIP run (COMPLETED, actionCount 2, commandCount 0, actions[] empty) renders no sentence that says it acted: the silent-skip headline, "Finished …, but nothing was changed.", the do-nothing pill [GREEN at HEAD — preservation, disclosed]', () => {
    const r = rendered(silentSkipRun());
    expect(r.text).toMatch(/ran when .*, but nothing was changed\./);
    expect(r.step).toContain('Finished in 0.0s, but nothing was changed.');
    expect(r.pill).toContain('Completed, nothing changed');
    expect(r.text).not.toMatch(/turned on|was asked to|confirmed|did turn/i);
  });
  it('the rider on the SILENT-SKIP run: the pill and the step label carry the count — "Completed, nothing changed · 0 commands" [RED at HEAD: the bare label]', () => {
    const r = rendered(silentSkipRun());
    expect(r.pill).toBe('Completed, nothing changed · 0 commands');
    expect(r.step).toContain('outcome — Completed, nothing changed · 0 commands.');
    expect(r.text).not.toMatch(/Completed, nothing changed(?! · 0 commands)/);
  });
  it('a COMPLETED run with commandCount 1 (the confirmed run): "Completed · 1 command" — singular — and its headline unchanged [RED at HEAD: the bare label]', () => {
    const r = rendered(confirmedRun());
    expect(r.pill).toBe('Completed · 1 command');
    expect(r.text).toMatch(/turned on because/);
  });
  it('every status, not only COMPLETED: a FAILED run with 2 commands → "Failed · 2 commands" [RED at HEAD]', () => {
    const c = confirmedRun();
    c.outcome = { ...c.outcome, status: 'FAILED', actionCount: 2, commandCount: 2 };
    expect(rendered(c).pill).toBe('Failed · 2 commands');
  });
  it('the inconsistent wire — a CONFIRMED action row beside commandCount 0: the count is the terminal event\'s word — the silent-skip headline, never "turned on"; the pill "Completed · 0 commands" [RED at HEAD: the acted headline]', () => {
    const c = confirmedRun();
    c.outcome = { ...c.outcome, actionCount: 1, commandCount: 0 };
    const r = rendered(c);
    expect(r.text).toMatch(/ran when .*, but nothing was changed\./);
    expect(r.text).not.toMatch(/turned on because/);
    // FLIP-6 (HERO-U5 U5, O-4): actionCount 1 · commandCount 0 IS the do-nothing class whatever the row shows — the do-nothing pill.
    expect(r.pill).toBe('Completed, nothing changed · 0 commands');
  });
  it('HERO-U5 U3 — IR-225 (c), the corner: a CONFIRMED action row beside actionCount 0 AND commandCount 0 — the silent-skip headline beside "Completed · 0 commands", never "turned on" [RED at HEAD: the acted headline]', () => {
    const c = confirmedRun();
    c.outcome = { ...c.outcome, actionCount: 0, commandCount: 0 };
    const r = rendered(c);
    expect(r.text).toMatch(/ran when .*, but nothing was changed\./);
    expect(r.text).not.toMatch(/turned on because/);
    expect(r.pill).toBe('Completed · 0 commands'); // not the do-nothing pill: that class needs actionCount > 0 (U5)
  });
  it('a count the wire did not carry as an integer ≥ 0 (null · a fraction · a negative) appends NOTHING — the bare label, never "null commands" [GREEN at HEAD by construction; teeth after the fold]', () => {
    for (const bad of [null, 1.5, -1]) {
      const c = confirmedRun();
      c.outcome = { ...c.outcome, commandCount: bad as unknown as number };
      const r = rendered(c);
      expect(r.pill, String(bad)).toBe('Completed');
      expect(r.text, String(bad)).not.toMatch(/Completed · /);
    }
  });
});

/*
 * HERO-U5 U5 (2026-10-18) — O-4 · O-9: the do-nothing status, whatever the rows show. J3 T-B (core 176adad) records a SKIPPED
 * row per command action that issued nothing (StandardExplanationService.java:895–:916: SKIPPED · command null · params {} ·
 * reason null · resultOutcome null · settled true · settledAt the completion · confirmedAt null), so an all-skipped run arrives
 * COMPLETED · actionCount N · commandCount 0 · N SKIPPED rows — and HEAD's `isDoNothingRun` (which needed actions[] EMPTY) let it
 * read "Completed · 0 commands" · "Done in …s." The class is now the counts alone: COMPLETED ∧ actionCount > 0 ∧ commandCount 0.
 * The synthetic do-nothing step and its hint render only when actions[] is empty — SKIPPED rows ARE the record, never shown twice.
 * The chain below is built from the mock's confirmed run with §1's values on the contract's camelCase keys (IR-89).
 */
const j3Chain = (): Chain => {
  const c = confirmedRun();
  const done = c.actions[0]!.settledAt ?? null; // the completion instant the mock already carries
  const skipped = (id: string) => ({
    type: 'device_command',
    targetRef: { type: 'ENTITY' as const, id },
    command: null,
    params: {},
    outcome: 'SKIPPED' as const,
    reason: null,
    resultOutcome: null,
    settled: true,
    settledAt: done,
    confirmedAt: null,
  });
  c.actions = [skipped('ent_hallway_light'), skipped('ent_livingroom_lamp')];
  c.outcome = { ...c.outcome, status: 'COMPLETED', actionCount: 2, commandCount: 0 };
  return c;
};

describe('HERO-U5 U5 — O-4: a J3 T-B all-skipped run (COMPLETED · 2 · 0 · two SKIPPED rows) carries the do-nothing status whatever the rows show', () => {
  it('the pill reads "Completed, nothing changed · 0 commands" and the terminal line "Finished in …s, but nothing was changed." [RED at HEAD: "Completed · 0 commands" · "Done in …s."]', () => {
    const r = rendered(j3Chain());
    expect(r.pill).toBe('Completed, nothing changed · 0 commands');
    expect(r.step).toContain('Finished in 0.4s, but nothing was changed.');
    expect(r.step).not.toContain('Done in');
  });
  it('the two SKIPPED rows are the record: exactly two action steps, no synthetic do-nothing step, no hint; the headline keeps row 5 (D-2) [GREEN at HEAD — preservation: HEAD never rendered the synthetic step beside rows]', () => {
    const c = j3Chain();
    const { container } = render(<CausalChain chain={c} />);
    const text = container.textContent ?? '';
    expect(container.querySelectorAll('li[data-kind="action"]').length).toBe(2);
    expect(text).not.toContain(t('explain.step.nothing.many' as MessageKey).split('{count}')[0]!.trim());
    expect(text).not.toContain(t('explain.step.nothing.hint' as MessageKey));
    expect(text).toMatch(/^.*Nothing was sent to Hallway Light when .* — that step was skipped\./);
    expect(text).not.toMatch(/turned on|was asked to|did turn/i);
  });
  it('the empty-rows arm is unchanged: the SILENT-SKIP mock run (actions[] empty) keeps its synthetic step and hint beside the same pill [preservation]', () => {
    const { container } = render(<CausalChain chain={silentSkipRun()} />);
    const text = container.textContent ?? '';
    expect(container.querySelectorAll('li[data-kind="action"]').length).toBe(1);
    expect(text).toContain(t('explain.step.nothing.hint' as MessageKey));
    expect(text).toContain('Completed, nothing changed · 0 commands');
  });
});

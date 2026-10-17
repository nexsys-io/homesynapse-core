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
    expect(r.pill).toBe('Completed · 0 commands');
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

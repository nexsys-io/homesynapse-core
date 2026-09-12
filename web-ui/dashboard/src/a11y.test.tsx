/*
 * Accessibility gate (Tier 3 / amendment G6) — axe-core over the user-facing surfaces that carry
 * the a11y-critical patterns: the causal-chain hero (semantic <ol>, live regions), the theme
 * toggle (radiogroup), the auth form (labelled, paste-allowed), the status pills (name = shape +
 * text, never color alone), and the honest feedback states. Structural WCAG rules are machine-
 * checked on every change, in both the light and dark token sets (colors are variables).
 *
 * jsdom cannot compute layout, so 'color-contrast' is disabled here (contrast is verified visually
 * in both themes); 'region' is disabled because these are isolated components, not whole pages.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import axe from 'axe-core';
import { CausalChain } from './components/CausalChain';
import { ThemeToggle } from './components/ThemeToggle';
import { AuthGate } from './components/AuthGate';
import { StatusPill } from './components/StatusPill';
import { Loading, EmptyState, ErrorState, OfflineState, ReplayingBanner } from './components/feedback';
import { causalChains } from './lib/api/mock/mockData';
import { SCENARIOS } from './lib/api/mock/scenarios';

afterEach(cleanup);

async function violations(node: Element): Promise<string[]> {
  const res = await axe.run(node as HTMLElement, {
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
  });
  return res.violations.map((v) => `${v.id}: ${v.help}`);
}

describe('accessibility — axe-core structural rules', () => {
  it('the causal-chain hero has no violations', async () => {
    const { container } = render(<CausalChain chain={causalChains['run_eh_001']!} />);
    expect(await violations(container)).toEqual([]);
  });

  it('the five-modes chain (distinct glyphs + a provisional pill) has no violations', async () => {
    const chain = SCENARIOS.find((s) => s.id === 'five-modes')!.build().causalChains['run_fm_all']!;
    const { container } = render(<CausalChain chain={chain} />);
    expect(await violations(container)).toEqual([]);
  });

  it('the theme toggle (radiogroup) has no violations', async () => {
    const { container } = render(<ThemeToggle />);
    expect(await violations(container)).toEqual([]);
  });

  it('the auth gate (labelled, paste-allowed) has no violations', async () => {
    const { container } = render(<AuthGate />);
    expect(await violations(container)).toEqual([]);
  });

  it('status pills carry an accessible name (not color alone)', async () => {
    const { container } = render(
      <div>
        <StatusPill tone="ok" label="Confirmed" />
        <StatusPill tone="warn" label="Sent, not confirmed" />
        <StatusPill tone="error" label="Failed" />
        <StatusPill tone="unknown" label="Unknown" />
      </div>,
    );
    expect(await violations(container)).toEqual([]);
  });

  it('the honest feedback states have no violations', async () => {
    const { container } = render(
      <div>
        <Loading />
        <EmptyState title="No runs yet" hint="Once an automation runs, you’ll see exactly why here." />
        <ErrorState />
        <OfflineState />
        <ReplayingBanner />
      </div>,
    );
    expect(await violations(container)).toEqual([]);
  });
});

/* ---- HERO-1b B5 (2026-09-12) — SPEC §8 (design/hero-v1/SPEC.md:281): shape and label on
 * every state. Each marker is aria-hidden and the step carries `explain.a11y.step` as
 * visually-hidden text; a provisional (dashed) pill appends `explain.a11y.provisional`; a
 * held action that settles on a later poll is announced ONCE through a polite role="status"
 * region (`explain.a11y.live`). RED at HEAD: no step carries hidden text (CausalChain.tsx
 * :289–:301 renders marker + line only), StatusPill.tsx has no provisional suffix, and no
 * role="status" exists in the hero. ---- */
describe('HERO-1b B5 — the hero is legible without the marker shapes', () => {
  const srText = (el: Element) => Array.from(el.querySelectorAll('.sr-only')).map((n) => n.textContent).join(' | ');

  it('every step carries "Step {n} of {N}: {kind} — {label}." as visually-hidden text', () => {
    const { container } = render(<CausalChain chain={causalChains['run_eh_001']!} />);
    const steps = Array.from(container.querySelectorAll('ol > li'));
    expect(steps.length).toBe(4); // trigger · condition · action · outcome
    expect(srText(steps[0]!)).toContain('Step 1 of 4: trigger — Trigger.');
    expect(srText(steps[1]!)).toContain('Step 2 of 4: condition — was true.');
    expect(srText(steps[2]!)).toContain('Step 3 of 4: action — Confirmed.');
    expect(srText(steps[3]!)).toContain('Step 4 of 4: outcome — Completed.');
    for (const s of steps) expect(s.querySelector('[aria-hidden="true"]')).toBeTruthy(); // the marker stays hidden
  });

  it('a provisional pill appends "Provisional — may still change" for screen readers', () => {
    const chain = SCENARIOS.find((s) => s.id === 'five-modes')!.build().causalChains['run_fm_all']!;
    const { container } = render(<CausalChain chain={chain} />);
    const held = Array.from(container.querySelectorAll('li[data-kind="action"]')).find((li) => li.textContent?.includes('Sent — not settled yet'))!;
    expect(held).toBeTruthy();
    expect(srText(held)).toContain('Provisional — may still change');
    const settled = Array.from(container.querySelectorAll('li[data-kind="action"]')).find((li) => li.textContent?.includes('Confirmed'))!;
    expect(srText(settled)).not.toContain('Provisional');
  });

  it('a held action that settles on a later read is announced once through role="status" (polite), and the region is present but silent before', () => {
    const before = structuredClone(causalChains['run_eh_001']!);
    before.actions[0]!.outcome = 'DISPATCHED';
    before.actions[0]!.resultOutcome = null;
    before.actions[0]!.settled = false;
    const { container, rerender } = render(<CausalChain chain={before} />);
    const region = container.querySelector('[role="status"]')!;
    expect(region).toBeTruthy();
    expect(region.getAttribute('aria-live')).toBe('polite');
    expect(region.textContent).toBe('');
    rerender(<CausalChain chain={causalChains['run_eh_001']!} />);
    expect(container.querySelector('[role="status"]')!.textContent).toBe('Updated: Confirmed');
  });

  it('the chain is a labelled <ol>, and the hero with its hidden text still has no axe violations', async () => {
    const { container } = render(<CausalChain chain={causalChains['run_eh_001']!} />);
    expect(container.querySelector('ol')!.getAttribute('aria-label')).toBe('Step-by-step explanation, from trigger to outcome');
    expect(await violations(container)).toEqual([]);
  });
});

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

/*
 * HERO-1d D4 (2026-09-13) — the run page's title is a catalog row (`explain.run.title`): the one
 * sentence the D0 lint flagged in RunChainView.tsx (:22), byte-identical on screen. RED at HEAD:
 * the key does not exist. The "← All runs" link is a two-word run the lint's pattern cannot see
 * and stays literal — filed in the return, not changed.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { RunChainView } from './RunChainView';
import { api } from '../lib/api';
import { t } from '../lib/i18n';
import { causalChains } from '../lib/api/mock/mockData';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('HERO-1d D4 — the run page title is the catalog', () => {
  it('title → explain.run.title', async () => {
    const META = { viewPosition: 1, timestamp: new Date().toISOString() };
    vi.spyOn(api, 'getCausalChain').mockResolvedValue({ data: causalChains['run_eh_001'], meta: META } as never);
    vi.spyOn(api, 'listEntities').mockResolvedValue({ data: [], meta: META } as never);
    const { container } = render(<RunChainView runId="run_eh_001" />);
    await act(async () => {});
    const h1 = container.querySelector('h1')?.textContent;
    expect(h1).toBe('Why this happened');
    expect(h1).toBe(t('explain.run.title'));
  });
});

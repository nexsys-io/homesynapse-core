/*
 * HERO-1d D4 (2026-09-13) — the runs page's title, lede and empty label are catalog rows
 * (`explain.runs.title` · `.lede` · `.empty`), byte-identical on screen. RED at HEAD:
 * RunsView.tsx:19 / :27 carry the literals and none of the three keys exists.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { RunsView } from './RunsView';
import { api } from '../lib/api';
import { t } from '../lib/i18n';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

async function renderRuns() {
  vi.spyOn(api, 'listRuns').mockResolvedValue({ data: [], meta: { viewPosition: 1, timestamp: new Date().toISOString() } } as never);
  const utils = render(<RunsView />);
  await act(async () => {});
  return utils.container;
}

describe('HERO-1d D4 — the runs page is the catalog', () => {
  it('title → explain.runs.title', async () => {
    const h1 = (await renderRuns()).querySelector('h1')?.textContent;
    expect(h1).toBe('Why did something happen?');
    expect(h1).toBe(t('explain.runs.title'));
  });

  it('lede → explain.runs.lede', async () => {
    const lede = (await renderRuns()).querySelector('header p')?.textContent;
    expect(lede).toBe('Pick a run to see exactly why it fired, step by step.');
    expect(lede).toBe(t('explain.runs.lede'));
  });

  it('the empty table → explain.runs.empty (never DataTable\'s generic "Nothing here yet.")', async () => {
    const paragraphs = Array.from((await renderRuns()).querySelectorAll('p')).map((p) => p.textContent);
    expect(paragraphs).toContain('No automation runs yet.');
    expect(paragraphs).toContain(t('explain.runs.empty'));
    expect(paragraphs).not.toContain('Nothing here yet.');
  });
});

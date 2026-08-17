/*
 * NEW-3 — CONTRACT-VS-WIRE NULLABILITY on the non-firing read (RED-FIRST).
 * ---------------------------------------------------------------------------
 * The defect (G1 rehearsal §6.1–6.2, DX-16): contract.ts declared
 * `lastEvaluation` a non-nullable object; the deployed wire serves
 * `"lastEvaluation": null` (200 OK, §4.5, byte-complete body on record). The
 * type system concealed the need for a guard, and every mock populated the
 * object — the manufactured false type. WhyNotView.tsx:84 (`nf.lastEvaluation.at`)
 * threw, Act 2 rendered an indefinite spinner, and the boundary that should
 * have contained it was not mounted there (NEW-2's half of the chain).
 *
 * Tier-1 H8 discipline: the primary fixture below is the REAL captured wire
 * body, verbatim (provenance in the fixture file) — not a mock authored to be
 * convenient. House law: written RED before the fix.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { WhyNotView } from './WhyNotView';
import { api } from '../lib/api';
import { WIRE_20260816_NONFIRING_BENCH_HERO } from '../lib/api/fixtures/wire-2026-08-16-nonfiring';
import { validateAgainstContract } from '../lib/api/shapes';
import { RENDER_ERROR_TITLE } from '../components/ErrorBoundary';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const FIX = WIRE_20260816_NONFIRING_BENCH_HERO;

async function renderDetail(data: unknown) {
  vi.spyOn(api, 'getNonFiring').mockResolvedValue({ data, meta: FIX.meta } as never);
  const utils = render(<WhyNotView automationId={(data as { automationId?: string }).automationId ?? 'auto_x'} />);
  await act(async () => {}); // flush the resolved fetch and the rerender
  return utils;
}

describe('the REAL 2026-08-16 wire body (lastEvaluation: null) renders honestly', () => {
  it('renders the verdict, the explanation, and NO "Last checked" row — absence, not fabrication, not a crash', async () => {
    const { container } = await renderDetail(FIX.data);
    const text = container.textContent ?? '';
    expect(text).toContain('Nothing set it off'); // the honest NEVER_TRIGGERED verdict pill
    expect(text).toContain("Automation 'bench-hero' has not been triggered");
    expect(text).toContain('What would make it run'); // the surviving row still renders
    expect(text).not.toContain('Last checked'); // the null case: the row simply does not render
    expect(text).not.toContain(RENDER_ERROR_TITLE); // the VIEW is honest — not the boundary card
    expect(text).not.toContain('Loading…'); // and never the eternal spinner
  });

  it('the fixture validates against the contract mirror (object-OR-null is the recorded wire truth)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', FIX)).not.toThrow();
  });

  it('the mirror REJECTS a malformed lastEvaluation (neither object nor null)', () => {
    const bad = { data: { ...FIX.data, lastEvaluation: 'yesterday' }, meta: FIX.meta };
    expect(() => validateAgainstContract('B3:nonFiring', bad)).toThrow();
  });
});

describe('the lastEvaluation tri-state renders honestly in every arm', () => {
  it('object with at + conditionsResult renders the full row', async () => {
    const data = {
      ...FIX.data,
      lastEvaluation: { at: '2026-08-16T05:00:00Z', conditionsResult: 'after sunset = false' },
    };
    const { container } = await renderDetail(data);
    const text = container.textContent ?? '';
    expect(text).toContain('Last checked');
    expect(text).toContain('after sunset = false');
  });

  it('object with conditionsResult: null renders the time without fabricating a result', async () => {
    const data = { ...FIX.data, lastEvaluation: { at: '2026-08-16T05:00:00Z', conditionsResult: null } };
    const { container } = await renderDetail(data);
    const text = container.textContent ?? '';
    expect(text).toContain('Last checked');
    expect(text).not.toContain('null'); // never the string "null" on a surface
  });

  it('object with at: null suppresses the row (the pre-existing guard, preserved)', async () => {
    const data = { ...FIX.data, lastEvaluation: { at: null, conditionsResult: null } };
    const { container } = await renderDetail(data);
    expect(container.textContent ?? '').not.toContain('Last checked');
  });
});

describe('open-vocabulary hardening on the same surface (the closed-switch class)', () => {
  it('an unrecognized verdict renders in the honest register — never a crash, never success', async () => {
    const data = { ...FIX.data, verdict: 'SOMETHING_NEW' };
    const { container } = await renderDetail(data);
    const text = container.textContent ?? '';
    expect(text).toContain('Recorded as "SOMETHING_NEW"'); // honest-can't-know register
    expect(text).not.toContain(RENDER_ERROR_TITLE);
  });
});

/*
 * FE-HONEST-1 — the store-truth device list (§10-G/H/I, MED).
 * ---------------------------------------------------------------------------
 * R-4 field evidence: the Devices page showed an ENTITY ULID under a 'Device'
 * column (a row could not be correlated with a `device_adopted` log line), and
 * the list said 'Current' — a freshness claim the frozen A1 row carries no
 * evidence for — while the detail said the report time was not recorded.
 * Locked here: the column is labeled 'Entity', the raw entity id is shown
 * verbatim (it is what the log carries), and the evidence-free 'Current'
 * claim is gone.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { DevicesView } from './DevicesView';
import { api } from '../lib/api';
import type { EntitySummary } from '../lib/api/contract';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const meta = { viewPosition: 7, timestamp: new Date().toISOString() };
const ROWS: EntitySummary[] = [
  { entityId: 'ent_hallway_light', name: 'Hallway Light', availability: 'AVAILABLE', stale: false },
  { entityId: '01KX1PB9AAB4VB3E10BD477TV3', availability: 'AVAILABLE', stale: true },
];

async function renderList() {
  vi.spyOn(api, 'listEntities').mockResolvedValue({ data: ROWS, meta });
  const utils = render(<DevicesView />);
  await act(async () => {});
  return utils;
}

describe('the device list is store-truth (§10-H/I)', () => {
  it('labels the rows ENTITY and shows the raw entity id verbatim (log-correlatable)', async () => {
    const { container } = await renderList();
    const text = container.textContent ?? '';
    const headers = Array.from(container.querySelectorAll('th')).map((th) => th.textContent);
    expect(headers).toContain('Entity');
    expect(headers).not.toContain('Device'); // the §10-H mislabel is gone
    expect(text).toContain('ent_hallway_light'); // the raw id, verbatim
    expect(text).toContain('01KX1PB9AAB4VB3E10BD477TV3');
  });

  it('makes no evidence-free freshness claim: Stale renders on evidence; "Current" never renders', async () => {
    const { container } = await renderList();
    const text = container.textContent ?? '';
    expect(text).toContain('Stale'); // stale: true IS store evidence
    expect(text).not.toContain('Current'); // the §10-I claim with no evidence is gone
  });
});

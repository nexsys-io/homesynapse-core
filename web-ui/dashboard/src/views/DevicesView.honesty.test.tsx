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
import { LIST_FRESHNESS_NO_CLAIM_TITLE, LIST_FRESHNESS_NULL_TITLE } from '../lib/format';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const meta = { viewPosition: 7, timestamp: new Date().toISOString() };
const ROWS: EntitySummary[] = [
  { entityId: 'ent_hallway_light', name: 'Hallway Light', availability: 'AVAILABLE', stale: false },
  { entityId: '01KX1PB9AAB4VB3E10BD477TV3', availability: 'AVAILABLE', stale: true },
];

async function renderList(rows: EntitySummary[] = ROWS) {
  vi.spyOn(api, 'listEntities').mockResolvedValue({ data: rows, meta });
  const utils = render(<DevicesView />);
  await act(async () => {});
  return utils;
}

/** The freshness cell of the FIRST data row: the third column (Entity · Status · Reading). */
function readingCell(container: Element): HTMLElement {
  const cells = container.querySelectorAll('tbody tr:first-child td');
  const cell = cells[2] as HTMLElement | undefined;
  if (!cell) throw new Error('no Reading cell rendered');
  return cell;
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

/* ---- FE-113 (v1.1.3): the list row CAN now carry `lastReported` and `deviceId` ----
 * The tri-state law on the freshness cell (FE-HONEST-1 §10-I, extended): the key
 * ABSENT (a pre-v1.1.3 hub) keeps the no-claim em-dash; the key PRESENT-BUT-NULL
 * (a v1.1.3 hub with nothing on record) is a DIFFERENT fact and gets its own
 * sentence; a PRESENT string renders the date-qualified stamp. `deviceId`
 * renders a muted "Device <ulid>" line only when the wire carried a string —
 * null and absent both render NOTHING (absence renders absence; never "null",
 * never a placeholder that could be mistaken for a device).
 * Red-first: the PRESENT-null, PRESENT-string and Device-line rows are RED at
 * HEAD (HEAD renders the em-dash + no-claim title unconditionally and never a
 * device line); the ABSENT row is green-by-construction (unchanged behavior). */
const DEVICE_ULID = '01M0GPZFVANYA5TZMZSXRCV063';
const REPORTED = '2026-09-06T02:45:29.123456Z'; // the wire form: Instant.toString(), nanos
const base = { entityId: 'ent_hallway_light', name: 'Hallway Light', availability: 'AVAILABLE', stale: false } as const;

describe('the freshness cell renders the v1.1.3 tri-state honestly (absent ≠ null ≠ value)', () => {
  it('key ABSENT (pre-v1.1.3 hub): em-dash + the no-claim title — unchanged', async () => {
    const { container } = await renderList([{ ...base }]);
    const cell = readingCell(container);
    expect(cell.textContent?.trim()).toBe('—');
    expect(cell.querySelector('[title]')?.getAttribute('title')).toBe(LIST_FRESHNESS_NO_CLAIM_TITLE);
  });

  it('key PRESENT-null (v1.1.3 hub, nothing on record): em-dash + the NULL title — a different sentence', async () => {
    const { container } = await renderList([{ ...base, deviceId: null, lastReported: null }]);
    const cell = readingCell(container);
    expect(cell.textContent?.trim()).toBe('—');
    expect(cell.querySelector('[title]')?.getAttribute('title')).toBe(LIST_FRESHNESS_NULL_TITLE);
    expect(cell.querySelector('[title]')?.getAttribute('title')).not.toBe(LIST_FRESHNESS_NO_CLAIM_TITLE);
  });

  it('key PRESENT-string: the date-qualified stamp renders (lastReportedCell) — no em-dash, no 1970', async () => {
    const { container } = await renderList([{ ...base, deviceId: DEVICE_ULID, lastReported: REPORTED }]);
    const cell = readingCell(container);
    const text = cell.textContent ?? '';
    expect(text.trim()).not.toBe('—');
    expect(text).not.toContain('1970');
    expect(text).not.toContain('Current'); // §10-I: still never the evidence-free word
    expect(text).toMatch(/\d/); // a clock time, date-qualified (the stamp is not today's)
  });
});

describe('the deviceId line renders presence only (absence renders absence)', () => {
  it('PRESENT-string: a muted "Device <ulid>" secondary line, verbatim id', async () => {
    const { container } = await renderList([{ ...base, deviceId: DEVICE_ULID, lastReported: null }]);
    const text = container.textContent ?? '';
    expect(text).toContain(`Device ${DEVICE_ULID}`);
    // The line sits inside the Entity cell (a secondary line), not a new column.
    const headers = Array.from(container.querySelectorAll('th')).map((th) => th.textContent);
    expect(headers).not.toContain('Device');
  });

  it('PRESENT-null: nothing rendered — never "null", never a placeholder', async () => {
    const { container } = await renderList([{ ...base, deviceId: null, lastReported: null }]);
    const text = container.textContent ?? '';
    expect(text).not.toMatch(/Device\s+[0-9A-Z]{26}/);
    expect(text).not.toContain('Device null');
    expect(text).not.toContain('null');
  });

  it('ABSENT (pre-v1.1.3 hub): nothing rendered', async () => {
    const { container } = await renderList([{ ...base }]);
    const text = container.textContent ?? '';
    expect(text).not.toMatch(/Device\s+[0-9A-Z]{26}/);
    expect(text).not.toContain('undefined');
  });
});

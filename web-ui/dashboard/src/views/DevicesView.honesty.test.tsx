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

/* ---- HERO-U2b R2 (2026-10-09) — the Devices row and detail swap to the recovery card (SPEC §2 surface 1–2;
 * §6 B: the "Stale" pill moves off the row's state onto the reading it describes — never a card word).
 * The Status cell renders the §3 decision for the row's stage (S1 on a pre-J1 row; S2 when the keys are
 * present); the old "Available / Offline / Not determined yet" vocabulary is gone from the list. RED at HEAD:
 * DevicesView.tsx:81–:82 renders availabilityMeta's pill ("Available"); :90 renders the Stale pill INSTEAD of
 * the stamp; the drawer (:151, :159–:161) renders the availability pill + the evidence prose. ---- */
import { fireEvent } from '@testing-library/preact';
import { clockTimeWithDate } from '../lib/format';
import type { EntityState } from '../lib/api/contract';

const NOW_ISO = new Date().toISOString();
const SEEN_OLD = new Date(Date.now() - 2 * 24 * 60 * 60_000).toISOString();
const statusCell = (container: Element, rowIndex = 0): HTMLElement => {
  const cells = container.querySelectorAll(`tbody tr:nth-child(${rowIndex + 1}) td`);
  const cell = cells[1] as HTMLElement | undefined;
  if (!cell) throw new Error('no Status cell rendered');
  return cell;
};

describe('HERO-U2b — the device row\'s Status cell is the recovery card (dense), per the row\'s stage', () => {
  it('S1 (a pre-J1 row): AVAILABLE → "Reporting" with the age; UNAVAILABLE → the degraded "Not responding since {t}" (no parenthesis); UNKNOWN → the fifth state', async () => {
    const { container } = await renderList([
      { ...base, lastReported: NOW_ISO },
      { entityId: 'ent_b', availability: 'UNAVAILABLE', stale: false, lastReported: REPORTED },
      { entityId: 'ent_c', availability: 'UNKNOWN', stale: false, lastReported: null },
    ]);
    expect(statusCell(container, 0).textContent).toContain('Reporting');
    expect(statusCell(container, 0).textContent).toContain('Last report just now.');
    expect(statusCell(container, 1).textContent).toContain(`Not responding since ${clockTimeWithDate(REPORTED)}`);
    expect(statusCell(container, 1).textContent).not.toContain('(asked twice'); // the probe clause is a claim S1 cannot make
    expect(statusCell(container, 1).textContent).toContain('Whether it has been asked since is not shown here yet.');
    expect(statusCell(container, 2).textContent).toContain('Not heard from since startup (not asked)');
    expect(statusCell(container, 2).textContent).toContain('No report on record.');
    const text = container.textContent ?? '';
    for (const old of ['Available', 'Offline', 'Not determined yet']) expect(text).not.toContain(old);
  });
  it('S2 (the J1 keys present): ping_timeout → the full label; silence_timeout → the passive dark row; the version-1 null row → the fifth state; the IR-133 null row → Reporting', async () => {
    const { container } = await renderList([
      { entityId: 'ent_plug', availability: 'UNAVAILABLE', stale: false, deviceId: DEVICE_ULID, lastReported: REPORTED, availabilityReason: 'ping_timeout', lastSeenAt: SEEN_OLD, link: { lqi: 96, rssiDbm: -83, at: SEEN_OLD } },
      { entityId: 'ent_sensor', availability: 'UNAVAILABLE', stale: false, deviceId: null, lastReported: REPORTED, availabilityReason: 'silence_timeout', lastSeenAt: SEEN_OLD, link: null },
      { entityId: 'ent_hue', availability: 'UNAVAILABLE', stale: false, deviceId: null, lastReported: '2026-07-19T00:49:15.787079Z', availabilityReason: null, lastSeenAt: null, link: null },
      { entityId: 'ent_ok', availability: 'AVAILABLE', stale: false, deviceId: null, lastReported: NOW_ISO, availabilityReason: null, lastSeenAt: null, link: null },
    ]);
    expect(statusCell(container, 0).textContent).toContain(`Not responding since ${clockTimeWithDate(SEEN_OLD)} (asked twice, no answer)`);
    expect(statusCell(container, 0).textContent).toContain('Asked twice; nothing came back.');
    expect(statusCell(container, 1).textContent).toContain(`Not responding since ${clockTimeWithDate(SEEN_OLD)}`);
    expect(statusCell(container, 1).textContent).toContain('It is never asked.');
    expect(statusCell(container, 2).textContent).toContain('Not heard from since startup (not asked)');
    expect(statusCell(container, 3).textContent).toContain('Reporting');
    expect(statusCell(container, 3).textContent).toContain('Last report just now.');
    expect(container.textContent).not.toMatch(/\b(null|undefined)\b/);
  });
  it('the row carries no contract sentence and no L2 (dense): the card\'s details live on the device page', async () => {
    const { container } = await renderList([{ ...base, lastReported: NOW_ISO }]);
    expect(statusCell(container).querySelector('details')).toBeNull();
    expect(statusCell(container).textContent).not.toContain('If this device goes quiet');
  });
});

describe('HERO-U2b — SPEC §6 B: "Stale" is about the READING and sits in the Reading cell beside the stamp, never in the Status cell', () => {
  it('a stale row shows its date-qualified stamp AND the Stale pill in the Reading cell; the Status cell (the card) never says "stale"', async () => {
    const { container } = await renderList([{ ...base, stale: true, deviceId: DEVICE_ULID, lastReported: REPORTED }]);
    const reading = readingCell(container);
    expect(reading.textContent).toContain('Stale');
    expect(reading.textContent).toContain(clockTimeWithDate(REPORTED)); // the stamp is no longer hidden behind the pill
    expect(statusCell(container).textContent).not.toMatch(/\bstale\b/i);
  });
  it('a stale row with no report time keeps the honest em-dash beside the pill', async () => {
    const { container } = await renderList([{ ...base, stale: true }]);
    const reading = readingCell(container);
    expect(reading.textContent).toContain('—');
    expect(reading.textContent).toContain('Stale');
  });
});

describe('HERO-U2b — the entity detail renders the full card from the list row; the evidence prose and the availability pill are gone', () => {
  const stateOf = (entityId: string): EntityState => ({
    entityId,
    availability: 'UNAVAILABLE',
    attributes: { power: { t: 'BOOL', v: true } },
    stateVersion: 3,
    lastChanged: REPORTED,
    lastUpdated: REPORTED,
    lastReported: REPORTED,
    stale: true,
    staleAfter: null,
  });
  it('clicking a dark row opens the drawer with the card (label, line, L2 "Show details") — "Offline — last heard from" is not rendered; "Stale reading" stays beside the values', async () => {
    vi.spyOn(api, 'getEntityState').mockResolvedValue({ data: stateOf('ent_plug'), meta } as never);
    const { container } = await renderList([
      { entityId: 'ent_plug', name: 'Kitchen Plug', availability: 'UNAVAILABLE', stale: true, deviceId: DEVICE_ULID, lastReported: REPORTED, availabilityReason: 'ping_timeout', lastSeenAt: SEEN_OLD, link: { lqi: 96, rssiDbm: -83, at: SEEN_OLD } },
    ]);
    fireEvent.click(container.querySelector('tbody tr')!);
    await act(async () => {});
    const text = container.textContent ?? '';
    expect(text).toContain(`Kitchen Plug: Not responding since ${clockTimeWithDate(SEEN_OLD)} (asked twice, no answer).`); // the a11y sentence
    expect(text).toContain('Show details');
    expect(text).toContain(`Last heard: ${clockTimeWithDate(SEEN_OLD)}`);
    expect(text).not.toContain('last heard from');
    expect(text).not.toContain('Offline');
    expect(text).not.toContain('Devices are rechecked every few minutes');
    expect(text).toContain('Stale reading'); // SPEC §6 B: the reading's pill stays with the values (DevicesView.tsx:152)
    expect(text).toContain('Last reported'); // the kv row stays
  });
});

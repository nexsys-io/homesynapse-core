/*
 * HERO-U2b R2 (2026-10-09) — RecoveryCard renders the §3 decision: L1 (the state pill — glyph + label — and the
 * line beneath; R5's second line) and L2 one expand away (the absolute instants, "last heard" vs "last report"
 * said, the reason verbatim, the signal when on record); `dense` for the device row (L1 only). The card carries
 * `recovery.a11y.state` as visually-hidden text and ONE polite role="status" region, silent on first render and
 * announcing once when the row's state changes (the rejoin moment — the row flipping by its own frame). No act
 * in this build (row 3's — Q3 (b)): no button. `stale` is never a card word (SPEC §6 B). RED at HEAD (2b4be09):
 * the component does not exist.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import { RecoveryCard } from './RecoveryCard';
import type { EntitySummary } from '../lib/api/contract';
import { clockTimeWithDate } from '../lib/format';
import { RECOVERY_GLYPHS } from '../lib/verdicts';

afterEach(cleanup);

const NOW = Date.parse('2026-10-09T19:30:00.000Z');
const minAgo = (m: number) => new Date(NOW - m * 60_000).toISOString();
const REPORTED = minAgo(14);
const SEEN_OLD = minAgo(2 * 24 * 60);
const clock = (iso: string) => clockTimeWithDate(iso, NOW);
const sr = (el: Element) => Array.from(el.querySelectorAll('.sr-only')).map((n) => n.textContent).join(' | ');

const plugDark: EntitySummary = { entityId: '01KX1PB9AAB4VB3E10BD477TV3', availability: 'UNAVAILABLE', stale: true, deviceId: '01KX1PB9A5931A8G0F0X03QXT2', lastReported: REPORTED, availabilityReason: 'ping_timeout', lastSeenAt: SEEN_OLD, link: { lqi: 182, rssiDbm: -61, at: SEEN_OLD } };
const plugBack: EntitySummary = { ...plugDark, availability: 'AVAILABLE', stale: false, lastReported: minAgo(0), availabilityReason: 'frame_received', lastSeenAt: minAgo(0), link: { lqi: 200, rssiDbm: -50, at: minAgo(0) } };
const lampUnasked: EntitySummary = { entityId: '01KX1PA4HSJ581GASYB7DHE40F', availability: 'UNAVAILABLE', stale: false, deviceId: '01KX1PA4GRZHY2GD37B5CFVQHY', lastReported: '2026-07-19T00:49:15.787079Z', availabilityReason: null, lastSeenAt: null, link: null };

describe('RecoveryCard — L1', () => {
  it('renders the state pill (an aria-hidden glyph + the §7 label) and the line beneath; the a11y sentence is "{deviceName}: {stateLabel}."', () => {
    const { container } = render(<RecoveryCard row={plugDark} name="Kitchen Plug" now={NOW} />);
    const text = container.textContent ?? '';
    expect(text).toContain(`Not responding since ${clock(SEEN_OLD)} (asked twice, no answer)`);
    expect(text).toContain(`Asked twice; nothing came back. Last heard ${clock(SEEN_OLD)}.`);
    const svg = container.querySelector('svg')!;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.querySelector('path')!.getAttribute('d')).toBe(RECOVERY_GLYPHS.notResponding.d);
    expect(sr(container)).toContain(`Kitchen Plug: Not responding since ${clock(SEEN_OLD)} (asked twice, no answer).`);
    expect(container.querySelector('[data-row="notResponding"]')).toBeTruthy();
  });
  it('never says "stale" or "offline" — even on a stale row (the stale pill lives on the reading, SPEC §6 B); never "null" / "undefined"', () => {
    const { container } = render(<RecoveryCard row={plugDark} name="Kitchen Plug" now={NOW} />);
    const text = container.textContent ?? '';
    expect(text).not.toMatch(/\bstale\b/i);
    expect(text).not.toMatch(/\boffline\b/i);
    expect(text).not.toMatch(/\b(null|undefined)\b/);
  });
  it('the fifth state beside R3: a different verb, a different glyph, a different hue — and its second line', () => {
    const { container } = render(
      <div>
        <RecoveryCard row={plugDark} name="Kitchen Plug" now={NOW} />
        <RecoveryCard row={lampUnasked} name="Porch Lamp" now={NOW} />
      </div>,
    );
    const cards = container.querySelectorAll('[data-row]');
    expect(cards.length).toBe(2);
    expect(cards[0]!.getAttribute('data-row')).toBe('notResponding');
    expect(cards[1]!.getAttribute('data-row')).toBe('unasked');
    expect(cards[1]!.textContent).toContain('Not heard from since startup (not asked)');
    expect(cards[1]!.textContent).toContain('Nothing from this device since startup. It has not been asked yet.');
    expect(cards[1]!.textContent).toContain(`Last report on record: ${clock('2026-07-19T00:49:15.787079Z')}.`);
    expect(cards[1]!.textContent).not.toContain('no answer');
    const paths = Array.from(container.querySelectorAll('svg path')).map((p) => p.getAttribute('d'));
    expect(paths).toContain(RECOVERY_GLYPHS.notResponding.d);
    expect(paths).toContain(RECOVERY_GLYPHS.unasked.d);
    const pills = Array.from(container.querySelectorAll('[class*="pill"]'));
    expect(pills.some((p) => /_error_/.test(p.className))).toBe(true);
    expect(pills.some((p) => /_unknown_/.test(p.className))).toBe(true);
  });
  it('the Reporting row carries the fallback contract sentence beneath its line; a dark row carries no contract sentence', () => {
    const up = render(<RecoveryCard row={plugBack} name="Kitchen Plug" now={NOW} />).container.textContent ?? '';
    expect(up).toContain('Reporting');
    expect(up).toContain('Last report just now.');
    expect(up).toContain('If this device goes quiet for a while, it is asked. How long is not shown here yet.');
    cleanup();
    const down = render(<RecoveryCard row={plugDark} name="Kitchen Plug" now={NOW} />).container.textContent ?? '';
    expect(down).not.toContain('If this device goes quiet');
  });
  it('the dotted clock (R4 dark) draws its dashed circle as a second path — the shape half of the law', () => {
    const sensor: EntitySummary = { ...plugDark, availabilityReason: 'silence_timeout' };
    const { container } = render(<RecoveryCard row={sensor} name="Hallway Sensor" now={NOW} />);
    const paths = container.querySelectorAll('svg path');
    expect(paths.length).toBe(2);
    expect(paths[1]!.getAttribute('stroke-dasharray')).toBeTruthy();
    expect(container.textContent).toContain(`Hallway Sensor: Not responding since ${clock(SEEN_OLD)}.`);
    expect(container.textContent).toContain('Nothing has arrived for longer than this device usually goes. It is never asked.');
  });
  it('no act in this build (row 3 — Q3 (b)): the card renders no button', () => {
    const { container } = render(<RecoveryCard row={plugDark} name="Kitchen Plug" now={NOW} />);
    expect(container.querySelector('button')).toBeNull();
    expect(container.textContent).not.toContain('Open a window');
  });
});

describe('RecoveryCard — L2, one expand away', () => {
  it('the full card has a <details> whose <summary> reads "Show details"; L2 lists the absolute instants ("last heard" vs "last report" said), the reason verbatim and the signal', () => {
    const { container } = render(<RecoveryCard row={plugDark} name="Kitchen Plug" now={NOW} />);
    const details = container.querySelector('details')!;
    expect(details).toBeTruthy();
    expect(details.querySelector('summary')!.textContent).toBe('Show details');
    const items = Array.from(details.querySelectorAll('li')).map((li) => li.textContent);
    expect(items).toEqual([
      `Last report: ${clock(REPORTED)}`,
      `Last heard: ${clock(SEEN_OLD)}`,
      'Recorded reason: ping_timeout',
      `Signal at the last frame: LQI 182, -61 dBm, at ${clock(SEEN_OLD)}.`,
    ]);
  });
  it('a present-null instant / reason reads "Not recorded." in L2; the signal row is absent when link is null', () => {
    const { container } = render(<RecoveryCard row={lampUnasked} name="Porch Lamp" now={NOW} />);
    const items = Array.from(container.querySelectorAll('details li')).map((li) => li.textContent);
    expect(items).toEqual([`Last report: ${clock('2026-07-19T00:49:15.787079Z')}`, 'Last heard: Not recorded.', 'Recorded reason: Not recorded.']);
  });
  it('dense (the device row) renders L1 only — no <details>, no contract sentence', () => {
    const { container } = render(<RecoveryCard row={plugBack} name="Kitchen Plug" dense now={NOW} />);
    expect(container.querySelector('details')).toBeNull();
    expect(container.textContent).toContain('Reporting');
    expect(container.textContent).toContain('Last report just now.');
    expect(container.textContent).not.toContain('If this device goes quiet');
  });
});

describe('RecoveryCard — the polite status region', () => {
  it('is present and silent on first render; a state change on a later read (the rejoin — the row flips by its own frame) is announced ONCE with the a11y sentence', () => {
    const { container, rerender } = render(<RecoveryCard row={plugDark} name="Kitchen Plug" now={NOW} />);
    const region = container.querySelector('[role="status"]')!;
    expect(region).toBeTruthy();
    expect(region.getAttribute('aria-live')).toBe('polite');
    expect(region.textContent).toBe('');
    rerender(<RecoveryCard row={plugBack} name="Kitchen Plug" now={NOW} />);
    expect(container.querySelector('[role="status"]')!.textContent).toBe('Kitchen Plug: Reporting.');
    rerender(<RecoveryCard row={plugBack} name="Kitchen Plug" now={NOW} />);
    expect(container.querySelector('[role="status"]')!.textContent).toBe(''); // once, not on every read
  });
});

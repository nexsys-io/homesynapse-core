/*
 * The "mom test" in code. These assertions lock the plain-language rules: a
 * stranger reads the output and is right. If someone makes the copy more technical,
 * these fail.
 */
import { describe, it, expect } from 'vitest';
import { causalSentence, labelFor, outcomeMeta, originMeta, runStatusMeta, timeAgo, verdictMeta } from './format';
import { causalChains } from './api/mock/mockData';
import { BRAND } from './i18n';

describe('plain-language formatting', () => {
  it('humanizes entity ids into readable names', () => {
    expect(labelFor('ent_hallway_light')).toBe('Hallway Light');
    expect(labelFor('ent_livingroom_lamp')).toBe('Livingroom Lamp');
  });

  it('writes a device-backward causal sentence a stranger understands', () => {
    const s = causalSentence(causalChains['run_eh_001']!);
    expect(s).toMatch(/^Hallway Light turned on because Hallway Motion detected motion at /);
    // No index paths, no internal jargon.
    expect(s).not.toMatch(/conditions\/\d/);
    expect(s.split(' ').length).toBeLessThanOrEqual(20);
  });

  it('tells the honest command-outcome truth', () => {
    expect(outcomeMeta('CONFIRMED')).toMatchObject({ tone: 'ok' });
    expect(outcomeMeta('UNCONFIRMED')).toMatchObject({ tone: 'warn', label: 'Sent, not confirmed' });
    expect(outcomeMeta('FAILED')).toMatchObject({ tone: 'error' });
  });

  it('never leaves origin a silent blank — UNKNOWN is an honest value', () => {
    expect(originMeta('UNKNOWN').label).toBe('Unknown');
    expect(originMeta('EXTERNAL').phrase).toBe(`outside ${BRAND.productName}`);
  });

  it('maps the three-way non-firing verdict to plain language', () => {
    expect(verdictMeta('CONDITION_NOT_MET').label).toMatch(/condition/i);
    expect(verdictMeta('NEVER_TRIGGERED').label).toMatch(/nothing/i);
    expect(verdictMeta('ACTED_BUT_UNCONFIRMED').label).toMatch(/never confirmed/i);
    expect(verdictMeta('DISABLED').label).toMatch(/off/i);
  });

  it('renders relative time in words', () => {
    const now = Date.parse('2026-06-26T12:00:00Z');
    expect(timeAgo(new Date(now - 3000).toISOString(), now)).toBe('just now');
    expect(timeAgo(new Date(now - 3 * 60_000).toISOString(), now)).toBe('3 min ago');
    expect(timeAgo(null, now)).toBe('never');
  });

  it('labels run status plainly', () => {
    expect(runStatusMeta('COMPLETED').label).toBe('Completed');
    expect(runStatusMeta('SKIPPED').tone).toBe('unknown');
  });
});

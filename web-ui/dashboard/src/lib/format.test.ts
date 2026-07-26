/*
 * The "mom test" in code. These assertions lock the plain-language rules: a
 * stranger reads the output and is right. If someone makes the copy more technical,
 * these fail.
 */
import { describe, it, expect } from 'vitest';
import {
  availabilityEvidence,
  availabilityMeta,
  brightnessDisplay,
  causalSentence,
  labelFor,
  NULL_NAME_NOTE,
  outcomeMeta,
  originMeta,
  runName,
  runStatusMeta,
  timeAgo,
  verdictMeta,
} from './format';
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

describe('availability is evidence-with-age — never the flag alone', () => {
  const now = Date.parse('2026-07-19T12:00:00Z');
  const ago = (min: number) => new Date(now - min * 60_000).toISOString();

  it('pairs AVAILABLE with when the device was last heard from (the rehydrated-flag exhibit)', () => {
    const s = availabilityEvidence('AVAILABLE', ago(2 * 24 * 60), now);
    expect(s).toMatch(/^Available — last heard from 2 days ago\./);
  });

  it('renders offline with its evidence age and the recheck cadence, calmly', () => {
    const s = availabilityEvidence('UNAVAILABLE', ago(9), now);
    expect(s).toMatch(/Offline — last heard from 9 min ago/);
    expect(s).toMatch(/rechecked every few minutes/);
  });

  it('renders honest UNKNOWN after a restart as normal, not a fault', () => {
    const s = availabilityEvidence('UNKNOWN', null, now);
    expect(s).toMatch(/waiting for the device’s first report/i);
    expect(s).toMatch(/normal/i);
    expect(s).not.toMatch(/error|wrong|fail/i);
  });

  /* G2 honesty locks (Rosonway §5.3, measured: AVAILABLE with staleAfter null
   * and hours-old lastReported is lawful): the flag copy must never claim live
   * radio contact, and the honest UNKNOWN state must never read as a fault. */
  it('AVAILABLE is presented as a conclusion from reports — NEVER a live-contact claim', () => {
    const m = availabilityMeta('AVAILABLE');
    expect(m.help).toMatch(/not a live connection test/i);
    expect(m.help).toMatch(/last concluded|concluded from/i);
    expect(m.help).not.toMatch(/\bonline\b|connected right now|live contact/i);
  });

  it('UNAVAILABLE states its evidence and the recheck cadence, calmly', () => {
    const m = availabilityMeta('UNAVAILABLE');
    expect(m.help).toMatch(/evidence/i);
    expect(m.help).toMatch(/rechecked every few minutes/i);
  });

  it('UNKNOWN-at-boot is labeled honestly and reads as normal, never a fault', () => {
    const m = availabilityMeta('UNKNOWN');
    expect(m.label).toBe('Not determined yet');
    expect(m.help).toMatch(/honest|normal/i);
    expect(m.help).not.toMatch(/error|wrong|fail/i);
  });
});

describe('the null-name run class (prior-instance runs) renders honestly', () => {
  it('never invents a name for a null', () => {
    expect(runName(null)).toBe('An earlier automation');
    expect(runName('Evening Hallway Light')).toBe('Evening Hallway Light');
  });

  it('explains WHY the name is missing, calmly (no blame, no alarm)', () => {
    expect(NULL_NAME_NOTE).toMatch(/earlier version/i);
    expect(NULL_NAME_NOTE).toMatch(/preserved/i);
  });
});

describe('brightness percent comes from the DERIVED key, never a client rescale', () => {
  it('prefers the hub-derived brightness_percent', () => {
    const b = brightnessDisplay({
      brightness: { t: 'NUMBER', v: 209 },
      brightness_percent: { t: 'PERCENT', v: 82 },
    });
    expect(b).toEqual({ key: 'brightness_percent', text: '82%' });
  });

  it('shows the canonical level honestly when no derived percent is present — NOT 209/254 rescaled', () => {
    const b = brightnessDisplay({ brightness: { t: 'NUMBER', v: 209 } });
    expect(b).toEqual({ key: 'brightness', text: 'level 209 of 254' });
    expect(b!.text).not.toContain('%');
  });

  it('returns null when the entity has no brightness at all', () => {
    expect(brightnessDisplay({ power: { t: 'BOOL', v: true } })).toBeNull();
  });
});

describe('the silent-skip run sentence (do-nothing runs never read as success)', () => {
  it('says up front that nothing was changed', () => {
    const chain = structuredClone(causalChains['run_eh_001']!);
    chain.actions = [];
    chain.outcome = { status: 'COMPLETED', reason: null, durationMs: 41, actionCount: 2, commandCount: 0 };
    const s = causalSentence(chain);
    expect(s).toMatch(/nothing was changed\.$/);
    expect(s).not.toMatch(/turned on/);
  });
});

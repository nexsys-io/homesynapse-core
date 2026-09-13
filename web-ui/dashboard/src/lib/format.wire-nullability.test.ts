/*
 * NEW-3 corpus sweep + NEW-6 riders — the formatting layer vs the live wire (RED-FIRST).
 * ---------------------------------------------------------------------------
 * Three classes pinned here:
 *
 * 1. THE SECONDS-AS-MS MISREAD (the §4e/STATE-DIALECT law): the live /state
 *    wire serves instants as fractional epoch-SECOND numbers today. At baseline
 *    clockTime() fed such a value to `new Date(number)` — epoch-MILLISECOND
 *    semantics — landing in 1970 and formatting as a plausible clock time
 *    ("Last reported 9:40 AM" beside prose "last heard from —": DX-20, the
 *    device-detail self-contradiction). The law: NEVER parse epoch-seconds as
 *    milliseconds; a non-ISO-string instant renders honest absence, both
 *    surfaces from ONE parse, so the contradiction is dead at the class.
 *    (Full contract-shape /state consumption is FE-LIVE-V112 item (h) — a
 *    separate charter; this lane only kills the misread + the contradiction.)
 *
 * 2. DATE-QUALIFIED STAMPS (NEW-6): a clock-only stamp on a report that can be
 *    >24 h old reads as today — a false claim. clockTimeWithDate() carries the
 *    date whenever the instant is not from today.
 *
 * 3. CLOSED-SWITCH-OVER-A-WIRE-STRING (the §4a open-vocabulary law, the
 *    runStatusMeta precedent): verdictMeta/originMeta returned undefined for
 *    an unrecognized value, so `.tone` crashed the view. Every such value now
 *    renders in the honest register — never a crash, never invented meaning.
 *
 * House law: written RED before the fix (the preservation pins are disclosed
 * green-at-baseline; the clockTimeWithDate pins are red-by-missing-export at
 * baseline — the function did not exist).
 */
import { describe, it, expect } from 'vitest';
import * as fmt from './format';

/** The live /state dialect: a fractional epoch-second instant, as a runtime
 *  number behind the string type (the observed wire, 2026-07-27 WCAP). */
const EPOCH_SECONDS = 1755321600.123456 as unknown as string;

describe('the seconds-as-ms misread is dead (never parse a non-ISO-string instant)', () => {
  it('clockTime() renders honest absence for a runtime-number instant — never a 1970 clock time', () => {
    expect(fmt.clockTime(EPOCH_SECONDS)).toBe('—');
  });

  it('clockTime() still renders a real ISO instant (preservation pin)', () => {
    expect(fmt.clockTime('2026-08-16T05:22:35Z')).toMatch(/\d{1,2}:\d{2}/);
  });

  it('timeAgo() behavior for strings is untouched, and a runtime number stays honest absence (preservation pins — DX-22 mechanism untouched)', () => {
    expect(fmt.timeAgo(new Date().toISOString())).toBe('just now');
    expect(fmt.timeAgo(null)).toBe('never');
    expect(fmt.timeAgo(EPOCH_SECONDS)).toBe('—');
  });
});

describe('clockTimeWithDate — clock-only stamps gain their date past 24 h (NEW-6)', () => {
  it('a stamp from today renders as clock time only', () => {
    const now = Date.parse('2026-08-17T18:00:00');
    expect(fmt.clockTimeWithDate('2026-08-17T09:40:00', now)).toMatch(/^\d{1,2}:\d{2}[^o]*$/);
    expect(fmt.clockTimeWithDate('2026-08-17T09:40:00', now)).not.toMatch(/ on /);
  });

  it('a stamp from another day carries its date — never a bare clock time', () => {
    const now = Date.parse('2026-08-17T18:00:00');
    const s = fmt.clockTimeWithDate('2026-08-16T09:40:00', now);
    expect(s).toMatch(/ on /);
    expect(s).toMatch(/Aug/);
  });

  it('a stamp from another year carries the year', () => {
    const now = Date.parse('2026-08-17T18:00:00');
    expect(fmt.clockTimeWithDate('2025-08-16T09:40:00', now)).toMatch(/2025/);
  });

  it('null / unparseable / runtime-number all render honest absence', () => {
    expect(fmt.clockTimeWithDate(null)).toBe('—');
    expect(fmt.clockTimeWithDate('not-a-time')).toBe('—');
    expect(fmt.clockTimeWithDate(EPOCH_SECONDS)).toBe('—');
  });
});

describe('availabilityEvidence — the prose and the row can no longer contradict (DX-20)', () => {
  it('a readable lastReported renders the age', () => {
    const now = Date.parse('2026-08-17T18:00:00Z');
    const s = fmt.availabilityEvidence('UNAVAILABLE', '2026-08-13T18:00:00Z', now);
    expect(s).toContain('last heard from 4 days ago');
  });

  it('an UNREADABLE lastReported never renders "last heard from —" — it says the stamp is ON RECORD but unreadable', () => {
    // FE-HONEST-1 (§10-G): the store HOLDS the row — "not recorded" was itself a
    // false claim on the unreadable-dialect path. The register is now store-truth:
    // on record, but not readable by this dashboard yet.
    const s = fmt.availabilityEvidence('UNAVAILABLE', EPOCH_SECONDS);
    expect(s).not.toContain('—.');
    expect(s).not.toContain('last heard from —');
    expect(s.toLowerCase()).toContain('on record');
    expect(s.toLowerCase()).toContain('cannot read');
    expect(s.toLowerCase()).not.toContain('not recorded');
  });

  it('the unreadable-stamp sentence is distinct from the honest no-report-yet sentence', () => {
    const noReport = fmt.availabilityEvidence('AVAILABLE', null);
    const unreadable = fmt.availabilityEvidence('AVAILABLE', EPOCH_SECONDS);
    expect(noReport).toContain('no report received yet');
    expect(unreadable).not.toContain('no report received yet');
  });
});

describe('open string vocabularies render honestly (the closed-switch class, §4a law)', () => {
  it('verdictMeta: an unrecognized verdict lands in the honest register — never undefined', () => {
    const m = fmt.verdictMeta('SOMETHING_NEW' as never);
    expect(m).toBeTruthy();
    expect(m.label).toBe('Recorded as "SOMETHING_NEW"');
    expect(m.tone).toBe('unknown');
  });

  it('verdictMeta: a null/absent verdict is said plainly', () => {
    const m = fmt.verdictMeta(null as never);
    expect(m.tone).toBe('unknown');
    expect(m.label.toLowerCase()).toContain('not recorded');
  });

  it('verdictMeta: the four frozen verdicts are untouched (preservation pins)', () => {
    expect(fmt.verdictMeta('NEVER_TRIGGERED').label).toBe('Nothing set it off');
    expect(fmt.verdictMeta('CONDITION_NOT_MET').tone).toBe('warn');
    expect(fmt.verdictMeta('ACTED_BUT_UNCONFIRMED').tone).toBe('warn');
    expect(fmt.verdictMeta('DISABLED').tone).toBe('unknown');
  });

  it('originMeta: an unrecognized origin lands in the honest register — never undefined', () => {
    const m = fmt.originMeta('SCHEDULER' as never);
    expect(m).toBeTruthy();
    expect(m.label).toBe('Recorded as "SCHEDULER"');
    expect(m.tone).toBe('unknown');
  });

  it('originMeta: the five frozen origins are untouched (preservation pins)', () => {
    expect(fmt.originMeta('AUTOMATION').label).toBe('Automation');
    expect(fmt.originMeta('UNKNOWN').label).toBe('Unknown');
  });

  it('runStatusMeta already carried the honest fallback (the in-repo precedent this sweep generalizes)', () => {
    expect(fmt.runStatusMeta('EXOTIC_STATUS').label).toBe('Recorded as "EXOTIC_STATUS"');
    expect(fmt.runStatusMeta(null).tone).toBe('unknown');
  });

  // The command-outcome-map row (an unrecognized ACTION outcome → honest can't-know) moved to
  // verdicts.test.ts (HERO-1c C3): the verdict layer's not-recorded arm carries it now.

  it('availabilityMeta: an unrecognized availability renders honestly — never undefined on the trust surface', () => {
    const m = fmt.availabilityMeta('DEGRADED' as never);
    expect(m).toBeTruthy();
    expect(m.label).toBe('Recorded as "DEGRADED"');
    expect(m.tone).toBe('unknown');
    expect(fmt.availabilityMeta('AVAILABLE').label).toBe('Available'); // preservation pin
  });

  it('availabilityEvidence: an off-vocabulary status still gets an honest evidence sentence — never "undefined"', () => {
    const s = fmt.availabilityEvidence('DEGRADED' as never, null);
    expect(s).not.toContain('undefined');
    expect(s).toContain('Status recorded as "DEGRADED"');
  });

  it('healthMeta: an unrecognized integration health renders honestly', () => {
    expect(fmt.healthMeta('FLAPPING' as never).label).toBe('Recorded as "FLAPPING"');
    expect(fmt.healthMeta('HEALTHY').tone).toBe('ok'); // preservation pin
  });
});

/*
 * HERO-U2b R2 (2026-10-09) — `recoveryRow(row, stage)`: the SPEC §3 state table, one assertion per cell of the
 * S1 and S2 columns (6 rows × 2 = 12) plus every null arm; the S3 column is `test.todo` by key behind
 * AVAIL-API-1 (design/recovery-card-v1/SPEC.md §3; the keys FIELDS.md §3 names). RED at HEAD (2b4be09):
 * `src/lib/recovery.ts` does not exist — the import fails and every row below is red.
 *
 * THE HONESTY LAW binds every sentence: a value the wire did not carry is never shown as if it had; a null
 * renders as its keyed sentence, never a blank, "null", an invented verb or an invented time. "Quiet (asked,
 * answered)" renders ONLY on the ping_success edge (lastSeenAt AFTER lastReported); at steady state the row
 * is R1's form with the age. The fifth state at S2 fires on UNKNOWN, or on UNAVAILABLE with BOTH
 * availabilityReason and lastSeenAt null (the version-1 event — the capture's data[0]); never from a null
 * reason alone (N1 — that is S3's lastProbeAt).
 *
 * Instants: labels' `since {time}` and every L2 instant are clockTimeWithDate (date-qualified past 24 h);
 * R1's line is the age (timeAgo) — the age beside the sentence is the honest tell. The expectations below
 * are computed through the SAME format functions the card uses (one parse — the DX-20 law).
 *
 * HERO-U3 (2026-10-11) — the S3 column on the LIVE record (AVAIL-API-1 T-B at core 2a54145; v1.1.7): the six `test.todo`
 * rows are real tests, re-keyed to the keys the record carries (U2c), one assertion per filled S3 cell of SPEC §3 plus every
 * null arm, the not-LIVE rule, the contract sentence per class (§4), L2's record lines and the honesty asserts (the card
 * renders the core's bound and computes none). The S1 / S2 tests above are unchanged — the preservation rows.
 */
import { describe, it, expect } from 'vitest';
import { contractSentence, recoveryRow, stageOf, type RecoveryDecision } from './recovery';
import type { EntitySummary } from './api/contract';
import { clockTimeWithDate, timeAgo } from './format';
import { t } from './i18n';
import { RECOVERY_GLYPHS } from './verdicts';
import { WIRE_20261009_J1_ENTITY_ROW as REAL_ROW } from './api/fixtures/wire-2026-10-09-entities-j1-row';
import { resolveScenario } from './api/mock/scenarios';
import RECOVERY_SOURCE from './recovery.ts?raw';
import {
  EMITTER_20261011_V117_META as EMITTER_META,
  EMITTER_20261011_V117_NOT_LIVE_META as EMITTER_NOT_LIVE_META,
  EMITTER_20261011_V117_NOT_LIVE_ROW as EMITTER_NOT_LIVE_ROW,
  EMITTER_20261011_V117_OVERDUE_META as EMITTER_OVERDUE_META,
  EMITTER_20261011_V117_OVERDUE_ROW as EMITTER_OVERDUE_ROW,
  EMITTER_20261011_V117_ROW as EMITTER_ROW,
} from './api/fixtures/emitter-2026-10-11-entities-v117-row';

const NOW = Date.parse('2026-10-09T19:30:00.000Z');
const minAgo = (m: number) => new Date(NOW - m * 60_000).toISOString();
const REPORTED = minAgo(14);
const SEEN = minAgo(3); // AFTER the last report — the ping_success edge
const SEEN_OLD = minAgo(2 * 24 * 60); // BEFORE the last report — a transition two days back
const clock = (iso: string) => clockTimeWithDate(iso, NOW);
const FALLBACK = t('recovery.contract.fallback');

/** S1 rows: the four mirror keys only — the three J1 keys ABSENT (a pre-J1 hub). */
const s1 = (availability: string, lastReported: string | null = REPORTED): EntitySummary =>
  ({ entityId: '01KX1PB9AAB4VB3E10BD477TV3', name: 'Kitchen Plug', availability, stale: false, deviceId: '01KX1PB9A5931A8G0F0X03QXT2', lastReported }) as EntitySummary;
/** S2 rows: the three J1 keys PRESENT (a value or null, never absent). */
const s2 = (availability: string, availabilityReason: string | null, lastSeenAt: string | null, lastReported: string | null = REPORTED): EntitySummary =>
  ({ ...s1(availability, lastReported), availabilityReason, lastSeenAt, link: lastSeenAt ? { lqi: 182, rssiDbm: -61, at: lastSeenAt } : null }) as EntitySummary;
const row = (r: EntitySummary) => recoveryRow(r, stageOf(r), NOW);
const neverLies = (d: RecoveryDecision) => {
  for (const s of [d.label, d.line, d.line2 ?? '', d.beneath ?? '', ...d.l2.map((x) => x.text)]) {
    expect(s).not.toMatch(/\b(null|undefined|NaN)\b|Invalid Date|1970|since —|since $/);
  }
};

describe('stageOf — the tri-state decides the column: ABSENT = S1 (a pre-J1 hub), PRESENT (null or value) = S2', () => {
  it('S1 when none of the three keys is on the row; S2 when any is present, null included', () => {
    expect(stageOf(s1('AVAILABLE'))).toBe('S1');
    expect(stageOf(s2('AVAILABLE', null, null))).toBe('S2');
    expect(stageOf({ ...s1('AVAILABLE'), availabilityReason: null } as EntitySummary)).toBe('S2');
    expect(stageOf(REAL_ROW)).toBe('S2');
  });
});

describe('SPEC §3 — the S1 column (today: availability · stale · deviceId · lastReported)', () => {
  it('R1 S1 — AVAILABLE → "Reporting" · "Last report {age}." · the fallback contract sentence · ok · the pulse', () => {
    const d = row(s1('AVAILABLE'));
    expect(d.key).toBe('reporting');
    expect(d.label).toBe('Reporting');
    expect(d.line).toBe(`Last report ${timeAgo(REPORTED, NOW)}.`);
    expect(d.line).toBe('Last report 14 min ago.');
    expect(d.beneath).toBe(FALLBACK);
    expect(d.tone).toBe('ok');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.reporting);
    expect(d.dark).toBe(false);
    neverLies(d);
  });
  it('R2 S1 — NOT SAYABLE: the wire cannot say "asked" or "answered"; the card shows R1\'s form with the age visible', () => {
    const d = row(s1('AVAILABLE'));
    expect(d.key).toBe('reporting');
    expect(d.label).not.toContain('Quiet');
    expect(`${d.label} ${d.line}`).not.toMatch(/asked|answered/);
    expect(d.line).toContain('14 min ago');
  });
  it('R3 S1 — UNAVAILABLE → the DEGRADED form: "Not responding since {t}" WITHOUT the parenthesis · "Last report {t}. Whether it has been asked since is not shown here yet." · error · two outgoing arrows', () => {
    const d = row(s1('UNAVAILABLE'));
    expect(d.key).toBe('notResponding');
    expect(d.form).toBe('bare');
    expect(d.label).toBe(`Not responding since ${clock(REPORTED)}`);
    expect(d.label).not.toContain('asked');
    expect(d.line).toBe(`Last report ${clock(REPORTED)}. Whether it has been asked since is not shown here yet.`);
    expect(d.line).not.toContain('not recorded'); // "not shown here" is the honest form — the hub holds it
    expect(d.tone).toBe('error');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.notResponding);
    expect(d.dark).toBe(true);
    expect(d.since).toEqual({ from: 'lastReported', iso: REPORTED });
    neverLies(d);
  });
  it('R4 S1 — the class is not on the wire: AVAILABLE renders R1\'s form, UNAVAILABLE renders R3\'s degraded form; the passive sentence is NOT sayable', () => {
    const up = row(s1('AVAILABLE'));
    const down = row(s1('UNAVAILABLE'));
    expect(up.key).toBe('reporting');
    expect(down.key).toBe('notResponding');
    expect(down.form).toBe('bare');
    for (const d of [up, down]) expect(`${d.label} ${d.line} ${d.beneath ?? ''}`).not.toContain('never asked');
    expect(up.beneath).toBe(FALLBACK);
  });
  it('R5 S1 — UNKNOWN → "Not heard from since startup (not asked)" · its line · "Last report on record: {t}." · unknown (purple) · the question mark; UNAVAILABLE at S1 is R3\'s degraded form, not the fifth', () => {
    const d = row(s1('UNKNOWN'));
    expect(d.key).toBe('unasked');
    expect(d.label).toBe('Not heard from since startup (not asked)');
    expect(d.line).toBe('Nothing from this device since startup. It has not been asked yet.');
    expect(d.line2).toBe(`Last report on record: ${clock(REPORTED)}.`);
    expect(d.tone).toBe('unknown');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.unasked);
    expect(d.dark).toBe(true);
    expect(row(s1('UNAVAILABLE')).key).toBe('notResponding'); // indistinguishable at S1 — the line admits it
    neverLies(d);
  });
  it('open vocabulary S1 — any other availability string → "Recorded as “{value}”" · "This status is not one the dashboard knows yet. Shown as recorded." · unknown · the dotted line — never success, never an alarm', () => {
    const d = row(s1('DEGRADED'));
    expect(d.key).toBe('unrecognized');
    expect(d.label).toBe('Recorded as “DEGRADED”');
    expect(d.line).toBe('This status is not one the dashboard knows yet. Shown as recorded.');
    expect(d.tone).toBe('unknown');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.unrecognized);
    expect(d.dark).toBe(false);
    neverLies(d);
  });
});

describe('SPEC §3 — the S2 column (after the mirror bump: + availabilityReason · lastSeenAt · link)', () => {
  it('R1 S2 — AVAILABLE with frame_received / first_contact / null → the same "Reporting" decision; the reason changes nothing and shows in L2 verbatim', () => {
    for (const reason of ['frame_received', 'first_contact', null]) {
      const d = row(s2('AVAILABLE', reason, reason ? SEEN_OLD : null));
      expect(d.key, String(reason)).toBe('reporting');
      expect(d.label).toBe('Reporting');
      expect(d.line).toBe('Last report 14 min ago.');
      expect(d.beneath).toBe(FALLBACK);
      expect(d.l2.find((x) => x.key === 'reason')?.text).toBe(reason ? `Recorded reason: ${reason}` : 'Recorded reason: Not recorded.');
      neverLies(d);
    }
    const real = recoveryRow(REAL_ROW, 'S2', Date.parse('2026-10-10T00:12:00Z'));
    expect(real.key).toBe('reporting'); // the real df2bc62 row: frame_received, lastSeenAt OLDER than lastReported
    expect(real.l2.find((x) => x.key === 'signal')?.text).toBe(`Signal at the last frame: LQI 248, -38 dBm, at ${clockTimeWithDate(REAL_ROW.lastSeenAt!, Date.parse('2026-10-10T00:12:00Z'))}.`);
  });
  it('R2 S2 — SAYABLE ON ONE EDGE ONLY: AVAILABLE ∧ ping_success ∧ lastSeenAt AFTER lastReported → "Quiet since {t} (asked, answered)" · the edge line · warn · two opposed arrows; the steady state (reply before the report) is R1', () => {
    const edge = row(s2('AVAILABLE', 'ping_success', SEEN));
    expect(edge.key).toBe('quiet');
    expect(edge.form).toBe('edge');
    expect(edge.label).toBe(`Quiet since ${clock(REPORTED)} (asked, answered)`);
    expect(edge.line).toBe(`No report since ${clock(REPORTED)}. It answered when asked at ${clock(SEEN)}.`);
    expect(edge.beneath).toBe(FALLBACK);
    expect(edge.tone).toBe('warn');
    expect(edge.glyph).toBe(RECOVERY_GLYPHS.quiet);
    expect(edge.dark).toBe(false);
    expect(edge.since).toEqual({ from: 'lastReported', iso: REPORTED });
    neverLies(edge);
    const steady = row(s2('AVAILABLE', 'ping_success', SEEN_OLD));
    expect(steady.key).toBe('reporting'); // a reply while AVAILABLE publishes nothing — no "asked at" is ever fabricated
    expect(steady.label).toBe('Reporting');
  });
  it('R3 S2 — UNAVAILABLE ∧ ping_timeout → the FULL label "since {lastSeenAt}" · "Asked twice; nothing came back. Last heard {t}." · error; `leave` → the bare label with the left line (nobody asked it)', () => {
    const d = row(s2('UNAVAILABLE', 'ping_timeout', SEEN_OLD));
    expect(d.key).toBe('notResponding');
    expect(d.form).toBe('full');
    expect(d.label).toBe(`Not responding since ${clock(SEEN_OLD)} (asked twice, no answer)`);
    expect(d.line).toBe(`Asked twice; nothing came back. Last heard ${clock(SEEN_OLD)}.`);
    expect(d.tone).toBe('error');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.notResponding);
    expect(d.dark).toBe(true);
    expect(d.since).toEqual({ from: 'lastSeenAt', iso: SEEN_OLD });
    neverLies(d);
    const left = row(s2('UNAVAILABLE', 'leave', SEEN_OLD));
    expect(left.key).toBe('notResponding');
    expect(left.form).toBe('left');
    expect(left.label).toBe(`Not responding since ${clock(SEEN_OLD)}`);
    expect(left.label).not.toContain('asked');
    expect(left.line).toBe(`This device left the network. Last heard ${clock(SEEN_OLD)}.`);
    expect(left.dark).toBe(true);
    neverLies(left);
  });
  it('R4 S2 — UNAVAILABLE ∧ silence_timeout → "Not responding since {t}" (no parenthesis) · "Nothing has arrived for longer than this device usually goes. It is never asked." · error · the dotted clock; AVAILABLE is still R1', () => {
    const d = row(s2('UNAVAILABLE', 'silence_timeout', SEEN_OLD));
    expect(d.key).toBe('passiveNotResponding');
    expect(d.label).toBe(`Not responding since ${clock(SEEN_OLD)}`);
    expect(d.label).not.toContain('asked');
    expect(d.line).toBe('Nothing has arrived for longer than this device usually goes. It is never asked.');
    expect(d.tone).toBe('error');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.passiveNotResponding);
    expect(d.glyph.dashed).toBeTruthy(); // the dotted clock — distinct from R3's arrows by shape
    expect(d.dark).toBe(true);
    expect(row(s2('AVAILABLE', 'frame_received', SEEN_OLD)).key).toBe('reporting'); // a frame reason does not name the class
    neverLies(d);
  });
  it('R5 S2 — UNAVAILABLE ∧ reason null ∧ lastSeenAt null → the fifth state (the seeded-dark version-1 event; the capture\'s data[0]); UNKNOWN → the same; the verb is "not asked", never "no answer"', () => {
    const hue = row({ entityId: '01KX1PA4HSJ581GASYB7DHE40F', availability: 'UNAVAILABLE', stale: false, deviceId: '01KX1PA4GRZHY2GD37B5CFVQHY', lastReported: '2026-07-19T00:49:15.787079Z', availabilityReason: null, lastSeenAt: null, link: null });
    expect(hue.key).toBe('unasked');
    expect(hue.label).toBe('Not heard from since startup (not asked)');
    expect(hue.label).not.toContain('no answer');
    expect(hue.line).toBe('Nothing from this device since startup. It has not been asked yet.');
    expect(hue.line2).toBe(`Last report on record: ${clock('2026-07-19T00:49:15.787079Z')}.`);
    expect(hue.tone).toBe('unknown');
    expect(hue.glyph).toBe(RECOVERY_GLYPHS.unasked);
    expect(hue.glyph).not.toBe(RECOVERY_GLYPHS.notResponding); // a question mark beside two arrows
    expect(hue.dark).toBe(true);
    expect(row(s2('UNKNOWN', null, null)).key).toBe('unasked');
    // N1: a null reason ALONE does not make the fifth state — a seeded-dark device with a recorded lastSeenAt is R3's degraded form
    const seeded = row(s2('UNAVAILABLE', null, SEEN_OLD));
    expect(seeded.key).toBe('notResponding');
    expect(seeded.form).toBe('bare');
    expect(seeded.label).toBe(`Not responding since ${clock(SEEN_OLD)}`);
    neverLies(hue);
  });
  it('open vocabulary S2 — an unknown availability → "Recorded as …"; an unknown REASON token changes no row and renders in L2 verbatim (AVAILABLE → R1; UNAVAILABLE → R3\'s degraded form)', () => {
    expect(row(s2('DEGRADED', 'frame_received', SEEN_OLD)).key).toBe('unrecognized');
    const up = row(s2('AVAILABLE', 'some_future_reason', SEEN_OLD));
    expect(up.key).toBe('reporting');
    expect(up.l2.find((x) => x.key === 'reason')?.text).toBe('Recorded reason: some_future_reason');
    const down = row(s2('UNAVAILABLE', 'some_future_reason', SEEN_OLD));
    expect(down.key).toBe('notResponding');
    expect(down.form).toBe('bare');
    expect(down.label).toBe(`Not responding since ${clock(SEEN_OLD)}`);
    expect(down.l2.find((x) => x.key === 'reason')?.text).toBe('Recorded reason: some_future_reason');
    // never .toUpperCase()'d to match an enum: the token is shown as the wire wrote it
    expect(row(s2('UNAVAILABLE', 'PING_TIMEOUT', SEEN_OLD)).form).toBe('bare');
  });
});

describe('every null arm renders its keyed sentence — never a blank, "null", an invented verb or an invented time', () => {
  it('R1 · lastReported null → "Reporting" with "No report on record." (a report-less adoption)', () => {
    for (const d of [row(s1('AVAILABLE', null)), row(s2('AVAILABLE', null, null, null))]) {
      expect(d.label).toBe('Reporting');
      expect(d.line).toBe('No report on record.');
      neverLies(d);
    }
  });
  it('R2 edge · lastReported null → not sayable ("since" needs the report) → R1\'s null arm, never "Quiet since —"', () => {
    const d = row(s2('AVAILABLE', 'ping_success', SEEN, null));
    expect(d.key).toBe('reporting');
    expect(d.line).toBe('No report on record.');
    neverLies(d);
  });
  it('R3 S1 · lastReported null → "Not responding" with "No report on record."', () => {
    const d = row(s1('UNAVAILABLE', null));
    expect(d.label).toBe('Not responding');
    expect(d.line).toBe('No report on record.');
    expect(d.since).toBeNull();
    neverLies(d);
  });
  it('R3 S2 ping_timeout · lastSeenAt null → "since {lastReported}" and the line\'s first sentence alone (no "Last heard" claim); both null → the label without a since clause', () => {
    const one = row(s2('UNAVAILABLE', 'ping_timeout', null));
    expect(one.label).toBe(`Not responding since ${clock(REPORTED)} (asked twice, no answer)`);
    expect(one.line).toBe('Asked twice; nothing came back.');
    expect(one.since).toEqual({ from: 'lastReported', iso: REPORTED });
    expect(one.l2.find((x) => x.key === 'lastHeard')?.text).toBe('Last heard: Not recorded.');
    const none = row(s2('UNAVAILABLE', 'ping_timeout', null, null));
    expect(none.label).toBe('Not responding (asked twice, no answer)');
    expect(none.line).toBe('Asked twice; nothing came back.');
    expect(none.since).toBeNull();
    neverLies(one);
    neverLies(none);
  });
  it('R3 S2 leave · both null → "Not responding" with "This device left the network."', () => {
    const d = row(s2('UNAVAILABLE', 'leave', null, null));
    expect(d.label).toBe('Not responding');
    expect(d.line).toBe('This device left the network.');
    neverLies(d);
  });
  it('R4 S2 silence_timeout · both null (the mock\'s bedroom motion) → "Not responding" with the passive line', () => {
    const d = row(s2('UNAVAILABLE', 'silence_timeout', null, null));
    expect(d.key).toBe('passiveNotResponding');
    expect(d.label).toBe('Not responding');
    expect(d.line).toBe('Nothing has arrived for longer than this device usually goes. It is never asked.');
    neverLies(d);
  });
  it('R5 · lastReported null → the second line is "No report on record."', () => {
    for (const d of [row(s1('UNKNOWN', null)), row(s2('UNAVAILABLE', null, null, null))]) {
      expect(d.key).toBe('unasked');
      expect(d.line2).toBe('No report on record.');
      neverLies(d);
    }
  });
  it('L2 · the instants are date-qualified absolutes; a null instant / reason reads "Not recorded."; the signal row exists only when link is on record; S1 shows the last report only', () => {
    const s1l2 = row(s1('UNAVAILABLE')).l2;
    expect(s1l2.map((x) => x.key)).toEqual(['lastReport']);
    expect(s1l2[0]!.text).toBe(`Last report: ${clock(REPORTED)}`);
    const s2l2 = row(s2('UNAVAILABLE', 'ping_timeout', SEEN_OLD)).l2;
    expect(s2l2.map((x) => x.key)).toEqual(['lastReport', 'lastHeard', 'reason', 'signal']);
    expect(s2l2.map((x) => x.text)).toEqual([
      `Last report: ${clock(REPORTED)}`,
      `Last heard: ${clock(SEEN_OLD)}`,
      'Recorded reason: ping_timeout',
      `Signal at the last frame: LQI 182, -61 dBm, at ${clock(SEEN_OLD)}.`,
    ]);
    const nulls = row(s2('UNAVAILABLE', null, null, null)).l2;
    expect(nulls.map((x) => x.text)).toEqual(['Last report: Not recorded.', 'Last heard: Not recorded.', 'Recorded reason: Not recorded.']);
  });
  it('an unparseable instant on record is never a 1970 time — the label falls to the since-less form and L2 says "Not recorded."', () => {
    const d = row(s2('UNAVAILABLE', 'ping_timeout', 'not-a-time', 'also-not'));
    expect(d.label).toBe('Not responding (asked twice, no answer)');
    neverLies(d);
  });
});

/* ---- S3 — the LIVE record (HERO-U3, 2026-10-11; SPEC §3's S3 column; AMD-103 R-B / R-C / R-G; v1.1.7 at core 2a54145).
 * HERO-U2b's six `test.todo` rows are real tests below, re-keyed to what the record carries (U2c): availabilityClass → the
 * contract sentence per class (§4) · reportIntervalSeconds → {reportMinutes} and the passive Quiet (Q4 (a)) ·
 * silenceLimitSeconds → {askMinutes} · lastProbeOutcome → R2's steady state from probeAnsweredAt · lastProbeAt → the RETIRED
 * R5 flip (Q9 (a): a device dark before a restart stays R3) · probeMisses → "twice" reads missesToDark (R-C).
 * RED at HEAD (2a54145): stageOf ignores `meta` (no row reaches S3), recoveryRow reads none of the eleven keys, and
 * contractSentence does not exist. ---- */
const LIVE = { viewPosition: 41, timestamp: new Date(NOW).toISOString(), recoveryViewPosition: 40 };
const NOT_LIVE = { ...LIVE, recoveryViewPosition: null };
/** Every v1.1.7 key PRESENT and null — written out, not derived from the mirror (this file stays runnable at HEAD). */
const NO_RECORD = {
  probeAnsweredAt: null,
  probeWindowStart: null,
  probesAnswered: null,
  probesMissed: null,
  availabilityClass: null,
  reportIntervalSeconds: null,
  silenceLimitSeconds: null,
  missesToDark: null,
  recordIntervalSeconds: null,
  recordDueBy: null,
  recordOverdueSince: null,
};
/** R-C's three contract records as StandardAvailabilityTracker.contractOf declares them (:773–:786). */
const METERED = { availabilityClass: 'mains-metered', reportIntervalSeconds: 600, silenceLimitSeconds: 660, missesToDark: 2, recordIntervalSeconds: 3600 };
const FLOOR = { availabilityClass: 'mains-floor', reportIntervalSeconds: null, silenceLimitSeconds: 60, missesToDark: 2, recordIntervalSeconds: 3600 };
const PASSIVE = { availabilityClass: 'passive', reportIntervalSeconds: null, silenceLimitSeconds: 90_000, missesToDark: 2, recordIntervalSeconds: null };
/** The newest probe_answered: answered `at` minutes ago, its window `span` minutes long, that ONE record's counts. */
const answer = (at: number, span = 60, answered = 55, missed = 1) => ({
  probeAnsweredAt: minAgo(at),
  probeWindowStart: minAgo(at + span),
  probesAnswered: answered,
  probesMissed: missed,
});
/** A v1.1.7 row: an S1/S2 row plus the eleven keys, null unless the record says otherwise. */
const v117 = (r: EntitySummary, record: Record<string, unknown> = {}) => ({ ...r, ...NO_RECORD, ...record }) as EntitySummary;
/** The same row with the eleven keys removed — a v1.1.6 row. */
const v116 = (r: EntitySummary) => Object.fromEntries(Object.entries(r).filter(([k]) => !(k in NO_RECORD))) as unknown as EntitySummary;
const live = (r: EntitySummary) => recoveryRow(r, stageOf(r, LIVE), NOW);
const l2keys = (d: RecoveryDecision) => d.l2.map((x) => x.key);
const l2text = (d: RecoveryDecision, key: string) => d.l2.find((x) => x.key === key)?.text;
/** SPEC §4's sentences with the card's {kind}: the entity reads carry no device class word, so "device" (§7's generic value). */
const SENTENCE = {
  metered: 'This device reports at least every 10 minutes. After 11 minutes of silence it is asked.',
  floor: 'This device has no report schedule on record. After one minute of silence it is asked.',
  passive: 'This device reports when something changes. It is never asked.',
};
const HOURGLASS = 'M3.5 2h7M3.5 12h7M4.5 2l5 10M9.5 2l-5 10'; // design/recovery-card-v1/states.html's R6 path, byte for byte
const RECORDING_HOURLY = 'Recording: one answered check per hour at most, plus one after each restart';
const NO_CHECK = 'Newest check on record: Not recorded.';

describe('stageOf(row, meta) — the S3 gate and THE NOT-LIVE RULE (row 2)', () => {
  it('S3 only when meta.recoveryViewPosition is a NUMBER (0 included) and at least one of the eleven keys is on the row [RED at HEAD: stageOf reads no meta]', () => {
    expect(stageOf(v117(s2('AVAILABLE', null, null)), LIVE)).toBe('S3');
    expect(stageOf(v117(s2('AVAILABLE', null, null), { ...METERED, ...answer(5) }), LIVE)).toBe('S3');
    expect(stageOf(v117(s2('AVAILABLE', null, null)), { ...LIVE, recoveryViewPosition: 0 })).toBe('S3');
    expect(stageOf({ ...s2('AVAILABLE', null, null), probeAnsweredAt: null } as EntitySummary, LIVE)).toBe('S3'); // one key is enough
    expect(stageOf(EMITTER_ROW, EMITTER_META)).toBe('S3');
  });
  it('a NULL position (catching up), an ABSENT one (a pre-v1.1.7 meta), no meta, or a row without the eleven keys keeps S2 / S1 as today [preservation]', () => {
    const r = v117(s2('AVAILABLE', null, null), { ...METERED, ...answer(5) });
    expect(stageOf(r, NOT_LIVE)).toBe('S2');
    expect(stageOf(r, { viewPosition: 41, timestamp: LIVE.timestamp })).toBe('S2');
    expect(stageOf(r)).toBe('S2');
    expect(stageOf(r, null)).toBe('S2');
    expect(stageOf(s2('AVAILABLE', null, null), LIVE)).toBe('S2'); // a position beside a v1.1.6 row: no key to read
    expect(stageOf(s1('AVAILABLE'), LIVE)).toBe('S1');
    expect(stageOf(EMITTER_NOT_LIVE_ROW, EMITTER_NOT_LIVE_META)).toBe('S2');
  });
  it('THE NOT-LIVE RULE — the keys present and null beside a null position render every row\'s S2 decision, field for field, across the S2 table and the emitter\'s not-LIVE leg [preservation; teeth by mutation]', () => {
    for (const r of [...resolveScenario('recovery-states').entities, EMITTER_NOT_LIVE_ROW]) {
      const plain = v116(r);
      const notLive = v117(plain);
      expect(recoveryRow(notLive, stageOf(notLive, NOT_LIVE), NOW), r.entityId).toEqual(recoveryRow(plain, stageOf(plain), NOW));
    }
  });
  it('…WHATEVER the eleven keys carry: a full record, a contract and an R6 verdict beside a null position still render S2 — the sixth cell never fires from a not-LIVE read [preservation; teeth by mutation]', () => {
    for (const r of resolveScenario('recovery-states').entities) {
      const plain = v116(r);
      const loud = v117(plain, { ...METERED, ...answer(5), recordDueBy: minAgo(1), recordOverdueSince: minAgo(70) });
      expect(recoveryRow(loud, stageOf(loud, NOT_LIVE), NOW), r.entityId).toEqual(recoveryRow(plain, stageOf(plain), NOW));
    }
  });
});

describe('SPEC §3 — the S3 column on the LIVE record (row 3: every filled S3 cell, every null arm)', () => {
  it('R1 S3 — AVAILABLE ∧ no answer on record → "Reporting" · the age · the CLASS\'s sentence (metered) · ok · the pulse; L2 adds "Newest check on record: Not recorded." and the record\'s cadence', () => {
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), METERED));
    expect(d.stage).toBe('S3');
    expect(d.key).toBe('reporting');
    expect(d.label).toBe('Reporting');
    expect(d.line).toBe('Last report 14 min ago.');
    expect(d.beneath).toBe(SENTENCE.metered);
    expect(d.tone).toBe('ok');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.reporting);
    expect(d.dark).toBe(false);
    expect(l2text(d, 'lastRecordedCheck')).toBe(NO_CHECK);
    expect(l2text(d, 'recordEvery')).toBe(RECORDING_HOURLY);
    neverLies(d);
  });
  it('R1 S3 — AVAILABLE ∧ the newest answer NOT after lastReported → still "Reporting"; L2 names that answer and its window', () => {
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, ...answer(20, 60, 3, 0) }));
    expect(d.key).toBe('reporting');
    expect(d.label).toBe('Reporting');
    expect(l2text(d, 'lastRecordedCheck')).toBe(`Newest check on record: ${clock(minAgo(20))} — answered`);
    expect(l2text(d, 'checkWindow')).toBe(`Checks in that record: ${clock(minAgo(80))} to ${clock(minAgo(20))} — 3 answered, 0 missed`);
    neverLies(d);
  });
  it('R1 S3 · the floor class → the floor sentence (no report schedule claimed) · NULL ARM: no contract record → S2\'s fallback sentence and no cadence row', () => {
    expect(live(v117(s2('AVAILABLE', null, null), FLOOR)).beneath).toBe(SENTENCE.floor);
    const none = live(v117(s2('AVAILABLE', null, null)));
    expect(none.stage).toBe('S3');
    expect(none.key).toBe('reporting');
    expect(none.beneath).toBe(FALLBACK);
    expect(l2keys(none)).toEqual(['lastReport', 'lastHeard', 'lastRecordedCheck', 'reason']);
    neverLies(none);
  });

  it('R2 S3 — AVAILABLE ∧ probeAnsweredAt AFTER lastReported → "Quiet since {lastReported} (asked, answered)" · "No report since {t}. It answered the newest check on record, at {checkedTime}." · the class sentence · warn · two opposed arrows — the last RECORDED answer, never "checked just now"', () => {
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, ...answer(6) }));
    expect(d.key).toBe('quiet');
    expect(d.form).toBe('recorded');
    expect(d.label).toBe(`Quiet since ${clock(REPORTED)} (asked, answered)`);
    expect(d.line).toBe(`No report since ${clock(REPORTED)}. It answered the newest check on record, at ${clock(minAgo(6))}.`);
    expect(d.beneath).toBe(SENTENCE.metered);
    expect(d.tone).toBe('warn');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.quiet);
    expect(d.dark).toBe(false);
    expect(d.since).toEqual({ from: 'lastReported', iso: REPORTED });
    expect(`${d.label} ${d.line}`).not.toMatch(/just now|latest check/);
    neverLies(d);
  });
  it('R2 S3 · the EMITTER\'s LIVE row (2a54145) IS this cell — reported 11:00, answered 11:30: the label, the line, the metered sentence and L2 in order', () => {
    const now = Date.parse(EMITTER_META.timestamp);
    const c = (iso: string) => clockTimeWithDate(iso, now);
    const d = recoveryRow(EMITTER_ROW, stageOf(EMITTER_ROW, EMITTER_META), now);
    expect(d.stage).toBe('S3');
    expect(d.key).toBe('quiet');
    expect(d.label).toBe(`Quiet since ${c('2026-10-11T11:00:00Z')} (asked, answered)`);
    expect(d.line).toBe(`No report since ${c('2026-10-11T11:00:00Z')}. It answered the newest check on record, at ${c('2026-10-11T11:30:00Z')}.`);
    expect(d.beneath).toBe(SENTENCE.metered);
    expect(d.l2.map((x) => x.text)).toEqual([
      `Last report: ${c('2026-10-11T11:00:00Z')}`,
      `Last heard: ${c('2026-10-11T11:00:00Z')}`,
      `Newest check on record: ${c('2026-10-11T11:30:00Z')} — answered`,
      `Checks in that record: ${c('2026-10-11T10:30:00Z')} to ${c('2026-10-11T11:30:00Z')} — 55 answered, 1 missed`,
      RECORDING_HOURLY,
      'Recorded reason: first_contact',
      `Signal at the last frame: LQI 200, -45 dBm, at ${c('2026-10-11T11:00:00Z')}.`,
    ]);
    neverLies(d);
  });
  it('R2 S3 · NULL ARM: lastReported null with an answer on record → "Quiet (asked, answered)" · "No report on record. It answered the newest check on record, at {checkedTime}." (SPEC §7\'s two null-arm rows)', () => {
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD, null), { ...METERED, ...answer(6) }));
    expect(d.key).toBe('quiet');
    expect(d.label).toBe('Quiet (asked, answered)');
    expect(d.line).toBe(`No report on record. It answered the newest check on record, at ${clock(minAgo(6))}.`);
    expect(d.since).toBeNull();
    neverLies(d);
  });
  it('R2 S3 · NULL ARM: no answer on record (the four probe* null) → S2\'s cell — the ping_success EDGE form where the wire shows it, else R1\'s; the sentence beneath is the class\'s', () => {
    const edge = live(v117(s2('AVAILABLE', 'ping_success', SEEN), METERED));
    expect(edge.key).toBe('quiet');
    expect(edge.form).toBe('edge');
    expect(edge.line).toBe(`No report since ${clock(REPORTED)}. It answered when asked at ${clock(SEEN)}.`);
    expect(edge.beneath).toBe(SENTENCE.metered);
    expect(live(v117(s2('AVAILABLE', 'ping_success', SEEN_OLD), METERED)).key).toBe('reporting');
  });

  it('R3 S3 — UNAVAILABLE ∧ ping_timeout ∧ missesToDark 2 → the full label and line, as at S2 ("twice" READS missesToDark); L2 adds the newest check on record — an answer before the dark edge — and never a "last asked" row', () => {
    const d = live(v117(s2('UNAVAILABLE', 'ping_timeout', SEEN_OLD), { ...METERED, ...answer(3000, 60, 40, 0) }));
    expect(d.key).toBe('notResponding');
    expect(d.form).toBe('full');
    expect(d.label).toBe(`Not responding since ${clock(SEEN_OLD)} (asked twice, no answer)`);
    expect(d.line).toBe(`Asked twice; nothing came back. Last heard ${clock(SEEN_OLD)}.`);
    expect(d.beneath).toBeNull();
    expect(d.dark).toBe(true);
    expect(l2text(d, 'lastRecordedCheck')).toBe(`Newest check on record: ${clock(minAgo(3000))} — answered`);
    expect(d.l2.map((x) => x.text).join(' ')).not.toMatch(/Last asked|no answer/);
    neverLies(d);
  });
  it('R3 S3 · missesToDark other than 2 → the parenthesis and the line drop: the bare label, the class\'s sentence as its line · NULL ARM: missesToDark null (no contract) → S2\'s "twice"', () => {
    const three = live(v117(s2('UNAVAILABLE', 'ping_timeout', SEEN_OLD), { ...METERED, missesToDark: 3 }));
    expect(three.key).toBe('notResponding');
    expect(three.form).toBe('bare');
    expect(three.label).toBe(`Not responding since ${clock(SEEN_OLD)}`);
    expect(three.line).toBe(SENTENCE.metered);
    expect(`${three.label} ${three.line}`).not.toMatch(/twice|Asked/);
    expect(three.dark).toBe(true);
    const none = live(v117(s2('UNAVAILABLE', 'ping_timeout', SEEN_OLD)));
    expect(none.form).toBe('full');
    expect(none.label).toBe(`Not responding since ${clock(SEEN_OLD)} (asked twice, no answer)`);
    neverLies(three);
  });
  it('R3 S3 · `leave` → as at S2 (nobody asked it), whatever the class', () => {
    for (const rec of [METERED, PASSIVE, {}]) {
      const d = live(v117(s2('UNAVAILABLE', 'leave', SEEN_OLD), rec));
      expect(d.form).toBe('left');
      expect(d.line).toBe(`This device left the network. Last heard ${clock(SEEN_OLD)}.`);
    }
  });
  it('R3 S3 · the RETIRED flip (U2a\'s lastProbeAt; SPEC §12 Q9 (a)): a device dark before a restart — ping_timeout, lastSeenAt on record, NO answer on record — stays R3 on its recorded transition, never the fifth state', () => {
    const d = live(v117(s2('UNAVAILABLE', 'ping_timeout', SEEN_OLD), METERED));
    expect(d.key).toBe('notResponding');
    expect(d.form).toBe('full');
    expect(l2text(d, 'lastRecordedCheck')).toBe(NO_CHECK);
  });

  it('R4 S3 — passive ∧ AVAILABLE with no declared interval (today\'s sensor, IR-121) → "Reporting" with the age: Quiet is NOT sayable (Q4 (a)); the passive sentence; the listening dot; L2 has no check on record and no cadence (never asked)', () => {
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD, minAgo(60 * 30)), PASSIVE));
    expect(d.key).toBe('passiveReporting');
    expect(d.label).toBe('Reporting');
    expect(d.line).toBe(`Last report ${timeAgo(minAgo(60 * 30), NOW)}.`);
    expect(d.beneath).toBe(SENTENCE.passive);
    expect(d.tone).toBe('ok');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.passiveReporting);
    expect(l2text(d, 'lastRecordedCheck')).toBe(NO_CHECK);
    expect(l2keys(d)).not.toContain('recordEvery');
    neverLies(d);
  });
  it('R4 S3 — passive with a DECLARED interval: an age within it → "Reporting"; past it → "Quiet since {t}" · "No report since {t}. This device is never asked." · warn · the listening dot', () => {
    expect(live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...PASSIVE, reportIntervalSeconds: 3600 })).key).toBe('passiveReporting');
    const past = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...PASSIVE, reportIntervalSeconds: 600 })); // 14 min > 10 min
    expect(past.key).toBe('passiveQuiet');
    expect(past.label).toBe(`Quiet since ${clock(REPORTED)}`);
    expect(past.line).toBe(`No report since ${clock(REPORTED)}. This device is never asked.`);
    expect(past.label).not.toContain('asked, answered');
    expect(past.beneath).toBe(SENTENCE.passive);
    expect(past.tone).toBe('warn');
    expect(past.glyph).toBe(RECOVERY_GLYPHS.passiveReporting);
    expect(past.dark).toBe(false);
    neverLies(past);
  });
  it('R4 S3 — passive ∧ UNAVAILABLE → the dark row "Not responding since {t}" with the passive line, whatever the reason token (the class is on record) · NULL ARM: no contract → S2\'s basis (silence_timeout names it; another token is R3\'s degraded form)', () => {
    for (const reason of ['silence_timeout', 'frame_received', null]) {
      const d = live(v117(s2('UNAVAILABLE', reason, SEEN_OLD), PASSIVE));
      expect(d.key, String(reason)).toBe('passiveNotResponding');
      expect(d.label).toBe(`Not responding since ${clock(SEEN_OLD)}`);
      expect(d.line).toBe('Nothing has arrived for longer than this device usually goes. It is never asked.');
      expect(d.glyph).toBe(RECOVERY_GLYPHS.passiveNotResponding);
    }
    expect(live(v117(s2('UNAVAILABLE', 'silence_timeout', SEEN_OLD))).key).toBe('passiveNotResponding');
    expect(live(v117(s2('UNAVAILABLE', 'frame_received', SEEN_OLD))).form).toBe('bare');
  });

  it('R5 S3 — S2\'s two arms carry S3: UNAVAILABLE ∧ reason null ∧ lastSeenAt null (the version-1 transition), or UNKNOWN → the fifth label and line; L2 "Newest check on record: Not recorded."', () => {
    const hue = live(v117({ entityId: '01KX1PA4HSJ581GASYB7DHE40F', availability: 'UNAVAILABLE', stale: false, deviceId: '01KX1PA4GRZHY2GD37B5CFVQHY', lastReported: '2026-07-19T00:49:15.787079Z', availabilityReason: null, lastSeenAt: null, link: null }, FLOOR));
    expect(hue.stage).toBe('S3');
    expect(hue.key).toBe('unasked');
    expect(hue.label).toBe('Not heard from since startup (not asked)');
    expect(hue.line).toBe('Nothing from this device since startup. It has not been asked yet.');
    expect(l2text(hue, 'lastRecordedCheck')).toBe(NO_CHECK);
    const unknown = live(v117(s2('UNKNOWN', null, null, null)));
    expect(unknown.key).toBe('unasked');
    expect(unknown.line2).toBe('No report on record.');
    neverLies(hue);
    neverLies(unknown);
  });

  it('R6 S3 — AVAILABLE ∧ recordOverdueSince SERVED → "No check recorded since {recordOverdueSince}" · "A newer check was due by now. What the device is doing is not known." · the class sentence · unknown · the HOURGLASS · no act; L2 "Newer check due by: {recordDueBy}"', () => {
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD, minAgo(95)), { ...METERED, ...answer(80, 64, 2, 0), recordDueBy: minAgo(9), recordOverdueSince: minAgo(80) }));
    expect(d.key).toBe('noCheckRecorded');
    expect(d.label).toBe(`No check recorded since ${clock(minAgo(80))}`);
    expect(d.line).toBe('A newer check was due by now. What the device is doing is not known.');
    expect(d.beneath).toBe(SENTENCE.metered);
    expect(d.tone).toBe('unknown');
    expect(d.glyph).toBe(RECOVERY_GLYPHS.noCheckRecorded);
    expect(d.glyph.d).toBe(HOURGLASS);
    expect(d.dark).toBe(false); // not known dark: no act
    expect(d.since).toEqual({ from: 'recordOverdueSince', iso: minAgo(80) });
    expect(l2keys(d)).toEqual(['lastReport', 'lastHeard', 'lastRecordedCheck', 'checkWindow', 'recordEvery', 'dueBy', 'reason', 'signal']);
    expect(l2text(d, 'dueBy')).toBe(`Newer check due by: ${clock(minAgo(9))}`);
    neverLies(d);
  });
  it('R6 S3 · the EMITTER\'s R6 leg: read past 12:41:10.050, the same plug is "No check recorded since 11:30" — the evidence instant the core served', () => {
    const now = Date.parse(EMITTER_OVERDUE_META.timestamp);
    const c = (iso: string) => clockTimeWithDate(iso, now);
    const d = recoveryRow(EMITTER_OVERDUE_ROW, stageOf(EMITTER_OVERDUE_ROW, EMITTER_OVERDUE_META), now);
    expect(d.key).toBe('noCheckRecorded');
    expect(d.label).toBe(`No check recorded since ${c('2026-10-11T11:30:00Z')}`);
    expect(l2text(d, 'dueBy')).toBe(`Newer check due by: ${c('2026-10-11T12:41:10.050Z')}`);
  });
  it('R6 replaces a Reporting, a Quiet and a passive claim the record can no longer back — in the AVAILABLE family only: never on UNAVAILABLE (R3 / R4 / R5 stand), never on UNKNOWN (R5 stands) — SPEC §12 Q11 (a)(a)', () => {
    const verdict = { recordDueBy: minAgo(9), recordOverdueSince: minAgo(80) };
    expect(live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, ...verdict })).key).toBe('noCheckRecorded');
    expect(live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, ...answer(6), ...verdict })).key).toBe('noCheckRecorded');
    expect(live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...PASSIVE, ...verdict })).key).toBe('noCheckRecorded');
    expect(live(v117(s2('UNAVAILABLE', 'ping_timeout', SEEN_OLD), { ...METERED, ...verdict })).key).toBe('notResponding');
    expect(live(v117(s2('UNAVAILABLE', 'silence_timeout', SEEN_OLD), { ...PASSIVE, ...verdict })).key).toBe('passiveNotResponding');
    expect(live(v117(s2('UNAVAILABLE', null, null), { ...FLOOR, ...verdict })).key).toBe('unasked');
    expect(live(v117(s2('UNKNOWN', null, null), { ...FLOOR, ...verdict })).key).toBe('unasked');
  });
  it('R6 · NULL ARM: recordOverdueSince null → no R6, whatever recordDueBy says; R5 and R6 differ in words, verb and shape with the colour covered (one hue by design, SPEC §9)', () => {
    const due = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, recordDueBy: minAgo(-30), recordOverdueSince: null }));
    expect(due.key).toBe('reporting');
    expect(l2keys(due)).not.toContain('dueBy'); // "due by" is R6's L2 row only
    const r6 = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, recordDueBy: minAgo(9), recordOverdueSince: minAgo(80) }));
    const r5 = live(v117(s2('UNAVAILABLE', null, null), FLOOR));
    expect(r6.label).toContain('No check recorded');
    expect(r6.label).not.toContain('not asked');
    expect(r5.label).toContain('not asked');
    expect(r6.glyph.d).not.toBe(r5.glyph.d);
    expect(r6.tone).toBe(r5.tone);
  });

  it('open vocabulary S3 — an unknown availability → "Recorded as …" (the same); an unknown CLASS token → the fallback sentence (the renderer names three classes and never closes the set)', () => {
    expect(live(v117(s2('DEGRADED', 'frame_received', SEEN_OLD), METERED)).key).toBe('unrecognized');
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, availabilityClass: 'mains-hybrid' }));
    expect(d.key).toBe('reporting');
    expect(d.beneath).toBe(FALLBACK);
  });
});

describe('SPEC §4 — the contract sentence per class (row 4)', () => {
  const at3 = (rec: Record<string, unknown>) => v117(s2('AVAILABLE', null, null), rec);
  it('word for word SPEC §4 with its {kind} ("plug" / "sensor"): metered 600 · 660 → "This plug reports at least every 10 minutes. After 11 minutes of silence it is asked." · floor → "This plug has no report schedule on record. After one minute of silence it is asked." · passive → "This sensor reports when something changes. It is never asked."', () => {
    expect(contractSentence(at3(METERED), 'S3', 'plug')).toBe('This plug reports at least every 10 minutes. After 11 minutes of silence it is asked.');
    expect(contractSentence(at3(FLOOR), 'S3', 'plug')).toBe('This plug has no report schedule on record. After one minute of silence it is asked.');
    expect(contractSentence(at3(PASSIVE), 'S3', 'sensor')).toBe('This sensor reports when something changes. It is never asked.');
  });
  it('the fallback — no contract record (the class null) or a read below S3 (not LIVE) → "If this device goes quiet for a while, it is asked. How long is not shown here yet."', () => {
    const fb = 'If this device goes quiet for a while, it is asked. How long is not shown here yet.';
    expect(contractSentence(at3({}), 'S3')).toBe(fb);
    expect(contractSentence(at3(METERED), 'S2')).toBe(fb);
    expect(contractSentence(at3(METERED), 'S1')).toBe(fb);
    expect(FALLBACK).toBe(fb);
  });
  it('the card\'s {kind} is "device": the entity reads carry no device class word, so no "plug", "lamp" or "sensor" is guessed', () => {
    expect(contractSentence(at3(METERED), 'S3')).toBe(SENTENCE.metered);
    expect(contractSentence(at3(FLOOR), 'S3')).toBe(SENTENCE.floor);
    expect(contractSentence(at3(PASSIVE), 'S3')).toBe(SENTENCE.passive);
    expect(live(at3(METERED)).beneath).toBe(SENTENCE.metered);
  });
  it('the numbers are the record\'s, whole minutes rendered by format — never a bound: 1200 · 1260 → 20 · 21; a sentence its numbers cannot fill honestly falls back (a metered class with no maximum; a 30-s contract, which §7 has no seconds form for; a floor whose limit is not one minute, or that declares a schedule)', () => {
    expect(contractSentence(at3({ ...METERED, reportIntervalSeconds: 1200, silenceLimitSeconds: 1260 }), 'S3')).toBe('This device reports at least every 20 minutes. After 21 minutes of silence it is asked.');
    expect(contractSentence(at3({ ...METERED, reportIntervalSeconds: null }), 'S3')).toBe(FALLBACK);
    expect(contractSentence(at3({ ...METERED, reportIntervalSeconds: 30, silenceLimitSeconds: 90 }), 'S3')).toBe(FALLBACK);
    expect(contractSentence(at3({ ...FLOOR, silenceLimitSeconds: 120 }), 'S3')).toBe(FALLBACK);
    expect(contractSentence(at3({ ...FLOOR, reportIntervalSeconds: 600 }), 'S3')).toBe(FALLBACK);
  });
});

describe('L2 at S3 — the newest check, its window, the recording cadence (row 5)', () => {
  it('the order: last report · last heard · the newest check · its window · the cadence · the reason · the signal (R6 adds "due by" before the reason)', () => {
    const d = live(v117(s2('UNAVAILABLE', 'ping_timeout', SEEN_OLD), { ...METERED, ...answer(3000, 60, 40, 2) }));
    expect(l2keys(d)).toEqual(['lastReport', 'lastHeard', 'lastRecordedCheck', 'checkWindow', 'recordEvery', 'reason', 'signal']);
  });
  it('"Checks in that record: {start} to {at} — {a} answered, {m} missed" — that ONE record\'s window, named at both ends, never "this hour", never summed', () => {
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, ...answer(6, 67, 1, 0) }));
    expect(l2text(d, 'checkWindow')).toBe(`Checks in that record: ${clock(minAgo(73))} to ${clock(minAgo(6))} — 1 answered, 0 missed`);
    expect(d.l2.map((x) => x.text).join(' ')).not.toMatch(/this hour/);
  });
  it('"Recording: one answered check per hour at most, plus one after each restart" names the hour ONLY when the key says 3600; 1800 → "per 30 minutes"; null (passive) → no row', () => {
    const at = (s: number | null) => l2text(live(v117(s2('AVAILABLE', null, null), { ...METERED, recordIntervalSeconds: s })), 'recordEvery');
    expect(at(3600)).toBe(RECORDING_HOURLY);
    expect(at(1800)).toBe('Recording: one answered check per 30 minutes at most, plus one after each restart');
    expect(at(null)).toBeUndefined();
  });
  it('NULL ARMS: no answer on record → "Newest check on record: Not recorded." and no window; an answer without its window start or a count → the newest check alone; an unreadable instant is never 1970', () => {
    const none = live(v117(s2('AVAILABLE', null, null), METERED));
    expect(l2text(none, 'lastRecordedCheck')).toBe(NO_CHECK);
    expect(l2keys(none)).not.toContain('checkWindow');
    for (const hole of [{ probeWindowStart: null }, { probesAnswered: null }, { probesMissed: null }]) {
      const d = live(v117(s2('AVAILABLE', null, null), { ...METERED, ...answer(6), ...hole }));
      expect(l2text(d, 'lastRecordedCheck'), JSON.stringify(hole)).toBe(`Newest check on record: ${clock(minAgo(6))} — answered`);
      expect(l2keys(d), JSON.stringify(hole)).not.toContain('checkWindow');
    }
    const bad = live(v117(s2('AVAILABLE', null, null), { ...METERED, ...answer(6), probeAnsweredAt: 'not-a-time' }));
    expect(l2text(bad, 'lastRecordedCheck')).toBe(NO_CHECK);
    neverLies(bad);
  });
});

describe('the honesty asserts (row 6) — the card renders the core\'s bound; it never computes one', () => {
  it('R6\'s instant IS the wire\'s recordOverdueSince and "due by" IS recordDueBy — through the one format, never derived', () => {
    const over = minAgo(123);
    const due = minAgo(1);
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, ...answer(500), recordDueBy: due, recordOverdueSince: over }));
    expect(d.label).toBe(`No check recorded since ${clock(over)}`);
    expect(d.since).toEqual({ from: 'recordOverdueSince', iso: over });
    expect(l2text(d, 'dueBy')).toBe(`Newer check due by: ${clock(due)}`);
  });
  it('changing the three bound inputs (recordIntervalSeconds · silenceLimitSeconds · missesToDark) moves no instant the card shows — only the sentence\'s minutes follow silenceLimitSeconds', () => {
    const verdict = { ...answer(80), recordDueBy: minAgo(9), recordOverdueSince: minAgo(80) };
    const a = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, ...verdict }));
    const b = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD), { ...METERED, ...verdict, recordIntervalSeconds: 1800, silenceLimitSeconds: 1260, missesToDark: 5 }));
    expect(b.label).toBe(a.label);
    expect(b.line).toBe(a.line);
    expect(b.since).toEqual(a.since);
    for (const k of ['lastReport', 'lastHeard', 'lastRecordedCheck', 'checkWindow', 'dueBy', 'reason', 'signal']) expect(l2text(b, k), k).toBe(l2text(a, k));
    expect(b.beneath).toBe('This device reports at least every 10 minutes. After 21 minutes of silence it is asked.');
  });
  it('the card never infers R6: an answer ten days old beside a NULL recordOverdueSince is not "No check recorded" — the bound is the core\'s to serve (Q10 (a))', () => {
    const d = live(v117(s2('AVAILABLE', 'frame_received', SEEN_OLD, minAgo(60 * 24 * 10)), { ...METERED, ...answer(60 * 24 * 10 - 5), recordDueBy: null, recordOverdueSince: null }));
    expect(d.key).not.toBe('noCheckRecorded');
    expect(d.label).not.toContain('No check recorded');
  });
  it('lib/recovery.ts reads the three bound inputs only to pick words — every code line naming one carries no arithmetic operator once comparisons and strings are set aside (the hub\'s R8 as a test; comments stripped) [RED at HEAD: the keys are not read]', () => {
    const code = RECOVERY_SOURCE.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    const lines = code.split('\n').filter((l) => /recordIntervalSeconds|silenceLimitSeconds|missesToDark/.test(l));
    expect(lines.length).toBeGreaterThanOrEqual(3); // read: "twice", {askMinutes}, the cadence's word — never a time
    for (const l of lines) {
      const bare = l.replace(/'[^']*'|"[^"]*"|`[^`]*`/g, '').replace(/=>|===|!==|==|!=|>=|<=|&&|\|\||\?\?|\?\./g, '');
      expect(bare, l.trim()).not.toMatch(/[-+*/%]/);
    }
  });
});

/* ---- The acceptance script's home: with the scenario loaded, every S2 cell (and every S1 cell via `legacy-hub`) is on screen ---- */
describe('the `recovery-states` scenario reaches every S2 cell of SPEC §3, and `legacy-hub` every S1 cell', () => {
  it('S2: reporting · quiet/edge · notResponding/full · notResponding/left · passiveNotResponding · unasked (UNAVAILABLE null·null AND UNKNOWN) · unrecognized-reason-as-R3-bare', () => {
    const rows = resolveScenario('recovery-states').entities;
    const cells = new Set(rows.map((r) => { const d = recoveryRow(r, 'S2', NOW); return `${d.key}/${d.form}`; }));
    for (const c of ['reporting/plain', 'quiet/edge', 'notResponding/full', 'notResponding/left', 'passiveNotResponding/plain', 'unasked/plain', 'notResponding/bare']) expect(cells.has(c), c).toBe(true);
    expect(rows.filter((r) => recoveryRow(r, 'S2', NOW).key === 'unasked').length).toBeGreaterThanOrEqual(2);
    for (const r of rows) expect(stageOf(r)).toBe('S2');
  });
  it('S1 (`legacy-hub`): reporting · notResponding/bare · unasked', () => {
    const rows = resolveScenario('legacy-hub').entities;
    const cells = new Set(rows.map((r) => { const d = recoveryRow(r, stageOf(r), NOW); return `${d.key}/${d.form}`; }));
    for (const c of ['reporting/plain', 'notResponding/bare', 'unasked/plain']) expect(cells.has(c), c).toBe(true);
    for (const r of rows) expect(stageOf(r)).toBe('S1');
  });
});

/* ---- HERO-U3 R7: with `recovery-live` loaded every S3 cell is on screen; `recovery-catching-up` keeps every row at its S2 form ---- */
describe('HERO-U3 R7 — `recovery-live` reaches the S3 cells; `recovery-catching-up` keeps every row at its S2 form [RED at HEAD: neither scenario exists]', () => {
  it('LIVE: Reporting with its class sentence · Quiet on the record · the passive Reporting · R6 · R3 beside a served past bound · the fifth state', () => {
    const ds = resolveScenario('recovery-live');
    expect(typeof ds.recoveryViewPosition).toBe('number');
    const meta = { viewPosition: 1, timestamp: new Date().toISOString(), recoveryViewPosition: ds.recoveryViewPosition };
    const now = Date.now();
    const cells = new Set(ds.entities.map((r) => {
      const d = recoveryRow(r, stageOf(r, meta), now);
      return `${d.stage}/${d.key}/${d.form}`;
    }));
    for (const c of ['S3/reporting/plain', 'S3/quiet/recorded', 'S3/passiveReporting/plain', 'S3/noCheckRecorded/plain', 'S3/notResponding/full', 'S3/unasked/plain']) {
      expect(cells.has(c), c).toBe(true);
    }
  });
  it('CATCHING UP: every row is S2, and its decision equals the same row\'s with no v1.1.7 keys at all — the not-LIVE rule, end to end', () => {
    const ds = resolveScenario('recovery-catching-up');
    expect(ds.recoveryViewPosition).toBeNull();
    const meta = { viewPosition: 1, timestamp: new Date().toISOString(), recoveryViewPosition: ds.recoveryViewPosition };
    for (const r of ds.entities) {
      expect(stageOf(r, meta), r.entityId).toBe('S2');
      expect(recoveryRow(r, stageOf(r, meta), NOW), r.entityId).toEqual(recoveryRow(v116(r), stageOf(v116(r)), NOW));
    }
  });
});

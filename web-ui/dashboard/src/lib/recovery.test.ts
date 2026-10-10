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
 */
import { describe, it, expect } from 'vitest';
import { recoveryRow, stageOf, type RecoveryDecision } from './recovery';
import type { EntitySummary } from './api/contract';
import { clockTimeWithDate, timeAgo } from './format';
import { t } from './i18n';
import { RECOVERY_GLYPHS } from './verdicts';
import { WIRE_20261009_J1_ENTITY_ROW as REAL_ROW } from './api/fixtures/wire-2026-10-09-entities-j1-row';
import { resolveScenario } from './api/mock/scenarios';

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

/* ---- S3 — red-pending behind AVAIL-API-1's keys (FIELDS.md §3). NO key below is read by this build. ---- */
describe('SPEC §3 — the S3 column waits for AVAIL-API-1 (test.todo by key; row 4 of the build)', () => {
  it.todo('availabilityClass — the contract sentence per class (recovery.contract.metered / floor / passive) replaces the fallback');
  it.todo('reportIntervalSeconds — "reports at least every {reportMinutes} minutes"; the passive Quiet row (Q4 a: only with a declared interval)');
  it.todo('silenceLimitSeconds — "after {askMinutes} minutes of silence it is asked"');
  it.todo('lastProbeOutcome — R2 in steady state ("Asked at {askedTime}; it answered."); R3\'s "no answer"; L2 "Last asked: {t} — {outcome}"');
  it.todo('lastProbeAt — null flips any UNAVAILABLE row to the fifth state regardless of the recorded reason (Q5 a); R2 requires lastProbeAt > lastReported');
  it.todo('probeMisses — "asked once" vs "asked twice" (K = 2); a single miss while AVAILABLE is not a row');
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

/*
 * recovery.ts — the recovery card's decision function (HERO-U2b R2; HERO-U3 S3; design/recovery-card-v1/SPEC.md §3).
 * ---------------------------------------------------------------------------
 * ONE device's recovery line in the three words of `VOCAB: a` — Reporting · Quiet since {t} (asked, answered) · Not
 * responding since {t} (asked twice, no answer) — plus the passive class, the fifth state (seeded dark, never asked), the
 * sixth cell (No check recorded since {t} — AMD-103 R-G) and the open-vocabulary arm. `recoveryRow(row, stage)` returns
 * the §3 cell for the row at its stage: the row key, the form, the L1 label and line(s), the sentence beneath, the tone and
 * glyph (shape + label on every state — colour reinforces, never carries), which instant the "since" is, and the L2 facts.
 * Every string is a SPEC §7 row behind t(); nothing here is a literal sentence.
 *
 * THE STAGES (the tri-state and the read's meta decide the column): S1 = the three J1 keys ABSENT (a pre-J1 hub — the four
 * mirror keys only); S2 = any of them PRESENT (null or value); S3 = v1.1.7 (AVAIL-API-1 T-B, core 2a54145) — the read's
 * `meta.recoveryViewPosition` is a NUMBER (the recovery-card projection is LIVE) AND at least one of the eleven record keys
 * is on the row. THE NOT-LIVE RULE: a null position (catching up, or a failed catch-up — AMD-103 §3) or an absent one keeps
 * the row at S2 WHATEVER the eleven keys carry, so every S3 cell renders its S2 form and the sixth cell never fires from a
 * null. Inside S3 each cell keeps its null arm: no answer on record → S2's cell; no contract record → the fallback sentence;
 * missesToDark null → S2's "twice". A sentence may only read fields its stage carries; "not sayable" renders the honest
 * degraded form the SPEC names, never a blank.
 *
 * THE HONESTY LAW: a value the wire did not carry is never shown as if it had. "Quiet (asked, answered)" renders at S3 from
 * the newest RECORDED answer (probeAnsweredAt after lastReported — at most one record per device per record interval, R-B;
 * never "checked just now") and at S2 ONLY on the ping_success edge (AVAILABLE ∧ reason = ping_success ∧ lastSeenAt AFTER
 * lastReported); in steady state a reply publishes nothing (SPEC correction 2), so the row is R1's form with a growing age.
 * The fifth state fires on UNKNOWN, or on UNAVAILABLE with BOTH reason and lastSeenAt null (the version-1 event); a null
 * reason ALONE is not "never asked" (N1), and U2a's "no probe on record → the fifth state" flip is retired with its key
 * (SPEC §12 Q9 (a)). The reason and the class are OPEN lower-cased vocabularies: a token this build does not know changes
 * no row — a reason shows in L2 verbatim, a class falls to the fallback sentence; nothing is ever .toUpperCase()'d.
 *
 * THE BOUND IS THE CORE'S (SPEC §12 Q10 (a)): R6 renders `recordOverdueSince` exactly as served and L2's "Newer check due
 * by" renders `recordDueBy`. This file never does arithmetic on a contract's seconds and never infers R6 from them: the
 * sentence's minutes and the cadence's word are format's (wholeMinutes / recordIntervalWord), "twice" is a comparison
 * with missesToDark, and R6 lives in the AVAILABLE family only (Q11 (a)(a)).
 *
 * THE INSTANTS: ISO strings parsed ONLY through format.parseInstant (the seconds-as-ms class is closed); labels' `since
 * {time}` and every L2 instant are clockTimeWithDate (date-qualified past 24 h); R1's line is the age (timeAgo) — "the age
 * beside the sentence is the honest tell". A null instant renders its keyed null arm (SPEC §3): "No report on record." /
 * the label without its since clause / "Not recorded." in L2. `stale` is NOT a card word (SPEC §6 B).
 */
import { RECOVERY_KEYS, type EntitySummary, type ResponseMeta } from './api/contract';
import {
  RECOVERY_KIND_FALLBACK,
  clockTimeWithDate,
  elapsedExceeds,
  fillSlots,
  parseInstant,
  recordIntervalWord,
  timeAgo,
  wholeMinutes,
  type Tone,
} from './format';
import { t, type MessageKey } from './i18n';
import { RECOVERY_GLYPHS, type RecoveryGlyph } from './verdicts';

export type RecoveryStage = 'S1' | 'S2' | 'S3';

/** The §3 rows: R1 · R2 · R3 · R4 (reporting · quiet · dark) · R5 · R6 · the open arm. */
export type RecoveryRowKey =
  | 'reporting'
  | 'quiet'
  | 'notResponding'
  | 'passiveReporting'
  | 'passiveQuiet'
  | 'passiveNotResponding'
  | 'unasked'
  | 'noCheckRecorded'
  | 'unrecognized';

/** How the row is said: `plain` (R1 / R4 / R5 / R6 / open) · `edge` (R2 on S2's ping_success edge) · `recorded` (R2 from the
 *  newest recorded answer, S3) · `full` (R3 with the probe clause — ping_timeout) · `bare` (R3 without it — S1's degraded
 *  form, an S2 reason that does not say "asked", or S3's missesToDark other than 2) · `left` (R3 by `leave` — nobody asked). */
export type RecoveryForm = 'plain' | 'edge' | 'recorded' | 'full' | 'bare' | 'left';

export interface RecoveryL2 {
  key: 'lastReport' | 'lastHeard' | 'lastRecordedCheck' | 'checkWindow' | 'recordEvery' | 'dueBy' | 'reason' | 'signal';
  text: string;
}

export interface RecoveryDecision {
  key: RecoveryRowKey;
  form: RecoveryForm;
  stage: RecoveryStage;
  /** L1 — the state label with its instant slot filled (a §7 `*.label` row). */
  label: string;
  /** L1 — the sentence beneath the label (a §7 `*.line` row). */
  line: string;
  /** L1 — R5's second line ("Last report on record: {t}." / "No report on record."); null elsewhere. */
  line2: string | null;
  /** The contract sentence (SPEC §4) — the class's at S3, the fallback below it; null on a dark row (the act's slot is row 3's). */
  beneath: string | null;
  tone: Tone;
  glyph: RecoveryGlyph;
  /** TRUE for the rows the act belongs to (R3 · R4 dark · R5) — "the one thing to do" lives here (row 3). R6 is not dark. */
  dark: boolean;
  /** Which instant the label's "since" is — L2 says which ("last heard" vs "last report"); R6's is the core's verdict. */
  since: { from: 'lastSeenAt' | 'lastReported' | 'recordOverdueSince'; iso: string } | null;
  /** L2 — the technical facts, one expand away; S1 carries the last report only; S3 adds the record's lines. */
  l2: RecoveryL2[];
}

/** The read's meta — any ResponseMeta (or none); the stage reads only v1.1.7's recoveryViewPosition from it. */
export type RecoveryMeta = Partial<ResponseMeta> | null | undefined;

const copy = (key: string, slots: Record<string, string> = {}) => fillSlots(t(key as MessageKey) ?? '', slots);
/** A §7 row's words before one slot ("Newest check on record: ") — for the null arm SPEC §3 names in words ("Newest check on
 *  record: Not recorded."), which §7 carries as `recovery.l2.lastRecordedCheck` + `recovery.l2.notRecorded` (the return §3). */
const lead = (key: string, slot: string) => (t(key as MessageKey) ?? '').split(`{${slot}}`)[0] ?? '';

/** S3 when the read is LIVE (a numeric recoveryViewPosition) and the row carries any of the eleven keys; else S2 when any of
 *  the three J1 keys is on the row (null included — PRESENT is the fact); S1 when none is. */
export function stageOf(row: EntitySummary, meta?: RecoveryMeta): RecoveryStage {
  const position = meta?.recoveryViewPosition;
  if (typeof position === 'number' && Number.isFinite(position) && RECOVERY_KEYS.some((k) => k in row)) return 'S3';
  return 'availabilityReason' in row || 'lastSeenAt' in row || 'link' in row ? 'S2' : 'S1';
}

/** A readable instant or null — ONE parse (DX-20); an unreadable stamp is treated as not sayable, never 1970. */
function readable(iso: string | null | undefined): string | null {
  return typeof iso === 'string' && parseInstant(iso) ? iso : null;
}
/** A window count the wire carried as an int ≥ 0, else null (not sayable). */
function count(n: number | null | undefined): number | null {
  return typeof n === 'number' && Number.isInteger(n) && n >= 0 ? n : null;
}
/** Instant `a` after instant `b` — both already readable. */
const after = (a: string, b: string) => parseInstant(a)!.getTime() > parseInstant(b)!.getTime();

/** SPEC §4 — the contract sentence from the contract record at S3; the fallback below S3 or with no contract on record. The
 *  class picks the sentence; the numbers are the record's, in whole minutes by format, never recomputed as a bound; a class
 *  this build does not know, or a sentence its numbers cannot fill honestly, falls back. {kind} is the device's class word:
 *  the entity reads carry none, so the card passes §7's generic "device" (a caller with a class word may pass it). */
export function contractSentence(row: EntitySummary, stage: RecoveryStage, kind: string = RECOVERY_KIND_FALLBACK): string {
  if (stage === 'S3') {
    if (row.availabilityClass === 'mains-metered') {
      const reportMinutes = wholeMinutes(row.reportIntervalSeconds);
      const askMinutes = wholeMinutes(row.silenceLimitSeconds);
      // §7's row says "minutes": a contract under two minutes has no §7 form (SPEC §4's seconds form is not keyed — the return §3).
      if (reportMinutes !== null && reportMinutes >= 2 && askMinutes !== null && askMinutes >= 2) {
        return copy('recovery.contract.metered', { kind, reportMinutes: String(reportMinutes), askMinutes: String(askMinutes) });
      }
    } else if (row.availabilityClass === 'mains-floor') {
      // Two claims, both the record's: no report schedule (R-C — the floor declares none) and one minute of silence.
      if (row.reportIntervalSeconds === null && wholeMinutes(row.silenceLimitSeconds) === 1) return copy('recovery.contract.floor', { kind });
    } else if (row.availabilityClass === 'passive') {
      return copy('recovery.contract.passive', { kind });
    }
  }
  return t('recovery.contract.fallback');
}

export function recoveryRow(row: EntitySummary, stage: RecoveryStage = stageOf(row), now: number = Date.now()): RecoveryDecision {
  const clock = (iso: string) => clockTimeWithDate(iso, now);
  const s3 = stage === 'S3';
  const reported = readable(row.lastReported);
  const seen = stage !== 'S1' ? readable(row.lastSeenAt) : null;
  const reason = stage !== 'S1' && typeof row.availabilityReason === 'string' ? row.availabilityReason : null;
  const availability = row.availability as string;
  // S3's record — each null arm keeps its S2 form: the newest recorded answer (R-B) and R-G's verdict as served (Q10 (a)).
  const checked = s3 ? readable(row.probeAnsweredAt) : null;
  const overdue = s3 ? readable(row.recordOverdueSince) : null;
  const passive = s3 && row.availabilityClass === 'passive';
  const beneath = contractSentence(row, stage);

  /* ---- L2: the facts one expand away. S1 shows the last report only; S3 adds the record's lines before the reason. ---- */
  const notRecorded = t('recovery.l2.notRecorded');
  const l2: RecoveryL2[] = [{ key: 'lastReport', text: copy('recovery.l2.lastReport', { absoluteTime: reported ? clock(reported) : notRecorded }) }];
  if (stage !== 'S1') {
    l2.push({ key: 'lastHeard', text: copy('recovery.l2.lastHeard', { absoluteTime: seen ? clock(seen) : notRecorded }) });
    if (s3) {
      l2.push({
        key: 'lastRecordedCheck',
        text: checked ? copy('recovery.l2.lastRecordedCheck', { absoluteTime: clock(checked) }) : `${lead('recovery.l2.lastRecordedCheck', 'absoluteTime')}${notRecorded}`,
      });
      // That ONE record's window, named at both ends — sayable only with its start and both counts (never "this hour", Q12).
      const start = readable(row.probeWindowStart);
      const answered = count(row.probesAnswered);
      const missed = count(row.probesMissed);
      if (checked && start && answered !== null && missed !== null) {
        l2.push({ key: 'checkWindow', text: copy('recovery.l2.checkWindow', { windowStart: clock(start), checkedTime: clock(checked), answered: String(answered), missed: String(missed) }) });
      }
      const every = recordIntervalWord(row.recordIntervalSeconds);
      if (every) l2.push({ key: 'recordEvery', text: copy('recovery.l2.recordEvery', { recordInterval: every }) });
    }
    l2.push({ key: 'reason', text: copy('recovery.l2.reason', { reason: reason ?? notRecorded }) });
    const link = row.link;
    if (link && typeof link === 'object') {
      const at = readable(link.at);
      l2.push({ key: 'signal', text: copy('recovery.l2.signal', { lqi: String(link.lqi), rssi: String(link.rssiDbm), absoluteTime: at ? clock(at) : notRecorded }) });
    }
  }

  const base = { stage, line2: null as string | null, beneath: null as string | null, l2 };

  /* ---- R1 Reporting (and R2's steady state, and R4's AVAILABLE form while the class is not on the wire) ---- */
  const reporting = (): RecoveryDecision => ({
    ...base,
    key: 'reporting',
    form: 'plain',
    label: t('recovery.state.reporting.label'),
    line: reported ? copy('recovery.state.reporting.line', { time: timeAgo(reported, now) }) : t('recovery.state.unasked.noReport'),
    beneath,
    tone: 'ok',
    glyph: RECOVERY_GLYPHS.reporting,
    dark: false,
    since: reported ? { from: 'lastReported', iso: reported } : null,
  });

  /* ---- R5 the fifth state — seeded dark, never asked ---- */
  const unasked = (): RecoveryDecision => ({
    ...base,
    key: 'unasked',
    form: 'plain',
    label: t('recovery.state.unasked.label'),
    line: t('recovery.state.unasked.line'),
    line2: reported ? copy('recovery.state.unasked.lastReport', { time: clock(reported) }) : t('recovery.state.unasked.noReport'),
    tone: 'unknown',
    glyph: RECOVERY_GLYPHS.unasked,
    dark: true,
    since: null,
  });

  /* ---- R6 the sixth cell — R-G's verdict as the core served it (S3, the AVAILABLE family only; no act: not known dark) ---- */
  const noCheckRecorded = (at: string): RecoveryDecision => {
    const due = readable(row.recordDueBy);
    const facts = [...l2];
    if (due) {
      const i = facts.findIndex((x) => x.key === 'reason');
      facts.splice(i < 0 ? facts.length : i, 0, { key: 'dueBy', text: copy('recovery.l2.dueBy', { absoluteTime: clock(due) }) });
    }
    return {
      ...base,
      l2: facts,
      key: 'noCheckRecorded',
      form: 'plain',
      label: copy('recovery.state.noCheckRecorded.label', { time: clock(at) }),
      line: t('recovery.state.noCheckRecorded.line'),
      beneath,
      tone: 'unknown',
      glyph: RECOVERY_GLYPHS.noCheckRecorded,
      dark: false,
      since: { from: 'recordOverdueSince', iso: at },
    };
  };

  /* ---- R4's AVAILABLE rows on the record (S3): Quiet only past a DECLARED interval (Q4 (a)), else Reporting with the age ---- */
  const passiveAvailable = (): RecoveryDecision => {
    if (reported && elapsedExceeds(reported, row.reportIntervalSeconds, now) === true) {
      return {
        ...base,
        key: 'passiveQuiet',
        form: 'plain',
        label: copy('recovery.state.passive.quiet.label', { time: clock(reported) }),
        line: copy('recovery.state.passive.quiet.line', { time: clock(reported) }),
        beneath,
        tone: 'warn',
        glyph: RECOVERY_GLYPHS.passiveReporting,
        dark: false,
        since: { from: 'lastReported', iso: reported },
      };
    }
    return {
      ...base,
      key: 'passiveReporting',
      form: 'plain',
      label: t('recovery.state.passive.reporting.label'),
      line: reported ? copy('recovery.state.reporting.line', { time: timeAgo(reported, now) }) : t('recovery.state.unasked.noReport'),
      beneath,
      tone: 'ok',
      glyph: RECOVERY_GLYPHS.passiveReporting,
      dark: false,
      since: reported ? { from: 'lastReported', iso: reported } : null,
    };
  };

  /* ---- The dark rows' "since": lastSeenAt when the stage carries it, else lastReported (L2 says which). ---- */
  const since: RecoveryDecision['since'] = seen ? { from: 'lastSeenAt', iso: seen } : reported ? { from: 'lastReported', iso: reported } : null;
  const sinceClock = since ? clock(since.iso) : null;
  const bareLabel = sinceClock ? copy('recovery.state.notResponding.bare.label', { time: sinceClock }) : t('recovery.state.notResponding.bare.noTime.label');

  /* ---- R3 the degraded / bare form (S1; an S2 reason that does not say "asked") ---- */
  const bare = (): RecoveryDecision => ({
    ...base,
    key: 'notResponding',
    form: 'bare',
    label: bareLabel,
    line: reported ? copy('recovery.state.notResponding.bare.line', { time: clock(reported) }) : t('recovery.state.unasked.noReport'),
    tone: 'error',
    glyph: RECOVERY_GLYPHS.notResponding,
    dark: true,
    since,
  });

  /* ---- R4's dark row — the passive class on record (S3), or the silence reason (S2) ---- */
  const passiveDark = (): RecoveryDecision => ({
    ...base,
    key: 'passiveNotResponding',
    form: 'plain',
    label: sinceClock ? copy('recovery.state.passive.notResponding.label', { time: sinceClock }) : t('recovery.state.notResponding.bare.noTime.label'),
    line: t('recovery.state.passive.notResponding.line'),
    tone: 'error',
    glyph: RECOVERY_GLYPHS.passiveNotResponding,
    dark: true,
    since,
  });

  /* ---- The open vocabulary — never success, never an alarm ---- */
  if (availability !== 'AVAILABLE' && availability !== 'UNAVAILABLE' && availability !== 'UNKNOWN') {
    return {
      ...base,
      key: 'unrecognized',
      form: 'plain',
      label: copy('recovery.state.unrecognized.label', { value: String(availability ?? '') }),
      line: t('recovery.state.unrecognized.line'),
      tone: 'unknown',
      glyph: RECOVERY_GLYPHS.unrecognized,
      dark: false,
      since: null,
    };
  }

  if (availability === 'UNKNOWN') return unasked();

  if (availability === 'AVAILABLE') {
    if (overdue) return noCheckRecorded(overdue); // R6 replaces a Reporting / Quiet claim the record can no longer back
    if (passive) return passiveAvailable();
    // R2 at S3 — the newest RECORDED answer after the last report (lastReported null with an answer: the noTime arm).
    if (checked && (reported === null || after(checked, reported))) {
      return {
        ...base,
        key: 'quiet',
        form: 'recorded',
        label: reported ? copy('recovery.state.quiet.label', { time: clock(reported) }) : t('recovery.state.quiet.noTime.label'),
        line: reported
          ? copy('recovery.state.quiet.line.recorded', { time: clock(reported), checkedTime: clock(checked) })
          : copy('recovery.state.quiet.line.recorded.noReport', { checkedTime: clock(checked) }),
        beneath,
        tone: 'warn',
        glyph: RECOVERY_GLYPHS.quiet,
        dark: false,
        since: reported ? { from: 'lastReported', iso: reported } : null,
      };
    }
    // R2 at S2 (S3's null arm) — sayable on the ping_success EDGE only: the reply moved the device UNAVAILABLE→AVAILABLE after its last report.
    if (stage !== 'S1' && reason === 'ping_success' && seen && reported && after(seen, reported)) {
      return {
        ...base,
        key: 'quiet',
        form: 'edge',
        label: copy('recovery.state.quiet.label', { time: clock(reported) }),
        line: copy('recovery.state.quiet.line.edge', { time: clock(reported), heardTime: clock(seen) }),
        beneath,
        tone: 'warn',
        glyph: RECOVERY_GLYPHS.quiet,
        dark: false,
        since: { from: 'lastReported', iso: reported },
      };
    }
    return reporting();
  }

  /* ---- UNAVAILABLE ---- */
  if (stage === 'S1') return bare(); // R3's degraded form — also R4's dark row and R5's UNAVAILABLE variant, which S1 cannot tell apart
  if (reason === null && seen === null) return unasked(); // the version-1 event (both null) is the fifth state — S2's arm carries S3 (P5)
  if (reason === 'leave') {
    return {
      ...base,
      key: 'notResponding',
      form: 'left',
      label: bareLabel,
      line: seen ? copy('recovery.state.left.line', { time: clock(seen) }) : t('recovery.state.left.line.noTime'),
      tone: 'error',
      glyph: RECOVERY_GLYPHS.notResponding,
      dark: true,
      since,
    };
  }
  if (passive || reason === 'silence_timeout') return passiveDark();
  if (reason === 'ping_timeout') {
    // "twice" READS missesToDark (R-C): another count drops the parenthesis and the line — the bare label with the class's
    // sentence; null (no contract record) keeps S2's basis.
    if (s3 && typeof row.missesToDark === 'number' && row.missesToDark !== 2) {
      return { ...base, key: 'notResponding', form: 'bare', label: bareLabel, line: beneath, tone: 'error', glyph: RECOVERY_GLYPHS.notResponding, dark: true, since };
    }
    return {
      ...base,
      key: 'notResponding',
      form: 'full',
      label: sinceClock ? copy('recovery.state.notResponding.label', { time: sinceClock }) : t('recovery.state.notResponding.noTime.label'),
      line: seen ? copy('recovery.state.notResponding.line', { time: clock(seen) }) : t('recovery.state.notResponding.line.noTime'),
      tone: 'error',
      glyph: RECOVERY_GLYPHS.notResponding,
      dark: true,
      since,
    };
  }
  // A null reason beside a recorded lastSeenAt (N1), frame_received / first_contact beside UNAVAILABLE, or a token this build
  // does not know: the degraded form — the label stands on the recorded transition; the token shows in L2 verbatim.
  return bare();
}

/** The visually-hidden sentence per card (SPEC §7 `recovery.a11y.state`): "{deviceName}: {stateLabel}." */
export function recoveryA11y(deviceName: string, d: RecoveryDecision): string {
  return copy('recovery.a11y.state', { deviceName, stateLabel: d.label });
}

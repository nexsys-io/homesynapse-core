/*
 * recovery.ts — the recovery card's decision function (HERO-U2b R2; design/recovery-card-v1/SPEC.md §3).
 * ---------------------------------------------------------------------------
 * ONE device's recovery line in the three words of `VOCAB: a` — Reporting · Quiet since {t} (asked,
 * answered) · Not responding since {t} (asked twice, no answer) — plus the passive class, the fifth state
 * (seeded dark, never asked) and the open-vocabulary arm. `recoveryRow(row, stage)` returns the §3 cell for
 * the row at its stage: the row key, the form, the L1 label and line(s), the sentence beneath, the tone and
 * glyph (shape + label on every state — colour reinforces, never carries), which instant the "since" is, and
 * the L2 facts. Every string is a SPEC §7 row behind t(); nothing here is a literal sentence.
 *
 * THE STAGES (the tri-state decides the column): S1 = the three J1 keys ABSENT (a pre-J1 hub — today's four
 * mirror keys only); S2 = any of them PRESENT (null or value). S3 (AVAIL-API-1's keys) is NOT read by this
 * build — the S3 cells are test.todo rows in recovery.test.ts. A sentence may only read fields its stage
 * carries; "not sayable" renders the honest degraded form the SPEC names, never a blank.
 *
 * THE HONESTY LAW: a value the wire did not carry is never shown as if it had. "Quiet (asked, answered)" at
 * S2 renders ONLY on the ping_success edge (AVAILABLE ∧ reason = ping_success ∧ lastSeenAt AFTER
 * lastReported — the reply published an UNAVAILABLE→AVAILABLE transition); in steady state a reply publishes
 * nothing (SPEC correction 2), so the row is R1's form with a growing age — no "asked at" is ever fabricated.
 * The fifth state at S2 fires on UNKNOWN, or on UNAVAILABLE with BOTH reason and lastSeenAt null (the
 * version-1 event — the real capture's data[0]); a null reason ALONE is not "never asked" (N1 — that is
 * S3's lastProbeAt). The reason is an OPEN lower-cased vocabulary: a token this build does not know changes
 * no row and shows in L2 verbatim; nothing is ever .toUpperCase()'d to match an enum.
 *
 * THE INSTANTS: ISO strings parsed ONLY through format.parseInstant (the seconds-as-ms class is closed);
 * labels' `since {time}` and every L2 instant are clockTimeWithDate (date-qualified past 24 h); R1's line is
 * the age (timeAgo) — "the age beside the sentence is the honest tell". A null instant renders its keyed
 * null arm (SPEC §3): "No report on record." / the label without its since clause / "Not recorded." in L2.
 * `stale` is NOT a card word (SPEC §6 B) — it stays on the reading it describes.
 */
import type { EntitySummary } from './api/contract';
import { clockTimeWithDate, fillSlots, parseInstant, timeAgo, type Tone } from './format';
import { t, type MessageKey } from './i18n';
import { RECOVERY_GLYPHS, type RecoveryGlyph } from './verdicts';

export type RecoveryStage = 'S1' | 'S2';

/** The §3 rows: R1 · R2 · R3 · R4 (its dark row — the only passive row today's wire can name) · R5 · the open arm. */
export type RecoveryRowKey = 'reporting' | 'quiet' | 'notResponding' | 'passiveNotResponding' | 'unasked' | 'unrecognized';

/** How the row is said: `plain` (R1 / R4 / R5 / open) · `edge` (R2 on the ping_success edge) ·
 *  `full` (R3 with the probe clause — ping_timeout) · `bare` (R3 without it — S1's degraded form, or an
 *  S2 reason that does not say "asked") · `left` (R3 by `leave` — nobody asked it). */
export type RecoveryForm = 'plain' | 'edge' | 'full' | 'bare' | 'left';

export interface RecoveryL2 {
  key: 'lastReport' | 'lastHeard' | 'reason' | 'signal';
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
  /** The contract sentence (SPEC §4) — the fallback at S1/S2; null on a dark row (the act's slot is row 3's). */
  beneath: string | null;
  tone: Tone;
  glyph: RecoveryGlyph;
  /** TRUE for the rows the act belongs to (R3 · R4 dark · R5) — "the one thing to do" lives here (row 3). */
  dark: boolean;
  /** Which projection instant the label's "since" is — L2 says which ("last heard" vs "last report"). */
  since: { from: 'lastSeenAt' | 'lastReported'; iso: string } | null;
  /** L2 — the technical facts, one expand away; S1 carries the last report only. */
  l2: RecoveryL2[];
}

const copy = (key: string, slots: Record<string, string> = {}) => fillSlots(t(key as MessageKey) ?? '', slots);

/** S2 when any of the three J1 keys is on the row (null included — PRESENT is the fact); S1 when none is. */
export function stageOf(row: EntitySummary): RecoveryStage {
  return 'availabilityReason' in row || 'lastSeenAt' in row || 'link' in row ? 'S2' : 'S1';
}

/** A readable instant or null — ONE parse (DX-20); an unreadable stamp is treated as not sayable, never 1970. */
function readable(iso: string | null | undefined): string | null {
  return typeof iso === 'string' && parseInstant(iso) ? iso : null;
}

export function recoveryRow(row: EntitySummary, stage: RecoveryStage = stageOf(row), now: number = Date.now()): RecoveryDecision {
  const clock = (iso: string) => clockTimeWithDate(iso, now);
  const reported = readable(row.lastReported);
  const seen = stage === 'S2' ? readable(row.lastSeenAt) : null;
  const reason = stage === 'S2' && typeof row.availabilityReason === 'string' ? row.availabilityReason : null;
  const availability = row.availability as string;

  /* ---- L2: the facts one expand away. S1 shows the last report only (the stage carries nothing else). ---- */
  const notRecorded = t('recovery.l2.notRecorded');
  const l2: RecoveryL2[] = [{ key: 'lastReport', text: copy('recovery.l2.lastReport', { absoluteTime: reported ? clock(reported) : notRecorded }) }];
  if (stage === 'S2') {
    l2.push({ key: 'lastHeard', text: copy('recovery.l2.lastHeard', { absoluteTime: seen ? clock(seen) : notRecorded }) });
    l2.push({ key: 'reason', text: copy('recovery.l2.reason', { reason: reason ?? notRecorded }) });
    const link = row.link;
    if (link && typeof link === 'object') {
      const at = readable(link.at);
      l2.push({ key: 'signal', text: copy('recovery.l2.signal', { lqi: String(link.lqi), rssi: String(link.rssiDbm), absoluteTime: at ? clock(at) : notRecorded }) });
    }
  }

  const base = { stage, line2: null as string | null, beneath: null as string | null, l2 };

  /* ---- R1 Reporting (and R2's not-sayable / steady-state form, and R4's AVAILABLE form) ---- */
  const reporting = (): RecoveryDecision => ({
    ...base,
    key: 'reporting',
    form: 'plain',
    label: t('recovery.state.reporting.label'),
    line: reported ? copy('recovery.state.reporting.line', { time: timeAgo(reported, now) }) : t('recovery.state.unasked.noReport'),
    beneath: t('recovery.contract.fallback'),
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

  /* ---- The dark rows' "since": lastSeenAt when the stage carries it, else lastReported (L2 says which). ---- */
  const since: RecoveryDecision['since'] = seen ? { from: 'lastSeenAt', iso: seen } : reported ? { from: 'lastReported', iso: reported } : null;
  const sinceClock = since ? clock(since.iso) : null;

  /* ---- R3 the degraded / bare form (S1; an S2 reason that does not say "asked") ---- */
  const bare = (): RecoveryDecision => ({
    ...base,
    key: 'notResponding',
    form: 'bare',
    label: sinceClock ? copy('recovery.state.notResponding.bare.label', { time: sinceClock }) : t('recovery.state.notResponding.bare.noTime.label'),
    line: reported ? copy('recovery.state.notResponding.bare.line', { time: clock(reported) }) : t('recovery.state.unasked.noReport'),
    tone: 'error',
    glyph: RECOVERY_GLYPHS.notResponding,
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
    // R2 — sayable on the ping_success EDGE only: the reply moved the device UNAVAILABLE→AVAILABLE after its last report.
    if (stage === 'S2' && reason === 'ping_success' && seen && reported && parseInstant(seen)!.getTime() > parseInstant(reported)!.getTime()) {
      return {
        ...base,
        key: 'quiet',
        form: 'edge',
        label: copy('recovery.state.quiet.label', { time: clock(reported) }),
        line: copy('recovery.state.quiet.line.edge', { time: clock(reported), heardTime: clock(seen) }),
        beneath: t('recovery.contract.fallback'),
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
  if (reason === null) return seen ? bare() : unasked(); // the version-1 event (both null) is the fifth state; a recorded lastSeenAt beside a null reason is not (N1)
  if (reason === 'ping_timeout') {
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
  if (reason === 'silence_timeout') {
    return {
      ...base,
      key: 'passiveNotResponding',
      form: 'plain',
      label: sinceClock ? copy('recovery.state.passive.notResponding.label', { time: sinceClock }) : t('recovery.state.notResponding.bare.noTime.label'),
      line: t('recovery.state.passive.notResponding.line'),
      tone: 'error',
      glyph: RECOVERY_GLYPHS.passiveNotResponding,
      dark: true,
      since,
    };
  }
  if (reason === 'leave') {
    return {
      ...base,
      key: 'notResponding',
      form: 'left',
      label: sinceClock ? copy('recovery.state.notResponding.bare.label', { time: sinceClock }) : t('recovery.state.notResponding.bare.noTime.label'),
      line: seen ? copy('recovery.state.left.line', { time: clock(seen) }) : t('recovery.state.left.line.noTime'),
      tone: 'error',
      glyph: RECOVERY_GLYPHS.notResponding,
      dark: true,
      since,
    };
  }
  // Any other token (frame_received / first_contact beside UNAVAILABLE, or one this build does not know): the
  // degraded form — the label stands on the recorded transition; the token shows in L2 verbatim.
  return bare();
}

/** The visually-hidden sentence per card (SPEC §7 `recovery.a11y.state`): "{deviceName}: {stateLabel}." */
export function recoveryA11y(deviceName: string, d: RecoveryDecision): string {
  return copy('recovery.a11y.state', { deviceName, stateLabel: d.label });
}

/*
 * HomeSynapse — The verdict vocabulary (the honest-outcome layer).
 * ---------------------------------------------------------------------------
 * The LIVE `command_result.outcome` vocabulary is TEN values (source of truth:
 * core/event-model/.../CommandResultEvent.java:22-27 — re-verify there; adapters
 * may publish additional protocol-specific strings, so the maps below carry an
 * honest fallback, never an exhaustive-switch crash).
 *
 * The charter (Nick's v18 beat-5 scope ruling, verbatim): "honest-can't-know vs
 * known-failed vs deliberately-superseded are its entire vocabulary." Three
 * verdict classes + the ack + restart accounting:
 *   honest-can't-know       — unconfirmed, timed_out (+ command_confirmation_timed_out).
 *                             Calm amber. Never fake success, never alarm.
 *   known-failed            — rejected, invalid, unsupported, handler_error,
 *                             integration_unavailable. A recorded, definite failure.
 *   deliberately-superseded — superseded. An INTENT CHANGE (a newer command took
 *                             over) — calm, NEVER rendered as a failure.
 *   restart-accounting      — expired_on_restart. Honest bookkeeping, not a fault.
 *   protocol-ack            — acknowledged. The device accepted the command; an
 *                             ack is NOT confirmation (only state_confirmed is).
 *
 * WHY THIS LAYER EXISTS: Core's StandardExplanationService currently flattens
 * every non-acknowledged command_result into the causal-chain's FAILED
 * (isFailure(String), StandardExplanationService.java:656) — erasing exactly the
 * distinctions above (field-proven: superseded + honest-unconfirmed results
 * render outcome:FAILED on the live wire). Until the core-side fix lands (see
 * the lane return's core proposals), the RECORDED REASON on the flattened
 * action still carries the truth for the ledger's deterministic disposition
 * strings — classifyRecordedReason() recovers it honestly, from data already on
 * the wire, never by inventing a field.
 *
 * Copy rules: Register C (Direct Neutral) — no self-reference, never blames the
 * user, never cheerful, never apologetic. Stranger-test locked by verdicts.test.ts.
 * Since HERO-1c C2 the MODE labels and helps are the §7 catalog (i18n.ts) behind t();
 * the ten-value sub-labels and their reason strings below are this layer's own.
 */
import type { Tone } from './format';
import { t } from './i18n';

/** The ten live outcome values (pointer: CommandResultEvent.java:22-27). */
export const COMMAND_RESULT_OUTCOMES = [
  'acknowledged',
  'rejected',
  'timed_out',
  'invalid',
  'unsupported',
  'handler_error',
  'integration_unavailable',
  'superseded',
  'expired_on_restart',
  'unconfirmed',
] as const;
export type CommandResultOutcome = (typeof COMMAND_RESULT_OUTCOMES)[number];

/** The charter's verdict classes. */
export type VerdictClass =
  | 'protocol-ack'
  | 'honest-cant-know'
  | 'known-failed'
  | 'deliberately-superseded'
  | 'restart-accounting';

export interface VerdictMeta {
  /** Short pill label. Distinct per outcome — zero flattening. */
  label: string;
  tone: Tone;
  klass: VerdictClass;
  /** One plain sentence a stranger reads aloud and is right. */
  help: string;
}

const VERDICTS: Record<CommandResultOutcome, VerdictMeta> = {
  acknowledged: {
    label: 'Accepted',
    tone: 'info',
    klass: 'protocol-ack',
    help: 'The device accepted the command. Accepting is not the same as doing — confirmation comes from the device’s own report.',
  },
  unconfirmed: {
    label: 'Sent, not confirmed',
    tone: 'warn',
    klass: 'honest-cant-know',
    help: 'The command was sent, but the device did not confirm it acted. The recorded reason says why confirmation was not possible.',
  },
  timed_out: {
    label: 'No reply',
    tone: 'warn',
    klass: 'honest-cant-know',
    help: 'The device did not reply in time. Whether it acted is unknown — this is reported honestly instead of guessed.',
  },
  rejected: {
    label: 'Rejected',
    tone: 'error',
    klass: 'known-failed',
    help: 'The device or network refused the command. The reason was recorded.',
  },
  invalid: {
    label: 'Invalid',
    tone: 'error',
    klass: 'known-failed',
    help: 'The command was not valid for this device, so it was not carried out.',
  },
  unsupported: {
    label: 'Not supported',
    tone: 'error',
    klass: 'known-failed',
    help: 'This device does not support that command.',
  },
  handler_error: {
    label: 'Error',
    tone: 'error',
    klass: 'known-failed',
    help: 'Something went wrong while handling the command. The error was recorded.',
  },
  integration_unavailable: {
    label: 'Bridge offline',
    tone: 'error',
    klass: 'known-failed',
    help: 'The connection that reaches this device was not available, so the command could not be delivered.',
  },
  superseded: {
    label: 'Replaced',
    tone: 'neutral',
    klass: 'deliberately-superseded',
    help: 'A newer command took over before this one finished. That is a change of intent, not a failure.',
  },
  expired_on_restart: {
    label: 'Expired at restart',
    tone: 'unknown',
    klass: 'restart-accounting',
    help: 'The system restarted while this command was still waiting, so its outcome was closed out honestly instead of guessed.',
  },
};

/** Fallback for adapter-published protocol-specific outcome strings
 *  (CommandResultEvent.java permits them). Honest, never a crash. */
const FALLBACK: VerdictMeta = {
  label: 'Reported',
  tone: 'unknown',
  klass: 'honest-cant-know',
  help: 'The device connection reported an outcome this dashboard does not recognize yet. The recorded text is shown as-is.',
};

export function resultOutcomeMeta(outcome: string): VerdictMeta {
  return (VERDICTS as Record<string, VerdictMeta>)[outcome] ?? FALLBACK;
}

/* ---- The command CLASS — moved here from format.ts by HERO-1c C2 so the verdict layer can
 * pick the effect-class help without importing format.ts (a cycle: format.ts imports
 * actionVerdict). format.ts re-exports it unchanged; every caller keeps its import. ---- */
export type CommandKind = 'effect' | 'color' | 'other';

/** Classify a command for confirmation-copy purposes. Effect/identify-class first —
 *  those are the measured UNCONFIRMABLE-by-report paths (an ACK is not confirmation). */
export function commandKind(command: string | null | undefined): CommandKind {
  // Null-guard (the live present-but-null class): no command string, no class.
  const c = (command ?? '').toLowerCase();
  // Measured unconfirmable-by-report class (bench 2026-07-01: identify + color_loop).
  if (/(identify|effect|loop|blink|flash)/.test(c)) return 'effect';
  // Color-class only when the command SAYS color (set_temperature on a thermostat is
  // NOT color; set_color_temperature contains "color" and matches).
  if (/(color|hue|saturation|kelvin|mired)/.test(c)) return 'color';
  return 'other';
}

/* ---------------------------------------------------------------------------
 * Recovering the erased distinction from the RECORDED REASON.
 *
 * The flattened causal-chain action (outcome: FAILED) carries
 * `firstNonBlank(failureReason, outcome)` as its reason
 * (StandardExplanationService.java:647-648). Two recovery paths, both from
 * deterministic wire truth:
 *   1. The bare outcome token itself (failureReason was null).
 *   2. The pending-command ledger's deterministic disposition strings:
 *      - superseded:         "superseded by a newer command on the same attribute; …"
 *        (StandardPendingCommandLedger.java:912-916)
 *      - expired_on_restart: "command was in-flight at restart and is not idempotent"
 *        (StandardPendingCommandLedger.java:922-924)
 * Zigbee's honest-unconfirmed reasons are profile-note-driven (variable), so they
 * are NOT pattern-matched — that class stays FAILED-rendered until the core fix
 * carries the raw outcome (a recorded limitation, not a guess).
 * ------------------------------------------------------------------------- */

const LEDGER_SUPERSEDED_PREFIX = 'superseded by a newer command on the same attribute';
const LEDGER_EXPIRED_REASON = 'command was in-flight at restart and is not idempotent';

/** If a flattened action's recorded reason deterministically identifies one of
 *  the erased dispositions, return that outcome; otherwise null (no guessing). */
export function classifyRecordedReason(reason: string | null | undefined): CommandResultOutcome | null {
  if (!reason) return null;
  const r = reason.trim();
  if ((COMMAND_RESULT_OUTCOMES as readonly string[]).includes(r)) {
    return r as CommandResultOutcome;
  }
  if (r.startsWith(LEDGER_SUPERSEDED_PREFIX)) return 'superseded';
  if (r === LEDGER_EXPIRED_REASON) return 'expired_on_restart';
  return null;
}

/* ---------------------------------------------------------------------------
 * Confirmation POSTURE (distinct from any verdict).
 *
 * A device's confirmation posture can honestly DEGRADE (e.g. configure-verify
 * timeout → best_effort) and be upgraded later. best_effort is a POSTURE — how
 * strongly future confirmations can be trusted — never a failure, and it gets
 * its own calm rendering. Field-proven on the live fleet (the S31
 * `confirmation_downgraded … posture=VERIFIED_REPORTS/SLEEPY outcome=best_effort`
 * class, 2026-07-19). NOTE: posture does NOT flow through the FROZEN v1.1
 * read-API today — this vocabulary is design-ready for when the wire carries it
 * (a recorded contract gap in the lane return); no mock invents the shape.
 * ------------------------------------------------------------------------- */

export interface PostureMeta {
  label: string;
  tone: Tone;
  help: string;
}

export function postureMeta(posture: string): PostureMeta {
  switch (posture.toLowerCase()) {
    case 'verified_reports':
      return {
        label: 'Verified by reports',
        tone: 'ok',
        help: 'This device reports what it does, so commands can be fully confirmed.',
      };
    case 'best_effort':
      return {
        label: 'Best-effort confirmation',
        tone: 'neutral',
        help: 'Confirmation for this device is currently best-effort — it still works; its reports are just not guaranteed. This can upgrade later.',
      };
    case 'sleepy':
      return {
        label: 'Sleeps between reports',
        tone: 'neutral',
        help: 'This device sleeps to save battery and reports on its own schedule. Slow confirmation here is normal.',
      };
    case 'disabled':
      return {
        label: 'Confirmation off',
        tone: 'unknown',
        help: 'Confirmation is turned off for this command, so it is honestly reported as unconfirmed — never silently skipped.',
      };
    default:
      return {
        label: 'Posture: ' + posture,
        tone: 'unknown',
        help: 'A confirmation posture this dashboard does not recognize yet, shown as recorded.',
      };
  }
}

/* ---------------------------------------------------------------------------
 * The silent-skip run class (field evidence, lawful Doc 07 §3.9 per-target skips).
 *
 * A COMPLETED run can carry outcome.actionCount > 0 with commandCount = 0 and an
 * EMPTY actions[] — every planned target was lawfully skipped (e.g. UNAVAILABLE),
 * nothing visible happened, and today no marker event records it. Until the
 * SKIP-VIS core WU lands, the actionCount-vs-actions[] disagreement IS the tell,
 * and such runs must never render as clean success.
 * ------------------------------------------------------------------------- */

export function isDoNothingRun(outcome: {
  status: string;
  actionCount: number;
  commandCount: number;
}, actionsShown: number): boolean {
  return (
    outcome.status === 'COMPLETED' &&
    outcome.actionCount > 0 &&
    outcome.commandCount === 0 &&
    actionsShown === 0
  );
}

/* ---------------------------------------------------------------------------
 * THE FIVE HONEST FAILURE MODES — DISTINCT, NEVER COLLAPSED.
 *
 * The standing law (Nick, 2026-07-25 — "the distinction IS the product"): the
 * five honest failure modes render DISTINCT: dispatched-and-timed-out ·
 * superseded-same-attribute · acked-then-silent-forever · held-DISPATCHED ·
 * settled-FAILED-on-window-close. The v1.1.2 wire makes them pairwise-distinct
 * on (outcome, resultOutcome, reason) — the signature table is test-pinned
 * core-side (explainRun_fiveFailureModesDistinct, SKIP-VIS DP-1). Per the
 * ALL-USERS/CVD mandate the distinction NEVER rides hue alone: each mode gets
 * a distinct LABEL + GLYPH; color reinforces, at AA in both themes on the
 * mode-paired tokens.
 *
 * CONSUMPTION ORDER (D-4 retirement, 2026-07-26): the first-class v1.1.2
 * `resultOutcome` field is consumed WHEN PRESENT; the recorded-reason recovery
 * (classifyRecordedReason) remains ONLY as the graceful-degrade path for
 * pre-v1.1.2 payloads (the deployed surface until the SKIP-VIS deploy) —
 * where the field is present it wins, always.
 * ------------------------------------------------------------------------- */

export interface ActionVerdictInput {
  /** ActionOutcome on a healthy wire; null/undefined tolerated (present-but-null
   *  hardening) and rendered as the honest "Not recorded" verdict. */
  outcome: string | null | undefined;
  reason: string | null;
  resultOutcome?: string | null;
  settled?: boolean;
  /** The action's command (HERO-1c C2): an effect/identify-class command that lands
   *  acked-silent takes the §7 `.unconfirmable` help. Absent → the plain mode help. */
  command?: string | null;
}

export type ActionMode =
  | 'confirmed'
  | 'held-dispatched' // mode 4 — the provisional one
  | 'timed-out' // mode 1
  | 'superseded' // mode 2
  | 'acked-silent' // mode 3
  | 'settled-failed' // mode 5
  | 'expired-restart' // wire failure-class (SD-7 residue); honest bookkeeping here
  | 'skipped'
  | 'not-recorded'; // present-but-null hardening: a null/absent outcome, said plainly

/** Distinct SVG glyph per MODE (14×14, stroke style matches StatusPill).
 *  The shape half of the never-hue-alone law. */
export const MODE_GLYPHS: Record<ActionMode, string> = {
  confirmed: 'M3.5 7.2l2.2 2.3L10.5 4', // check
  'held-dispatched': 'M2.5 7h7M7 4.5L9.5 7 7 9.5', // arrow, still travelling
  'timed-out': 'M7 7m-4.5 0a4.5 4.5 0 109 0a4.5 4.5 0 10-9 0M7 4.6V7l1.8 1.1', // clock
  superseded: 'M3 4.8h7L8.2 3M11 9.2H4l1.8 1.8', // swap arrows (intent change)
  'acked-silent': 'M1.8 5.6l1.8 1.8 3.4-3.4M9 10.4h.05M11 10.4h.05M12.9 10.4h.05', // ack, then silence
  'settled-failed': 'M3.5 3.5l7 7M10.5 3.5l-7 7', // x
  'expired-restart': 'M11.5 7A4.5 4.5 0 113.9 3.8M11.5 2.5v2h-2', // restart arc
  skipped: 'M2.5 7h5.5M6 4.5L8.5 7 6 9.5M11 4.5v5', // skip-to-end
  'not-recorded': 'M3.5 7h2M6.5 7h2M9.5 7h2', // dotted line — nothing on record
};

export interface ActionVerdict {
  mode: ActionMode;
  /** Distinct per mode (the label half of the never-hue-alone law). */
  label: string;
  tone: Tone;
  glyph: string;
  help: string;
  /** True exactly while the outcome may still settle (§5.9) — render visibly
   *  provisional in the calm register, never a settled pill. */
  provisional: boolean;
  /** True when the disposition came from recorded-reason recovery on a
   *  pre-v1.1.2 payload (no first-class resultOutcome on the wire). */
  recovered: boolean;
  /** The raw disposition backing this verdict (first-class or recovered), or null. */
  resultOutcome: string | null;
}

/** The Q1b settled rule — the SAME derivation the core instruction states
 *  (SKIP-VIS DP-4): an action is provisional exactly while it is DISPATCHED
 *  with no settling record (resultOutcome null/absent or bare "acknowledged");
 *  a superseded DISPATCHED is settled. The first-class `settled` field wins
 *  when present; this derivation covers pre-v1.1.2 payloads identically. */
export function isActionSettled(a: ActionVerdictInput): boolean {
  if (a.settled !== undefined) return a.settled;
  return !(a.outcome === 'DISPATCHED' && (a.resultOutcome == null || a.resultOutcome === 'acknowledged'));
}

const KNOWN_FAILED = new Set(['rejected', 'invalid', 'unsupported', 'handler_error', 'integration_unavailable']);

/** Classify one causal-chain action into its honest render mode. The label and help of
 *  every mode are the §7 catalog's rows (`explain.mode.<key>.label` / `.help`, HERO-1c C2),
 *  read through t() from i18n.ts directly — format.ts imports this module, so the catalog
 *  is reached without a cycle. The settled-failed sub-labels (Rejected · Invalid · Not
 *  supported · Error · Bridge offline · No reply) stay the ten-value layer's words. */
export function actionVerdict(a: ActionVerdictInput): ActionVerdict {
  // Present-but-null hardening (FE-LIVE-V112 item 1): a null/absent OUTCOME is
  // not a failure and not a guess — it is said plainly. (An unrecognized
  // non-null string takes the honest `default` arm below — SPEC §5's last row.)
  if (a.outcome == null || a.outcome === '') {
    return {
      mode: 'not-recorded',
      label: t('explain.mode.notRecorded.label'),
      tone: 'unknown',
      glyph: MODE_GLYPHS['not-recorded'],
      // The §7 row (`explain.mode.notRecorded.help`, given its key by the HERO-1c D2 correction).
      help: t('explain.mode.notRecorded.help'),
      provisional: false,
      recovered: false,
      resultOutcome: a.resultOutcome ?? null,
    };
  }
  const hasField = a.resultOutcome !== undefined;
  const recoveredRo = hasField ? null : (a.outcome === 'FAILED' ? classifyRecordedReason(a.reason) : null);
  const ro: string | null = hasField ? (a.resultOutcome as string | null) : recoveredRo;
  const recovered = !hasField && recoveredRo !== null;
  const settled = isActionSettled(hasField ? a : { ...a, resultOutcome: ro });
  const base = {
    provisional: !settled,
    recovered,
    resultOutcome: ro,
  };
  // Mode 2 — superseded-same-attribute, wherever the wire carries it (DISPATCHED, UNCONFIRMED,
  // or a pre-v1.1.2 FAILED recovered from the ledger string): an intent change, never a failure.
  const superseded = (): ActionVerdict => ({
    ...base,
    mode: 'superseded',
    label: t('explain.mode.superseded.label'),
    tone: 'neutral',
    glyph: MODE_GLYPHS.superseded,
    help: t('explain.mode.superseded.help'),
  });

  switch (a.outcome) {
    case 'CONFIRMED':
      return { ...base, mode: 'confirmed', label: t('explain.mode.confirmed.label'), tone: 'ok', glyph: MODE_GLYPHS.confirmed, help: t('explain.mode.confirmed.help') };
    case 'SKIPPED':
      // SPEC §5 gives the skipped row no help ("—"): the pill carries no tooltip.
      return { ...base, mode: 'skipped', label: t('explain.mode.skipped.label'), tone: 'unknown', glyph: MODE_GLYPHS.skipped, help: '' };
    case 'DISPATCHED': {
      if (ro === 'superseded') return superseded();
      // Mode 4 — held-DISPATCHED (bare, or protocol-acked with no settling
      // record). The §5.9 field truth: a late report can settle this after the
      // run reads COMPLETED — the text itself says "not settled yet" so the
      // provisionality never rides styling alone. (An `acknowledged` disposition
      // stays visible in the L2 "Recorded outcome" detail; the help is the one §7 row.)
      return { ...base, mode: 'held-dispatched', label: t('explain.mode.heldDispatched.label'), tone: 'info', glyph: MODE_GLYPHS['held-dispatched'], help: t('explain.mode.heldDispatched.help') };
    }
    case 'UNCONFIRMED': {
      if (ro === 'unconfirmed') {
        // Mode 3 — acked-then-silent-forever: the system explicitly refusing to
        // treat an ACK as proof. The recorded reason is shown VERBATIM in L2. An
        // effect/identify-class command is acknowledged and never reports (measured,
        // bench 2026-07-01), so its help is the §7 `.unconfirmable` sentence.
        return {
          ...base,
          mode: 'acked-silent',
          label: t('explain.mode.ackedSilent.label'),
          tone: 'warn',
          glyph: MODE_GLYPHS['acked-silent'],
          help: commandKind(a.command) === 'effect' ? t('explain.mode.ackedSilent.unconfirmable') : t('explain.mode.ackedSilent.help'),
        };
      }
      // Mode 2's timed-out variant (UNCONFIRMED / "superseded" / timeout text):
      // still an intent change, never a failure.
      if (ro === 'superseded') return superseded();
      // Mode 1 — dispatched-and-timed-out: sent, window closed, honestly unknown.
      return { ...base, mode: 'timed-out', label: t('explain.mode.timedOut.label'), tone: 'warn', glyph: MODE_GLYPHS['timed-out'], help: t('explain.mode.timedOut.help') };
    }
    case 'FAILED': {
      // Pre-v1.1.2 flattening recovered (or an anomalous wire): an intent
      // change NEVER renders as a failure.
      if (ro === 'superseded') return superseded();
      if (ro === 'expired_on_restart') {
        return {
          ...base,
          mode: 'expired-restart',
          label: t('explain.mode.expiredRestart.label'),
          tone: VERDICTS.expired_on_restart.tone,
          glyph: MODE_GLYPHS['expired-restart'],
          help: t('explain.mode.expiredRestart.help'),
        };
      }
      // Mode 5 — settled-FAILED. The ten-value sub-verdict layer carries the
      // distinct label/tone where the disposition is known (rejected vs error vs
      // bridge-offline vs the D-1 calm "No reply" for timed_out); an unknown
      // adapter string stays conservative FAILED (SD-7), reason shown verbatim.
      // The help is the one §7 row: the recorded reason (L2) says why.
      if (ro && (KNOWN_FAILED.has(ro) || ro === 'timed_out')) {
        const m = resultOutcomeMeta(ro);
        return { ...base, mode: 'settled-failed', label: m.label, tone: m.tone, glyph: MODE_GLYPHS['settled-failed'], help: t('explain.mode.settledFailed.help') };
      }
      return { ...base, mode: 'settled-failed', label: t('explain.mode.settledFailed.label'), tone: 'error', glyph: MODE_GLYPHS['settled-failed'], help: t('explain.mode.settledFailed.help') };
    }
    default:
      // Open-vocabulary hardening (HERO-1c C3; SPEC §5's last row — the pin moved here from the
      // retired format.ts command-outcome map): an OUTCOME string this build does not know is shown as
      // recorded in the honest register — the not-recorded mode with `explain.mode.unknownOutcome.*`
      // — never success, never "Failed", never a crash. (SD-7's conservative FAILED still governs
      // an unknown resultOutcome on a FAILED action, above.)
      return {
        ...base,
        mode: 'not-recorded',
        label: t('explain.mode.unknownOutcome.label').replace('{outcome}', String(a.outcome)),
        tone: 'unknown',
        glyph: MODE_GLYPHS['not-recorded'],
        help: t('explain.mode.unknownOutcome.help'),
      };
  }
}

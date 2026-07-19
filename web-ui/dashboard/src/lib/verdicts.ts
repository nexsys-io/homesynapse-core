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
 */
import type { Tone } from './format';

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

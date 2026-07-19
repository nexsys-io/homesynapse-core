/*
 * HomeSynapse — Plain-language formatting (the "mom-test" layer).
 * ---------------------------------------------------------------------------
 * The stranger test lives here: every label and sentence must read aloud and be
 * right to someone who has never seen the system. Device-backward, <= ~20 words,
 * no index paths, no internal jargon. Status is carried by tone + label TEXT (the
 * component adds the icon shape) — never color alone (WCAG 1.4.1).
 */
import type {
  ActionOutcome,
  Availability,
  CausalChain,
  IntegrationHealth,
  NonFiringVerdict,
  Origin,
  RunStatus,
  TypedValue,
} from './api/contract';
import { t } from './i18n';

export type Tone = 'ok' | 'warn' | 'error' | 'info' | 'unknown' | 'neutral';

/* ---- Names. The v1.1 contract carries an OPTIONAL entity display `name` (additive
   C8, 2026-06-26): prefer it when present; fall back to humanizing the entityId.
   (Core fills it when the config/M9 work lands — clients tolerate absence.) ---- */
export function displayName(e: { entityId: string; name?: string }): string {
  return e.name ?? labelFor(e.entityId);
}

export function labelFor(id: string): string {
  const stripped = id.replace(/^(ent_|sys_|auto_|dev_)/, '');
  if (!stripped) return id;
  return stripped
    .split(/[_\s]+/)
    .map((w) => (w ? w[0]!.toUpperCase() + w.slice(1) : ''))
    .join(' ');
}

/* ---- Time, in human words ---- */
export function timeAgo(isoOrNull: string | null | undefined, now = Date.now()): string {
  if (!isoOrNull) return 'never';
  const t = Date.parse(isoOrNull);
  if (Number.isNaN(t)) return '—';
  const s = Math.round((now - t) / 1000);
  if (s < 5) return 'just now';
  if (s < 60) return `${s} sec ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} hr ago`;
  const d = Math.round(h / 24);
  return `${d} day${d === 1 ? '' : 's'} ago`;
}

export function clockTime(isoOrNull: string | null | undefined): string {
  if (!isoOrNull) return '—';
  const t = new Date(isoOrNull);
  if (Number.isNaN(t.getTime())) return '—';
  return t.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

/* ---- Command outcome (the trust win) ---- */
export function outcomeMeta(o: ActionOutcome): { label: string; tone: Tone; help: string } {
  switch (o) {
    case 'CONFIRMED':
      return { label: 'Confirmed', tone: 'ok', help: 'The device reported it actually did it.' };
    case 'DISPATCHED':
      return { label: 'Sent', tone: 'info', help: 'The command was sent. Waiting for the device to confirm.' };
    case 'UNCONFIRMED':
      return {
        label: 'Sent, not confirmed',
        tone: 'warn',
        // Neutral on WHY (some devices never report; some were briefly offline) —
        // the per-action reason carries the specifics. Calm and honest, never alarm.
        help: 'We sent it, but the device did not confirm it acted.',
      };
    case 'FAILED':
      return { label: 'Failed', tone: 'error', help: 'The command failed. See the reason.' };
    case 'SKIPPED':
      return { label: 'Skipped', tone: 'unknown', help: 'This step did not run.' };
  }
}

/* ---- Measured confirmation-rendering semantics (AMD-97, ratified 2026-07-01) ----
 * The moat's honesty is a UI behavior too. The UI NEVER runs its own confirmation
 * timeout — the backend owns the per-capability window (Doc 08 §3.6 `confirmation[]`,
 * measured in nexsys-bench/corpus/) and the poll renders each transition when the
 * projection advances. These hints are presentation-level plain language keyed on the
 * COMMAND CLASS only: no numbers, no timers, no hardcoded global (SK-INV-01-safe).
 */

export type CommandKind = 'effect' | 'color' | 'other';

/** Classify a command for confirmation-copy purposes. Effect/identify-class first —
 *  those are the measured UNCONFIRMABLE-by-report paths (an ACK is not confirmation). */
export function commandKind(command: string): CommandKind {
  const c = command.toLowerCase();
  // Measured unconfirmable-by-report class (bench 2026-07-01: identify + color_loop).
  if (/(identify|effect|loop|blink|flash)/.test(c)) return 'effect';
  // Color-class only when the command SAYS color (set_temperature on a thermostat is
  // NOT color; set_color_temperature contains "color" and matches).
  if (/(color|hue|saturation|kelvin|mired)/.test(c)) return 'color';
  return 'other';
}

/** Shown while an action is DISPATCHED (pending). Color-class capabilities legitimately
 *  confirm slowly (measured: batched color reporting) — say so calmly, so waiting reads
 *  as normal, never as failure. Returns null when there is nothing useful to add. */
export function pendingHint(command: string): string | null {
  if (commandKind(command) === 'color') {
    return 'Color changes confirm slowly on some bulbs — this can take several seconds.';
  }
  return null;
}

/** Shown when an effect/identify-class action lands UNCONFIRMED: these devices acknowledge
 *  the command but never report doing it, so an immediate honest "not confirmed" is the
 *  EXPECTED behavior — not a fault. Returns null for other command kinds. */
export function unconfirmableHint(command: string): string | null {
  if (commandKind(command) === 'effect') {
    return 'This kind of command is acknowledged but never reported back, so it cannot be confirmed.';
  }
  return null;
}

/* ---- Event origin (never a silent blank) ---- */
export function originMeta(o: Origin): { label: string; tone: Tone; phrase: string } {
  switch (o) {
    case 'AUTOMATION':
      return { label: 'Automation', tone: 'info', phrase: 'by an automation' };
    case 'DEVICE':
      return { label: 'Device', tone: 'neutral', phrase: 'by the device itself' };
    case 'USER':
      return { label: 'You', tone: 'neutral', phrase: 'by you' };
    case 'EXTERNAL':
      return { label: 'Outside', tone: 'warn', phrase: t('origin.external.phrase') };
    case 'UNKNOWN':
      return { label: 'Unknown', tone: 'unknown', phrase: "and we're not sure what caused it" };
  }
}

export function availabilityMeta(a: Availability): { label: string; tone: Tone } {
  switch (a) {
    case 'AVAILABLE':
      return { label: 'Available', tone: 'ok' };
    case 'UNAVAILABLE':
      return { label: 'Offline', tone: 'error' };
    case 'UNKNOWN':
      return { label: 'Unknown', tone: 'unknown' };
  }
}

/* ---- Availability as EVIDENCE WITH AGE — never the flag alone. ----
 * Field-proven both directions on the live fleet: honest UNKNOWN at boot until
 * evidence; UNAVAILABLE declared on evidence at ping-resolution (minutes-scale BY
 * DESIGN); and a rehydrated AVAILABLE can outlive the device being physically
 * off-network until the next ping resolves (the AVAIL-RECONCILE class). So every
 * availability rendering pairs the flag with the last-evidence age — the flag says
 * what the system last CONCLUDED; the age says how old the evidence is. */
export function availabilityEvidence(
  a: Availability,
  lastReported: string | null | undefined,
  now = Date.now(),
): string {
  const age = lastReported ? timeAgo(lastReported, now) : null;
  switch (a) {
    case 'AVAILABLE':
      return age
        ? `Available — last heard from ${age}.`
        : 'Available — no report received yet.';
    case 'UNAVAILABLE':
      return age
        ? `Offline — last heard from ${age}. Devices are rechecked every few minutes.`
        : 'Offline — no report has been received. Devices are rechecked every few minutes.';
    case 'UNKNOWN':
      // Honest state after a restart (AMD-99): rehydrated from the log, waiting
      // for the first fresh report. Calm — it resolves on the first report.
      return age
        ? `Not determined yet — the last report on record is from ${age}. This settles after the next report.`
        : 'Not determined yet — waiting for the device’s first report. This is normal right after a restart.';
  }
}

export function healthMeta(h: IntegrationHealth): { label: string; tone: Tone } {
  switch (h) {
    case 'HEALTHY':
      return { label: 'Healthy', tone: 'ok' };
    case 'DEGRADED':
      return { label: 'Degraded', tone: 'warn' };
    case 'UNHEALTHY':
      return { label: 'Unhealthy', tone: 'error' };
    case 'UNKNOWN':
      return { label: 'Unknown', tone: 'unknown' };
  }
}

export function runStatusMeta(s: RunStatus): { label: string; tone: Tone } {
  switch (s) {
    case 'COMPLETED':
      return { label: 'Completed', tone: 'ok' };
    case 'FAILED':
      return { label: 'Failed', tone: 'error' };
    case 'SKIPPED':
      return { label: 'Skipped', tone: 'unknown' };
    case 'CANCELLED':
      return { label: 'Cancelled', tone: 'neutral' };
    case 'INTERRUPTED':
      return { label: 'Interrupted', tone: 'warn' };
  }
}

export function verdictMeta(v: NonFiringVerdict): { label: string; tone: Tone } {
  switch (v) {
    case 'CONDITION_NOT_MET':
      return { label: 'A condition was not met', tone: 'warn' };
    case 'NEVER_TRIGGERED':
      return { label: 'Nothing set it off', tone: 'neutral' };
    case 'ACTED_BUT_UNCONFIRMED':
      return { label: 'It ran, but the device never confirmed', tone: 'warn' };
    case 'DISABLED':
      return { label: 'It is turned off', tone: 'unknown' };
  }
}

/* ---- Names for runs whose automation is no longer on record ----
 * Field evidence: prior-instance runs render `automationName` (and `trigger.type`)
 * as null on the LIVE wire — automation instance identity re-mints per YAML load,
 * so runs from an earlier load lose their name. Render the class honestly and
 * calmly; NEVER invent a name for a null. */
export function runName(name: string | null | undefined): string {
  return name ?? 'An earlier automation';
}

/** One plain sentence explaining WHY a run can have no name — shown wherever the
 *  null-name class surfaces (calm, honest; not an error). */
export const NULL_NAME_NOTE =
  'This run happened under an earlier version of your automations, so its name is no longer on record. The run itself is preserved.';

/* ---- Brightness: percent comes from the DERIVED key, never a client rescale ----
 * Canonical brightness state is 0–254 LEVEL units (Doc 08 §3.5); the percentage is
 * derived AT QUERY TIME by Core and arrives as the additive `brightness_percent`
 * data key inside the A3 attributes map (M9.4b — MaterializedStateQueryService).
 * The dashboard displays that derived percent and NEVER rescales the raw level
 * itself. When only the raw level is present, show it honestly in level units. */
export function brightnessDisplay(
  attributes: Record<string, TypedValue>,
): { key: string; text: string } | null {
  const pct = attributes['brightness_percent'];
  if (pct && typeof pct.v === 'number') {
    return { key: 'brightness_percent', text: `${pct.v}%` };
  }
  const raw = attributes['brightness'];
  if (raw && typeof raw.v === 'number') {
    // No derived percent on this payload — show the canonical level honestly,
    // never a client-side 0–254 → % rescale.
    return { key: 'brightness', text: `level ${raw.v} of 254` };
  }
  return null;
}

/* ---- Render a typed attribute value plainly ---- */
export function attrValue(tv: TypedValue): string {
  const v = tv.v;
  if (typeof v === 'boolean') {
    if (tv.t === 'BOOL') return v ? 'on' : 'off';
    return v ? 'yes' : 'no';
  }
  if (typeof v === 'number') {
    if (tv.t === 'PERCENT') return `${v}%`;
    if (tv.t === 'KELVIN') return `${v}K`;
    return String(v);
  }
  return v == null ? '—' : String(v);
}

/** Render an action params object as a short trailing clause, e.g. " (brightness 82)". */
export function attrValueList(params: Record<string, unknown>): string {
  const entries = Object.entries(params);
  if (entries.length === 0) return '';
  return ' (' + entries.map(([k, v]) => `${k} ${String(v)}`).join(', ') + ')';
}

/* ---- The hero device-backward sentence (the mom test) ---- */
export function causalSentence(chain: CausalChain): string {
  const triggerSubject = labelFor(chain.trigger.subjectRef.id);
  const triggerVerb = triggerVerbFromValue(chain.trigger.firingValue);
  const when = clockTime(chain.trigger.matchedAt);
  // The silent-skip class: the run finished without doing anything visible —
  // say so up front, never a sentence that implies something happened.
  if (
    chain.actions.length === 0 &&
    chain.outcome.actionCount > 0 &&
    chain.outcome.commandCount === 0
  ) {
    return `${runName(chain.automationName)} ran when ${triggerSubject} ${triggerVerb} at ${when}, but nothing was changed.`;
  }
  const action = chain.actions[0];
  const target = action ? labelFor(action.targetRef.id) : runName(chain.automationName);
  const verb = action ? commandVerb(action.command) : 'ran';
  return `${target} ${verb} because ${triggerSubject} ${triggerVerb} at ${when}.`;
}

function commandVerb(command: string): string {
  switch (command) {
    case 'turn_on':
      return 'turned on';
    case 'turn_off':
      return 'turned off';
    case 'dim':
      return 'dimmed';
    default:
      return `ran "${command}"`;
  }
}

function triggerVerbFromValue(firingValue: string): string {
  const v = firingValue.toLowerCase();
  if (v.includes('motion')) return 'detected motion';
  if (v.includes('open')) return 'was opened';
  if (v.includes('close')) return 'was closed';
  return `changed (${firingValue})`;
}

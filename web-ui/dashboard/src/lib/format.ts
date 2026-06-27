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

export type Tone = 'ok' | 'warn' | 'error' | 'info' | 'unknown' | 'neutral';

/* ---- Names: humanize an entityId (the contract carries no display name — see
   the lane return; a `name` field is a candidate additive contract change). ---- */
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
        help: 'We sent it, but the device never confirmed — it may be slow or briefly offline.',
      };
    case 'FAILED':
      return { label: 'Failed', tone: 'error', help: 'The command failed. See the reason.' };
    case 'SKIPPED':
      return { label: 'Skipped', tone: 'unknown', help: 'This step did not run.' };
  }
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
      return { label: 'Outside', tone: 'warn', phrase: 'outside HomeSynapse' };
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
  const action = chain.actions[0];
  const target = action ? labelFor(action.targetRef.id) : chain.automationName;
  const verb = action ? commandVerb(action.command) : 'ran';
  const triggerSubject = labelFor(chain.trigger.subjectRef.id);
  const triggerVerb = triggerVerbFromValue(chain.trigger.firingValue);
  const when = clockTime(chain.trigger.matchedAt);
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

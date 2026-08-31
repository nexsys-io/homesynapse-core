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

/* ---- The honest-absence marker (FE-LIVE-V112 item 1) ----
 * The LIVE wire serves optionals PRESENT-BUT-NULL beside populated siblings
 * (field evidence, 2026-07-27): the real seam is absent / null / value. Where a
 * value is null, the surface says so in words — never a placeholder that could
 * be mistaken for data, never the string "null", never an invented value. */
export const NOT_RECORDED = 'not recorded';

/** The genuinely-empty chain (a real, successful response with nothing planned):
 *  an explicit, calm statement — nothing failed, and nothing is hidden. */
export const EMPTY_CHAIN_NOTE =
  'This run finished without recording any steps — no conditions were checked and no commands were sent.';

/* ---- Names. The v1.1 contract carries an OPTIONAL entity display `name` (additive
   C8, 2026-06-26): prefer it when present; fall back to humanizing the entityId.
   (Core fills it when the config/M9 work lands — clients tolerate absence.) ---- */
export function displayName(e: { entityId: string; name?: string }): string {
  return e.name ?? labelFor(e.entityId);
}

export function labelFor(id: string | null | undefined): string {
  // Present-but-null guard: an id the wire did not resolve is said plainly,
  // never rendered as "null" and never invented.
  if (!id) return 'Something not on record';
  const stripped = id.replace(/^(ent_|sys_|auto_|dev_)/, '');
  if (!stripped) return id;
  return stripped
    .split(/[_\s]+/)
    .map((w) => (w ? w[0]!.toUpperCase() + w.slice(1) : ''))
    .join(' ');
}

/* ---- Unresolvable refs render LOUD (FE-HONEST-1 — the §10-J law) ----
 * R-4 field evidence (2026-08-30): the explain surface concealed a dangling
 * `entity_ref` it could see — friendly prose over a rule pointing at an entity
 * this hub's registry does not hold. The law: an unresolvable ref renders the
 * NAMED ULID plus "not in this hub's registry", visually failing — never a
 * paraphrase that hides it. The claim is only made on a complete registry
 * census (lib/registry.ts); anything less renders neutral. */

/** Structural mirror of registry.RefResolution (kept import-free — format is a leaf). */
export interface RefLook {
  kind: 'named' | 'known' | 'dangling' | 'unverified';
  name?: string;
}

/** The one phrase for the unresolvable-ref state — test-locked. */
export const UNRESOLVED_REF_PHRASE = 'not in this hub’s registry';
export const UNRESOLVED_REF_PILL = 'Not in registry';
export const UNRESOLVED_REF_HELP =
  'This rule points at an entity that is not in this hub’s registry, so it cannot reach a real device. It may belong to another hub’s records or to a device that was removed.';

/** Name an entity ref for an explain surface: the registry name when the census
 *  resolves it; the ULID VERBATIM when it is dangling (never prettified — the
 *  raw id is what correlates with the log); the humanized fallback otherwise. */
export function refLabel(id: string | null | undefined, res: RefLook): string {
  if (!id) return 'Something not on record';
  if (res.kind === 'named' && res.name) return res.name;
  if (res.kind === 'dangling') return id;
  return labelFor(id);
}

/** The loud trigger line: the named ULID + the registry fact + the recorded time. */
export function danglingTriggerLine(id: string, verb: string, when: string): string {
  return `Entity ${id} — ${UNRESOLVED_REF_PHRASE} — ${verb} at ${when}.`;
}

/** The loud action line: what was attempted, at a ref the registry cannot resolve. */
export function danglingTargetLine(phrase: string, id: string): string {
  return `${phrase} entity ${id} — ${UNRESOLVED_REF_PHRASE}.`;
}

/* ---- Time, in human words ---- */

/** ONE parse for every displayed instant — the seconds-as-ms guard (NEW-6 /
 *  the STATE-DIALECT law). The contract carries instants as ISO-8601 STRINGS;
 *  the live /state wire currently serves fractional epoch-SECOND numbers
 *  behind the same field. `new Date(<number>)` reads epoch-MILLISECONDS, so
 *  such a value landed in 1970 and formatted as a plausible clock time — the
 *  arithmetically-proven misread ("Last reported 9:40 AM" beside prose
 *  "last heard from —", DX-20's self-contradiction). The rule: NEVER coerce a
 *  non-string instant; every surface derives from this one parse, so the
 *  prose and the row can no longer disagree. (Consuming the /state dialect
 *  properly is FE-LIVE-V112 item (h) — a separate charter; until it lands the
 *  surface says honest absence, never a fabricated time.) */
export function parseInstant(isoOrNull: string | null | undefined): Date | null {
  if (typeof isoOrNull !== 'string' || isoOrNull === '') return null;
  const d = new Date(isoOrNull);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function timeAgo(isoOrNull: string | null | undefined, now = Date.now()): string {
  if (isoOrNull == null || isoOrNull === '') return 'never';
  const d = parseInstant(isoOrNull);
  if (!d) return '—';
  const s = Math.round((now - d.getTime()) / 1000);
  if (s < 5) return 'just now';
  if (s < 60) return `${s} sec ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} hr ago`;
  const d2 = Math.round(h / 24);
  return `${d2} day${d2 === 1 ? '' : 's'} ago`;
}

export function clockTime(isoOrNull: string | null | undefined): string {
  const t = parseInstant(isoOrNull);
  if (!t) return '—';
  return t.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

/** Clock time, DATE-QUALIFIED when the instant is not from today (NEW-6): a
 *  bare "9:40 AM" on a report that is days old reads as this morning — a
 *  false claim on the availability-honesty surface. Same-day stamps stay
 *  clock-only; another day carries the date; another year carries the year. */
export function clockTimeWithDate(isoOrNull: string | null | undefined, now = Date.now()): string {
  const t = parseInstant(isoOrNull);
  if (!t) return '—';
  const time = t.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const n = new Date(now);
  const sameDay =
    t.getFullYear() === n.getFullYear() && t.getMonth() === n.getMonth() && t.getDate() === n.getDate();
  if (sameDay) return time;
  const sameYear = t.getFullYear() === n.getFullYear();
  const date = t.toLocaleDateString([], sameYear ? { month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', year: 'numeric' });
  return `${time} on ${date}`;
}

/* ---- The Last-reported cell (FE-HONEST-1 — the §10-G/I store-truth law) ----
 * Three honest states, ONE parse (DX-20): a readable stamp renders date-qualified;
 * a stamp that is ON RECORD but unreadable says so (never "not recorded" — the
 * store holds the row; the wire dialect is the gap); no stamp at all renders the
 * honest absence marker. */
export function lastReportedCell(lastReported: string | null | undefined): string {
  if (lastReported == null || lastReported === '') return '—';
  const t = parseInstant(lastReported);
  if (!t) return 'On record — unreadable by this dashboard';
  return clockTimeWithDate(lastReported);
}

/** The device LIST carries no report time (frozen A1), so it makes NO freshness
 *  claim (§10-I: "Current" with no evidence was a lie by omission). This title
 *  explains the em-dash — test-locked. */
export const LIST_FRESHNESS_NO_CLAIM_TITLE =
  'Whether this reading is current is not shown in this list — open the device to see when it last reported.';

/* ---- Command outcome (the trust win) ---- */
export function outcomeMeta(o: ActionOutcome | string | null | undefined): { label: string; tone: Tone; help: string } {
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
  // Open-vocabulary hardening (NEW-3 sweep; the §4a law: a value the mapping
  // does not cover renders honest-can't-know — never success, never a crash).
  if (o == null || o === '') return { label: `Outcome ${NOT_RECORDED}`, tone: 'unknown', help: 'No outcome was recorded for this step.' };
  return { label: `Recorded as "${o}"`, tone: 'unknown', help: 'The device reported an outcome this dashboard does not recognize yet — shown as recorded.' };
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

/** Shown while an action is DISPATCHED (pending). Color-class capabilities legitimately
 *  confirm slowly (measured: batched color reporting) — say so calmly, so waiting reads
 *  as normal, never as failure. Returns null when there is nothing useful to add. */
export function pendingHint(command: string | null | undefined): string | null {
  if (commandKind(command) === 'color') {
    return 'Color changes confirm slowly on some bulbs — this can take several seconds.';
  }
  return null;
}

/** Shown when an effect/identify-class action lands UNCONFIRMED: these devices acknowledge
 *  the command but never report doing it, so an immediate honest "not confirmed" is the
 *  EXPECTED behavior — not a fault. Returns null for other command kinds. */
export function unconfirmableHint(command: string | null | undefined): string | null {
  if (commandKind(command) === 'effect') {
    return 'This kind of command is acknowledged but never reported back, so it cannot be confirmed.';
  }
  return null;
}

/* ---- Event origin (never a silent blank) ---- */
export function originMeta(o: Origin | string | null | undefined): { label: string; tone: Tone; phrase: string } {
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
  // Open-vocabulary hardening (NEW-3 sweep): B1 is unbuilt on the live wire, so
  // its value set is unverifiable today — an off-vocabulary origin renders in
  // the honest register when the endpoint ships, never a crash.
  if (o == null || o === '') return { label: `Origin ${NOT_RECORDED}`, tone: 'unknown', phrase: "and we're not sure what caused it" };
  return { label: `Recorded as "${o}"`, tone: 'unknown', phrase: "and we're not sure what caused it" };
}

/* Availability honesty (Rosonway §5.3, measured): AVAILABLE is what the system
 * last CONCLUDED from reports — `staleAfter: null` with an hours-old
 * `lastReported` is lawful, so AVAILABLE must NEVER be presented as proof of
 * live radio contact. The help strings say what the flag actually means; the
 * evidence-with-age line (availabilityEvidence) carries the age. */
export function availabilityMeta(a: Availability | string | null | undefined): { label: string; tone: Tone; help: string } {
  switch (a) {
    case 'AVAILABLE':
      return {
        label: 'Available',
        tone: 'ok',
        help: 'As last concluded from the device’s reports — not a live connection test. The device page shows when it was last heard from.',
      };
    case 'UNAVAILABLE':
      return {
        label: 'Offline',
        tone: 'error',
        help: 'Concluded from evidence — the device stopped answering its rechecks. Devices are rechecked every few minutes.',
      };
    case 'UNKNOWN':
      return {
        label: 'Not determined yet',
        tone: 'unknown',
        help: 'An honest state, normal right after a restart — it settles on the device’s first report.',
      };
  }
  // Open-vocabulary hardening (NEW-3 sweep, the closed-switch class).
  if (a == null || a === '') return { label: `Status ${NOT_RECORDED}`, tone: 'unknown', help: 'No availability was recorded for this device.' };
  return { label: `Recorded as "${a}"`, tone: 'unknown', help: 'The hub reported a status this dashboard does not recognize yet — shown as recorded.' };
}

/* ---- Availability as EVIDENCE WITH AGE — never the flag alone. ----
 * Field-proven both directions on the live fleet: honest UNKNOWN at boot until
 * evidence; UNAVAILABLE declared on evidence at ping-resolution (minutes-scale BY
 * DESIGN); and a rehydrated AVAILABLE can outlive the device being physically
 * off-network until the next ping resolves (the AVAIL-RECONCILE class). So every
 * availability rendering pairs the flag with the last-evidence age — the flag says
 * what the system last CONCLUDED; the age says how old the evidence is. */
export function availabilityEvidence(
  a: Availability | string | null | undefined,
  lastReported: string | null | undefined,
  now = Date.now(),
): string {
  // ONE parse governs the whole surface (NEW-6 / DX-20): the age renders only
  // when the stamp actually parses, so "last heard from —" is unreachable and
  // the prose can never contradict the Last-reported row (both derive from
  // parseInstant). Three honest branches: readable age · a report whose time
  // is not usably recorded · no report at all.
  const t = parseInstant(lastReported);
  const age = t ? timeAgo(lastReported, now) : null;
  const unreadableStamp = lastReported != null && !t;
  switch (a) {
    case 'AVAILABLE':
      if (age) return `Available — last heard from ${age}.`;
      if (unreadableStamp) return 'Available — a report time is on record, but this dashboard cannot read it yet.';
      return 'Available — no report received yet.';
    case 'UNAVAILABLE':
      if (age) return `Offline — last heard from ${age}. Devices are rechecked every few minutes.`;
      if (unreadableStamp) return 'Offline — a report time is on record, but this dashboard cannot read it yet. Devices are rechecked every few minutes.';
      return 'Offline — no report has been received. Devices are rechecked every few minutes.';
    case 'UNKNOWN':
      // Honest state after a restart (AMD-99): rehydrated from the log, waiting
      // for the first fresh report. Calm — it resolves on the first report.
      if (age) return `Not determined yet — the last report on record is from ${age}. This settles after the next report.`;
      if (unreadableStamp) return 'Not determined yet — a report time is on record, but this dashboard cannot read it yet. This settles after the next report.';
      return 'Not determined yet — waiting for the device’s first report. This is normal right after a restart.';
  }
  // Open-vocabulary hardening: an off-vocabulary status still gets an honest
  // evidence sentence — never the string "undefined" on a trust surface.
  return age
    ? `Status recorded as "${String(a)}" — last heard from ${age}.`
    : `Status recorded as "${String(a)}" — no readable report time on record.`;
}

export function healthMeta(h: IntegrationHealth | string | null | undefined): { label: string; tone: Tone } {
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
  // Open-vocabulary hardening (NEW-3 sweep, the closed-switch class).
  if (h == null || h === '') return { label: `Health ${NOT_RECORDED}`, tone: 'unknown' };
  return { label: `Recorded as "${h}"`, tone: 'unknown' };
}

export function runStatusMeta(s: RunStatus | string | null | undefined): { label: string; tone: Tone } {
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
  // The live-wire hardening: a null status is said plainly; an unrecognized
  // string is shown as recorded (honest fallback, never a crash, never a guess).
  if (s == null || s === '') return { label: `Outcome ${NOT_RECORDED}`, tone: 'unknown' };
  return { label: `Recorded as "${s}"`, tone: 'unknown' };
}

export function verdictMeta(v: NonFiringVerdict | string | null | undefined): { label: string; tone: Tone } {
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
  // Open-vocabulary hardening (NEW-3 sweep; the runStatusMeta precedent + the
  // §4a law): a value this mapping does not cover renders in the honest
  // register — never a crash (`.tone` of undefined was a live crash class),
  // never invented meaning, never success.
  if (v == null || v === '') return { label: `Verdict ${NOT_RECORDED}`, tone: 'unknown' };
  return { label: `Recorded as "${v}"`, tone: 'unknown' };
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
export function attrValueList(params: Record<string, unknown> | null | undefined): string {
  const entries = Object.entries(params ?? {});
  if (entries.length === 0) return '';
  return ' (' + entries.map(([k, v]) => `${k} ${String(v)}`).join(', ') + ')';
}

/* ---- The hero device-backward sentence (the mom test) ---- */
export function causalSentence(
  chain: CausalChain,
  // FE-HONEST-1 (§10-J): the headline must not paraphrase over a dangling ref.
  // With a complete-census resolver, an unresolvable id appears VERBATIM with
  // the registry fact; without one (the default), nothing is accused.
  resolve: (id: string | null | undefined) => RefLook = () => ({ kind: 'unverified' }),
): string {
  // Live-wire hardening (FE-LIVE-V112 item 1): every field the wire has served
  // null — or could omit — is guarded; the sentence stays honest, never invents.
  const trigger = chain.trigger;
  const trigId = trigger?.subjectRef?.id;
  const trigRes = resolve(trigId);
  const triggerSubject =
    trigId && trigRes.kind === 'dangling' ? `entity ${trigId} (${UNRESOLVED_REF_PHRASE})` : refLabel(trigId, trigRes);
  const triggerVerb = triggerVerbFromValue(trigger?.firingValue ?? null);
  // Date-qualified (NEW-6): a run can be days old; "at 9:40 AM" alone would
  // read as this morning. Same-day runs stay clock-only (the mom-test budget).
  const when = clockTimeWithDate(trigger?.matchedAt);
  const actions = chain.actions ?? [];
  const outcome = chain.outcome;
  // The silent-skip class: the run finished without doing anything visible —
  // say so up front, never a sentence that implies something happened.
  if (actions.length === 0 && (outcome?.actionCount ?? 0) > 0 && (outcome?.commandCount ?? 0) === 0) {
    return `${runName(chain.automationName)} ran when ${triggerSubject} ${triggerVerb} at ${when}, but nothing was changed.`;
  }
  const action = actions[0];
  const targetId = action?.targetRef?.id;
  const targetRes = resolve(targetId);
  const target = action
    ? targetId && targetRes.kind === 'dangling'
      ? `Entity ${targetId} (${UNRESOLVED_REF_PHRASE})`
      : refLabel(targetId, targetRes)
    : runName(chain.automationName);
  const verb = action ? commandVerb(action.command) : 'ran';
  return `${target} ${verb} because ${triggerSubject} ${triggerVerb} at ${when}.`;
}

function commandVerb(command: string | null | undefined): string {
  switch (command) {
    case 'turn_on':
      return 'turned on';
    case 'turn_off':
      return 'turned off';
    case 'dim':
      return 'dimmed';
    default:
      // A null command is the present-but-null class: say it acted without
      // naming a command it doesn't have — never render "null" as a verb.
      return command ? `ran "${command}"` : 'acted';
  }
}

/** The trigger verb from `firingValue` — OBSERVED NULL ON THE LIVE WIRE in all
 *  eras (the `.toLowerCase()` crash field, 2026-07-27 chain-glance return).
 *  Null → the plain verb with NO parenthetical: the value's absence is disclosed
 *  in the trigger step's detail as "value not recorded", never invented here. */
export function triggerVerbFromValue(firingValue: string | null | undefined): string {
  if (firingValue == null || firingValue === '') return 'changed';
  const v = firingValue.toLowerCase();
  if (v.includes('motion')) return 'detected motion';
  if (v.includes('open')) return 'was opened';
  if (v.includes('close')) return 'was closed';
  return `changed (${firingValue})`;
}

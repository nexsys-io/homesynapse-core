/*
 * HomeSynapse — Plain-language formatting (the "mom-test" layer).
 * ---------------------------------------------------------------------------
 * The stranger test lives here: every label and sentence must read aloud and be
 * right to someone who has never seen the system. Device-backward, <= ~20 words,
 * no index paths, no internal jargon. Status is carried by tone + label TEXT (the
 * component adds the icon shape) — never color alone (WCAG 1.4.1).
 */
import type {
  Availability,
  CausalChain,
  IntegrationHealth,
  NonFiringVerdict,
  Origin,
  RunStatus,
  TypedValue,
} from './api/contract';
import { t, type MessageKey } from './i18n';
import { actionVerdict, commandKind } from './verdicts';

export type Tone = 'ok' | 'warn' | 'error' | 'info' | 'unknown' | 'neutral';

/* ---- The honest-absence marker (FE-LIVE-V112 item 1) ----
 * The LIVE wire serves optionals PRESENT-BUT-NULL beside populated siblings
 * (field evidence, 2026-07-27): the real seam is absent / null / value. Where a
 * value is null, the surface says so in words — never a placeholder that could
 * be mistaken for data, never the string "null", never an invented value.
 * FE-114 D8: the §7 row `explain.detail.notRecorded` is the one home (byte-identical to the former literal). */
export const NOT_RECORDED = t('explain.detail.notRecorded');

/* ---- FE-NULL-1 (2026-09-10): the causal chain's REQUIRED-NULLABLE arms — the honest
 * sentences HERO-0 wrote (context/research/2026-09-06_HERO-0_null-census_v1.1.3_return.md
 * §1). Each is name-light and test-locked; a null renders as the sentence, never as a
 * blank, never as "null", never as an invented name or verb, and a null ref accuses no
 * registry (it is not a dangling id). */
/** `trigger.subjectRef` null — the triggering event is outside the run's correlation. */
export function unrecordedTriggerLine(when: string): string {
  return `Something set it off at ${when} — what isn't recorded.`;
}
/** `observedState[].value` null — the §7 row `explain.condition.noReading` ("{entity} {attr} had no reading
 *  yet."), read through heroCopy (FE-114 D8); `NO_READING_YET` is the sentence's tail, the test-side name. */
export const NO_READING_YET = 'had no reading yet.';
export function noReadingLine(entityLabel: string, attribute: string): string {
  return heroCopy('explain.condition.noReading', { entity: entityLabel, attr: attribute });
}
/** `actions[].command` null — a SKIPPED/FAILED action that never issued a command. */
export const SKIPPED_BEFORE_COMMAND = t('explain.mode.skipped.line'); // the §7 row (HERO-1c: the chain renders the key; this constant is its test-side name)
/** `actions[].targetRef` null — the action line ends with this; no target is named, none accused.
 *  FE-114 D8: the §7 row `explain.slot.target.unnamed` is the one home (a sixth twin the census missed). */
export const UNNAMED_TARGET = t('explain.slot.target.unnamed');
/** `cascade.depth > 0` with `parentRunId` null (always null in V1 — F4): started by a run the record cannot name.
 *  FE-114 D8: the §7 row `explain.cascade.parentUnrecorded` is the one home. */
export const CASCADE_PARENT_UNRECORDED = t('explain.cascade.parentUnrecorded');

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

/** The device LIST on a hub that does not serve `lastReported` on the list
 *  (pre-v1.1.3 — the key ABSENT from the A1 row) makes NO freshness claim
 *  (§10-I: "Current" with no evidence was a lie by omission). This title explains
 *  that em-dash — test-locked; i18n-keyed (FE-113). */
export const LIST_FRESHNESS_NO_CLAIM_TITLE = t('devices.freshness.noClaimTitle');

/** The OTHER fact (FE-113, v1.1.3): the key is PRESENT BUT NULL — this hub serves
 *  report times on the list and has none on record for this entity. Two facts,
 *  two sentences: absence ≠ null (FE-HONEST-1 §10-H/I). Test-locked. */
export const LIST_FRESHNESS_NULL_TITLE = t('devices.freshness.nullTitle');

/* ---- Command outcome: the format-side map RETIRED (HERO-1c C3) — the verdict layer
 * (verdicts.actionVerdict, one source per mode from the §7 catalog) is the command-
 * outcome rendering; its open-vocabulary arm carries the former NEW-3 pin. ---- */

/* ---- Measured confirmation-rendering semantics (AMD-97, ratified 2026-07-01) ----
 * The moat's honesty is a UI behavior too. The UI NEVER runs its own confirmation
 * timeout — the backend owns the per-capability window (Doc 08 §3.6 `confirmation[]`,
 * measured in nexsys-bench/corpus/) and the poll renders each transition when the
 * projection advances. These hints are presentation-level plain language keyed on the
 * COMMAND CLASS only: no numbers, no timers, no hardcoded global (SK-INV-01-safe).
 */

/* The command CLASS (`commandKind`) lives in verdicts.ts since HERO-1c C2 — the verdict
 * layer picks the effect-class help and cannot import this module (a cycle); it is
 * re-exported here unchanged, so every caller keeps its import. */
export { commandKind, type CommandKind } from './verdicts';

/** Shown while an action is DISPATCHED (pending). Color-class capabilities legitimately
 *  confirm slowly (measured: batched color reporting) — say so calmly, so waiting reads
 *  as normal, never as failure. Returns null when there is nothing useful to add. */
export function pendingHint(command: string | null | undefined): string | null {
  if (commandKind(command) === 'color') {
    return t('explain.action.pending.color'); // the §7 row — FE-114 D6 (IR-14): one home for the sentence; byte-identical to the former literal
  }
  return null;
}

/** Shown when an effect/identify-class action lands UNCONFIRMED: these devices acknowledge
 *  the command but never report doing it, so an immediate honest "not confirmed" is the
 *  EXPECTED behavior — not a fault. Returns null for other command kinds. */
export function unconfirmableHint(command: string | null | undefined): string | null {
  if (commandKind(command) === 'effect') {
    return t('explain.mode.ackedSilent.unconfirmable'); // the §7 row, byte-identical to the former literal (HERO-1c C2)
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
  return name ?? t('explain.slot.automation.earlier'); // the §7 row — FE-114 D8: one home, byte-identical
}

/** One plain sentence explaining WHY a run can have no name — shown wherever the
 *  null-name class surfaces (calm, honest; not an error). FE-114 D8: the §7 row
 *  `explain.nullName.note` is the one home (byte-identical to the former literal). */
export const NULL_NAME_NOTE = t('explain.nullName.note');

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

/* ---- The hero L1 headline — the SPEC §3 grammar (HERO-1b B1, 2026-09-12) ----
 * The headline follows the RUN'S STATUS and the LEADING ACTION'S OUTCOME, never
 * the command's verb alone (the FE-NULL-1 O1 defect: `commandVerb` turned
 * `turn_on` into "turned on" on a SKIPPED run). A COMPLETED run is device-led;
 * a run that did not complete is automation-led — a frame sentence, then one
 * tail sentence about the leading device — so a skipped or failed run never
 * opens with a device acting. The mode override comes from `actionVerdict()`
 * (never re-derived here): a superseded or expired-at-restart leading action
 * replaces the cell's clause — a replaced command is an intent change, and the
 * headline may not say "failed" for it. Every slot has a keyed null arm (§7
 * `explain.slot.*`) so no cell is left to inference; a value the wire did not
 * carry is never shown as if it had. Templates are keyed by their §7 key. */

/** A §7 string by key (B2: the catalog in i18n.ts is the single source; an unknown key
 *  is the empty string, never "undefined" on a surface). */
function hs(key: string): string {
  return t(key as MessageKey) ?? '';
}

/** Fill `{slot}` placeholders in a §7 string; a slot the template does not use is ignored. */
export function fillSlots(template: string, slots: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in slots ? slots[k]! : m));
}
const fill = fillSlots;

/** A §7 string with its slots filled (the one call sites use). */
export function heroCopy(key: MessageKey, slots: Record<string, string> = {}): string {
  return fillSlots(t(key) ?? '', slots);
}
const cap = (s: string) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

/** The command's verbs: plain (turn on) and past (turned on); the §7 null arms. */
export function commandVerbs(command: string | null | undefined): { verb: string; verbPast: string } {
  switch (command) {
    case 'turn_on':
      return { verb: 'turn on', verbPast: 'turned on' };
    case 'turn_off':
      return { verb: 'turn off', verbPast: 'turned off' };
    case 'dim':
      return { verb: 'dim', verbPast: 'dimmed' };
  }
  if (command == null || command === '') return { verb: hs('explain.slot.verb.null'), verbPast: hs('explain.slot.verbPast.null') };
  return { verb: fill(hs('explain.slot.verb.unknown'), { command }), verbPast: fill(hs('explain.slot.verbPast.unknown'), { command }) };
}

/** The `{time}` slot: date-qualified clock time, or the honest unparseable arm. */
export function headlineTime(matchedAt: string | null | undefined): string {
  return parseInstant(matchedAt) ? clockTimeWithDate(matchedAt) : hs('explain.slot.time.unparseable');
}

/** The `{because}` slot with its null arms: subjectRef null → the unrecorded arm;
 *  a dangling ref on a complete census → the loud arm; firingValue null → "changed". */
export function becauseClause(
  trigger: CausalChain['trigger'] | null | undefined,
  resolve: (id: string | null | undefined) => RefLook = () => ({ kind: 'unverified' }),
): string {
  const time = headlineTime(trigger?.matchedAt);
  const id = trigger?.subjectRef?.id;
  if (!id) return fill(hs('explain.slot.because.unrecorded'), { time });
  const res = resolve(id);
  if (res.kind === 'dangling') return fill(hs('explain.slot.because.dangling'), { id, time });
  const triggerVerb = trigger?.firingValue == null || trigger.firingValue === '' ? hs('explain.slot.triggerVerb.null') : triggerVerbFromValue(trigger.firingValue);
  return fill(hs('explain.slot.because'), { Trigger: refLabel(id, res), triggerVerb, time });
}

/** The `{target}` slot (lower-case form; callers capitalise for `{Target}`). */
function targetSlot(action: CausalChain['actions'][number], resolve: (id: string | null | undefined) => RefLook): string {
  const id = action.targetRef?.id;
  if (!id) return hs('explain.slot.target.unnamed');
  const res = resolve(id);
  if (res.kind === 'dangling') return fill(hs('explain.slot.target.dangling'), { id });
  return refLabel(id, res);
}

export interface CausalHeadline {
  text: string;
  /** The §7 key(s) the sentence was rendered from — one COMPLETED key, or frame + tail. */
  keys: string[];
}

const FRAME_KEY: Record<string, string> = {
  SKIPPED: 'explain.headline.skipped.frame',
  FAILED: 'explain.headline.failed.frame',
  CANCELLED: 'explain.headline.cancelled.frame',
  INTERRUPTED: 'explain.headline.interrupted.frame',
};
const OUTCOME_SUFFIX: Record<string, string> = {
  CONFIRMED: 'confirmed',
  DISPATCHED: 'dispatched',
  UNCONFIRMED: 'unconfirmed',
  FAILED: 'failed',
  SKIPPED: 'skipped',
};

/** The L1 headline with the keys it came from (SPEC §3). */
export function causalHeadline(
  chain: CausalChain,
  resolve: (id: string | null | undefined) => RefLook = () => ({ kind: 'unverified' }),
): CausalHeadline {
  const because = becauseClause(chain.trigger, resolve);
  const Automation = runName(chain.automationName);
  const actions = chain.actions ?? [];
  const outcome = chain.outcome;
  const status = outcome?.status;
  const action = actions[0];

  // The leading action's clause suffix: the mode override first (never re-derived —
  // actionVerdict() owns superseded / expired-restart), then the outcome cell.
  let suffix: string | null = null;
  let slots: Record<string, string> = { because, Automation };
  if (action) {
    const mode = actionVerdict(action).mode;
    const target = targetSlot(action, resolve);
    const { verb, verbPast } = commandVerbs(action.command);
    slots = { ...slots, target, Target: cap(target), verb, verbPast };
    suffix =
      mode === 'superseded' ? 'superseded'
      : mode === 'expired-restart' ? 'expiredRestart'
      : (OUTCOME_SUFFIX[action.outcome as string] ?? null);
    if (suffix === null) {
      // Open-vocabulary hardening: an outcome this build does not know renders the
      // honest not-recorded line — never success, never a crash (no §7 headline cell
      // exists for it; filed with the hub).
      const line = fill(hs('explain.mode.notRecorded.line'), slots);
      const frameKey = FRAME_KEY[status as string];
      return frameKey
        ? { text: `${fill(hs(frameKey), slots)} ${line}`, keys: [frameKey, 'explain.mode.notRecorded.line'] }
        : { text: cap(line), keys: ['explain.mode.notRecorded.line'] };
    }
  }

  if (status === 'COMPLETED') {
    if (!action) {
      // "No action": recorded no steps (actionCount 0) vs the silent skip
      // (actionCount > 0, commandCount 0 — planned steps, nothing sent).
      const key =
        (outcome?.actionCount ?? 0) > 0 && (outcome?.commandCount ?? 0) === 0
          ? 'explain.headline.completed.silentSkip'
          : 'explain.headline.completed.none';
      return { text: fill(hs(key), slots), keys: [key] };
    }
    const key = `explain.headline.completed.${suffix}`;
    return { text: fill(hs(key), slots), keys: [key] };
  }

  const frameKey = FRAME_KEY[status as string];
  const tailKey = action ? `explain.headline.tail.${suffix}` : 'explain.headline.tail.none';
  const tail = fill(hs(tailKey), slots);
  if (!frameKey) {
    // A status this build does not know (the closed-switch class): no frame is
    // invented — the recorded status is shown as recorded, then the honest tail.
    const shown = `${runStatusMeta(status).label}.`;
    return { text: tail ? `${shown} ${tail}` : shown, keys: [tailKey] };
  }
  const frame = fill(hs(frameKey), slots);
  return { text: tail ? `${frame} ${tail}` : frame, keys: [frameKey, tailKey] };
}

/* ---- The hero device-backward sentence (the mom test) — the L1 headline text. ---- */
export function causalSentence(
  chain: CausalChain,
  // FE-HONEST-1 (§10-J): the headline must not paraphrase over a dangling ref.
  // With a complete-census resolver, an unresolvable id appears VERBATIM with
  // the registry fact; without one (the default), nothing is accused.
  resolve: (id: string | null | undefined) => RefLook = () => ({ kind: 'unverified' }),
): string {
  return causalHeadline(chain, resolve).text;
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

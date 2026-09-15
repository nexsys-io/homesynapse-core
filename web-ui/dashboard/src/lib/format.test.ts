/*
 * The "mom test" in code. These assertions lock the plain-language rules: a
 * stranger reads the output and is right. If someone makes the copy more technical,
 * these fail.
 */
import { describe, it, expect } from 'vitest';
import {
  availabilityEvidence,
  availabilityMeta,
  brightnessDisplay,
  CASCADE_PARENT_UNRECORDED,
  causalSentence,
  danglingTargetLine,
  danglingTriggerLine,
  heroCopy,
  labelFor,
  lastReportedCell,
  LIST_FRESHNESS_NO_CLAIM_TITLE,
  LIST_FRESHNESS_NULL_TITLE,
  NO_READING_YET,
  noReadingLine,
  NOT_RECORDED,
  NULL_NAME_NOTE,
  originMeta,
  refLabel,
  runName,
  runStatusMeta,
  timeAgo,
  UNNAMED_TARGET,
  UNRESOLVED_REF_PHRASE,
  UNRESOLVED_REF_PILL,
  verdictMeta,
} from './format';
import { causalChains } from './api/mock/mockData';
import { BRAND, t } from './i18n';

describe('plain-language formatting', () => {
  it('humanizes entity ids into readable names', () => {
    expect(labelFor('ent_hallway_light')).toBe('Hallway Light');
    expect(labelFor('ent_livingroom_lamp')).toBe('Livingroom Lamp');
  });

  it('writes a device-backward causal sentence a stranger understands', () => {
    const s = causalSentence(causalChains['run_eh_001']!);
    expect(s).toMatch(/^Hallway Light turned on because Hallway Motion detected motion at /);
    // No index paths, no internal jargon.
    expect(s).not.toMatch(/conditions\/\d/);
    expect(s.split(' ').length).toBeLessThanOrEqual(20);
  });

  // 'tells the honest command-outcome truth' (format.ts's command-outcome map) retired by HERO-1c C3 — the
  // command-outcome truth is verdicts.test.ts's (actionVerdict: modes, labels, helps).

  it('never leaves origin a silent blank — UNKNOWN is an honest value', () => {
    expect(originMeta('UNKNOWN').label).toBe('Unknown');
    expect(originMeta('EXTERNAL').phrase).toBe(`outside ${BRAND.productName}`);
  });

  it('maps the three-way non-firing verdict to plain language', () => {
    expect(verdictMeta('CONDITION_NOT_MET').label).toMatch(/condition/i);
    expect(verdictMeta('NEVER_TRIGGERED').label).toMatch(/nothing/i);
    expect(verdictMeta('ACTED_BUT_UNCONFIRMED').label).toMatch(/never confirmed/i);
    expect(verdictMeta('DISABLED').label).toMatch(/off/i);
  });

  it('renders relative time in words', () => {
    const now = Date.parse('2026-06-26T12:00:00Z');
    expect(timeAgo(new Date(now - 3000).toISOString(), now)).toBe('just now');
    expect(timeAgo(new Date(now - 3 * 60_000).toISOString(), now)).toBe('3 min ago');
    expect(timeAgo(null, now)).toBe('never');
  });

  it('labels run status plainly', () => {
    expect(runStatusMeta('COMPLETED').label).toBe('Completed');
    expect(runStatusMeta('SKIPPED').tone).toBe('unknown');
  });
});

describe('availability is evidence-with-age — never the flag alone', () => {
  const now = Date.parse('2026-07-19T12:00:00Z');
  const ago = (min: number) => new Date(now - min * 60_000).toISOString();

  it('pairs AVAILABLE with when the device was last heard from (the rehydrated-flag exhibit)', () => {
    const s = availabilityEvidence('AVAILABLE', ago(2 * 24 * 60), now);
    expect(s).toMatch(/^Available — last heard from 2 days ago\./);
  });

  it('renders offline with its evidence age and the recheck cadence, calmly', () => {
    const s = availabilityEvidence('UNAVAILABLE', ago(9), now);
    expect(s).toMatch(/Offline — last heard from 9 min ago/);
    expect(s).toMatch(/rechecked every few minutes/);
  });

  it('renders honest UNKNOWN after a restart as normal, not a fault', () => {
    const s = availabilityEvidence('UNKNOWN', null, now);
    expect(s).toMatch(/waiting for the device’s first report/i);
    expect(s).toMatch(/normal/i);
    expect(s).not.toMatch(/error|wrong|fail/i);
  });

  /* G2 honesty locks (Rosonway §5.3, measured: AVAILABLE with staleAfter null
   * and hours-old lastReported is lawful): the flag copy must never claim live
   * radio contact, and the honest UNKNOWN state must never read as a fault. */
  it('AVAILABLE is presented as a conclusion from reports — NEVER a live-contact claim', () => {
    const m = availabilityMeta('AVAILABLE');
    expect(m.help).toMatch(/not a live connection test/i);
    expect(m.help).toMatch(/last concluded|concluded from/i);
    expect(m.help).not.toMatch(/\bonline\b|connected right now|live contact/i);
  });

  it('UNAVAILABLE states its evidence and the recheck cadence, calmly', () => {
    const m = availabilityMeta('UNAVAILABLE');
    expect(m.help).toMatch(/evidence/i);
    expect(m.help).toMatch(/rechecked every few minutes/i);
  });

  it('UNKNOWN-at-boot is labeled honestly and reads as normal, never a fault', () => {
    const m = availabilityMeta('UNKNOWN');
    expect(m.label).toBe('Not determined yet');
    expect(m.help).toMatch(/honest|normal/i);
    expect(m.help).not.toMatch(/error|wrong|fail/i);
  });
});

describe('the null-name run class (prior-instance runs) renders honestly', () => {
  it('never invents a name for a null', () => {
    expect(runName(null)).toBe('An earlier automation');
    expect(runName('Evening Hallway Light')).toBe('Evening Hallway Light');
  });

  it('explains WHY the name is missing, calmly (no blame, no alarm)', () => {
    expect(NULL_NAME_NOTE).toMatch(/earlier version/i);
    expect(NULL_NAME_NOTE).toMatch(/preserved/i);
  });
});

describe('brightness percent comes from the DERIVED key, never a client rescale', () => {
  it('prefers the hub-derived brightness_percent', () => {
    const b = brightnessDisplay({
      brightness: { t: 'NUMBER', v: 209 },
      brightness_percent: { t: 'PERCENT', v: 82 },
    });
    expect(b).toEqual({ key: 'brightness_percent', text: '82%' });
  });

  it('shows the canonical level honestly when no derived percent is present — NOT 209/254 rescaled', () => {
    const b = brightnessDisplay({ brightness: { t: 'NUMBER', v: 209 } });
    expect(b).toEqual({ key: 'brightness', text: 'level 209 of 254' });
    expect(b!.text).not.toContain('%');
  });

  it('returns null when the entity has no brightness at all', () => {
    expect(brightnessDisplay({ power: { t: 'BOOL', v: true } })).toBeNull();
  });
});

describe('the silent-skip run sentence (do-nothing runs never read as success)', () => {
  it('says up front that nothing was changed', () => {
    const chain = structuredClone(causalChains['run_eh_001']!);
    chain.actions = [];
    chain.outcome = { status: 'COMPLETED', reason: null, durationMs: 41, actionCount: 2, commandCount: 0 };
    const s = causalSentence(chain);
    expect(s).toMatch(/nothing was changed\.$/);
    expect(s).not.toMatch(/turned on/);
  });
});

/* ---- FE-HONEST-1: the loud unresolvable-ref register (§10-J) + store-truth
 * Last-reported (§10-G/I). These locks are the point of the lane: the copy that
 * surfaces a dangling ref, and the copy that stops the evidence-free freshness
 * claim, must not quietly soften. */
const ULID = '01KX1PB9AAB4VB3E10BD477TV3'; // the R-4 §10-J field exhibit, verbatim

describe('the loud unresolvable-ref register (§10-J)', () => {
  it('names the ULID verbatim and says "not in this hub’s registry" — never a paraphrase', () => {
    const line = danglingTriggerLine(ULID, 'changed', '9:42 PM');
    expect(line).toContain(ULID);
    expect(line).toContain(UNRESOLVED_REF_PHRASE);
    expect(UNRESOLVED_REF_PHRASE).toBe('not in this hub’s registry');
    const act = danglingTargetLine('Turned on', ULID);
    expect(act).toContain(ULID);
    expect(act).toContain(UNRESOLVED_REF_PHRASE);
    expect(UNRESOLVED_REF_PILL).toBe('Not in registry');
  });

  it('refLabel: registry name > verbatim ULID when dangling > humanized fallback; never accuses unverified', () => {
    expect(refLabel(ULID, { kind: 'named', name: 'Hallway Motion' })).toBe('Hallway Motion');
    expect(refLabel(ULID, { kind: 'dangling' })).toBe(ULID); // verbatim — correlates with the log
    expect(refLabel('ent_hallway_light', { kind: 'unverified' })).toBe('Hallway Light');
    expect(refLabel(null, { kind: 'unverified' })).toBe('Something not on record');
  });

  it('the headline goes loud with a dangling resolver — and stays neutral without one', () => {
    const chain = structuredClone(causalChains['run_eh_001']!);
    chain.trigger.subjectRef = { type: 'ENTITY', id: ULID };
    const loud = causalSentence(chain, () => ({ kind: 'dangling' }));
    expect(loud).toContain(ULID);
    expect(loud).toContain(UNRESOLVED_REF_PHRASE);
    // No resolver wired (no census): no accusation — the pre-existing neutral render.
    const neutral = causalSentence(chain);
    expect(neutral).not.toContain(UNRESOLVED_REF_PHRASE);
  });
});

describe('store-truth Last-reported (§10-G) and the no-claim list (§10-I)', () => {
  it('a readable stamp renders; an on-record-but-unreadable stamp says so — never "not recorded"', () => {
    expect(lastReportedCell(null)).toBe('—');
    expect(lastReportedCell(new Date().toISOString())).not.toBe('—');
    // The /state dialect class: the wire serves a non-string where the contract
    // says ISO string. The store HOLDS the row — the cell must say "on record".
    expect(lastReportedCell(1756500000.123 as unknown as string)).toBe('On record — unreadable by this dashboard');
  });

  it('availabilityEvidence distinguishes no-report from unreadable-report (store truth)', () => {
    expect(availabilityEvidence('AVAILABLE', null)).toBe('Available — no report received yet.');
    expect(availabilityEvidence('AVAILABLE', 1756500000.123 as unknown as string)).toBe(
      'Available — a report time is on record, but this dashboard cannot read it yet.',
    );
  });

  it('the list makes no freshness claim it cannot evidence', () => {
    expect(LIST_FRESHNESS_NO_CLAIM_TITLE).toContain('open the device');
  });

  /* FE-113 (v1.1.3): the list row now CAN carry `lastReported`. Two facts, two
   * sentences (FE-HONEST-1 §10-H/I): the key ABSENT = a hub that does not serve
   * a report time on the list (pre-v1.1.3) — the no-claim title; the key PRESENT
   * BUT NULL = this v1.1.3 hub has nothing on record — a different sentence. */
  it('a present-but-null report time has its OWN sentence — never the pre-v1.1.3 no-claim title', () => {
    expect(LIST_FRESHNESS_NULL_TITLE).toBe('This hub has no report time on record for this entity.');
    expect(LIST_FRESHNESS_NULL_TITLE).not.toBe(LIST_FRESHNESS_NO_CLAIM_TITLE);
    expect(LIST_FRESHNESS_NULL_TITLE).not.toContain('open the device'); // absence ≠ null: no redirect for a fact that IS on the wire
  });

  it('lastReportedCell reads the v1.1.3 wire form (Instant.toString(), nanos) — never an em-dash, never 1970', () => {
    const cell = lastReportedCell('2026-09-06T02:45:29.123456Z');
    expect(cell).not.toBe('—');
    expect(cell).not.toContain('1970');
    expect(cell).not.toBe('On record — unreadable by this dashboard');
  });
});

/* ---- HERO-1b B1 — the L1 headline grammar (SPEC §3, design/hero-v1/SPEC.md:25–:66).
 * One assertion per cell of the 30-row table at the sample slots (Hallway Light ·
 * Hallway Motion · Evening Lights · 9:42 pm), the mode overrides, and every slot
 * null arm. RED at HEAD: HEAD's `causalSentence` writes "{Target} {verbPast}
 * because {because}." for EVERY status and outcome (format.ts:472–:517), so a
 * SKIPPED run read "Hallway Light turned on because…" — the FE-NULL-1 O1 defect.
 * Disclosed GREEN-at-HEAD by construction: row 1 (the happy path), row 6's silent
 * skip, and the targetRef-null CONFIRMED arm (HEAD already wrote those three). */
import { causalHeadline } from './format';
import type { CausalChain, RunStatus, ActionOutcome } from './api/contract';

const AT = (() => {
  const d = new Date();
  d.setHours(21, 42, 0, 0); // the sample slot: 9:42 pm today (date-qualified by clockTimeWithDate)
  return d.toISOString();
})();
const WHEN = (() => {
  // the exact {time} slot as the formatter writes it (locale-cased "9:42 PM")
  const d = new Date(AT);
  return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
})();
const BECAUSE = `Hallway Motion detected motion at ${WHEN}`;

type Over = Partial<{
  command: string | null;
  targetRef: CausalChain['actions'][number]['targetRef'];
  resultOutcome: string | null;
  settled: boolean;
  automationName: string | null;
  subjectRef: CausalChain['trigger']['subjectRef'];
  firingValue: string | null;
  matchedAt: string;
  actionCount: number;
  commandCount: number;
}>;

function mk(status: RunStatus, outcome: ActionOutcome | null, over: Over = {}): CausalChain {
  const c = structuredClone(causalChains['run_eh_001']!);
  c.automationName = over.automationName === undefined ? 'Evening Lights' : over.automationName;
  c.trigger.matchedAt = over.matchedAt ?? AT;
  c.trigger.firingValue = over.firingValue === undefined ? 'motion = detected' : over.firingValue;
  if (over.subjectRef !== undefined) c.trigger.subjectRef = over.subjectRef;
  c.outcome.status = status;
  if (outcome === null) {
    c.actions = [];
    c.outcome.actionCount = over.actionCount ?? 0;
    c.outcome.commandCount = over.commandCount ?? 0;
  } else {
    const a = c.actions[0]!;
    a.outcome = outcome;
    a.resultOutcome = over.resultOutcome === undefined ? null : over.resultOutcome;
    a.settled = over.settled ?? outcome !== 'DISPATCHED';
    if (over.command !== undefined) a.command = over.command;
    if (over.targetRef !== undefined) a.targetRef = over.targetRef;
  }
  return c;
}

describe('HERO-1b B1 — the 30-cell headline table (SPEC §3)', () => {
  const S = 'Evening Lights skipped this run when';
  const F = 'Evening Lights failed part-way when';
  const C = 'Evening Lights was cancelled after';
  const I = 'Evening Lights was cut off before it finished, after';
  const tails: [ActionOutcome | null, string][] = [
    ['CONFIRMED', ' Hallway Light did turn on first, and confirmed it.'],
    ['DISPATCHED', ' Hallway Light was asked to turn on; no confirmation has come back.'],
    ['UNCONFIRMED', ' Hallway Light was asked to turn on; it never confirmed.'],
    ['FAILED', ' The command to Hallway Light failed.'],
    ['SKIPPED', ' Nothing was sent to Hallway Light.'],
    [null, ''],
  ];
  const rows: [number, RunStatus, ActionOutcome | null, string][] = [
    [1, 'COMPLETED', 'CONFIRMED', `Hallway Light turned on because ${BECAUSE}.`],
    [2, 'COMPLETED', 'DISPATCHED', `Hallway Light was asked to turn on because ${BECAUSE} — no confirmation yet.`],
    [3, 'COMPLETED', 'UNCONFIRMED', `Hallway Light was asked to turn on because ${BECAUSE} — it never confirmed.`],
    [4, 'COMPLETED', 'FAILED', `Hallway Light was asked to turn on because ${BECAUSE}, but the command failed.`],
    [5, 'COMPLETED', 'SKIPPED', `Nothing was sent to Hallway Light when ${BECAUSE} — that step was skipped.`],
    [6, 'COMPLETED', null, `Evening Lights ran when ${BECAUSE} and recorded no steps.`],
  ];
  const frames: [number, RunStatus, string][] = [[7, 'SKIPPED', S], [13, 'FAILED', F], [19, 'CANCELLED', C], [25, 'INTERRUPTED', I]];
  for (const [n, status, frame] of frames) {
    tails.forEach(([outcome, tail], i) => rows.push([n + i, status, outcome, `${frame} ${BECAUSE}.${tail}`]));
  }

  it.each(rows)('row %i — %s × %s', (_n, status, outcome, expected) => {
    expect(causalSentence(mk(status, outcome))).toBe(expected);
  });

  it('row 6, the silent skip (actionCount > 0, commandCount 0) [GREEN at HEAD — the FE-LIVE sentence, preserved]', () => {
    expect(causalSentence(mk('COMPLETED', null, { actionCount: 2, commandCount: 0 }))).toBe(
      `Evening Lights ran when ${BECAUSE}, but nothing was changed.`,
    );
  });

  it('every headline sentence is ≤ 21 words at the sample slots (SPEC §3)', () => {
    for (const [, status, outcome] of rows) {
      for (const sentence of causalSentence(mk(status, outcome)).split(/(?<=\.)\s+/)) {
        expect(sentence.split(/\s+/).length).toBeLessThanOrEqual(21);
      }
    }
  });

  it('reports the §7 keys it rendered from (frame + tail, or the one COMPLETED key)', () => {
    expect(causalHeadline(mk('COMPLETED', 'CONFIRMED')).keys).toEqual(['explain.headline.completed.confirmed']);
    expect(causalHeadline(mk('SKIPPED', 'SKIPPED')).keys).toEqual(['explain.headline.skipped.frame', 'explain.headline.tail.skipped']);
    expect(causalHeadline(mk('FAILED', null)).keys).toEqual(['explain.headline.failed.frame', 'explain.headline.tail.none']);
  });
});

describe('HERO-1b B1 — the mode override via actionVerdict() (superseded · expired-restart)', () => {
  it('COMPLETED × DISPATCHED/superseded → the superseded clause, never "no confirmation yet"', () => {
    const s = causalSentence(mk('COMPLETED', 'DISPATCHED', { resultOutcome: 'superseded', settled: true }));
    expect(s).toBe(`Hallway Light was asked to turn on because ${BECAUSE}, then a newer command replaced it.`);
  });
  it('COMPLETED × FAILED/superseded → the superseded clause, never "the command failed"', () => {
    const s = causalSentence(mk('COMPLETED', 'FAILED', { resultOutcome: 'superseded' }));
    expect(s).toBe(`Hallway Light was asked to turn on because ${BECAUSE}, then a newer command replaced it.`);
    expect(s).not.toMatch(/fail/i);
  });
  it('COMPLETED × FAILED/expired_on_restart → the expired-restart clause', () => {
    const s = causalSentence(mk('COMPLETED', 'FAILED', { resultOutcome: 'expired_on_restart' }));
    expect(s).toBe(`Hallway Light was asked to turn on because ${BECAUSE}; the hub restarted before it could confirm.`);
  });
  it('a non-completed frame keeps its frame and swaps the TAIL (SKIPPED × DISPATCHED/superseded — the §10 sentence 6)', () => {
    const s = causalSentence(mk('SKIPPED', 'DISPATCHED', { resultOutcome: 'superseded', settled: true }));
    expect(s).toBe(`Evening Lights skipped this run when ${BECAUSE}. Hallway Light was asked to turn on, then a newer command replaced it.`);
  });
  it('FAILED × FAILED/expired_on_restart → frame + the expired-restart tail', () => {
    const s = causalSentence(mk('FAILED', 'FAILED', { resultOutcome: 'expired_on_restart' }));
    expect(s).toBe(`Evening Lights failed part-way when ${BECAUSE}. Hallway Light was asked to turn on; the hub restarted before it could confirm.`);
  });
  it('UNCONFIRMED/unconfirmed (mode 3, acked-silent) keeps the unconfirmed clause — the pill carries the mode', () => {
    expect(causalSentence(mk('COMPLETED', 'UNCONFIRMED', { resultOutcome: 'unconfirmed' }))).toBe(
      `Hallway Light was asked to turn on because ${BECAUSE} — it never confirmed.`,
    );
  });
});

describe('HERO-1b B1 — every slot null arm (SPEC §3 / §7 explain.slot.*)', () => {
  it('targetRef null, sentence-initial → "A device the run didn\'t name …" [GREEN at HEAD by construction]', () => {
    expect(causalSentence(mk('COMPLETED', 'CONFIRMED', { targetRef: null }))).toBe(`A device the run didn't name turned on because ${BECAUSE}.`);
  });
  it('targetRef null mid-sentence → "…to a device the run didn\'t name"', () => {
    expect(causalSentence(mk('SKIPPED', 'SKIPPED', { targetRef: null }))).toBe(`${'Evening Lights skipped this run when'} ${BECAUSE}. Nothing was sent to a device the run didn't name.`);
  });
  it('a dangling target on a complete census → "entity {id} (not in this hub’s registry)" — verbatim, never paraphrased', () => {
    const c = mk('COMPLETED', 'CONFIRMED', { targetRef: { type: 'ENTITY', id: ULID } });
    const s = causalSentence(c, (id) => (id === ULID ? { kind: 'dangling' } : { kind: 'unverified' }));
    expect(s).toBe(`Entity ${ULID} (${UNRESOLVED_REF_PHRASE}) turned on because ${BECAUSE}.`);
  });
  it('subjectRef null → "…because something set it off at {time} (what isn\'t recorded)"', () => {
    expect(causalSentence(mk('COMPLETED', 'CONFIRMED', { subjectRef: null }))).toBe(
      `Hallway Light turned on because something set it off at ${WHEN} (what isn't recorded).`,
    );
  });
  it('a dangling trigger on a complete census → "entity {id} (not in this hub’s registry) changed at {time}"', () => {
    const c = mk('COMPLETED', 'CONFIRMED', { subjectRef: { type: 'ENTITY', id: ULID } });
    const s = causalSentence(c, (id) => (id === ULID ? { kind: 'dangling' } : { kind: 'unverified' }));
    expect(s).toBe(`Hallway Light turned on because entity ${ULID} (${UNRESOLVED_REF_PHRASE}) changed at ${WHEN}.`);
  });
  it('firingValue null (today: always) → the verb "changed", no invented reading', () => {
    expect(causalSentence(mk('COMPLETED', 'CONFIRMED', { firingValue: null }))).toBe(`Hallway Light turned on because Hallway Motion changed at ${WHEN}.`);
  });
  it('automationName null → "An earlier automation" leads the frame', () => {
    expect(causalSentence(mk('SKIPPED', null, { automationName: null }))).toBe(`An earlier automation skipped this run when ${BECAUSE}.`);
    expect(causalSentence(mk('COMPLETED', null, { automationName: null }))).toBe(`An earlier automation ran when ${BECAUSE} and recorded no steps.`);
  });
  it('a command with no plain verb → run "{command}" / ran "{command}"', () => {
    expect(causalSentence(mk('COMPLETED', 'CONFIRMED', { command: 'set_brightness' }))).toBe(`Hallway Light ran "set_brightness" because ${BECAUSE}.`);
    expect(causalSentence(mk('COMPLETED', 'DISPATCHED', { command: 'set_brightness' }))).toBe(
      `Hallway Light was asked to run "set_brightness" because ${BECAUSE} — no confirmation yet.`,
    );
  });
  it('command null → act / acted (defined so no cell infers; unreachable on a dispatched cell)', () => {
    expect(causalSentence(mk('COMPLETED', 'CONFIRMED', { command: null }))).toBe(`Hallway Light acted because ${BECAUSE}.`);
    expect(causalSentence(mk('COMPLETED', 'UNCONFIRMED', { command: null }))).toBe(`Hallway Light was asked to act because ${BECAUSE} — it never confirmed.`);
  });
  it('an unparseable matchedAt → "at an unrecorded time" — never "—", never 1970', () => {
    const s = causalSentence(mk('COMPLETED', 'CONFIRMED', { matchedAt: 1756500000.123 as unknown as string }));
    expect(s).toBe('Hallway Light turned on because Hallway Motion detected motion at an unrecorded time.');
  });
  it('an outcome string this build does not know renders the honest not-recorded line — never success, never a crash', () => {
    const c = mk('COMPLETED', 'CONFIRMED');
    (c.actions[0] as { outcome: string }).outcome = 'SOMETHING_NEW';
    const s = causalSentence(c);
    expect(s).toBe("What happened to Hallway Light isn't recorded.");
  });
});

describe('HERO-1b — the §10 acceptance sentences the headline layer owns (1 · 2 · 5 · 6), word for word at the sample slots', () => {
  it('(1) why did it fire — the happy path', () => {
    expect(causalSentence(mk('COMPLETED', 'CONFIRMED'))).toBe(`Hallway Light turned on because Hallway Motion detected motion at ${WHEN}.`);
  });
  it('(2) a skipped run never opens with a device acting', () => {
    expect(causalSentence(mk('SKIPPED', 'SKIPPED'))).toBe(`Evening Lights skipped this run when Hallway Motion detected motion at ${WHEN}. Nothing was sent to Hallway Light.`);
  });
  it('(5) sent, never confirmed — calm, no delivery claim', () => {
    expect(causalSentence(mk('COMPLETED', 'UNCONFIRMED'))).toBe(`Hallway Light was asked to turn on because Hallway Motion detected motion at ${WHEN} — it never confirmed.`);
  });
  it('(6) replaced, not failed', () => {
    expect(causalSentence(mk('SKIPPED', 'DISPATCHED', { resultOutcome: 'superseded', settled: true }))).toContain(
      'Hallway Light was asked to turn on, then a newer command replaced it.',
    );
  });
});

/* ---- FE-114 D8 (2026-09-14) — the twins fold: the format-side constants and helpers that carried a sentence
 * the §7 catalog also carried now READ the catalog (one home; the screen bytes unchanged). GREEN at HEAD by
 * construction — the bytes were already equal (preservation; disclosed): these rows lock the fold. ---- */
describe('FE-114 D8 — the twins read the catalog', () => {
  it('each constant and helper equals its §7 row', () => {
    expect(NOT_RECORDED).toBe(t('explain.detail.notRecorded'));
    expect(CASCADE_PARENT_UNRECORDED).toBe(t('explain.cascade.parentUnrecorded'));
    expect(UNNAMED_TARGET).toBe(t('explain.slot.target.unnamed'));
    expect(NULL_NAME_NOTE).toBe(t('explain.nullName.note'));
    expect(runName(null)).toBe(t('explain.slot.automation.earlier'));
    expect(noReadingLine('Sun', 'elevation')).toBe(heroCopy('explain.condition.noReading', { entity: 'Sun', attr: 'elevation' }));
  });

  it('and the screen bytes are HEAD\'s literals, character for character', () => {
    expect(NOT_RECORDED).toBe('not recorded');
    expect(CASCADE_PARENT_UNRECORDED).toBe("Started by another run — which one isn't recorded.");
    expect(UNNAMED_TARGET).toBe("a device the run didn't name");
    expect(runName(null)).toBe('An earlier automation');
    expect(noReadingLine('Sun', 'elevation')).toBe(`Sun elevation ${NO_READING_YET}`);
    expect(noReadingLine('Sun', 'elevation')).toBe('Sun elevation had no reading yet.');
  });
});

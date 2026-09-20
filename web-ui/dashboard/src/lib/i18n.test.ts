/*
 * The brand-token pin (FE-SWAP-GATE). One positive assertion on the VALUE of
 * BRAND.productName so the rename is red-first by construction: every other
 * name-touching assertion in the suite is token-relative (it imports BRAND and
 * follows the flip), so without this row a rename — partial, accidental, or
 * real — passes the whole suite silently. This row is the gate.
 */
import { describe, it, expect } from 'vitest';
import { BRAND } from './i18n';

describe('brand token', () => {
  it('pins the working product name', () => {
    // The rename gate: this row goes RED at the swap and is updated in the same commit as i18n.ts:18 — W-11.
    expect(BRAND.productName).toBe('HomeSynapse');
  });
});

/*
 * HERO-1b B2 (2026-09-12) — the hero strings live behind t() under the SPEC §7 keys
 * (design/hero-v1/SPEC.md:130–:275, 140 keyed rows), and Register C binds them: no
 * product name (BRAND.productName — the name is in a trademark search; `{{NAME}}`
 * is the only sanctioned token and no hero string needs one), no "we" (no
 * self-reference), and the permanence footer names nothing (Q3 ruled (a)).
 * RED at HEAD: none of the 140 keys exists in the catalog (i18n.ts:24–:44 carries
 * 13 keys; the one hero key, `hero.permanence`, carries the product name).
 */
import { t, type MessageKey } from './i18n';
/** The 140 §7 keys, verbatim from SPEC.md:136–:275 (extracted by script, not typed) — minus the six FE-115 D3 retired
 *  (the list is now 134; the retired six are named in the §FE-115 D3 block at the end of this file). */
const SPEC7_KEYS = [
  "explain.headline.completed.confirmed",
  "explain.headline.completed.dispatched",
  "explain.headline.completed.unconfirmed",
  "explain.headline.completed.failed",
  "explain.headline.completed.skipped",
  "explain.headline.completed.none",
  "explain.headline.completed.silentSkip",
  "explain.headline.skipped.frame",
  "explain.headline.failed.frame",
  "explain.headline.cancelled.frame",
  "explain.headline.interrupted.frame",
  "explain.headline.tail.confirmed",
  "explain.headline.tail.dispatched",
  "explain.headline.tail.unconfirmed",
  "explain.headline.tail.failed",
  "explain.headline.tail.skipped",
  "explain.headline.tail.none",
  "explain.headline.tail.superseded",
  "explain.headline.tail.expiredRestart",
  "explain.headline.completed.superseded",
  "explain.headline.completed.expiredRestart",
  "explain.slot.because",
  "explain.slot.because.unrecorded",
  "explain.slot.because.dangling",
  "explain.slot.target.unnamed",
  "explain.slot.target.dangling",
  "explain.slot.automation.earlier",
  "explain.slot.verb.unknown",
  "explain.slot.verbPast.unknown",
  "explain.slot.verb.null",
  "explain.slot.verbPast.null",
  "explain.slot.time.unparseable",
  "explain.slot.triggerVerb.null",
  "whyNot.neverTriggered.body",
  "explain.chain.noDetail.title",
  "explain.chain.noDetail.body",
  "explain.trigger.readingNotRecorded",
  "explain.action.pending.color",
  "explain.mode.confirmed.label",
  "explain.mode.confirmed.line",
  "explain.mode.confirmed.help",
  "explain.mode.heldDispatched.label",
  "explain.mode.heldDispatched.line",
  "explain.mode.heldDispatched.help",
  "explain.mode.timedOut.label",
  "explain.mode.timedOut.line",
  "explain.mode.timedOut.help",
  "explain.mode.superseded.label",
  "explain.mode.superseded.line",
  "explain.mode.superseded.help",
  "explain.mode.ackedSilent.label",
  "explain.mode.ackedSilent.line",
  "explain.mode.ackedSilent.help",
  "explain.mode.ackedSilent.unconfirmable",
  "explain.mode.settledFailed.label",
  "explain.mode.settledFailed.line",
  "explain.mode.settledFailed.help",
  "explain.mode.expiredRestart.label",
  "explain.mode.expiredRestart.line",
  "explain.mode.expiredRestart.help",
  "explain.mode.skipped.label",
  "explain.mode.skipped.line",
  "explain.mode.skipped.lineNamed",
  "explain.mode.notRecorded.label",
  "explain.mode.notRecorded.line",
  "explain.mode.unknownOutcome.label",
  "explain.mode.unknownOutcome.help",
  "explain.terminal.completed",
  "explain.terminal.completed.open",
  "explain.terminal.completed.nothing",
  "explain.terminal.skipped",
  "explain.terminal.failed",
  "explain.terminal.cancelled",
  "explain.terminal.interrupted",
  "whyNot.headline.conditionNotMet",
  "whyNot.headline.conditionNotMet.noTime",
  "whyNot.headline.neverTriggered",
  "whyNot.headline.neverTriggered.ranFine",
  "whyNot.headline.neverTriggered.ranFine.noTime",
  "whyNot.headline.actedButUnconfirmed",
  "whyNot.headline.disabled",
  "whyNot.headline.sentNothing",
  "whyNot.headline.unknown",
  "whyNot.body.conditionNotMet",
  "whyNot.body.actedButUnconfirmed",
  "whyNot.body.disabled",
  "whyNot.body.sentNothing",
  "whyNot.body.ranFine",
  "whyNot.link.conditionNotMet",
  "whyNot.link.ranFine",
  "whyNot.link.actedButUnconfirmed",
  "whyNot.link.sentNothing",
  "whyNot.kv.trigger",
  "whyNot.kv.watching",
  "whyNot.kv.lastChecked",
  "whyNot.kv.lastChecked.unclean",
  "whyNot.kv.neverChecked",
  "explain.hub.title",
  "explain.hub.lede",
  "explain.hub.fire.kicker",
  "explain.hub.fire.title",
  "explain.hub.fire.text",
  "explain.hub.fire.go",
  "explain.hub.not.kicker",
  "explain.hub.not.title",
  "explain.hub.not.text",
  "explain.hub.not.go",
  "explain.hub.autos.noRuns",
  "explain.permanence",
  "explain.nullName.note",
  "explain.cascade.parentUnrecorded",
  "explain.cascade.parent",
  "explain.trigger.detail",
  "explain.trigger.detail.noType",
  "explain.trigger.detail.noValue",
  "explain.condition.line",
  "explain.condition.atTheTime",
  "explain.condition.noReading",
  "explain.action.detail.command",
  "explain.action.detail.reason",
  "explain.action.detail.outcome",
  "explain.detail.notRecorded",
  "explain.a11y.step",
  "explain.a11y.chain",
  "explain.a11y.provisional",
  "explain.a11y.live",
  "explain.error.title",
  "explain.error.body",
  "explain.error.retry",
  "explain.offline.title",
  "explain.offline.body",
  "explain.replaying.title",
  "explain.replaying.body",
  "explain.loading",
] as const;

describe('HERO-1b B2 — the §7 copy table is the catalog', () => {
  // FE-115 D3 (2026-09-19): 140 → 134 — six of the HERO-1b rows retired on the hub's rulings (the NAMED flip of D3;
  // the retired keys are listed in the §FE-115 D3 block below); `boot.startingBody` was never a §7 row.
  it('carries all 134 §7 keys (140 HERO-1b rows − the 6 FE-115 D3 retired), each resolving to a string', () => {
    expect(SPEC7_KEYS.length).toBe(134);
    for (const k of SPEC7_KEYS) {
      expect(typeof t(k as MessageKey), k).toBe('string');
    }
  });

  it('no hero string carries the product name or the brand accessor — name-light (P3)', () => {
    for (const k of SPEC7_KEYS) {
      const s = t(k as MessageKey) ?? '';
      expect(s, k).not.toContain(BRAND.productName);
      expect(s, k).not.toContain('{{NAME}}'); // no §7 string needs the token either
    }
  });

  it('no hero string says "we" — Register C has no self-reference (P3)', () => {
    for (const k of SPEC7_KEYS) {
      expect(t(k as MessageKey) ?? '', k).not.toMatch(/\bwe\b/i);
    }
  });

  it('the permanence footer is the Q3 (a) sentence: name-free, rename-proof', () => {
    expect(t('explain.permanence' as MessageKey)).toBe(
      'Rebuilt from the permanent activity log. Nothing here is ever deleted, so this run is always here.',
    );
    expect(t('explain.permanence' as MessageKey)).not.toContain(BRAND.productName);
  });

  it('the old name-carrying `hero.permanence` key is gone (one key, one sentence)', () => {
    expect(t('hero.permanence' as MessageKey)).toBeUndefined();
  });
});

/*
 * HERO-1c C0 (2026-09-13) — the hub's SPEC §7 amendment (design/hero-v1/SPEC.md:276–:279:
 * the HERO-1b audit's D2/D3/D5 rows) is in the catalog with the same strings, verbatim.
 * RED at HEAD: none of the four keys exists (the catalog ends at `explain.loading`,
 * i18n.ts:213), so each `t()` read is undefined. The Register-C row is green by construction
 * at HEAD (an undefined string carries nothing) — disclosed.
 */
const SPEC7_HERO1C_KEYS = {
  'explain.terminal.noSteps': 'Done, recorded no steps.',
  'whyNot.headline.actedButUnconfirmed.noTime': 'It ran, but the device never confirmed it acted.',
  'whyNot.headline.sentNothing.noTime': 'It ran, but sent nothing — every step was skipped.',
  'explain.headline.completed.notRecorded': "{Target} was asked to {verb} because {because}; what happened isn't recorded.",
} as const;

describe('HERO-1c C0 — the four amendment rows are in the catalog, verbatim', () => {
  it('carries the four keys with the SPEC strings (140 + 4 = 144 §7 rows)', () => {
    expect(Object.keys(SPEC7_HERO1C_KEYS).length).toBe(4);
    for (const [k, s] of Object.entries(SPEC7_HERO1C_KEYS)) {
      expect(t(k as MessageKey), k).toBe(s);
    }
  });

  it('Register C holds on the four: no product name, no token, no "we" [GREEN at HEAD by construction]', () => {
    for (const k of Object.keys(SPEC7_HERO1C_KEYS)) {
      const s = t(k as MessageKey) ?? '';
      expect(s, k).not.toContain(BRAND.productName);
      expect(s, k).not.toContain('{{NAME}}');
      expect(s, k).not.toMatch(/\bwe\b/i);
    }
  });
});

/* HERO-1c correction D2 (2026-09-13) — the hub's two further §7 rows (design/hero-v1/SPEC.md:280–:281),
 * the R3 check extended: catalog strings == SPEC strings. RED at the HERO-1c tree: neither key exists. */
const SPEC7_HERO1C_D2_KEYS = {
  'explain.mode.notRecorded.help': 'What happened to this step was not recorded. The step itself is preserved.',
  'explain.mode.settledFailed.lineNoCommand': 'No command was sent to {target} — this step failed{reasonClause}.',
} as const;

describe('HERO-1c D2 — the two correction rows are in the catalog, verbatim', () => {
  it('carries the two keys with the SPEC strings (144 + 2 = 146 §7 rows), Register C', () => {
    for (const [k, s] of Object.entries(SPEC7_HERO1C_D2_KEYS)) {
      expect(t(k as MessageKey), k).toBe(s);
      expect(s, k).not.toContain(BRAND.productName);
      expect(s, k).not.toMatch(/\bwe\b/i);
    }
  });
});

/*
 * HERO-1d D1–D4 (2026-09-13) — the last literal hero sentences, keyed byte-identically (the SPEC §7
 * amendment rows tagged HERO-1d). RED at HEAD: none of the keys exists. Every string is HEAD's
 * literal, character for character — the recovered suffix keeps its leading space; the hub link's
 * `&rsquo;` is the rendered ’; `{slots}` stand where the component interpolated. `ui.on` / `ui.off`
 * are the app's register (the HERO-1c D3 ruling), listed here because this lane added them.
 */
const SPEC7_HERO1D_KEYS = {
  // D1 — terminalLine
  'explain.terminal.unrecorded': 'Outcome {notRecorded}.',
  'explain.terminal.completed.noTime': 'Done.',
  'explain.terminal.completed.nothing.noTime': 'Finished, but nothing was changed.',
  'explain.terminal.completed.open.one': 'Done in {secs}s — one outcome has not settled yet.',
  'explain.terminal.completed.open.noTime': 'Done — {count} outcomes have not settled yet.',
  'explain.terminal.completed.open.one.noTime': 'Done — one outcome has not settled yet.',
  'explain.terminal.status': '{label}.',
  // D2 — the do-nothing step and the recovered suffix
  'explain.step.nothing.one': 'Nothing was changed: the planned step ended without sending a command.',
  'explain.step.nothing.many': 'Nothing was changed: all {count} planned steps ended without sending a command.',
  'explain.step.nothing.hint': 'This usually means the devices this automation targets were unavailable, so each was skipped by design. The step-by-step record of these skips is not kept yet.',
  'explain.action.detail.outcome.recovered': ' (recovered from the recorded reason — this record predates the current hub software)',
  // D3 — the hub page
  'explain.hub.autos.title': 'Your automations',
  'explain.hub.autos.whyFire': 'Why did it fire?',
  'explain.hub.autos.whyNot': 'Why didn’t it?',
  'ui.on': 'On',
  'ui.off': 'Off',
  // D4 — the why-not, runs and run pages
  'explain.whyNot.pick.title': "Why didn't it happen?",
  'explain.whyNot.pick.lede': 'Choose the automation you expected to run.',
  'explain.whyNot.title': "Why this didn't happen",
  'explain.whyNot.back': '← Pick another automation',
  'explain.runs.title': 'Why did something happen?',
  'explain.runs.lede': 'Pick a run to see exactly why it fired, step by step.',
  'explain.runs.empty': 'No automation runs yet.',
  'explain.run.title': 'Why this happened',
} as const;

describe('HERO-1d — the amendment rows are in the catalog, verbatim (146 + 24 = 170 rows)', () => {
  it('carries the twenty-four keys with the literals of HEAD, character for character', () => {
    expect(Object.keys(SPEC7_HERO1D_KEYS).length).toBe(24);
    for (const [k, s] of Object.entries(SPEC7_HERO1D_KEYS)) {
      expect(t(k as MessageKey), k).toBe(s);
    }
  });

  it('Register C holds on the twenty-four: no product name, no token, no "we"', () => {
    for (const [k, s] of Object.entries(SPEC7_HERO1D_KEYS)) {
      expect(s, k).not.toContain(BRAND.productName);
      expect(s, k).not.toContain('{{NAME}}');
      expect(s, k).not.toMatch(/\bwe\b/i);
    }
  });
});

/* HERO-1d D6 (2026-09-13) — the spelling of record is the key's own (`explain.action.pending.color`): the
 * UK → US sweep of that word (arc 25). RED at HEAD: the catalog string carried the UK spelling. After the
 * sweep the row is byte-identical to format.ts pendingHint's literal (still a literal there — filed). */
describe('HERO-1d D6 — the spelling of record', () => {
  it('explain.action.pending.color reads "Color", the US spelling of its own key', () => {
    expect(t('explain.action.pending.color')).toBe('Color changes confirm slowly on some bulbs — this can take several seconds.');
  });
});

/* FE-114 D5 (2026-09-14, IR-13) — US is the dialect of record: the one RENDERED UK string
 * (`explain.mode.unknownOutcome.help`, the "-ise" spelling) takes the US "-ize" — a §7 TEXT-CHANGE row
 * (SPEC :207). RED at HEAD: the catalog string carried the UK spelling. (The charter named an existing pin
 * on this string as the third flip; no test pinned the string at HEAD — verdicts.test.ts:385 is
 * key-relative — so this is a NEW red-first pin, not a flip.) */
describe('FE-114 D5 — the dialect of record on the last rendered UK spelling', () => {
  it('explain.mode.unknownOutcome.help reads "recognize"', () => {
    expect(t('explain.mode.unknownOutcome.help')).toBe('The device reported an outcome this dashboard does not recognize yet — shown as recorded.');
    expect(t('explain.mode.unknownOutcome.help')).toMatch(/recognize yet/);
  });
});

/*
 * FE-114 D1–D4 (2026-09-14) — the v1.1.4 keys' sentences (D1 EXPLAIN-9 · D2 EXPLAIN-8 · D3 EXPLAIN-6) and
 * the literals D4's widened lint reached, as SPEC §7 amendment rows tagged FE-114. RED at HEAD: none of the
 * thirteen keys exists. D4's strings are HEAD's literals character for character (`{command}` stands where
 * the template interpolated); D1–D3's are the charter's forms, with D3's `{time}` in the run clause
 * (lastEvaluation.at is the evaluation instant — the FE-114 return §0 names the deviation).
 */
const SPEC7_FE114_KEYS = {
  // D1 — EXPLAIN-9
  'explain.action.detail.confirmedAt': 'Confirmed at {time}, {delta} after it fired.',
  'explain.action.detail.confirmedAt.noDelta': 'Confirmed at {time}.',
  // D2 — EXPLAIN-8
  'whyNot.body.disabled.at': 'Turned off {when}{reason}. Turn it on in your automation settings to let it run.',
  // D3 — EXPLAIN-6 (FIRED_CONFIRMED)
  'whyNot.headline.firedConfirmed': 'It ran at {time}, and the record says the device confirmed it.',
  'whyNot.headline.firedConfirmed.noTime': 'It ran, and the record says the device confirmed it.',
  'whyNot.pill.firedConfirmed': 'Ran and confirmed',
  // D4 — the six lint hits, byte-identical
  'explain.terminal.completed.nothing.pill': 'Completed, nothing changed',
  'explain.action.ran.on': 'Ran {command} on',
  'explain.action.ran.unrecorded.on': 'Ran an unrecorded command on',
  'ui.signingIn': 'Signing in…',
  'explain.run.back': '← All runs',
  'whyNot.pill.sentNothing': 'Ran, but sent nothing',
  'whyNot.pill.didRun': 'It did run',
} as const;

describe('FE-114 — the amendment rows are in the catalog, verbatim (170 + 13 = 183 §7-tagged rows)', () => {
  it('carries the thirteen keys with their strings, character for character', () => {
    expect(Object.keys(SPEC7_FE114_KEYS).length).toBe(13);
    for (const [k, s] of Object.entries(SPEC7_FE114_KEYS)) {
      expect(t(k as MessageKey), k).toBe(s);
    }
  });

  it('Register C holds on the thirteen: no product name, no token, no "we"', () => {
    for (const [k, s] of Object.entries(SPEC7_FE114_KEYS)) {
      expect(s, k).not.toContain(BRAND.productName);
      expect(s, k).not.toContain('{{NAME}}');
      expect(s, k).not.toMatch(/\bwe\b/i);
    }
  });
});

/*
 * FE-115 D1 (2026-09-19) — the v1.1.5 `conditions[].definition` sentences, as SPEC §7 amendment rows tagged
 * FE-115: the frame, one clause per emitter permit (ConditionDefinitionRenderer's seven `type` strings —
 * StateCondition · NumericCondition · TimeCondition · AndCondition · OrCondition · NotCondition · ZoneCondition),
 * the keyed connectives, the empty-group and unknown-type arms, and the null (not-recorded) sentence. Every word
 * `definitionSentence` (format.ts) can produce is one of these rows. RED at HEAD: none of the keys exists.
 */
const SPEC7_FE115_D1_KEYS = {
  'explain.condition.def.frame': 'It checks that {clause}.',
  'explain.condition.def.notRecorded': "What this rule checked isn't on record for this run.",
  'explain.condition.def.StateCondition': '{entity} {attribute} is "{value}"',
  'explain.condition.def.StateCondition.noValue': "{entity} {attribute} matches a value that isn't recorded",
  'explain.condition.def.NumericCondition.between': '{entity} {attribute} is above {above} and below {below}',
  'explain.condition.def.NumericCondition.above': '{entity} {attribute} is above {above}',
  'explain.condition.def.NumericCondition.below': '{entity} {attribute} is below {below}',
  'explain.condition.def.NumericCondition.unbounded': '{entity} {attribute} has no bounds recorded',
  'explain.condition.def.TimeCondition.between': 'the time is after {after} and before {before}',
  'explain.condition.def.TimeCondition.after': 'the time is after {after}',
  'explain.condition.def.TimeCondition.before': 'the time is before {before}',
  'explain.condition.def.TimeCondition.unbounded': "the time window isn't recorded",
  'explain.condition.def.AndCondition.join': ' and ',
  'explain.condition.def.OrCondition.join': ' or ',
  'explain.condition.def.NotCondition': 'it is not true that {clause}',
  'explain.condition.def.list.join': ', ',
  'explain.condition.def.group': '({clause})',
  'explain.condition.def.emptyGroup': 'an empty group of rules',
  'explain.condition.def.emptyGroup.sentence': 'This rule is an empty group — it has nothing to check.',
  'explain.condition.def.ZoneCondition': "This is a zone rule — this hub doesn't record its details yet.",
  'explain.condition.def.ZoneCondition.clause': 'a zone rule (details not recorded)',
  'explain.condition.def.unknown': 'This is a rule of kind "{type}" — this dashboard can\'t describe it yet.',
  'explain.condition.def.unknown.clause': 'a rule of kind "{type}" (not described yet)',
  'explain.condition.def.entity.unnamed': 'an unnamed device',
} as const;

describe('FE-115 D1 — the definition-sentence rows are in the catalog, verbatim (183 − 6 retired + 24 = 201 §7-tagged rows)', () => {
  it('carries the twenty-four keys with their strings, character for character', () => {
    expect(Object.keys(SPEC7_FE115_D1_KEYS).length).toBe(24);
    for (const [k, s] of Object.entries(SPEC7_FE115_D1_KEYS)) {
      expect(t(k as MessageKey), k).toBe(s);
    }
  });

  it('Register C holds on the twenty-four: no product name, no token, no "we"', () => {
    for (const [k, s] of Object.entries(SPEC7_FE115_D1_KEYS)) {
      expect(s, k).not.toContain(BRAND.productName);
      expect(s, k).not.toContain('{{NAME}}');
      expect(s, k).not.toMatch(/\bwe\b/i);
    }
  });

  it('one clause key per emitter permit — the seven `type` strings of ConditionDefinitionRenderer each resolve', () => {
    for (const ty of ['StateCondition', 'NumericCondition.between', 'TimeCondition.between', 'AndCondition.join', 'OrCondition.join', 'NotCondition', 'ZoneCondition']) {
      expect(typeof t(`explain.condition.def.${ty}` as MessageKey), ty).toBe('string');
    }
  });
});

/*
 * FE-115 D3 (2026-09-19) — the dead keys, retired on the hub's rulings (the v75-b3 audit §3, reversible by REVERT):
 * `whyNot.neverTriggered.title` (a twin of the consumed headline), `explain.action.unconfirmed.title` / `.body` and
 * `explain.action.pending.title` / `.body` (superseded by HERO-1c's mode keys), `boot.startingBody` (the banner
 * carries its own literal), `explain.hub.autos.noRunsSinceLoad` ("since it was loaded" is not something the wire
 * can vouch for). Each was grep-proven UNCONSUMED outside the catalog and this file before removal (the counts in
 * the FE-115 return §0). KEPT: `explain.headline.completed.notRecorded` (ruling-gated) and `whyNot.neverTriggered.body`
 * (consumed by WhyNotView). RED at HEAD: every one of the seven still resolves. The §7 count pin above moves
 * 140 → 134 — the NAMED flip of this row.
 */
const RETIRED_FE115 = [
  'whyNot.neverTriggered.title',
  'explain.action.unconfirmed.title',
  'explain.action.unconfirmed.body',
  'explain.action.pending.title',
  'explain.action.pending.body',
  'boot.startingBody',
  'explain.hub.autos.noRunsSinceLoad',
] as const;

describe('FE-115 D3 — the seven dead keys are GONE from the catalog; the kept twins remain', () => {
  it('none of the seven resolves any more', () => {
    for (const k of RETIRED_FE115) expect(t(k as unknown as MessageKey), k).toBeUndefined();
  });
  it('the kept keys still resolve: explain.headline.completed.notRecorded (ruling-gated) · whyNot.neverTriggered.body (consumed) · explain.action.pending.color (consumed by format.pendingHint)', () => {
    expect(t('explain.headline.completed.notRecorded')).toBe("{Target} was asked to {verb} because {because}; what happened isn't recorded.");
    expect(typeof t('whyNot.neverTriggered.body')).toBe('string');
    expect(typeof t('explain.action.pending.color')).toBe('string');
  });
});

/*
 * FE-115 D4 (2026-09-19) — the lint's reach closed: `ReturnStatement > Literal` and `ConditionalExpression > Literal`
 * join the six-file rule; the literals it (and its one-word / out-of-scope blind spots) still hid are §7 rows,
 * byte-identical — `actionPhrase`'s three verbs (CausalChain.tsx; the widened rule reached "Turned on" / "Turned off";
 * "Dimmed" is one word and outside the pattern, keyed anyway) and format.ts `EMPTY_CHAIN_NOTE` (a const outside the
 * six files by the scope law, keyed anyway — the FE-114 D8 twin-fold pattern). RED at HEAD: none of the four exists.
 */
const SPEC7_FE115_D4_KEYS = {
  'explain.action.phrase.turnedOn': 'Turned on',
  'explain.action.phrase.turnedOff': 'Turned off',
  'explain.action.phrase.dimmed': 'Dimmed',
  'explain.chain.empty': 'This run finished without recording any steps — no conditions were checked and no commands were sent.',
} as const;

describe('FE-115 D4 — the four lint-reach rows are in the catalog, verbatim', () => {
  it('carries the four keys with their strings, character for character', () => {
    expect(Object.keys(SPEC7_FE115_D4_KEYS).length).toBe(4);
    for (const [k, s] of Object.entries(SPEC7_FE115_D4_KEYS)) expect(t(k as MessageKey), k).toBe(s);
  });
  it('Register C holds on the four', () => {
    for (const [k, s] of Object.entries(SPEC7_FE115_D4_KEYS)) {
      expect(s, k).not.toContain(BRAND.productName);
      expect(s, k).not.toMatch(/\bwe\b/i);
    }
  });
});

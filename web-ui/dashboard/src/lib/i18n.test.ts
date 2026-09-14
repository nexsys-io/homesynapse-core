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
/** The 140 §7 keys, verbatim from SPEC.md:136–:275 (extracted by script, not typed). */
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
  "whyNot.neverTriggered.title",
  "whyNot.neverTriggered.body",
  "explain.chain.noDetail.title",
  "explain.chain.noDetail.body",
  "explain.trigger.readingNotRecorded",
  "explain.action.unconfirmed.title",
  "explain.action.unconfirmed.body",
  "explain.action.pending.title",
  "explain.action.pending.body",
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
  "explain.hub.autos.noRunsSinceLoad",
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
  it('carries all 140 §7 keys, each resolving to a string', () => {
    expect(SPEC7_KEYS.length).toBe(140);
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

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

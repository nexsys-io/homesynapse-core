/*
 * HERO-1b B3 (2026-09-12) — the why-not card's L1 sentence per verdict (SPEC §3 N1–N7,
 * design/hero-v1/SPEC.md:68–:80) with the no-time arm, the keyed bodies (§7 whyNot.*),
 * N3 in the info register (Q1 ruled (a): "It did run" is an inference from a non-null run
 * id, not a confirmed outcome) and the DISABLED body's "isn't recorded" sentence.
 * RED at HEAD: WhyNotView.tsx:109 renders the wire's `explanation` string as the L1 and
 * :99 renders N3 in the ok register; none of the keyed sentences exists on the surface.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { WhyNotView } from './WhyNotView';
import { api } from '../lib/api';
import { clockTimeWithDate, timeAgo } from '../lib/format';
import { t, type MessageKey } from '../lib/i18n';
import type { NonFiringExplanation } from '../lib/api/contract';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const AT = (() => {
  const d = new Date();
  d.setHours(21, 42, 0, 0);
  return d.toISOString();
})();
const WHEN = clockTimeWithDate(AT);

function nf(over: Omit<Partial<NonFiringExplanation>, 'verdict'> & { verdict: string }): NonFiringExplanation {
  return {
    automationId: 'auto_x',
    automationName: 'Evening Lights',
    enabled: true,
    lastRelevantRunId: null,
    explanation: 'the wire sentence',
    triggerSummary: 'motion in the hallway',
    lastEvaluation: { at: AT, conditionsResult: 'false' },
    noCommandsIssued: null,
    triggerRef: null,
    ...(over as Partial<NonFiringExplanation>),
  } as NonFiringExplanation;
}

async function renderCard(data: NonFiringExplanation) {
  vi.spyOn(api, 'getNonFiring').mockResolvedValue({ data, meta: { viewPosition: 1, timestamp: new Date().toISOString() } } as never);
  const utils = render(<WhyNotView automationId="auto_x" />);
  await act(async () => {});
  return utils.container.textContent ?? '';
}

describe('HERO-1b B3 — the why-not L1 sentence per verdict', () => {
  it('N1 CONDITION_NOT_MET with a time', async () => {
    const text = await renderCard(nf({ verdict: 'CONDITION_NOT_MET', lastRelevantRunId: 'run_1' }));
    expect(text).toContain(`It was set off at ${WHEN}, but a condition was false, so it didn't act.`);
    expect(text).toContain('It ran on motion in the hallway, checked its conditions, and one was false. The run shows which.');
    expect(text).toContain('See which condition blocked it →');
    expect(text).toContain('A condition was not met');
  });

  it('N1 no-time arm (lastEvaluation null) — the §7 .noTime key, never "at —"', async () => {
    const text = await renderCard(nf({ verdict: 'CONDITION_NOT_MET', lastEvaluation: null }));
    expect(text).toContain("It was set off, but a condition was false, so it didn't act.");
    expect(text).not.toContain('at —');
  });

  it('N2 NEVER_TRIGGERED with no run — the §10 sentence (3), word for word', async () => {
    const text = await renderCard(nf({ verdict: 'NEVER_TRIGGERED', lastEvaluation: null }));
    // the §10 sentence (3) is the title + the body's first sentence, two elements on the card
    expect(text).toContain("It hasn't run yet.");
    expect(text).toContain("Nothing has set it off since this automation was loaded. It runs on motion in the hallway. Nothing is wrong — it's waiting.");
    expect(text).toContain('Nothing set it off');
    expect(text).not.toContain('It did run');
    expect(text).not.toContain('→'); // no run to see: no link is offered
  });

  it('N3 NEVER_TRIGGERED with a run id — the inference, in the info register (Q1 a), with the ranFine body and link', async () => {
    const { container } = await (async () => {
      vi.spyOn(api, 'getNonFiring').mockResolvedValue({
        data: nf({ verdict: 'NEVER_TRIGGERED', lastRelevantRunId: 'run_9', lastEvaluation: { at: AT, conditionsResult: 'true' } }),
        meta: { viewPosition: 1, timestamp: new Date().toISOString() },
      } as never);
      const u = render(<WhyNotView automationId="auto_x" />);
      await act(async () => {});
      return u;
    })();
    const text = container.textContent ?? '';
    expect(text).toContain(`It has run — most recently at ${WHEN}.`);
    expect(text).toContain("Whether anything changed is on the run's own page.");
    expect(text).toContain('See that run — including whether anything changed →');
    const pill = Array.from(container.querySelectorAll('span')).find((s) => s.textContent === 'It did run')!; // the pill (outer span)
    expect(pill).toBeTruthy();
    expect(pill.className).toMatch(/info/);
    expect(pill.className).not.toMatch(/_ok_/);
  });

  it('N3 no-time arm', async () => {
    const text = await renderCard(nf({ verdict: 'NEVER_TRIGGERED', lastRelevantRunId: 'run_9', lastEvaluation: null }));
    expect(text).toContain('It has run. The most recent run is on record.');
  });

  it('N4 ACTED_BUT_UNCONFIRMED — with and without a time', async () => {
    let text = await renderCard(nf({ verdict: 'ACTED_BUT_UNCONFIRMED', lastRelevantRunId: 'run_2', lastEvaluation: { at: AT, conditionsResult: null } }));
    expect(text).toContain(`It ran at ${WHEN}, but the device never confirmed it acted.`);
    expect(text).toContain("The command was sent. No confirmation came back, so whether it worked isn't known.");
    expect(text).toContain('See the run where the device never confirmed →');
    cleanup();
    text = await renderCard(nf({ verdict: 'ACTED_BUT_UNCONFIRMED', lastEvaluation: null }));
    expect(text).toContain('It ran, but the device never confirmed it acted.');
  });

  it('N5 DISABLED — the body says when it was turned off isn\'t recorded (EXPLAIN-8 placeholder)', async () => {
    const text = await renderCard(nf({ verdict: 'DISABLED', enabled: false, lastEvaluation: null }));
    expect(text).toContain("It's turned off, so it can't run.");
    expect(text).toContain("Turn it on in your automation settings to let it run. When it was turned off isn't recorded.");
    expect(text).toContain('It is turned off');
  });

  it('N6 noCommandsIssued true (any verdict) — with and without a time', async () => {
    let text = await renderCard(nf({ verdict: 'ACTED_BUT_UNCONFIRMED', noCommandsIssued: true, lastRelevantRunId: 'run_3', lastEvaluation: { at: AT, conditionsResult: 'true' } }));
    expect(text).toContain(`It ran at ${WHEN}, but sent nothing — every step was skipped.`);
    expect(text).toContain("Every step ended without sending a command. Why each was skipped isn't recorded yet.");
    expect(text).toContain('See the run that sent no commands →');
    expect(text).toContain('Ran, but sent nothing');
    cleanup();
    text = await renderCard(nf({ verdict: 'ACTED_BUT_UNCONFIRMED', noCommandsIssued: true, lastEvaluation: null }));
    expect(text).toContain('It ran, but sent nothing — every step was skipped.');
  });

  it('N7 an unknown verdict string — recorded as such, never a crash, never success', async () => {
    const text = await renderCard(nf({ verdict: 'PAUSED', lastEvaluation: null }));
    expect(text).toContain('Recorded as "PAUSED" — a verdict this dashboard can\'t explain yet.');
    expect(text).toContain('Recorded as "PAUSED"');
  });

  it('the §10 sentence (4), word for word', async () => {
    const text = await renderCard(nf({ verdict: 'CONDITION_NOT_MET' }));
    expect(text).toContain(`It was set off at ${WHEN}, but a condition was false, so it didn't act.`);
  });

  it('"Last checked": a null conditionsResult beside a time says the run did not finish cleanly (source: StandardExplanationService:281–:288)', async () => {
    const text = await renderCard(nf({ verdict: 'ACTED_BUT_UNCONFIRMED', lastEvaluation: { at: AT, conditionsResult: null } }));
    expect(text).toContain('Last checked');
    expect(text).toContain("It ran, but didn't finish cleanly.");
    expect(text).not.toContain('null');
  });
});

/* ---- HERO-1d D4 (2026-09-13): the two page titles, the picker lede and the back link are
 * catalog rows (`explain.whyNot.pick.title` · `.pick.lede` · `explain.whyNot.title` ·
 * `explain.whyNot.back`), byte-identical on screen. RED at HEAD: none of the four keys exists. ---- */
describe('HERO-1d D4 — the why-not pages are the catalog', () => {
  const META = () => ({ viewPosition: 1, timestamp: new Date().toISOString() });

  it('the picker: title and lede → explain.whyNot.pick.title / .pick.lede', async () => {
    vi.spyOn(api, 'listAutomations').mockResolvedValue({ data: [], meta: META() } as never);
    const { container } = render(<WhyNotView />);
    await act(async () => {});
    const h1 = container.querySelector('h1')?.textContent;
    expect(h1).toBe("Why didn't it happen?");
    expect(h1).toBe(t('explain.whyNot.pick.title'));
    const lede = container.querySelector('header p')?.textContent;
    expect(lede).toBe('Choose the automation you expected to run.');
    expect(lede).toBe(t('explain.whyNot.pick.lede'));
  });

  it('the detail: title → explain.whyNot.title; the back link → explain.whyNot.back', async () => {
    vi.spyOn(api, 'getNonFiring').mockResolvedValue({ data: nf({ verdict: 'DISABLED', enabled: false, lastEvaluation: null }), meta: META() } as never);
    const { container } = render(<WhyNotView automationId="auto_x" />);
    await act(async () => {});
    const h1 = container.querySelector('h1')?.textContent;
    expect(h1).toBe("Why this didn't happen");
    expect(h1).toBe(t('explain.whyNot.title'));
    const back = container.querySelector('a[href$="/explain/why-not"]')!;
    expect(back).toBeTruthy();
    expect(back.textContent).toBe('← Pick another automation');
    expect(back.textContent).toBe(t('explain.whyNot.back'));
  });
});

/* ---- FE-114 D2 (2026-09-14) — EXPLAIN-8 (SPEC §6 :125): the DISABLED body reads the v1.1.4 wire —
 * `whyNot.body.disabled.at` "Turned off {when}{reason}. Turn it on in your automation settings to let it
 * run." with {when} = timeAgo(disabledAt) and {reason} = " — {disabledReason}" or "". The tri-state:
 * `disabledAt` null / absent keep `whyNot.body.disabled` (HEAD's "…isn't recorded" sentence). RED at HEAD:
 * no key, no arm. NO LIVE v1.1.4 CAPTURE exists — the payloads are hand-built (charter §4). ---- */
describe('FE-114 D2 — EXPLAIN-8: the DISABLED body says WHEN it was turned off (disabledAt) and why (disabledReason)', () => {
  const DISABLED_AT = new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString(); // "3 hr ago"
  const OLD_BODY = "Turn it on in your automation settings to let it run. When it was turned off isn't recorded.";

  it('disabledAt + disabledReason → the §7 sentence with the reason clause, shown as recorded; the headline unchanged', async () => {
    const text = await renderCard(nf({ verdict: 'DISABLED', enabled: false, lastEvaluation: null, disabledAt: DISABLED_AT, disabledReason: 'repeated_failure', definitionKey: null }));
    expect(text).toContain(`Turned off ${timeAgo(DISABLED_AT)} — repeated_failure. Turn it on in your automation settings to let it run.`);
    expect(text).not.toContain(OLD_BODY);
    expect(text).toContain("It's turned off, so it can't run.");
    expect(text).toContain('It is turned off');
  });

  it('disabledAt with disabledReason null → the sentence without a reason clause', async () => {
    const text = await renderCard(nf({ verdict: 'DISABLED', enabled: false, lastEvaluation: null, disabledAt: DISABLED_AT, disabledReason: null }));
    expect(text).toContain(`Turned off ${timeAgo(DISABLED_AT)}. Turn it on in your automation settings to let it run.`);
    expect(text).not.toContain('— null');
  });

  it('the catalog row is the charter\'s form, verbatim', () => {
    expect(t('whyNot.body.disabled.at' as MessageKey)).toBe('Turned off {when}{reason}. Turn it on in your automation settings to let it run.');
  });

  it('disabledAt PRESENT-null (DP-6: DISABLED by configuration, no marker on the log) → today\'s body, unchanged', async () => {
    const text = await renderCard(nf({ verdict: 'DISABLED', enabled: false, lastEvaluation: null, disabledAt: null, disabledReason: 'configuration' }));
    expect(text).toContain(OLD_BODY);
    expect(text).not.toContain('Turned off ');
  });

  it('disabledAt ABSENT (a pre-v1.1.4 hub) → today\'s body, unchanged [GREEN at HEAD; preservation]', async () => {
    const text = await renderCard(nf({ verdict: 'DISABLED', enabled: false, lastEvaluation: null }));
    expect(text).toContain(OLD_BODY);
    expect(text).not.toContain('Turned off ');
  });
});

/* ---- FE-114 D3 (2026-09-14) — the FIRED_CONFIRMED arm (EXPLAIN-6, SPEC §6 :123): the v1.1.4 verdict
 * renders its own L1 (`whyNot.headline.firedConfirmed` / `.noTime`), the ok pill `whyNot.pill.firedConfirmed`
 * "Ran and confirmed" and the run link — never the unknown arm. "the record says" is the register: the
 * claim is Core's, shown as recorded (the Q1 ruling). The `{time}` is `lastEvaluation.at` — the EVALUATION
 * instant, so it attaches to "It ran at", never to "confirmed" (the wire carries no confirmation instant on
 * this read). N6 (noCommandsIssued true) still wins. RED at HEAD: WhyNotView.tsx:143's unknown arm renders
 * `Recorded as "FIRED_CONFIRMED" — a verdict this dashboard can't explain yet.` ---- */
describe('FE-114 D3 — FIRED_CONFIRMED renders as a confirmed run, not as a verdict this dashboard cannot explain', () => {
  async function renderFired(over: Partial<NonFiringExplanation> = {}) {
    vi.spyOn(api, 'getNonFiring').mockResolvedValue({
      data: nf({ verdict: 'FIRED_CONFIRMED', lastRelevantRunId: 'run_7', lastEvaluation: { at: AT, conditionsResult: 'true' }, disabledAt: null, disabledReason: null, definitionKey: null, ...over }),
      meta: { viewPosition: 1, timestamp: new Date().toISOString() },
    } as never);
    const u = render(<WhyNotView automationId="auto_x" />);
    await act(async () => {});
    return u.container;
  }

  it('with a time: the L1, the ok pill with its label, the run link — never the unknown arm', async () => {
    const container = await renderFired();
    const text = container.textContent ?? '';
    expect(text).toContain(`It ran at ${WHEN}, and the record says the device confirmed it.`);
    expect(text).not.toContain('Recorded as "FIRED_CONFIRMED"');
    expect(text).not.toContain("can't explain yet");
    const pill = Array.from(container.querySelectorAll('span')).find((s) => s.textContent === 'Ran and confirmed')!;
    expect(pill).toBeTruthy();
    expect(pill.className).toMatch(/_ok_/);
    const link = container.querySelector('a[href$="/explain/run/run_7"]')!;
    expect(link).toBeTruthy();
    expect(link.textContent).toBe(t('whyNot.link.ranFine'));
  });

  it('the no-time arm (lastEvaluation null)', async () => {
    const text = (await renderFired({ lastEvaluation: null })).textContent ?? '';
    expect(text).toContain('It ran, and the record says the device confirmed it.');
    expect(text).not.toContain('at —');
  });

  it('the catalog rows, verbatim (the time slot sits in the run clause — lastEvaluation.at is the evaluation instant)', () => {
    expect(t('whyNot.headline.firedConfirmed' as MessageKey)).toBe('It ran at {time}, and the record says the device confirmed it.');
    expect(t('whyNot.headline.firedConfirmed.noTime' as MessageKey)).toBe('It ran, and the record says the device confirmed it.');
    expect(t('whyNot.pill.firedConfirmed' as MessageKey)).toBe('Ran and confirmed');
  });

  it('N6 still wins: FIRED_CONFIRMED beside noCommandsIssued true is the sent-nothing card', async () => {
    const text = (await renderFired({ noCommandsIssued: true })).textContent ?? '';
    expect(text).toContain(`It ran at ${WHEN}, but sent nothing — every step was skipped.`);
    expect(text).not.toContain('Ran and confirmed');
  });
});

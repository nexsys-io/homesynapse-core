/*
 * HERO-1c C4 (2026-09-13) — the hub page renders the §7 `explain.hub.*` rows through t() (the
 * HERO-1b audit's D7). RED at HEAD: ExplainHubView.tsx:20–:39 carries literals — the lede
 * ("Understand what your home did — and just as importantly, what it didn't.") and both card
 * texts differ from the catalog, and the "Why didn't" kicker + text use a typographic apostrophe
 * (&rsquo;) where the catalog has a plain one. Preservation (green at HEAD, named): the title,
 * the "Why did" kicker, both card titles, both go-lines and the "No runs yet" cell are byte-
 * identical to their rows. The two per-automation link texts ("Why did it fire?" / "Why didn't
 * it?") and the "Your automations" subhead have no §7 key and stay literal — filed, not changed.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { ExplainHubView } from './ExplainHubView';
import { api } from '../lib/api';
import { t } from '../lib/i18n';
import type { AutomationSummary } from '../lib/api/contract';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const AUTOS: AutomationSummary[] = [
  { automationId: 'auto_1', name: 'Evening Lights', enabled: true, lastRunId: 'run_1', components: [{ type: 'trigger', summary: 'motion in the hallway', ref: null }] },
  { automationId: 'auto_2', name: 'Porch Light', enabled: false, lastRunId: null, components: [] },
];

async function renderHub() {
  vi.spyOn(api, 'listAutomations').mockResolvedValue({ data: AUTOS, meta: { viewPosition: 1, timestamp: new Date().toISOString() } } as never);
  const utils = render(<ExplainHubView />);
  await act(async () => {});
  return utils.container;
}

const spans = (card: Element) => Array.from(card.querySelectorAll('span')).map((s) => s.textContent?.replace(/\s+/g, ' ').trim());

describe('HERO-1c C4 — the hub page is the catalog', () => {
  it('title [GREEN at HEAD — byte-identical; preservation] and lede', async () => {
    const c = await renderHub();
    expect(c.querySelector('h1')?.textContent).toBe(t('explain.hub.title'));
    expect(c.textContent ?? '').toContain(t('explain.hub.lede'));
    expect(c.textContent ?? '').not.toContain('just as importantly');
  });

  it('the "why did" card: kicker, title, text, go-line (kicker · title · go-line are GREEN at HEAD; the text differs)', async () => {
    const card = (await renderHub()).querySelector('a[href$="/explain/runs"]')!;
    expect(card).toBeTruthy();
    expect(spans(card)).toEqual([t('explain.hub.fire.kicker'), t('explain.hub.fire.title'), t('explain.hub.fire.text'), t('explain.hub.fire.go')]);
  });

  it("the \"why didn't\" card: kicker, title, text, go-line — the catalog's plain apostrophe (title · go-line are GREEN at HEAD)", async () => {
    const card = (await renderHub()).querySelector('a[href$="/explain/why-not"]')!;
    expect(card).toBeTruthy();
    expect(spans(card)).toEqual([t('explain.hub.not.kicker'), t('explain.hub.not.title'), t('explain.hub.not.text'), t('explain.hub.not.go')]);
  });

  it('an automation with no run shows explain.hub.autos.noRuns [GREEN at HEAD — byte-identical; preservation]', async () => {
    const text = (await renderHub()).textContent ?? '';
    expect(text).toContain(t('explain.hub.autos.noRuns'));
    expect(text).toContain('Evening Lights');
    expect(text).toContain('Porch Light');
  });
});

/* ---- HERO-1d D3 (2026-09-13): the three literals HERO-1c filed — the subhead and the two
 * per-automation link texts — and the On / Off pills are catalog rows (`explain.hub.autos.title` ·
 * `.whyFire` · `.whyNot` · `ui.on` · `ui.off`), byte-identical on screen; the link's `&rsquo;` is
 * the catalog's ’ (asserted on the rendered text, charter §4). RED at HEAD: none of the five keys
 * exists (t() reads undefined). ---- */
describe('HERO-1d D3 — the subhead, the per-automation links and the On / Off pills are the catalog', () => {
  it('the subhead → explain.hub.autos.title', async () => {
    const h2 = (await renderHub()).querySelector('h2')!;
    expect(h2.textContent).toBe('Your automations');
    expect(h2.textContent).toBe(t('explain.hub.autos.title'));
  });

  it('"Why did it fire?" → explain.hub.autos.whyFire (the run link of an automation with a run)', async () => {
    const a = (await renderHub()).querySelector('a[href$="/explain/run/run_1"]')!;
    expect(a).toBeTruthy();
    expect(a.textContent).toBe('Why did it fire?');
    expect(a.textContent).toBe(t('explain.hub.autos.whyFire'));
  });

  it('"Why didn’t it?" → explain.hub.autos.whyNot — the typographic apostrophe, asserted on the screen text', async () => {
    const a = (await renderHub()).querySelector('a[href$="/explain/why-not/auto_1"]')!;
    expect(a).toBeTruthy();
    expect(a.textContent).toBe('Why didn’t it?');
    expect(a.textContent).toBe(t('explain.hub.autos.whyNot'));
  });

  it('the On / Off pills → ui.on / ui.off', async () => {
    const c = await renderHub();
    const pillTexts = Array.from(c.querySelectorAll('li span span')).map((s) => s.textContent);
    expect(pillTexts).toContain('On');
    expect(pillTexts).toContain('Off');
    expect(t('ui.on')).toBe('On');
    expect(t('ui.off')).toBe('Off');
  });
});

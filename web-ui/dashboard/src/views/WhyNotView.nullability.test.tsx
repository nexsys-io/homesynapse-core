/*
 * NEW-3 — CONTRACT-VS-WIRE NULLABILITY on the non-firing read (RED-FIRST).
 * ---------------------------------------------------------------------------
 * The defect (G1 rehearsal §6.1–6.2, DX-16): contract.ts declared
 * `lastEvaluation` a non-nullable object; the deployed wire serves
 * `"lastEvaluation": null` (200 OK, §4.5, byte-complete body on record). The
 * type system concealed the need for a guard, and every mock populated the
 * object — the manufactured false type. WhyNotView.tsx:84 (`nf.lastEvaluation.at`)
 * threw, Act 2 rendered an indefinite spinner, and the boundary that should
 * have contained it was not mounted there (NEW-2's half of the chain).
 *
 * Tier-1 H8 discipline: the primary fixture below is the REAL captured wire
 * body, verbatim (provenance in the fixture file) — not a mock authored to be
 * convenient. House law: written RED before the fix.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { WhyNotView } from './WhyNotView';
import { api } from '../lib/api';
import { WIRE_20260816_NONFIRING_BENCH_HERO } from '../lib/api/fixtures/wire-2026-08-16-nonfiring';
import { WIRE_20260820_NEVER_TRIGGERED_BENCH_HERO } from '../lib/api/fixtures/wire-2026-08-20-never-triggered';
import { validateAgainstContract } from '../lib/api/shapes';
import { RENDER_ERROR_TITLE } from '../components/ErrorBoundary';
import { UNRESOLVED_REF_PHRASE, UNRESOLVED_REF_PILL } from '../lib/format';
import { t } from '../lib/i18n';
import type { EntitySummary } from '../lib/api/contract';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const FIX = WIRE_20260816_NONFIRING_BENCH_HERO;
const FIX_0820 = WIRE_20260820_NEVER_TRIGGERED_BENCH_HERO;

async function renderDetail(data: unknown, meta: typeof FIX.meta = FIX.meta) {
  vi.spyOn(api, 'getNonFiring').mockResolvedValue({ data, meta } as never);
  const utils = render(<WhyNotView automationId={(data as { automationId?: string }).automationId ?? 'auto_x'} />);
  await act(async () => {}); // flush the resolved fetch and the rerender
  return utils;
}

describe('the REAL 2026-08-16 wire body (lastEvaluation: null) renders honestly', () => {
  it('renders the verdict, the explanation, and NO "Last checked" row — absence, not fabrication, not a crash', async () => {
    const { container } = await renderDetail(FIX.data);
    const text = container.textContent ?? '';
    expect(text).toContain('Nothing set it off'); // the honest NEVER_TRIGGERED verdict pill
    expect(text).toContain("It hasn't run yet."); // HERO-1b B3: the keyed L1 (SPEC §3 N2) — the wire's own `explanation` string has no slot on the card
    expect(text).toContain('What would make it run'); // the surviving row still renders
    expect(text).toContain('Last checked'); // HERO-1c C6: FLIPPED from not.toContain — the null case now SAYS so in the value cell
    expect(text).toContain(t('whyNot.kv.neverChecked')); // "Never checked yet." (SPEC §7)
    expect(text).not.toContain(RENDER_ERROR_TITLE); // the VIEW is honest — not the boundary card
    expect(text).not.toContain(t('explain.loading')); // and never the eternal spinner (HERO-1c C5: the catalog row, was the literal)
  });

  it('the fixture validates against the contract mirror (object-OR-null is the recorded wire truth)', () => {
    expect(() => validateAgainstContract('B3:nonFiring', FIX)).not.toThrow();
  });

  it('the mirror REJECTS a malformed lastEvaluation (neither object nor null)', () => {
    const bad = { data: { ...FIX.data, lastEvaluation: 'yesterday' }, meta: FIX.meta };
    expect(() => validateAgainstContract('B3:nonFiring', bad)).toThrow();
  });
});

/* The 2026-08-20 capture (the NEW-2/3 build, a different deployment) is the SECOND
 * real-wire body of the same null arm — filed as cross-deployment stability, not
 * as a third arm (fixtures.stability.test.ts is the drift detector). Here: the
 * view renders it to the SAME honest never-triggered surface as the 08-16 body.
 * Disclosed: green-by-construction on a stable wire (the render path has no
 * branch on any of the three values that differ). */
describe('the REAL 2026-08-20 wire body (second deployment, same null arm) renders the same honest surface', () => {
  /** The header's "Updated …" stamp is the one surface string that legitimately
   *  differs between the two captures (it derives from meta.timestamp); mask it
   *  so the comparison is the explanation surface, not the freshness stamp. */
  const withoutFreshness = (text: string) => text.replace(/Updated .*? ago/, 'Updated <stamp>');

  it('renders the (b)-arm "why didn\'t it?" copy — verdict pill, explanation, NO "Last checked" row, no spinner, no throw', async () => {
    const { container } = await renderDetail(FIX_0820.data, FIX_0820.meta);
    const text = container.textContent ?? '';
    expect(text).toContain('Nothing set it off');
    expect(text).toContain("It hasn't run yet."); // HERO-1b B3: the keyed L1 (SPEC §3 N2) — the wire's own `explanation` string has no slot on the card
    expect(text).toContain('What would make it run');
    expect(text).toContain('state change');
    expect(text).toContain(t('whyNot.kv.neverChecked')); // HERO-1c C6: FLIPPED from not.toContain('Last checked') — the keyed sentence
    expect(text).not.toContain('It did run'); // lastRelevantRunId null → never the ran-fine pill
    expect(text).not.toContain('Ran, but sent nothing'); // noCommandsIssued null → never the silent-skip pill
    expect(text).not.toContain('null'); // never the string "null" on a surface
    expect(text).not.toContain(RENDER_ERROR_TITLE);
    expect(text).not.toContain(t('explain.loading')); // HERO-1c C5: the spinner's default label is the catalog row (this pin was the literal 'Loading…')
  });

  it('renders text-identical to the 2026-08-16 body once the freshness stamp is masked — the surface does not see the deployment', async () => {
    const a = await renderDetail(FIX.data, FIX.meta);
    const textA = withoutFreshness(a.container.textContent ?? '');
    cleanup();
    vi.restoreAllMocks();
    const b = await renderDetail(FIX_0820.data, FIX_0820.meta);
    const textB = withoutFreshness(b.container.textContent ?? '');
    expect(textB).toBe(textA);
    expect(textA).toContain('Updated <stamp>'); // the mask actually matched (the stamp rendered)
  });
});

describe('the lastEvaluation tri-state renders honestly in every arm', () => {
  it('object with at + conditionsResult renders the full row', async () => {
    const data = {
      ...FIX.data,
      lastEvaluation: { at: '2026-08-16T05:00:00Z', conditionsResult: 'after sunset = false' },
    };
    const { container } = await renderDetail(data);
    const text = container.textContent ?? '';
    expect(text).toContain('Last checked');
    expect(text).toContain('after sunset = false');
  });

  it('object with conditionsResult: null renders the time without fabricating a result', async () => {
    const data = { ...FIX.data, lastEvaluation: { at: '2026-08-16T05:00:00Z', conditionsResult: null } };
    const { container } = await renderDetail(data);
    const text = container.textContent ?? '';
    expect(text).toContain('Last checked');
    expect(text).not.toContain('null'); // never the string "null" on a surface
  });

  it('object with at: null suppresses the row (the pre-existing guard, preserved)', async () => {
    const data = { ...FIX.data, lastEvaluation: { at: null, conditionsResult: null } };
    const { container } = await renderDetail(data);
    expect(container.textContent ?? '').not.toContain('Last checked');
  });
});

describe('open-vocabulary hardening on the same surface (the closed-switch class)', () => {
  it('an unrecognized verdict renders in the honest register — never a crash, never success', async () => {
    const data = { ...FIX.data, verdict: 'SOMETHING_NEW' };
    const { container } = await renderDetail(data);
    const text = container.textContent ?? '';
    expect(text).toContain('Recorded as "SOMETHING_NEW"'); // honest-can't-know register
    expect(text).not.toContain(RENDER_ERROR_TITLE);
  });
});

/* ---- FE-113 (v1.1.3): `triggerRef` — the non-firing read can now name WHICH entity ----
 * The R-4 concealment (FE-HONEST-1 §10-J) on THIS surface: "it fires on state
 * change" with no ref to check. With v1.1.3 the wire carries
 * `triggerRef: {type: "entity", id} | null` beside `triggerSummary`. The law:
 * PRESENT-object → the entity is rendered THROUGH the registry census
 * (resolved → its display name, linked; dangling on a complete census → LOUD,
 * exactly the FE-HONEST-1 pill + phrase); PRESENT-null and ABSENT → the sentence
 * alone, no claim, no accusation. Red-first: resolved + dangling are RED at
 * HEAD (HEAD renders no ref at all); null + absent are green-by-construction
 * (the surface must not change for them — disclosed). */
const GHOST = '01KX1PB9AAB4VB3E10BD477TV3'; // the R-4 §10-J exhibit ULID, verbatim
const REGISTRY: EntitySummary[] = [
  { entityId: 'ent_hallway_motion', name: 'Hallway Motion', availability: 'AVAILABLE', stale: false, deviceId: null, lastReported: null },
  { entityId: 'ent_hallway_light', name: 'Hallway Light', availability: 'AVAILABLE', stale: false, deviceId: null, lastReported: null },
];

async function renderWithCensus(data: unknown, complete = true) {
  vi.spyOn(api, 'listEntities').mockResolvedValue({
    data: REGISTRY,
    // hasMore:true with a cursor = an INCOMPLETE census: the walker stops at its page bound
    // and the resolver must stay no-claim.
    pagination: complete ? undefined : { nextCursor: 'opaque', hasMore: true, limit: 500 },
    meta: FIX.meta,
  } as never);
  const utils = await renderDetail(data);
  // The census walk is a second async chain (listEntities → the walker → the resolver memo);
  // flush it fully before asserting so 'unverified' cannot masquerade as the final render.
  await act(async () => {});
  await act(async () => {});
  return utils;
}

describe('triggerRef renders through the registry census (v1.1.3, the §10-J law on the non-firing surface)', () => {
  it('PRESENT-object, resolved: the display name renders as a link beside the sentence — no accusation', async () => {
    const data = { ...FIX.data, triggerRef: { type: 'entity', id: 'ent_hallway_motion' } };
    const { container } = await renderWithCensus(data);
    const text = container.textContent ?? '';
    expect(text).toContain('state change'); // the sentence survives
    expect(text).toContain('Hallway Motion'); // the registry name, not the id
    const link = Array.from(container.querySelectorAll('a')).find((a) => a.textContent?.includes('Hallway Motion'));
    expect(link).toBeTruthy();
    expect(link?.getAttribute('href')).toContain('ent_hallway_motion');
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
    expect(text).not.toContain(UNRESOLVED_REF_PILL);
  });

  it('PRESENT-object, dangling on a COMPLETE census: LOUD — the ULID verbatim, the registry phrase, the failing pill', async () => {
    const data = { ...FIX.data, triggerRef: { type: 'entity', id: GHOST } };
    const { container } = await renderWithCensus(data);
    const text = container.textContent ?? '';
    expect(text).toContain(GHOST); // named, never paraphrased away
    expect(text).toContain(UNRESOLVED_REF_PHRASE); // "not in this hub's registry"
    expect(text).toContain(UNRESOLVED_REF_PILL); // the visually-failing pill
    expect(text).toContain('state change'); // and the sentence still renders
    expect(text).not.toContain(RENDER_ERROR_TITLE);
  });

  it('PRESENT-object on an INCOMPLETE census: no accusation (unverified renders neutral)', async () => {
    const data = { ...FIX.data, triggerRef: { type: 'entity', id: GHOST } };
    const { container } = await renderWithCensus(data, false);
    const text = container.textContent ?? '';
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
    expect(text).not.toContain(UNRESOLVED_REF_PILL);
  });

  it('PRESENT-null: the sentence alone — no name, no accusation, no "null"', async () => {
    const data = { ...FIX.data, triggerRef: null };
    const { container } = await renderWithCensus(data);
    const text = container.textContent ?? '';
    expect(text).toContain('state change');
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
    expect(text).not.toContain('null');
    expect(container.querySelector('a[href*="/devices/"]')).toBeNull();
  });

  it('ABSENT (the REAL 2026-08-16 v1.1.2 body): the sentence alone — the surface is unchanged for a pre-v1.1.3 hub', async () => {
    expect('triggerRef' in FIX.data).toBe(false);
    const { container } = await renderWithCensus(FIX.data);
    const text = container.textContent ?? '';
    expect(text).toContain('state change');
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
    expect(container.querySelector('a[href*="/devices/"]')).toBeNull();
  });
});

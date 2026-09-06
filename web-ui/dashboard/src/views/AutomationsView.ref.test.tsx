/*
 * FE-113 (v1.1.3) — `components[].ref` on the automation list, rendered three ways.
 * ---------------------------------------------------------------------------
 * The wire now carries `ref: {type: "entity", id} | null` on every component of
 * `GET /api/v1/automations` (CG-123; the hub's R1 rule: a component contributes
 * a ref iff it addresses exactly ONE entity by identity). The same §10-J law as
 * the causal chain and the non-firing read: PRESENT-object → the entity through
 * the registry census (resolved → its display name; dangling on a complete
 * census → LOUD, the FE-HONEST-1 pill + phrase); PRESENT-null and ABSENT → the
 * summary alone, no claim.
 *
 * Red-first: resolved + dangling are RED at HEAD (HEAD renders summaries only);
 * null + absent are green-by-construction (the surface must not change for a
 * pre-v1.1.3 hub — disclosed). This file is NEW: no AutomationsView test existed
 * at HEAD (the instruction's "+ its test" presumed one — see the return §0).
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, act } from '@testing-library/preact';
import { AutomationsView } from './AutomationsView';
import { api } from '../lib/api';
import type { AutomationSummary, EntitySummary } from '../lib/api/contract';
import { UNRESOLVED_REF_PHRASE, UNRESOLVED_REF_PILL } from '../lib/format';
import { RENDER_ERROR_TITLE } from '../components/ErrorBoundary';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const GHOST = '01KX1PB9AAB4VB3E10BD477TVX'; // the R-4 §10-J exhibit's target ULID, verbatim
const meta = { viewPosition: 9, timestamp: new Date().toISOString() };
const REGISTRY: EntitySummary[] = [
  { entityId: 'ent_hallway_motion', name: 'Hallway Motion', availability: 'AVAILABLE', stale: false, deviceId: null, lastReported: null },
  { entityId: 'ent_hallway_light', name: 'Hallway Light', availability: 'AVAILABLE', stale: false, deviceId: null, lastReported: null },
];

function auto(components: AutomationSummary['components']): AutomationSummary {
  return { automationId: 'auto_x', name: 'Evening Hallway Light', enabled: true, components, lastRunId: null };
}

async function renderList(rows: AutomationSummary[], complete = true) {
  vi.spyOn(api, 'listAutomations').mockResolvedValue({ data: rows, meta });
  vi.spyOn(api, 'listEntities').mockResolvedValue({
    data: REGISTRY,
    pagination: complete ? undefined : { nextCursor: 'opaque', hasMore: true, limit: 500 },
    meta,
  } as never);
  const utils = render(<AutomationsView />);
  await act(async () => {});
  await act(async () => {});
  await act(async () => {});
  return utils;
}

describe('components[].ref renders three ways on the automation list (v1.1.3)', () => {
  it('PRESENT-object, resolved: the registry name renders beside the summary', async () => {
    const { container } = await renderList([
      auto([{ type: 'trigger', summary: 'When motion is detected', ref: { type: 'entity', id: 'ent_hallway_motion' } }]),
    ]);
    const text = container.textContent ?? '';
    expect(text).toContain('When motion is detected');
    expect(text).toContain('Hallway Motion');
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
  });

  it('PRESENT-object, dangling on a complete census: LOUD — the ULID verbatim, the phrase, the failing pill', async () => {
    const { container } = await renderList([auto([{ type: 'action', summary: 'Dim the lamp', ref: { type: 'entity', id: GHOST } }])]);
    const text = container.textContent ?? '';
    expect(text).toContain(GHOST);
    expect(text).toContain(UNRESOLVED_REF_PHRASE);
    expect(text).toContain(UNRESOLVED_REF_PILL);
    expect(text).toContain('Dim the lamp'); // the summary still renders
    expect(text).not.toContain(RENDER_ERROR_TITLE);
  });

  it('PRESENT-object on an INCOMPLETE census: no accusation', async () => {
    const { container } = await renderList([auto([{ type: 'action', summary: 'Dim the lamp', ref: { type: 'entity', id: GHOST } }])], false);
    const text = container.textContent ?? '';
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
    expect(text).not.toContain(UNRESOLVED_REF_PILL);
  });

  it('PRESENT-null: the summary alone — no name, no "null"', async () => {
    const { container } = await renderList([auto([{ type: 'condition', summary: 'Only after sunset', ref: null }])]);
    const text = container.textContent ?? '';
    expect(text).toContain('Only after sunset');
    expect(text).not.toContain('null');
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
  });

  it('ABSENT (a v1.1.2 payload): the summary alone — the surface is unchanged for a pre-v1.1.3 hub', async () => {
    const { container } = await renderList([auto([{ type: 'condition', summary: 'Only after sunset' }])]);
    const text = container.textContent ?? '';
    expect(text).toContain('Only after sunset');
    expect(text).not.toContain('undefined');
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
  });

  it('mixed components render per component — one resolved, one dangling, one null — never collapsed', async () => {
    const { container } = await renderList([
      auto([
        { type: 'trigger', summary: 'When motion is detected', ref: { type: 'entity', id: 'ent_hallway_motion' } },
        { type: 'condition', summary: 'Only after sunset', ref: null },
        { type: 'action', summary: 'Dim the lamp', ref: { type: 'entity', id: GHOST } },
      ]),
    ]);
    const text = container.textContent ?? '';
    expect(text).toContain('Hallway Motion');
    expect(text).toContain('Only after sunset');
    expect(text).toContain(GHOST);
    expect((text.match(new RegExp(UNRESOLVED_REF_PILL, 'g')) ?? []).length).toBe(1); // exactly the one dangling component
  });
});

/*
 * FE-HONEST-1 — the LOUD unresolvable-ref render on the hero (§10-J, HIGH).
 * ---------------------------------------------------------------------------
 * The R-4 exhibit: the explain surface said "it fires on state change" while
 * the rule's entity_ref (01KX1PB9AAB4VB3E10BD477TV3) was dangling — a
 * paraphrase that concealed a fault the surface could see. The law locked
 * here: with a complete registry census, a dangling ref renders the NAMED
 * ULID + "not in this hub's registry" + a failing pill; without a census,
 * nothing is accused (a false accusation is the same defect class).
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import { CausalChain } from './CausalChain';
import { makeRefResolver } from '../lib/registry';
import { UNRESOLVED_REF_PHRASE, UNRESOLVED_REF_PILL } from '../lib/format';
import type { CausalChain as Chain, EntitySummary } from '../lib/api/contract';

afterEach(cleanup);

const GHOST = '01KX1PB9AAB4VB3E10BD477TV3'; // the R-4 §10-J field exhibit, verbatim
const GHOST_TARGET = '01KX1PB9AAB4VB3E10BD477TVX';

const REGISTRY: EntitySummary[] = [
  { entityId: 'ent_hallway_motion', availability: 'AVAILABLE', stale: false },
  { entityId: 'ent_hallway_light', name: 'Hallway Light', availability: 'AVAILABLE', stale: false },
];

function chain(over: Partial<Chain> = {}): Chain {
  return {
    runId: 'run_x',
    automationId: 'auto_x',
    automationName: 'Occupancy light',
    trigger: {
      type: 'state_changed',
      subjectRef: { type: 'ENTITY', id: GHOST },
      matchedAt: new Date().toISOString(),
      firingValue: 'occupied = true',
    },
    conditions: [],
    actions: [
      {
        type: 'device_command',
        targetRef: { type: 'ENTITY', id: GHOST_TARGET },
        command: 'turn_on',
        params: {},
        outcome: 'UNCONFIRMED',
        reason: null,
        resultOutcome: null,
        settled: true,
      },
    ],
    outcome: { status: 'COMPLETED', reason: null, durationMs: 100, actionCount: 1, commandCount: 1 },
    cascade: { parentRunId: null, depth: 0 },
    ...over,
  };
}

describe('a dangling ref renders LOUD on the chain (complete census)', () => {
  it('names both ULIDs verbatim, says the registry fact, and shows the failing pill', () => {
    const resolve = makeRefResolver(REGISTRY, true);
    const { container } = render(<CausalChain chain={chain()} resolveRef={resolve} />);
    const text = container.textContent ?? '';
    expect(text).toContain(GHOST); // the named ULID — never paraphrased away
    expect(text).toContain(GHOST_TARGET);
    expect(text).toContain(UNRESOLVED_REF_PHRASE); // "not in this hub's registry"
    expect(text).toContain(UNRESOLVED_REF_PILL); // the visually-failing pill's label
  });

  it('a resolvable ref renders its registry name — and no accusation', () => {
    const resolve = makeRefResolver(REGISTRY, true);
    const ok = chain({
      trigger: {
        type: 'state_changed',
        subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' },
        matchedAt: new Date().toISOString(),
        firingValue: 'motion = detected',
      },
      actions: [
        {
          type: 'device_command',
          targetRef: { type: 'ENTITY', id: 'ent_hallway_light' },
          command: 'turn_on',
          params: {},
          outcome: 'CONFIRMED',
          reason: null,
          resultOutcome: 'acknowledged',
          settled: true,
        },
      ],
    });
    const { container } = render(<CausalChain chain={ok} resolveRef={resolve} />);
    const text = container.textContent ?? '';
    expect(text).toContain('Hallway Light'); // the registry display name (C8) wins
    expect(text).not.toContain(UNRESOLVED_REF_PHRASE);
  });
});

describe('no census, no accusation (the other half of honesty)', () => {
  it('an INCOMPLETE census renders the same ids neutrally', () => {
    const resolve = makeRefResolver(REGISTRY, false);
    const { container } = render(<CausalChain chain={chain()} resolveRef={resolve} />);
    expect(container.textContent ?? '').not.toContain(UNRESOLVED_REF_PHRASE);
  });

  it('no resolver wired (the default) renders neutrally — the pre-lane behavior', () => {
    const { container } = render(<CausalChain chain={chain()} />);
    expect(container.textContent ?? '').not.toContain(UNRESOLVED_REF_PHRASE);
  });
});

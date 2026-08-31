/*
 * FE-HONEST-1 — registry resolution honesty (§10-J).
 * The discipline cuts both ways: 'dangling' is only claimed on a COMPLETE
 * census; a partial or unloaded census must never accuse. And the census walk
 * echoes opaque cursors — it never constructs one (contract §0).
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { fetchRegistryCensus, isUlid, makeRefResolver, UNVERIFIED_RESOLVER } from './registry';
import { api } from './api';
import type { EntitySummary } from './api/contract';

afterEach(() => vi.restoreAllMocks());

const ULID = '01KX1PB9AAB4VB3E10BD477TV3'; // the R-4 §10-J field exhibit

const ROWS: EntitySummary[] = [
  { entityId: 'ent_hallway_light', availability: 'AVAILABLE', stale: false },
  { entityId: 'ent_named', name: 'Porch Light', availability: 'AVAILABLE', stale: false },
];

describe('isUlid', () => {
  it('recognizes the 26-char Crockford shape and rejects slugs', () => {
    expect(isUlid(ULID)).toBe(true);
    expect(isUlid('ent_hallway_light')).toBe(false);
    expect(isUlid(null)).toBe(false);
    expect(isUlid('01KX1PB9AAB4VB3E10BD477TVI')).toBe(false); // I is not in the alphabet
  });
});

describe('makeRefResolver — no false accusations', () => {
  it('claims dangling ONLY on a complete census', () => {
    expect(makeRefResolver(ROWS, true)(ULID)).toEqual({ kind: 'dangling' });
    expect(makeRefResolver(ROWS, false)(ULID)).toEqual({ kind: 'unverified' });
    expect(makeRefResolver(null, true)(ULID)).toEqual({ kind: 'unverified' });
    expect(UNVERIFIED_RESOLVER(ULID)).toEqual({ kind: 'unverified' });
  });

  it('resolves registry members — name preferred, honest null-id handling', () => {
    const r = makeRefResolver(ROWS, true);
    expect(r('ent_named')).toEqual({ kind: 'named', name: 'Porch Light' });
    expect(r('ent_hallway_light')).toEqual({ kind: 'known' });
    expect(r(null)).toEqual({ kind: 'unverified' });
  });
});

describe('fetchRegistryCensus — walks A1 to completeness, echoing opaque cursors', () => {
  const meta = { viewPosition: 1, timestamp: new Date().toISOString() };

  it('single page, no pagination block: complete', async () => {
    vi.spyOn(api, 'listEntities').mockResolvedValue({ data: ROWS, meta });
    const res = await fetchRegistryCensus();
    expect(res.data.complete).toBe(true);
    expect(res.data.entities).toHaveLength(2);
  });

  it('two pages: echoes the opaque cursor and completes', async () => {
    const spy = vi
      .spyOn(api, 'listEntities')
      .mockResolvedValueOnce({
        data: [ROWS[0]!],
        meta,
        pagination: { nextCursor: 'OPAQUE_1', hasMore: true, limit: 500 },
      })
      .mockResolvedValueOnce({
        data: [ROWS[1]!],
        meta,
        pagination: { nextCursor: null, hasMore: false, limit: 500 },
      });
    const res = await fetchRegistryCensus();
    expect(res.data.complete).toBe(true);
    expect(res.data.entities).toHaveLength(2);
    expect(spy.mock.calls[1]?.[0]).toMatchObject({ cursor: 'OPAQUE_1' });
  });

  it('never claims completeness past the page bound', async () => {
    vi.spyOn(api, 'listEntities').mockResolvedValue({
      data: ROWS,
      meta,
      pagination: { nextCursor: 'MORE', hasMore: true, limit: 500 },
    });
    const res = await fetchRegistryCensus();
    expect(res.data.complete).toBe(false); // bounded walk, honest incompleteness
  });
});

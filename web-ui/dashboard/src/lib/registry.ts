/*
 * registry.ts — entity-registry resolution for the explain surfaces (FE-HONEST-1).
 * ---------------------------------------------------------------------------
 * THE §10-J LAW (R-4 field evidence, 2026-08-30): the explain surface must never
 * conceal a dangling `entity_ref` it can see. A rule that points at an entity
 * this hub's registry does not hold rendered as friendly prose ("it fires on
 * state change") cost an hour of journal archaeology; one honest sentence would
 * have surfaced it. So: every entity ref an explain surface renders is checked
 * against the registry (A1, the store's own projection), and an unresolvable ref
 * renders LOUD — the named ULID, "not in this hub's registry", visually failing.
 *
 * The honesty discipline cuts both ways: we only CLAIM "not in this registry"
 * when we hold a COMPLETE registry census (every A1 page walked). A partial or
 * unloaded census resolves to 'unverified' and renders neutral — a false
 * accusation on a trust surface is the same defect class as a false success.
 *
 * Contract note: this consumes A1 exactly as frozen (v1.1). The non-firing and
 * automations reads carry NO entity refs, so the same loudness on those
 * surfaces needs a contract addition — filed as a CONTRACT-GAP PROPOSAL in the
 * FE-HONEST-1 lane return, not improvised here.
 */
import { useMemo } from 'preact/hooks';
import { api } from './api';
import type { ApiResult } from './api/client';
import type { EntitySummary } from './api/contract';
import { useApi } from './poll';
import { displayName } from './format';

/** ULID: 26 chars of Crockford base32 (no I, L, O, U). The dangling-ref class
 *  presents as a raw ULID where a name should be — recognizing the shape lets
 *  the surface say "entity <ulid>" instead of pretending it is a name. */
export function isUlid(id: string | null | undefined): boolean {
  return typeof id === 'string' && /^[0-9A-HJKMNP-TV-Z]{26}$/.test(id);
}

export type RefResolution =
  | { kind: 'named'; name: string } // in the registry, with a display name
  | { kind: 'known' } // in the registry, no display name
  | { kind: 'dangling' } // registry census COMPLETE and the id is not in it
  | { kind: 'unverified' }; // census unavailable/incomplete — make no claim

export type RefResolver = (id: string | null | undefined) => RefResolution;

/** The no-claim resolver — the default wherever no registry census is wired. */
export const UNVERIFIED_RESOLVER: RefResolver = () => ({ kind: 'unverified' });

/** Build a resolver from a registry census. `complete` must be TRUE only when
 *  every A1 page was walked — 'dangling' is a strong claim and is only made on
 *  a complete census. */
export function makeRefResolver(
  entities: EntitySummary[] | null | undefined,
  complete: boolean,
): RefResolver {
  if (!entities) return UNVERIFIED_RESOLVER;
  const byId = new Map(entities.map((e) => [e.entityId, e]));
  return (id) => {
    if (!id) return { kind: 'unverified' };
    const hit = byId.get(id);
    if (hit) return hit.name ? { kind: 'named', name: displayName(hit) } : { kind: 'known' };
    return complete ? { kind: 'dangling' } : { kind: 'unverified' };
  };
}

/* ---- Census fetch: walk A1 to completeness (bounded) ---- */

const PAGE_LIMIT = 500;
const MAX_PAGES = 4; // 2000 entities — far beyond a V1 home; beyond it, no claim.

export interface RegistryCensus {
  entities: EntitySummary[];
  /** TRUE only when every page was walked (hasMore exhausted within bounds). */
  complete: boolean;
}

/** Walk the A1 list to a complete census. Cursors are opaque and echoed, never
 *  constructed (contract §0). If the walk is cut short (page bound reached),
 *  `complete` is false and the resolver stays no-claim. */
export async function fetchRegistryCensus(): Promise<ApiResult<RegistryCensus>> {
  let cursor: string | undefined;
  const all: EntitySummary[] = [];
  let complete = false;
  let meta: ApiResult<EntitySummary[]>['meta'] | undefined;
  for (let page = 0; page < MAX_PAGES; page++) {
    const res = await api.listEntities({ limit: PAGE_LIMIT, sort: 'ASC', ...(cursor ? { cursor } : {}) });
    all.push(...res.data);
    meta = res.meta;
    const p = res.pagination;
    if (!p || !p.hasMore || p.nextCursor == null) {
      complete = true;
      break;
    }
    cursor = p.nextCursor;
  }
  // meta is always set (the loop runs at least once).
  return { data: { entities: all, complete }, meta: meta! };
}

/** Hook: the registry census for an explain surface, refetched on the shared
 *  projection cursor like every other read (one poll loop, no storms). Returns
 *  a resolver that is 'unverified' until the census is in. */
export function useRefResolver(): RefResolver {
  const state = useApi(fetchRegistryCensus);
  return useMemo(() => {
    if (state.status !== 'ok' || !state.data) return UNVERIFIED_RESOLVER;
    return makeRefResolver(state.data.entities, state.data.complete);
  }, [state.status, state.data]);
}

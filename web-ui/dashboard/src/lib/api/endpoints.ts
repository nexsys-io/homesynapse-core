/*
 * HomeSynapse — Typed endpoint layer.
 * One function per frozen-contract read. Transport-agnostic: runs identically
 * over the real or mock transport. A-class are live; B-class are mocked until
 * Core delivers them (B1 soon, B3 after M7.2b — see the contract freeze §C).
 */
import type { ApiClient, ApiResult } from './client';
import type {
  AutomationSummary,
  CausalChain,
  ConsolidatedHealth,
  DlqStatus,
  EntityDetail,
  EntityState,
  EntitySummary,
  EventSummary,
  NonFiringExplanation,
  ProjectionStatus,
  RunSummary,
} from './contract';

export type Sort = 'ASC' | 'DESC';

export function makeApi(client: ApiClient) {
  return {
    // ---- A-class (EXISTING — live) ----
    listEntities: (q?: { limit?: number; cursor?: string; sort?: Sort }) =>
      client.get<EntitySummary[]>('A1:entities', '/api/v1/entities', q),

    getEntity: (entityId: string) =>
      client.get<EntityDetail>('A2:entity', `/api/v1/entities/${encodeURIComponent(entityId)}`),

    getEntityState: (entityId: string) =>
      client.get<EntityState>('A3:entityState', `/api/v1/entities/${encodeURIComponent(entityId)}/state`),

    getProjection: () => client.get<ProjectionStatus>('A4:projection', '/internal/projection'),

    getDlq: () => client.get<DlqStatus>('A5:dlq', '/internal/dlq'),

    // ---- B-class (FROZEN-UNBUILT — mocked now) ----
    listEvents: (q?: { since?: string; limit?: number; type?: string; subjectId?: string; sort?: Sort }) =>
      client.get<EventSummary[]>('B1:events', '/api/v1/events', q),

    getHealth: () => client.get<ConsolidatedHealth>('B2:health', '/api/v1/health'),

    listRuns: (q?: { automationId?: string; since?: string; limit?: number }) =>
      client.get<RunSummary[]>('B3:runs', '/api/v1/runs', q),

    getCausalChain: (runId: string) =>
      client.get<CausalChain>('B3:causalChain', `/api/v1/runs/${encodeURIComponent(runId)}/causal-chain`),

    getNonFiring: (automationId: string, q?: { expectedSince?: string }) =>
      client.get<NonFiringExplanation>(
        'B3:nonFiring',
        `/api/v1/automations/${encodeURIComponent(automationId)}/non-firing`,
        q,
      ),

    listAutomations: () => client.get<AutomationSummary[]>('B3:automations', '/api/v1/automations'),
  };
}

export type Api = ReturnType<typeof makeApi>;
export type { ApiResult };

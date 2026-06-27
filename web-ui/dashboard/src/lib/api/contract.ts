/*
 * HomeSynapse — Dashboard Read-API Contract (TypeScript mirror)
 * ---------------------------------------------------------------------------
 * Source of truth: context/decisions/2026-06-21_dashboard-read-API-contract-freeze.md
 * (FROZEN v1.1, source-verified against core 1541446). These types mirror that
 * contract field-for-field. If Core needs a shape to change, that is a CROSS-LANE
 * EVENT routed to the hub — do NOT edit these to "match" a drifted endpoint.
 *
 * Two classes:
 *   A-class = EXISTING endpoints (consume live).
 *   B-class = FROZEN-UNBUILT (mock to these shapes; Core implements TO them).
 */

export const CONTRACT_VERSION = 'v1.1-2026-06-21' as const;

/* ===========================================================================
 * 0. Transport + cross-cutting (binds every endpoint)
 * ========================================================================= */

export interface ResponseMeta {
  /** Monotonic projection cursor. Poll change-detection compares this. */
  viewPosition: number;
  /** ISO-8601 server timestamp. */
  timestamp: string;
}

export interface PaginationMeta {
  /** Opaque — echo it back as `cursor`/`since`; never construct one. */
  nextCursor: string | null;
  hasMore: boolean;
  limit: number;
}

export interface Envelope<T> {
  data: T;
  pagination?: PaginationMeta;
  meta: ResponseMeta;
}

/** RFC 9457 problem+json. Non-2xx bodies are application/problem+json. */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}

/** Frozen ProblemType taxonomy (slug -> status). */
export const PROBLEM_TYPES = {
  'not-found': 404,
  'entity-disabled': 409,
  'integration-unhealthy': 503,
  'invalid-command': 422,
  'invalid-parameters': 400,
  'authentication-required': 401,
  'forbidden': 403,
  'rate-limited': 429,
  'command-not-found': 404,
  'state-store-replaying': 503,
  'internal-error': 500,
  'idempotency-key-conflict': 409,
  'device-orphaned': 503,
} as const;
export type ProblemType = keyof typeof PROBLEM_TYPES;

/* ===========================================================================
 * Shared enums
 * ========================================================================= */

export type Availability = 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN';

/** Never a silent blank (v1.1 / research FM-1). UNKNOWN is an honest value. */
export type Origin = 'AUTOMATION' | 'DEVICE' | 'USER' | 'EXTERNAL' | 'UNKNOWN';

export type RunStatus = 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'CANCELLED' | 'INTERRUPTED';

/**
 * The command-outcome trust win (v1.1 / research §3).
 *  DISPATCHED  — command sent.
 *  CONFIRMED   — device reported the expected state (needs M7.3).
 *  UNCONFIRMED — sent, no confirmation within timeout (the honest "we don't know").
 *  FAILED      — recorded failure reason.
 *  SKIPPED     — not executed (e.g. a fail-fast earlier in the sequence).
 */
export type ActionOutcome = 'DISPATCHED' | 'CONFIRMED' | 'UNCONFIRMED' | 'FAILED' | 'SKIPPED';

/** "Why didn't it fire?" — the three-way verdict (+ DISABLED). The most differentiated read. */
export type NonFiringVerdict =
  | 'CONDITION_NOT_MET'
  | 'NEVER_TRIGGERED'
  | 'ACTED_BUT_UNCONFIRMED'
  | 'DISABLED';

export type IntegrationHealth = 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY' | 'UNKNOWN';

export type ProjectionMode = 'REPLAY' | 'TRANSITION' | 'LIVE';

export interface SubjectRef {
  type: string; // e.g. ENTITY
  id: string;
}

/** AMD-52 typed-value envelope: {"t": <AttributeType>, "v": ...}. */
export interface TypedValue {
  t: string;
  v: unknown;
}

/* ===========================================================================
 * A. EXISTING contracts (consume live)
 * ========================================================================= */

/** A1 — GET /api/v1/entities (3-field hot-path projection). */
export interface EntitySummary {
  entityId: string;
  availability: Availability;
  stale: boolean;
}

/** A2 — GET /api/v1/entities/{id} (hot-path detail). */
export interface EntityDetail {
  entityId: string;
  availability: Availability;
  attributes: Record<string, TypedValue>;
}

/** A3 — GET /api/v1/entities/{id}/state (full materialized EntityState, Doc 03 §4.1). */
export interface EntityState {
  entityId: string;
  availability: Availability;
  attributes: Record<string, TypedValue>;
  stateVersion: number;
  lastChanged: string | null;
  lastUpdated: string | null;
  lastReported: string | null;
  stale: boolean;
  staleAfter: string | null;
}

/** A4 — GET /internal/projection. */
export interface ProjectionStatus {
  mode: ProjectionMode;
  viewPosition: number;
  lagEvents: number;
  projectionVersion: number;
}

/** A5 — GET /internal/dlq. */
export interface DlqStatus {
  depth: number;
  parkedSubscribers: { subscriberId: string; reason?: string }[];
}

/* ===========================================================================
 * B. FROZEN-UNBUILT contracts (mock now; Core implements TO them)
 * ========================================================================= */

/** B1 — GET /api/v1/events (flattened event summary). */
export interface EventSummary {
  eventId: string;
  type: string;
  category: string;
  occurredAt: string;
  viewPosition: number;
  subjectRef: SubjectRef;
  correlationId: string;
  causationId: string | null;
  origin: Origin;
  summary: string;
}

/** B2 — GET /api/v1/health (consolidated; or compose A4+A5). */
export interface ConsolidatedHealth {
  phase: string;
  projection: { mode: ProjectionMode; viewPosition: number; lagEvents: number };
  dlq: { depth: number; parkedSubscribers: string[] };
  integrations: { id: string; health: IntegrationHealth }[];
}

/** B3 — GET /api/v1/runs (the "why did this fire?" entry list). */
export interface RunSummary {
  runId: string;
  automationId: string;
  automationName: string;
  triggeredAt: string;
  status: RunStatus;
  terminalReason: string | null;
}

export interface CausalTrigger {
  type: string;
  subjectRef: SubjectRef;
  matchedAt: string;
  firingValue: string;
}

export interface CausalCondition {
  expression: string;
  evaluated: boolean;
  result: boolean;
  observedState: { entityId: string; attribute: string; value: string }[];
}

export interface CausalAction {
  type: string;
  targetRef: SubjectRef;
  command: string;
  params: Record<string, unknown>;
  outcome: ActionOutcome;
  reason: string | null;
}

/** B3 — GET /api/v1/runs/{runId}/causal-chain (the hero "why did this fire?" tree). */
export interface CausalChain {
  runId: string;
  automationId: string;
  automationName: string;
  trigger: CausalTrigger;
  conditions: CausalCondition[];
  actions: CausalAction[];
  outcome: {
    status: RunStatus;
    reason: string | null;
    durationMs: number;
    actionCount: number;
    commandCount: number;
  };
  cascade: { parentRunId: string | null; depth: number };
}

/** B3 — GET /api/v1/automations/{id}/non-firing (the co-equal "why didn't it fire?" read). */
export interface NonFiringExplanation {
  automationId: string;
  automationName: string;
  enabled: boolean;
  verdict: NonFiringVerdict;
  lastRelevantRunId: string | null;
  explanation: string;
  triggerSummary: string;
  lastEvaluation: { at: string | null; conditionsResult: string | null };
}

/** B3 — GET /api/v1/automations (supporting surface). */
export interface AutomationSummary {
  automationId: string;
  name: string;
  enabled: boolean;
  components: { type: string; summary: string }[];
  lastRunId: string | null;
}

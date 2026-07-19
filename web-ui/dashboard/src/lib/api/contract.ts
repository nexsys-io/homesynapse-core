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

export const CONTRACT_VERSION = 'v1.1.1-2026-07-02' as const;

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

/** RFC 9457 problem+json. Non-2xx bodies are application/problem+json.
 *  `type` is the full URI form `https://homesynapse.local/problems/<slug>`
 *  (v1.1.1 correction, 2026-07-02 — per Locked Doc 09 §3.8 + the shipped
 *  `ProblemType.TYPE_URI_PREFIX`). Clients key on the trailing SLUG — never
 *  match the whole URI byte-for-byte. See `problemSlug()`. */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}

/** The ratified problem-type URI prefix (v1.1.1). Mirror of the constant the
 *  amendment pins: Doc 09 §3.8 / `ProblemType.TYPE_URI_PREFIX` (source-verified
 *  ProblemType.java:160). Wire `type` = this prefix + slug. */
export const PROBLEM_TYPE_URI_PREFIX = 'https://homesynapse.local/problems/' as const;

/** Derive the stable slug from a problem `type` (v1.1.1 matching rule):
 *  strip the ratified URI prefix when present; tolerate a bare slug (client-
 *  minted problems like `network-unreachable` never travel the wire). */
export function problemSlug(type: string | undefined): string {
  if (!type) return '';
  return type.startsWith(PROBLEM_TYPE_URI_PREFIX)
    ? type.slice(PROBLEM_TYPE_URI_PREFIX.length)
    : type;
}

/** Frozen ProblemType taxonomy (slug -> status). Slugs are the stable
 *  identifier set; the wire carries them in URI form (v1.1.1). */
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

/** A1 — GET /api/v1/entities (hot-path projection).
 *  `name` is the v1.1 ADDITIVE optional display name (freeze §A, 2026-06-26 C8):
 *  Core returns it when set, omits it when unset — clients tolerate absence and
 *  fall back to a humanized slug (format.displayName). */
export interface EntitySummary {
  entityId: string;
  name?: string;
  availability: Availability;
  stale: boolean;
}

/** A2 — GET /api/v1/entities/{id} (hot-path detail). Optional `name` per C8. */
export interface EntityDetail {
  entityId: string;
  name?: string;
  availability: Availability;
  attributes: Record<string, TypedValue>;
}

/** A3 — GET /api/v1/entities/{id}/state (full materialized EntityState, Doc 03 §4.1).
 *  Optional `name` per C8 (additive, tolerate absence). */
export interface EntityState {
  entityId: string;
  name?: string;
  availability: Availability;
  attributes: Record<string, TypedValue>;
  stateVersion: number;
  lastChanged: string | null;
  lastUpdated: string | null;
  lastReported: string | null;
  stale: boolean;
  staleAfter: string | null;
}

/** A4 — GET /internal/projection. Enveloped `{data, meta}` since M7.5c-a
 *  (v1.1.1 DRIFT-1 conformance). Frozen four + the ruled ADDITIVE extras
 *  (`entityCount`, `ready`) — optional here per the C8 additive-tolerance
 *  precedent; live Core emits them. */
export interface ProjectionStatus {
  mode: ProjectionMode;
  viewPosition: number;
  lagEvents: number;
  projectionVersion: number;
  entityCount?: number;
  ready?: boolean;
}

/** A5 additive per-subscriber detail (ruled extra, M7.5c-a). */
export interface DlqSubscriber {
  subscriberId: string;
  mode: string;
  dlqDepth: number;
  crashCount: number;
  oldestParkedAt: string | null;
}

/** A5 — GET /internal/dlq. Enveloped since M7.5c-a (v1.1.1 DRIFT-1
 *  conformance). `parkedSubscribers` is the RATIFIED shape: subscriber IDS
 *  (ids with dlqDepth > 0) — consistent with B2's `parkedSubscribers:
 *  string[]`. `subscribers` is the ruled additive detail. */
export interface DlqStatus {
  depth: number;
  parkedSubscribers: string[];
  subscribers?: DlqSubscriber[];
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

/** B3 — GET /api/v1/runs (the "why did this fire?" entry list).
 *  [OBSERVED LIVE NULLABILITY, field-evidenced 2026-07-18]: `automationName` is
 *  `null` on the wire for runs recorded under a prior automation instance
 *  (instance ULIDs re-mint per YAML load; StandardExplanationService.toSummary
 *  serves registry-miss names as null). The v1.1 freeze text does not annotate
 *  this field nullable — a contract-clarification ask is recorded with the hub
 *  (lane return 2026-07-19); the client tolerates null and renders the class
 *  honestly (format.runName), never inventing a name. */
export interface RunSummary {
  runId: string;
  automationId: string;
  automationName: string | null;
  triggeredAt: string;
  status: RunStatus;
  terminalReason: string | null;
}

/** [OBSERVED LIVE NULLABILITY — same class as RunSummary.automationName]:
 *  `type` is null when the automation definition is no longer registered
 *  (StandardExplanationService.buildTrigger derives it from the registry).
 *  Tolerated + rendered honestly; clarification ask recorded with the hub. */
export interface CausalTrigger {
  type: string | null;
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

/** B3 — GET /api/v1/runs/{runId}/causal-chain (the hero "why did this fire?" tree).
 *  `automationName` nullability: the same observed prior-instance class as
 *  RunSummary.automationName (tolerated; rendered honestly). */
export interface CausalChain {
  runId: string;
  automationId: string;
  automationName: string | null;
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

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

export const CONTRACT_VERSION = 'v1.1.3-2026-09-06' as const;
/* v1.1.3 (docket Row 14 RULED (a) 2026-09-03; landed core-side 2026-09-06, CG-123 at
 * f25291b, the SKIP-VIS shape; the FE mirror is FE-113). The same four-constraint law.
 * FOUR ADDITIVE keys across THREE reads, zero changes to any existing field/casing/
 * nesting/order (existing keys first; the new keys APPENDED, in this order):
 *   - A1 `entities[].deviceId: string | null` — the owning device's ULID, read from the
 *     LIVE registry at request time (two instants: the row's other fields come from the
 *     state snapshot at viewPosition; a null is honest, never a defect).
 *   - A1 `entities[].lastReported: string | null` — `Instant.toString()`: ISO-8601 UTC
 *     with nanos when present (e.g. "2026-09-06T02:45:29.123456Z"); NEVER epoch seconds.
 *   - non-firing `data.triggerRef: SubjectRef | null` — the first trigger's single-entity
 *     ref, `{type: "entity", id}` (the causal chain's own literal; lowercase), null when
 *     the trigger names no single entity.
 *   - automations `data[].components[].ref: SubjectRef | null` — per component, the same
 *     literal; null when the component names no single entity (the hub's R1 rule: a ref
 *     iff exactly ONE entity by identity).
 * EVERY new key is PRESENT in every v1.1.3 payload — JSON null when unknown, never
 * absent. A v1.1.2 hub omits them entirely (lawful): the mirror marks them OPTIONAL and
 * the validators enforce the TRI-STATE — absent passes, null passes, a present key
 * must be typed (a wrong type is contract drift, never "optional").
 * THE HONESTY LAW (FE-HONEST-1): ABSENCE and NULL are two different facts and render
 * as two different sentences; a dangling ref renders LOUD through the registry census.
 * H8 WARNING (the false-type class): a mock that ALWAYS populates a nullable key
 * manufactures a false type — the default mock carries ≥1 null per new key and one
 * dangling ref by law (v113-additive.test.ts pins it). */
/* v1.1.2 (ratified 2026-07-22, Nick ruling 1 — the four-constraint law: additive-only ·
 * per-endpoint camelCase · the emitter leads · version discipline; landed core-side
 * 2026-07-26, WU-SKIP-VIS, DP-4 GO). Three ADDITIVE keys, zero changes to any
 * existing v1.1 field/casing/nesting:
 *   - causal-chain `actions[].resultOutcome` (raw ten-value command_result.outcome +
 *     adapter strings; null when no command_result exists) — the un-collapsed
 *     disposition. Superseded no longer renders FAILED; honest-"unconfirmed"
 *     derives UNCONFIRMED with its recorded reason.
 *   - causal-chain `actions[].settled` (the Q1b provisionality flag: false exactly
 *     while DISPATCHED with no settling record).
 *   - non-firing `data.noCommandsIssued` (true exactly for the COMPLETED
 *     zero-command silent-skip run; JSON null otherwise — never false).
 * CONSUMPTION NOTE (law (c), emitter-leads): SKIP-VIS is ON MAIN but the DEPLOYED
 * Pi read surface predates it until the deploy evening completes — the mirror
 * therefore marks the new keys OPTIONAL (absence = a lawful pre-v1.1.2 payload;
 * presence is validated). The client degrades gracefully on pre-v1.1.2 payloads
 * via the recorded-reason recovery + client-side settled derivation (verdicts.ts,
 * the SAME rule the core instruction states). */

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
  /** The subject category, LOWERCASE on the wire: `"entity"` (RunExplanation.java:95,
   *  served since M7.5a; re-pinned at the v1.1.3 bytes, CG-123 audit §0). The old
   *  "e.g. ENTITY" note here was a stale FE comment, never a wire fact — and the mirror
   *  never normalizes case: the literal is rendered/compared as served. */
  type: string;
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
  /** v1.1.3 ADDITIVE (CG-2): the owning device's ULID string, or null when this hub's
   *  LIVE registry holds no device for the entity. OPTIONAL = absent on a pre-v1.1.3
   *  hub (render absence); present-null = "nothing on record" (render that fact). */
  deviceId?: string | null;
  /** v1.1.3 ADDITIVE (CG-3): the projection's last report instant as
   *  `Instant.toString()` (ISO-8601 UTC, nanos when present), or null when no report
   *  is on record. NEVER epoch seconds — parse only via format.parseInstant. */
  lastReported?: string | null;
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
  /** [v1.1.2 VALUE note, 2026-07-26 — SKIP-VIS DP-3]: the SHAPE is unchanged, but the
   *  VALUE is corrected — pre-fix wires understate triggeredAt by exactly durationMs
   *  whenever the terminal envelope carries a DP-G-inherited eventTime (Rosonway §4).
   *  Post-fix, runs[].triggeredAt ≡ causal-chain trigger.matchedAt (test-pinned core-
   *  side). INTERIM CAVEAT: until the SKIP-VIS landing DEPLOYS, do not build ordering/
   *  age logic that trusts this field on the live wire — trigger.matchedAt on the
   *  causal chain is the true instant there. This client only displays it. */
  triggeredAt: string;
  status: RunStatus;
  terminalReason: string | null;
}

/** [OBSERVED LIVE NULLABILITY — same class as RunSummary.automationName]:
 *  `type` is null when the automation definition is no longer registered
 *  (StandardExplanationService.buildTrigger derives it from the registry).
 *  Tolerated + rendered honestly; clarification ask recorded with the hub.
 *  [OBSERVED LIVE NULLABILITY, 2026-07-27 — FE-LIVE-V112 item 1]: `firingValue`
 *  is null on the live wire in ALL eras ("what set it off" never carries a
 *  value; minted as its own hub row, priority ruled at STATE-DIALECT
 *  authoring). The v1.1 freeze text does not annotate it nullable — the mirror
 *  records the observed wire (the automationName precedent); the client
 *  tolerates null and renders the honest "value not recorded" marker, never an
 *  invented value. Guarded only — no other action taken (the hub's ruling). */
export interface CausalTrigger {
  type: string | null;
  subjectRef: SubjectRef;
  matchedAt: string;
  firingValue: string | null;
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
  /** v1.1.2 ADDITIVE (GAP-1 → SKIP-VIS DP-1): the raw `command_result.outcome`
   *  string associated with this action's command (the live ten-value vocabulary
   *  plus adapter-specific strings), or null when no command_result exists in the
   *  chain — a pure fact-carry, independent of which branch classified `outcome`.
   *  OPTIONAL in the mirror only because the deployed read surface may predate the
   *  landing (absence = pre-v1.1.2 payload; the recorded-reason recovery covers it). */
  resultOutcome?: string | null;
  /** v1.1.2 ADDITIVE (Q1b → SKIP-VIS DP-4 GO): false exactly while the action is
   *  DISPATCHED with no settling record (resultOutcome null or bare "acknowledged");
   *  a superseded DISPATCHED is settled. A COMPLETED run's action can settle AFTER
   *  terminal (a late command_result re-derives on the next read — Rosonway §5.9),
   *  so an unsettled action renders visibly PROVISIONAL, never as a settled pill.
   *  OPTIONAL for pre-v1.1.2 payloads; verdicts.isActionSettled derives the same
   *  rule client-side when absent. */
  settled?: boolean;
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
  /** v1.1.2 VALUE note (SKIP-VIS DP-3b): `at` carries the same eventTime-present
   *  correction as runs[].triggeredAt (pre-fix wires understate it by durationMs).
   *  [OBSERVED LIVE NULLABILITY, 2026-08-16 — G1 rehearsal §4.5, DX-16]: the
   *  deployed server serves `"lastEvaluation": null` (200 OK, byte-complete body
   *  on record) for a NEVER_TRIGGERED automation with no evaluation on record.
   *  The freeze text writes the object form with nullable INSIDES only; the wire
   *  nulls the WHOLE object. Per the automationName/firingValue precedent the
   *  mirror records the observed wire — the server is the frozen contract's
   *  reality; this is a CLIENT-TYPE correction toward it, not a contract change.
   *  A freeze-text clarification ask is recorded with the hub (FE lane return
   *  2026-08-17). The non-nullable declaration was DX-16's defect: it concealed
   *  the need for a guard and the always-populated mock manufactured the false
   *  type (H8's origin exhibit). */
  lastEvaluation: { at: string | null; conditionsResult: string | null } | null;
  /** v1.1.2 ADDITIVE (CORE-P2 → SKIP-VIS DP-2): TRUE exactly when the governing
   *  COMPLETED run's terminal payload shows actionCount > 0 with commandCount == 0
   *  (the silent-skip class — §3.9 all-skipped runs emit nothing; the payload
   *  arithmetic is the only log-visible disclosure); JSON null otherwise — NEVER
   *  false. Such runs report ACTED_BUT_UNCONFIRMED; the clean-success sentence is
   *  unreachable for them. OPTIONAL for pre-v1.1.2 payloads. */
  noCommandsIssued?: true | null;
  /** v1.1.3 ADDITIVE (CG-1): the first trigger's single-entity reference —
   *  `{type: "entity", id}`, the causal chain's own literal — or null when the
   *  trigger names no single entity (a group selector, a calendar, a webhook…).
   *  Appended after noCommandsIssued; always present on a v1.1.3 hub. OPTIONAL for
   *  pre-v1.1.3 payloads (the two recorded fixtures carry no key — lawful). The
   *  surface resolves it through the registry census: dangling renders LOUD. */
  triggerRef?: SubjectRef | null;
}

/** B3 — one component of an automation (trigger · condition · action), as listed. */
export interface ComponentSummary {
  type: string;
  summary: string;
  /** v1.1.3 ADDITIVE (CG-1): the single entity this component addresses by identity
   *  (`{type: "entity", id}`), or null when it names none or several (the hub's R1
   *  rule). Appended after summary; always present on a v1.1.3 hub; OPTIONAL for
   *  pre-v1.1.3 payloads. */
  ref?: SubjectRef | null;
}

/** B3 — GET /api/v1/automations (supporting surface). */
export interface AutomationSummary {
  automationId: string;
  name: string;
  enabled: boolean;
  components: ComponentSummary[];
  lastRunId: string | null;
}

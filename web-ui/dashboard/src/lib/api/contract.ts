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

export const CONTRACT_VERSION = 'v1.1.5-2026-09-19' as const;
/* v1.1.5 (Nick's `EXPLAIN: three`, the THIRD bump; landed core-side 2026-09-14 EXPLAIN-114c at e56f555; the
 * freeze doc's stamp is v1.1.5; the FE mirror is FE-115, 2026-09-19 — the pin's date is the mirror's). The same
 * four-constraint law. ONE ADDITIVE key on ONE read, zero changes to any existing field/casing/nesting/order:
 *   - causal-chain `conditions[].definition: ConditionDefinition | null` (5th, after observedState) — the
 *     condition as STRUCTURED DATA from the registry definition the run ran under, a nested object in EXACTLY
 *     the order type · selector · attribute · value · above · below · after · before · children (recursive;
 *     `children` is ALWAYS an array, `[]` for a leaf, never null — GetRunCausalChainEndpoint.definitionMap
 *     :178–:195, RunExplanation.ConditionDefinitionView :203–:206). PRESENT in every v1.1.5 payload; JSON null
 *     when the projection cannot VOUCH for it (the registry no longer holds the run's definition by hash, the
 *     automation is gone, or conditionIndex is out of range — StandardExplanationService.buildConditions).
 *   - `type` is the definition's simple class name — `ConditionDefinitionRenderer.render` (the sealed permits:
 *     StateCondition · NumericCondition · TimeCondition · AndCondition · OrCondition · NotCondition ·
 *     ZoneCondition); an OPEN vocabulary to this mirror — a name it does not know renders as recorded.
 *   - `above` / `below` are NUMBERS on the wire (Double); `after` / `before` are "HH:MM" strings; `value` is a
 *     string as recorded (it may look numeric — never coerced); `selector` is ONE string.
 * A pre-v1.1.5 hub omits the key (lawful): the mirror marks it OPTIONAL and the validators enforce the TRI-STATE
 * (absent passes · null passes · a present key must be the typed object). NO LIVE v1.1.5 CAUSAL-CHAIN CAPTURE
 * exists in the corpus — the H8-a bodies (2026-09-19) are entities / automations / non-firing only — so this key
 * is MIRRORED, not VERIFIED (H8): the live-wire bar for it is owed to the next sitting. */
/* v1.1.4 (HERO-0 §3 / Nick's `EXPLAIN: three`; landed core-side 2026-09-12 EXPLAIN-114a at
 * 5f918c7 with its R3 correction, 2026-09-13 EXPLAIN-114b at fed99e8 wire byte-identical; the
 * freeze doc's amendment date is the 13th; the FE mirror is FE-114). The same four-constraint
 * law. SEVEN ADDITIVE keys across THREE reads + ONE enum value, zero changes to any existing
 * field/casing/nesting/order (each new key APPENDED at the END of its object):
 *   - causal-chain `actions[].settledAt: string | null` (9th, after settled) — the CLASSIFYING
 *     envelope's instant (state_confirmed / the last classifying command_result / the timeout /
 *     a command-less action's completion); null for a bare or acknowledged DISPATCHED.
 *   - causal-chain `actions[].confirmedAt: string | null` (10th) — the `state_confirmed`
 *     instant; null when there is none. `settled: true` beside `confirmedAt: null` is LAWFUL
 *     (a FAILED or UNCONFIRMED action settles without a confirmation).
 *   - causal-chain `data.definitionKey: string | null` (9th, after cascade) — the run's
 *     `automation_triggered.definitionHash` (SHA-256 hex); null when the log carries none.
 *   - non-firing `data.disabledAt: string | null` (11th, after triggerRef) · `disabledReason:
 *     string | null` (12th) · `definitionKey: string | null` (13th) — the LATEST
 *     `automation_disabled`'s instant and reason ("repeated_failure"), else `disabledReason`
 *     "configuration" on the DISABLED verdict (DP-6); both null on any non-DISABLED verdict;
 *     `definitionKey` never null in production (null only for a fixture).
 *   - non-firing `data.verdict` gains the value `FIRED_CONFIRMED` (the enum grows LAST) — the
 *     DP-B2 clean-confirmed-success case, with a non-null `lastRelevantRunId`.
 *   - automations `data[].definitionKey: string | null` (6th, after lastRunId) — never null in
 *     production (null only for a fixture).
 * Every instant is `Instant.toString()` (ISO-8601 UTC, nanos when present) — NEVER epoch
 * seconds; parse only via format.parseInstant. EVERY new key is PRESENT in every v1.1.4
 * payload — JSON null when the log carries no value, never absent. A pre-v1.1.4 hub omits them
 * (lawful): the mirror marks them OPTIONAL and the validators enforce the TRI-STATE (absent
 * passes · null passes · a present key must be typed). NO LIVE v1.1.4 CAPTURE exists in the
 * corpus yet — the mirror is MIRRORED, not VERIFIED, until H8's real-wire read (charter §4).
 * v1.1.5 (`conditions[].definition`, EXPLAIN-114c) is read since FE-115 — the paragraph above. */
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

/** "Why didn't it fire?" — the three-way verdict (+ DISABLED). The most differentiated read.
 *  v1.1.4 ADDITIVE (EXPLAIN-114a): `FIRED_CONFIRMED` joins LAST — the DP-B2 clean-confirmed-success
 *  case (the most recent in-window run completed and every device action confirmed;
 *  `lastRelevantRunId` is that run — NonFiringExplanation.java:159–:165). Not a non-firing at
 *  all; rendered "as recorded" (the Q1 register: the claim is Core's). */
export type NonFiringVerdict =
  | 'CONDITION_NOT_MET'
  | 'NEVER_TRIGGERED'
  | 'ACTED_BUT_UNCONFIRMED'
  | 'DISABLED'
  | 'FIRED_CONFIRMED';

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
  /** REQUIRED-NULLABLE (FE-NULL-1, HERO-0 F2 2026-09-06): null when the triggering event is
   *  outside the run's correlation — StandardExplanationService.java:644–:649 (`.orElse(null)`).
   *  The key is always PRESENT on the v1.1 wire; only its VALUE is nullable. */
  subjectRef: SubjectRef | null;
  matchedAt: string;
  firingValue: string | null;
}

/** v1.1.5 ADDITIVE (EXPLAIN-114c; FE-115): one condition definition as the emitter renders it —
 *  `RunExplanation.ConditionDefinitionView` through `GetRunCausalChainEndpoint.definitionMap` (:178–:195),
 *  a flat nine-key shape that covers every permit of the sealed `ConditionDefinition` hierarchy: the
 *  components a permit does not carry are null; `children` is ALWAYS an array (`[]` for a leaf, never
 *  null) and recursive for AndCondition / OrCondition (≥ 2 operands in practice) and NotCondition (one).
 *  `type` is the definition's simple class name (`ConditionDefinitionRenderer.render`: StateCondition ·
 *  NumericCondition · TimeCondition · AndCondition · OrCondition · NotCondition · ZoneCondition) — an OPEN
 *  vocabulary to this mirror. `selector` is ONE string (an entity ULID, a slug, `<kind>:<value>/<roles>`
 *  for area / label / type / tag, compound parts joined by `+`). `above` / `below` are numbers (Double);
 *  `after` / `before` are "HH:MM" strings; `value` is the recorded string, never coerced. Readonly and
 *  recursive: the mirror never mutates what the wire carried. */
export interface ConditionDefinition {
  readonly type: string;
  readonly selector: string | null;
  readonly attribute: string | null;
  readonly value: string | null;
  readonly above: number | null;
  readonly below: number | null;
  readonly after: string | null;
  readonly before: string | null;
  readonly children: readonly ConditionDefinition[];
}

export interface CausalCondition {
  expression: string;
  evaluated: boolean;
  result: boolean;
  /** `value` REQUIRED-NULLABLE (FE-NULL-1, HERO-0 F2 2026-09-06): null when the entity had no
   *  value for the attribute at evaluation — RunExplanation.java:137 ("or null if unreported"). */
  observedState: { entityId: string; attribute: string; value: string | null }[];
  /** v1.1.5 ADDITIVE (EXPLAIN-114c; 5th, after observedState): the condition as structured data from the
   *  registry definition the run ran under, or JSON null when the projection cannot VOUCH for it (the
   *  registry's definition no longer hashes to the run's stamped `definitionHash`, the automation is gone,
   *  or `conditionIndex` is out of range — StandardExplanationService.buildConditions; never a guess).
   *  PRESENT in every v1.1.5 payload; OPTIONAL = absent on a pre-v1.1.5 hub (the tri-state). Rendered by
   *  format.definitionSentence under the condition row (FE-115 D1); absent renders nothing. */
  definition?: ConditionDefinition | null;
}

export interface CausalAction {
  type: string;
  /** REQUIRED-NULLABLE (FE-NULL-1, HERO-0 F2 2026-09-06): null for a non-dispatched action with
   *  no target refs — StandardExplanationService.java:771 (`targetRefs().isEmpty() ? null : …`). */
  targetRef: SubjectRef | null;
  /** REQUIRED-NULLABLE (FE-NULL-1, HERO-0 F2 2026-09-06): null for a SKIPPED/FAILED action that
   *  never issued a command — StandardExplanationService.java:776 (`new ActionView(…, targetRef, null, "{}", …)`). */
  command: string | null;
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
  /** v1.1.4 ADDITIVE (EXPLAIN-114a; 9th, after settled): the CLASSIFYING envelope's instant as
   *  `Instant.toString()` — `state_confirmed`, the last classifying `command_result`, the
   *  timeout, or a command-less action's `automation_action_completed`; JSON null for a bare or
   *  acknowledged DISPATCHED (no classifying event). OPTIONAL = absent on a pre-v1.1.4 hub.
   *  Typed and validated by FE-114; not rendered yet (no §7 row asks for it). */
  settledAt?: string | null;
  /** v1.1.4 ADDITIVE (EXPLAIN-114a; 10th): the `state_confirmed` instant, or JSON null when
   *  there is none — `settled: true` beside `confirmedAt: null` is LAWFUL (a FAILED or
   *  UNCONFIRMED action settles without a confirmation). A value renders EXPLAIN-9's sentence
   *  (`explain.action.detail.confirmedAt`); null / absent keep the mode help. */
  confirmedAt?: string | null;
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
  /** `parentRunId` is ALWAYS null in V1 — RunExplanation.java:213–:219 (the events carry only
   *  the flattened depth). A null is NOT "root": depth > 0 with a null parent renders the honest
   *  "started by another run — which one isn't recorded" line (HERO-0 F4, FE-NULL-1). */
  cascade: { parentRunId: string | null; depth: number };
  /** v1.1.4 ADDITIVE (EXPLAIN-114a; 9th, after cascade): the run's definition hash —
   *  `automation_triggered.definitionHash`, a SHA-256 hex string — or JSON null when the log
   *  carries none. Equals the non-firing / automations `definitionKey` for the same definition
   *  (DP-5). OPTIONAL = absent on a pre-v1.1.4 hub. Typed and validated; not rendered. */
  definitionKey?: string | null;
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
  /** v1.1.4 ADDITIVE (EXPLAIN-114a; 11th, after triggerRef): the LATEST `automation_disabled`
   *  instant for this automation as `Instant.toString()`, or JSON null — no marker on the log,
   *  or any non-DISABLED verdict. A value renders EXPLAIN-8's body (`whyNot.body.disabled.at`);
   *  null / absent keep `whyNot.body.disabled`. OPTIONAL = absent on a pre-v1.1.4 hub. */
  disabledAt?: string | null;
  /** v1.1.4 ADDITIVE (12th): the marker's reason ("repeated_failure"), else "configuration" on
   *  the DISABLED verdict (DP-6); JSON null on any non-DISABLED verdict. Shown as recorded. */
  disabledReason?: string | null;
  /** v1.1.4 ADDITIVE (13th): `DefinitionHashes` over the registry definition — never null in
   *  production (the registry answered); null only for a fixture. Typed and validated; not rendered. */
  definitionKey?: string | null;
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
  /** v1.1.4 ADDITIVE (EXPLAIN-114a; 6th, after lastRunId): `DefinitionHashes` over each registry
   *  definition, no store read (DP-5) — never null in production; null only for a fixture.
   *  OPTIONAL = absent on a pre-v1.1.4 hub. Typed and validated; not rendered. */
  definitionKey?: string | null;
}

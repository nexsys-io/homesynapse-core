/*
 * HomeSynapse — Mock fixtures (to the FROZEN contract shapes).
 * ---------------------------------------------------------------------------
 * A small, coherent home that demonstrates the differentiator end-to-end:
 *   - the happy path: motion -> light, the action CONFIRMED ("it actually did it");
 *   - the honest path: a run whose action is UNCONFIRMED (sent, no confirmation);
 *   - "why didn't it fire?": NEVER_TRIGGERED + a DISABLED automation;
 *   - an UNKNOWN-origin event (never a silent blank).
 * Every fixture conforms to src/lib/api/shapes.ts (contract.test.ts enforces it).
 */
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
  ResponseMeta,
  RunSummary,
} from '../contract';
import { BRAND } from '../../i18n';

const T0 = Date.now();
const iso = (minAgo: number) => new Date(T0 - minAgo * 60_000).toISOString();
/** An instant `plusMs` after `iso(minAgo)` — a v1.1.4 settledAt / confirmedAt beside its trigger's matchedAt. */
const isoPlus = (minAgo: number, plusMs: number) => new Date(T0 - minAgo * 60_000 + plusMs).toISOString();
/* FE-115 D2 — the default mock is a v1.1.5 hub: every v1.1.4 / v1.1.5 key PRESENT on the four hero reads, a value
 * where the story has one and JSON null elsewhere (never absent; ≥ 1 null per key — H8). The definition keys are
 * mock values in the DefinitionHashes SHAPE (64 hex), one per automation and EQUAL across the automations /
 * non-firing / causal-chain reads for the same definition (DP-5) — never a hash of anything real. */
export const DEFINITION_KEY_EVENING_HALLWAY = '3f1c9a0e7b2d4c6581a3e5f7092b4d6c8e0f1a2b3c4d5e6f708192a3b4c5d6e7';
export const DEFINITION_KEY_FRONTDOOR_WELCOME = 'a7e2c4d19b0f38657d2e4c6a8f0b1d3e5c7a9f1b2d4e6c8a0b2d4f6e8a1c3e5b';
export const DEFINITION_KEY_BEDROOM_NIGHTLIGHT = 'c94b1e6d2a7f0538e1c3b5d7f9a2c4e6081b3d5f7a9c1e3b5d7f9a1c3e5b7d9f';
let vp = 48217;
const nextVp = () => ++vp;

export const meta = (): ResponseMeta => ({ viewPosition: nextVp(), timestamp: new Date().toISOString() });

/* ---- Entities (A1/A2/A3) ----
 * v1.1.3 (FE-113 / CG-2, CG-3): every row carries BOTH additive keys, as a v1.1.3 hub
 * serves them — PRESENT, JSON null when unknown, never absent. And NOT always
 * populated (the H8 false-type law): the front-door contact has no device on
 * record (`deviceId: null` — the LIVE registry holds none for it), the bedroom
 * motion has never reported (`lastReported: null`). `lastReported` here mirrors
 * the same entity's A3 state below (one home, one clock); the device ids are
 * ULID strings (LTD-04) — the token that correlates a row with `device_adopted`. */
export const entities: EntitySummary[] = [
  { entityId: 'ent_hallway_motion', availability: 'AVAILABLE', stale: false, deviceId: '01M0H4A2Q8Z3N5R7T9V1X3B5D7', lastReported: iso(0.5) },
  { entityId: 'ent_hallway_light', availability: 'AVAILABLE', stale: false, deviceId: '01M0H4A2Q8Z3N5R7T9V1X3B5D9', lastReported: iso(1) },
  { entityId: 'ent_livingroom_lamp', availability: 'AVAILABLE', stale: false, deviceId: '01M0H4A2Q8Z3N5R7T9V1X3B5E1', lastReported: iso(120) },
  { entityId: 'ent_frontdoor_contact', availability: 'AVAILABLE', stale: false, deviceId: null, lastReported: iso(240) },
  { entityId: 'ent_kitchen_light', availability: 'AVAILABLE', stale: true, deviceId: '01M0H4A2Q8Z3N5R7T9V1X3B5E3', lastReported: iso(190) },
  { entityId: 'ent_bedroom_motion', availability: 'UNAVAILABLE', stale: false, deviceId: null, lastReported: null },
];

const entityLabels: Record<string, string> = {
  ent_hallway_motion: 'Hallway Motion',
  ent_hallway_light: 'Hallway Light',
  ent_livingroom_lamp: 'Living Room Lamp',
  ent_frontdoor_contact: 'Front Door',
  ent_kitchen_light: 'Kitchen Light',
  ent_bedroom_motion: 'Bedroom Motion',
};
export const labelFor = (id: string) => entityLabels[id] ?? id;

export const entityDetail: Record<string, EntityDetail> = {
  ent_hallway_light: {
    entityId: 'ent_hallway_light',
    availability: 'AVAILABLE',
    attributes: { power: { t: 'BOOL', v: true }, brightness: { t: 'PERCENT', v: 82 } },
  },
  ent_hallway_motion: {
    entityId: 'ent_hallway_motion',
    availability: 'AVAILABLE',
    attributes: { motion: { t: 'BOOL', v: false }, battery: { t: 'PERCENT', v: 91 } },
  },
};

export const entityState: Record<string, EntityState> = {
  ent_hallway_light: {
    entityId: 'ent_hallway_light',
    availability: 'AVAILABLE',
    attributes: {
      power: { t: 'BOOL', v: true },
      brightness: { t: 'PERCENT', v: 82 },
      color_temp: { t: 'KELVIN', v: 2700 },
    },
    stateVersion: 14,
    lastChanged: iso(3),
    lastUpdated: iso(3),
    lastReported: iso(1),
    stale: false,
    staleAfter: null,
  },
  ent_hallway_motion: {
    entityId: 'ent_hallway_motion',
    availability: 'AVAILABLE',
    attributes: { motion: { t: 'BOOL', v: false }, battery: { t: 'PERCENT', v: 91 }, lux: { t: 'NUMBER', v: 6 } },
    stateVersion: 220,
    lastChanged: iso(3),
    lastUpdated: iso(3),
    lastReported: iso(0.5),
    stale: false,
    staleAfter: null,
  },
  ent_kitchen_light: {
    entityId: 'ent_kitchen_light',
    availability: 'AVAILABLE',
    attributes: { power: { t: 'BOOL', v: false }, brightness: { t: 'PERCENT', v: 0 } },
    stateVersion: 5,
    lastChanged: iso(190),
    lastUpdated: iso(190),
    lastReported: iso(190),
    stale: true,
    staleAfter: iso(120),
  },
};

/* ---- Health (A4 / A5 / B2) ---- */
/* Wire-faithful since M7.5c-a (v1.1.1): live Core always emits the ruled additive
   extras (A4 entityCount/ready; A5 subscribers[]), so the mock carries them too —
   live integration must meet no shape the UI hasn't already faced. */
export const projection: ProjectionStatus = {
  mode: 'LIVE',
  viewPosition: vp,
  lagEvents: 0,
  projectionVersion: 5,
  entityCount: entities.length,
  ready: true,
};
export const dlq: DlqStatus = {
  depth: 0,
  parkedSubscribers: [],
  subscribers: [
    { subscriberId: 'state_projection', mode: 'LIVE', dlqDepth: 0, crashCount: 0, oldestParkedAt: null },
    { subscriberId: 'automation_engine', mode: 'LIVE', dlqDepth: 0, crashCount: 0, oldestParkedAt: null },
    { subscriberId: 'pending_command_ledger', mode: 'LIVE', dlqDepth: 0, crashCount: 0, oldestParkedAt: null },
  ],
};
export const health: ConsolidatedHealth = {
  phase: 'RUNNING',
  projection: { mode: 'LIVE', viewPosition: vp, lagEvents: 0 },
  dlq: { depth: 0, parkedSubscribers: [] },
  integrations: [
    { id: 'zigbee', health: 'HEALTHY' },
    { id: 'persistence', health: 'HEALTHY' },
    { id: 'automation_engine', health: 'HEALTHY' },
  ],
};

/* ---- Automations (B3 supporting) ----
 * v1.1.3 (FE-113 / CG-1): every component carries `ref` — `{type: "entity", id}` when
 * it addresses exactly ONE entity by identity, JSON null otherwise (the sunset
 * conditions name no entity). ONE dangling ref by law: the disabled night light's
 * lamp is not in this registry (the R-4 §10-J class — a rule pointing at an entity
 * this hub does not hold; the ULID is the field exhibit's target, verbatim) so the
 * LOUD render is exercised on the default scenario, not only on `dangling-ref`. */
export const DANGLING_LAMP_ULID = '01KX1PB9AAB4VB3E10BD477TVX';
export const automations: AutomationSummary[] = [
  {
    automationId: 'auto_evening_hallway',
    name: 'Evening Hallway Light',
    enabled: true,
    components: [
      { type: 'trigger', summary: 'When Hallway Motion detects motion', ref: { type: 'entity', id: 'ent_hallway_motion' } },
      { type: 'condition', summary: 'Only after sunset', ref: null },
      { type: 'action', summary: 'Turn on Hallway Light', ref: { type: 'entity', id: 'ent_hallway_light' } },
    ],
    lastRunId: 'run_eh_001',
    definitionKey: DEFINITION_KEY_EVENING_HALLWAY,
  },
  {
    automationId: 'auto_frontdoor_welcome',
    name: 'Front Door Welcome',
    enabled: true,
    components: [
      { type: 'trigger', summary: 'When Front Door opens', ref: { type: 'entity', id: 'ent_frontdoor_contact' } },
      { type: 'condition', summary: 'Only after sunset', ref: null },
      { type: 'action', summary: 'Turn on Living Room Lamp', ref: { type: 'entity', id: 'ent_livingroom_lamp' } },
    ],
    lastRunId: 'run_fd_001',
    definitionKey: DEFINITION_KEY_FRONTDOOR_WELCOME,
  },
  {
    automationId: 'auto_bedroom_nightlight',
    name: 'Bedroom Night Light',
    enabled: false,
    components: [
      { type: 'trigger', summary: 'When Bedroom Motion detects motion', ref: { type: 'entity', id: 'ent_bedroom_motion' } },
      { type: 'action', summary: 'Dim Bedroom Lamp to 10%', ref: { type: 'entity', id: DANGLING_LAMP_ULID } },
    ],
    lastRunId: null,
    definitionKey: DEFINITION_KEY_BEDROOM_NIGHTLIGHT,
  },
];

/* ---- Runs (B3) ---- */
export const runs: RunSummary[] = [
  {
    runId: 'run_eh_001',
    automationId: 'auto_evening_hallway',
    automationName: 'Evening Hallway Light',
    triggeredAt: iso(3),
    status: 'COMPLETED',
    terminalReason: null,
  },
  {
    runId: 'run_eh_002',
    automationId: 'auto_evening_hallway',
    automationName: 'Evening Hallway Light',
    triggeredAt: iso(46),
    status: 'COMPLETED',
    terminalReason: 'Action sent; device did not confirm within 5s',
  },
  {
    runId: 'run_eh_003',
    automationId: 'auto_evening_hallway',
    automationName: 'Evening Hallway Light',
    triggeredAt: iso(610),
    status: 'SKIPPED',
    terminalReason: 'Condition not met: it was before sunset',
  },
  {
    runId: 'run_fd_001',
    automationId: 'auto_frontdoor_welcome',
    automationName: 'Front Door Welcome',
    triggeredAt: iso(82),
    status: 'COMPLETED',
    terminalReason: null,
  },
];

/* ---- Causal chains (B3 — the hero) ----
 * [MOCK — pending the SKIP-VIS DEPLOY] The `resultOutcome`/`settled` keys below
 * are the ruled v1.1.2 additive shape (Nick ruling 1; SKIP-VIS DP-1/DP-4 GO,
 * LANDED on main 2026-07-26). They are mocked to the ruled shape per law (c)
 * (the emitter leads, the consumer follows) because the DEPLOYED read surface
 * predates the landing until the deploy evening completes — re-verify against
 * live payloads at the first post-deploy run.
 * FE-NULL-1 (2026-09-10, HERO-0 F2/F4): the v1.1 truth the mock had hidden (H8 — an
 * always-populated mock manufactures a false type). The four REQUIRED-NULLABLE arms are
 * carried here, each once, exactly as the emitter serves them at core 39c8dd3:
 *   - run_eh_003 (SKIPPED, the OLDEST run): the action carries `command: null` and
 *     `targetRef: null` (StandardExplanationService:771/:776 — no command was ever issued;
 *     `resultOutcome: null`, `settled: true`), and `trigger.subjectRef: null` (:644–:649 —
 *     its triggering event lies outside the run's correlation);
 *   - run_fd_001: one observedState entry with `value: null` (RunExplanation:137 — the lamp
 *     had no brightness reading at evaluation) and `cascade: { parentRunId: null, depth: 1 }`
 *     (RunExplanation:213–:219 — V1 never carries a parent id; depth > 0 is NOT "root").
 *   run_eh_001 / run_eh_002 are untouched (the happy path + the honest-unconfirmed path).
 * HERO-1b B7 (2026-09-12): the CONFIRMED actions carry `resultOutcome: null` — the live truth
 * (F-R4b-H: command_result is published only on failure); 'acknowledged' beside CONFIRMED was
 * the docketed H8 false value. The hero-states scenario lives in scenarios.ts.
 * FE-115 D2 (2026-09-19): the v1.1.4 / v1.1.5 keys PRESENT, in the wire order —
 *   - actions[]: `settledAt` (the classifying instant) · `confirmedAt` (the state_confirmed instant): the
 *     CONFIRMED actions carry both; UNCONFIRMED carries settledAt (the timeout) beside confirmedAt null (the
 *     lawful pair); the SKIPPED command-less action carries settledAt (its completion) beside null;
 *   - conditions[].definition: the structured rule ("time is after sunset" is the sun's elevation below 0 —
 *     a NumericCondition on sys_sun) on the three current-instance runs, an AndCondition on run_fd_001, and
 *     JSON null on run_eh_003 (the OLDEST run: its stamped hash is not on the log, so the projection vouches
 *     for nothing — `definitionKey` null too);
 *   - data.definitionKey: the automation's key (equal to the automations / non-firing reads' — DP-5) or null. */
export const causalChains: Record<string, CausalChain> = {
  // The happy path: motion -> light, CONFIRMED.
  run_eh_001: {
    runId: 'run_eh_001',
    automationId: 'auto_evening_hallway',
    automationName: 'Evening Hallway Light',
    trigger: {
      type: 'state_changed',
      subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' },
      matchedAt: iso(3),
      firingValue: 'motion = detected',
    },
    conditions: [
      {
        expression: 'time is after sunset',
        evaluated: true,
        result: true,
        observedState: [{ entityId: 'sys_sun', attribute: 'elevation', value: '-6.2°' }],
        definition: { type: 'NumericCondition', selector: 'sys_sun', attribute: 'elevation', value: null, above: null, below: 0, after: null, before: null, children: [] },
      },
    ],
    actions: [
      {
        type: 'device_command',
        targetRef: { type: 'ENTITY', id: 'ent_hallway_light' },
        command: 'turn_on',
        params: { brightness: 82 },
        outcome: 'CONFIRMED',
        reason: null,
        resultOutcome: null, // HERO-1b B7: confirmed by the device's own report — no verdict row (F-R4b-H; the 'acknowledged' value was the H8 false value)
        settled: true,
        settledAt: isoPlus(3, 380),
        confirmedAt: isoPlus(3, 380),
      },
    ],
    outcome: { status: 'COMPLETED', reason: null, durationMs: 412, actionCount: 1, commandCount: 1 },
    cascade: { parentRunId: null, depth: 0 },
    definitionKey: DEFINITION_KEY_EVENING_HALLWAY,
  },
  // The honest path: sent, but the device never confirmed.
  run_eh_002: {
    runId: 'run_eh_002',
    automationId: 'auto_evening_hallway',
    automationName: 'Evening Hallway Light',
    trigger: {
      type: 'state_changed',
      subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' },
      matchedAt: iso(46),
      firingValue: 'motion = detected',
    },
    conditions: [
      {
        expression: 'time is after sunset',
        evaluated: true,
        result: true,
        observedState: [{ entityId: 'sys_sun', attribute: 'elevation', value: '-9.1°' }],
        definition: { type: 'NumericCondition', selector: 'sys_sun', attribute: 'elevation', value: null, above: null, below: 0, after: null, before: null, children: [] },
      },
    ],
    actions: [
      {
        type: 'device_command',
        targetRef: { type: 'ENTITY', id: 'ent_hallway_light' },
        command: 'turn_on',
        params: { brightness: 82 },
        outcome: 'UNCONFIRMED',
        reason: 'No state_confirmed within 5s timeout',
        resultOutcome: null,
        settled: true,
        settledAt: isoPlus(46, 5021), // the timeout's instant
        confirmedAt: null, // settled: true beside confirmedAt: null — the lawful pair
      },
    ],
    outcome: {
      status: 'COMPLETED',
      reason: 'Action sent; device did not confirm within 5s',
      durationMs: 5021,
      actionCount: 1,
      commandCount: 1,
    },
    cascade: { parentRunId: null, depth: 0 },
    definitionKey: DEFINITION_KEY_EVENING_HALLWAY,
  },
  // Skipped: condition false (it was daytime). FE-NULL-1: the triggering event is outside
  // this run's correlation (subjectRef null) and the skipped action never issued a command.
  run_eh_003: {
    runId: 'run_eh_003',
    automationId: 'auto_evening_hallway',
    automationName: 'Evening Hallway Light',
    trigger: {
      type: 'state_changed',
      subjectRef: null,
      matchedAt: iso(610),
      firingValue: 'motion = detected',
    },
    conditions: [
      {
        expression: 'time is after sunset',
        evaluated: true,
        result: false,
        observedState: [{ entityId: 'sys_sun', attribute: 'elevation', value: '+24.7°' }],
        definition: null, // FE-115: the projection vouches for nothing on this prior-instance run (its stamped hash is not on the log)
      },
    ],
    actions: [
      {
        type: 'device_command',
        targetRef: null,
        command: null,
        params: {},
        outcome: 'SKIPPED',
        reason: 'Condition not met',
        resultOutcome: null,
        settled: true,
        settledAt: isoPlus(610, 38), // a command-less action settles at its completion envelope
        confirmedAt: null,
      },
    ],
    outcome: { status: 'SKIPPED', reason: 'Condition not met: before sunset', durationMs: 38, actionCount: 1, commandCount: 0 },
    cascade: { parentRunId: null, depth: 0 },
    definitionKey: null, // the log carries no definition hash for this run
  },
  run_fd_001: {
    runId: 'run_fd_001',
    automationId: 'auto_frontdoor_welcome',
    automationName: 'Front Door Welcome',
    trigger: {
      type: 'state_changed',
      subjectRef: { type: 'ENTITY', id: 'ent_frontdoor_contact' },
      matchedAt: iso(82),
      firingValue: 'contact = open',
    },
    conditions: [
      {
        expression: 'time is after sunset',
        evaluated: true,
        result: true,
        observedState: [
          { entityId: 'sys_sun', attribute: 'elevation', value: '-12.0°' },
          { entityId: 'ent_livingroom_lamp', attribute: 'brightness', value: null },
        ],
        definition: {
          type: 'AndCondition',
          selector: null,
          attribute: null,
          value: null,
          above: null,
          below: null,
          after: null,
          before: null,
          children: [
            { type: 'NumericCondition', selector: 'sys_sun', attribute: 'elevation', value: null, above: null, below: 0, after: null, before: null, children: [] },
            { type: 'NumericCondition', selector: 'ent_livingroom_lamp', attribute: 'brightness', value: null, above: null, below: 20, after: null, before: null, children: [] },
          ],
        },
      },
    ],
    actions: [
      {
        type: 'device_command',
        targetRef: { type: 'ENTITY', id: 'ent_livingroom_lamp' },
        command: 'turn_on',
        params: { brightness: 60 },
        outcome: 'CONFIRMED',
        reason: null,
        resultOutcome: null, // HERO-1b B7: confirmed by the device's own report — no verdict row (F-R4b-H; the 'acknowledged' value was the H8 false value)
        settled: true,
        settledAt: isoPlus(82, 360),
        confirmedAt: isoPlus(82, 360),
      },
    ],
    outcome: { status: 'COMPLETED', reason: null, durationMs: 389, actionCount: 1, commandCount: 1 },
    cascade: { parentRunId: null, depth: 1 },
    definitionKey: DEFINITION_KEY_FRONTDOOR_WELCOME,
  },
};

/* ---- Non-firing explanations (B3 — the co-equal hero half) ----
 * [MOCK — pending the SKIP-VIS DEPLOY] `noCommandsIssued` is the ruled v1.1.2
 * additive marker (DP-2): null on every non-silent-skip construction (never
 * false — the additive-nullable idiom).
 * v1.1.3 (FE-113 / CG-1): `triggerRef` is PRESENT on every entry, appended after
 * noCommandsIssued — an object (`{type: "entity", id}`, lowercase, the causal
 * chain's literal) on the CONDITION_NOT_MET and DISABLED entries, and JSON null on
 * the NEVER_TRIGGERED one (mirroring the live shape's honesty: the mock never
 * always-populates a nullable key — H8). */
export const nonFiring: Record<string, NonFiringExplanation> = {
  auto_evening_hallway: {
    automationId: 'auto_evening_hallway',
    automationName: 'Evening Hallway Light',
    enabled: true,
    verdict: 'NEVER_TRIGGERED',
    lastRelevantRunId: null,
    explanation: 'Nothing set it off. Hallway Motion has not detected motion in the last hour.',
    triggerSummary: 'This runs when Hallway Motion detects motion, after sunset.',
    // OBSERVED LIVE SHAPE (2026-08-16, G1 rehearsal §4.5 / DX-16): the wire
    // serves the WHOLE object null for NEVER_TRIGGERED — the mock carries the
    // real tri-state so a fixture-green build cannot hide the null again.
    lastEvaluation: null,
    noCommandsIssued: null,
    triggerRef: null,
    // v1.1.4 (FE-115 D2): a non-DISABLED verdict — both null; the key never null (the registry answered).
    disabledAt: null,
    disabledReason: null,
    definitionKey: DEFINITION_KEY_EVENING_HALLWAY,
  },
  auto_frontdoor_welcome: {
    automationId: 'auto_frontdoor_welcome',
    automationName: 'Front Door Welcome',
    enabled: true,
    verdict: 'CONDITION_NOT_MET',
    lastRelevantRunId: 'run_fd_002',
    explanation: 'It did not run because it was before sunset when the front door opened.',
    triggerSummary: 'This runs when the Front Door opens, after sunset.',
    lastEvaluation: { at: iso(240), conditionsResult: 'after sunset = false' },
    noCommandsIssued: null,
    triggerRef: { type: 'entity', id: 'ent_frontdoor_contact' },
    disabledAt: null,
    disabledReason: null,
    definitionKey: DEFINITION_KEY_FRONTDOOR_WELCOME,
  },
  auto_bedroom_nightlight: {
    automationId: 'auto_bedroom_nightlight',
    automationName: 'Bedroom Night Light',
    enabled: false,
    verdict: 'DISABLED',
    lastRelevantRunId: null,
    explanation: 'This automation is turned off, so it cannot run.',
    triggerSummary: 'This would run when Bedroom Motion detects motion.',
    lastEvaluation: { at: null, conditionsResult: null },
    noCommandsIssued: null,
    triggerRef: { type: 'entity', id: 'ent_bedroom_motion' },
    // v1.1.4 (FE-115 D2): the DISABLED verdict with the auto-disable marker on the log — its instant and reason
    // (EXPLAIN-8 renders `whyNot.body.disabled.at`: "Turned off 3 hr ago — repeated_failure. …").
    disabledAt: iso(180),
    disabledReason: 'repeated_failure',
    definitionKey: DEFINITION_KEY_BEDROOM_NIGHTLIGHT,
  },
};

/* ---- Event feed (B1 — never a silent blank) ---- */
export const events: EventSummary[] = [
  {
    eventId: 'evt_0009',
    type: 'state_changed',
    category: 'STATE',
    occurredAt: iso(1),
    viewPosition: nextVp(),
    subjectRef: { type: 'ENTITY', id: 'ent_hallway_light' },
    correlationId: 'cor_eh_001',
    causationId: 'evt_0008',
    origin: 'AUTOMATION',
    summary: 'Hallway Light turned on',
  },
  {
    eventId: 'evt_0008',
    type: 'motion_detected',
    category: 'STATE',
    occurredAt: iso(3),
    viewPosition: nextVp(),
    subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' },
    correlationId: 'cor_eh_001',
    causationId: null,
    origin: 'DEVICE',
    summary: 'Hallway Motion detected motion',
  },
  {
    eventId: 'evt_0007',
    type: 'command_issued',
    category: 'COMMAND',
    occurredAt: iso(46),
    viewPosition: nextVp(),
    subjectRef: { type: 'ENTITY', id: 'ent_hallway_light' },
    correlationId: 'cor_eh_002',
    causationId: 'evt_0006',
    origin: 'AUTOMATION',
    summary: 'Sent turn-on to Hallway Light (unconfirmed)',
  },
  {
    eventId: 'evt_0005',
    type: 'state_changed',
    category: 'STATE',
    occurredAt: iso(120),
    viewPosition: nextVp(),
    subjectRef: { type: 'ENTITY', id: 'ent_livingroom_lamp' },
    correlationId: 'cor_user_01',
    causationId: null,
    origin: 'USER',
    summary: 'Living Room Lamp turned off from the app',
  },
  {
    eventId: 'evt_0004',
    type: 'state_changed',
    category: 'STATE',
    occurredAt: iso(180),
    viewPosition: nextVp(),
    subjectRef: { type: 'ENTITY', id: 'ent_kitchen_light' },
    correlationId: 'cor_ext_01',
    causationId: null,
    origin: 'EXTERNAL',
    // Name-light: even mock strings that mimic server copy go through the token.
    summary: `Kitchen Light changed outside ${BRAND.productName}`,
  },
  {
    eventId: 'evt_0003',
    type: 'state_changed',
    category: 'STATE',
    occurredAt: iso(205),
    viewPosition: nextVp(),
    subjectRef: { type: 'ENTITY', id: 'ent_kitchen_light' },
    correlationId: 'cor_unk_01',
    causationId: null,
    origin: 'UNKNOWN',
    summary: 'Kitchen Light reported a brightness change',
  },
];

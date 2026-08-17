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
let vp = 48217;
const nextVp = () => ++vp;

export const meta = (): ResponseMeta => ({ viewPosition: nextVp(), timestamp: new Date().toISOString() });

/* ---- Entities (A1/A2/A3) ---- */
export const entities: EntitySummary[] = [
  { entityId: 'ent_hallway_motion', availability: 'AVAILABLE', stale: false },
  { entityId: 'ent_hallway_light', availability: 'AVAILABLE', stale: false },
  { entityId: 'ent_livingroom_lamp', availability: 'AVAILABLE', stale: false },
  { entityId: 'ent_frontdoor_contact', availability: 'AVAILABLE', stale: false },
  { entityId: 'ent_kitchen_light', availability: 'AVAILABLE', stale: true },
  { entityId: 'ent_bedroom_motion', availability: 'UNAVAILABLE', stale: false },
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

/* ---- Automations (B3 supporting) ---- */
export const automations: AutomationSummary[] = [
  {
    automationId: 'auto_evening_hallway',
    name: 'Evening Hallway Light',
    enabled: true,
    components: [
      { type: 'trigger', summary: 'When Hallway Motion detects motion' },
      { type: 'condition', summary: 'Only after sunset' },
      { type: 'action', summary: 'Turn on Hallway Light' },
    ],
    lastRunId: 'run_eh_001',
  },
  {
    automationId: 'auto_frontdoor_welcome',
    name: 'Front Door Welcome',
    enabled: true,
    components: [
      { type: 'trigger', summary: 'When Front Door opens' },
      { type: 'condition', summary: 'Only after sunset' },
      { type: 'action', summary: 'Turn on Living Room Lamp' },
    ],
    lastRunId: 'run_fd_001',
  },
  {
    automationId: 'auto_bedroom_nightlight',
    name: 'Bedroom Night Light',
    enabled: false,
    components: [
      { type: 'trigger', summary: 'When Bedroom Motion detects motion' },
      { type: 'action', summary: 'Dim Bedroom Lamp to 10%' },
    ],
    lastRunId: null,
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
 * live payloads at the first post-deploy run. */
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
        resultOutcome: 'acknowledged',
        settled: true,
      },
    ],
    outcome: { status: 'COMPLETED', reason: null, durationMs: 412, actionCount: 1, commandCount: 1 },
    cascade: { parentRunId: null, depth: 0 },
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
  },
  // Skipped: condition false (it was daytime).
  run_eh_003: {
    runId: 'run_eh_003',
    automationId: 'auto_evening_hallway',
    automationName: 'Evening Hallway Light',
    trigger: {
      type: 'state_changed',
      subjectRef: { type: 'ENTITY', id: 'ent_hallway_motion' },
      matchedAt: iso(610),
      firingValue: 'motion = detected',
    },
    conditions: [
      {
        expression: 'time is after sunset',
        evaluated: true,
        result: false,
        observedState: [{ entityId: 'sys_sun', attribute: 'elevation', value: '+24.7°' }],
      },
    ],
    actions: [
      {
        type: 'device_command',
        targetRef: { type: 'ENTITY', id: 'ent_hallway_light' },
        command: 'turn_on',
        params: {},
        outcome: 'SKIPPED',
        reason: 'Condition not met',
        resultOutcome: null,
        settled: true,
      },
    ],
    outcome: { status: 'SKIPPED', reason: 'Condition not met: before sunset', durationMs: 38, actionCount: 1, commandCount: 0 },
    cascade: { parentRunId: null, depth: 0 },
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
        observedState: [{ entityId: 'sys_sun', attribute: 'elevation', value: '-12.0°' }],
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
        resultOutcome: 'acknowledged',
        settled: true,
      },
    ],
    outcome: { status: 'COMPLETED', reason: null, durationMs: 389, actionCount: 1, commandCount: 1 },
    cascade: { parentRunId: null, depth: 0 },
  },
};

/* ---- Non-firing explanations (B3 — the co-equal hero half) ----
 * [MOCK — pending the SKIP-VIS DEPLOY] `noCommandsIssued` is the ruled v1.1.2
 * additive marker (DP-2): null on every non-silent-skip construction (never
 * false — the additive-nullable idiom). */
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

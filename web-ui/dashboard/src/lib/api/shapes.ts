/*
 * HomeSynapse — Runtime contract validators.
 * ---------------------------------------------------------------------------
 * A thin, dependency-free guard that the data we render matches the FROZEN
 * read-API contract. Two uses:
 *   1. Build/CI: contract.test.ts validates every mock fixture against these.
 *   2. Dev runtime (optional): validate real responses to catch Core drift early.
 * If a real endpoint ever fails a validator, that is a cross-lane event (raise to
 * the hub) — not something to paper over in the client.
 */
import {
  CONTRACT_VERSION,
  type Availability,
  type Origin,
  type RunStatus,
  type ActionOutcome,
  type NonFiringVerdict,
  type IntegrationHealth,
  type ProjectionMode,
} from './contract';

export { CONTRACT_VERSION };

/** Canonical endpoint set of the FROZEN read-API contract. Must stay complete. */
export const ENDPOINT_IDS = [
  'A1:entities',
  'A2:entity',
  'A3:entityState',
  'A4:projection',
  'A5:dlq',
  'B1:events',
  'B2:health',
  'B3:runs',
  'B3:causalChain',
  'B3:nonFiring',
  'B3:automations',
] as const;
export type EndpointId = (typeof ENDPOINT_IDS)[number];

const AVAILABILITY: Availability[] = ['AVAILABLE', 'UNAVAILABLE', 'UNKNOWN'];
const ORIGIN: Origin[] = ['AUTOMATION', 'DEVICE', 'USER', 'EXTERNAL', 'UNKNOWN'];
const RUN_STATUS: RunStatus[] = ['COMPLETED', 'FAILED', 'SKIPPED', 'CANCELLED', 'INTERRUPTED'];
const ACTION_OUTCOME: ActionOutcome[] = ['DISPATCHED', 'CONFIRMED', 'UNCONFIRMED', 'FAILED', 'SKIPPED'];
const VERDICT: NonFiringVerdict[] = ['CONDITION_NOT_MET', 'NEVER_TRIGGERED', 'ACTED_BUT_UNCONFIRMED', 'DISABLED'];
const INTEGRATION_HEALTH: IntegrationHealth[] = ['HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN'];
const PROJECTION_MODE: ProjectionMode[] = ['REPLAY', 'TRANSITION', 'LIVE'];

export class ContractError extends Error {}

function isObj(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}
function req(o: Record<string, unknown>, key: string, path: string): unknown {
  if (!(key in o)) throw new ContractError(`${path}: missing required field "${key}"`);
  return o[key];
}
function oneOf<T extends string>(v: unknown, domain: T[], path: string): asserts v is T {
  if (typeof v !== 'string' || !domain.includes(v as T)) {
    throw new ContractError(`${path}: "${String(v)}" not in {${domain.join(', ')}}`);
  }
}
function isStr(v: unknown, path: string): asserts v is string {
  if (typeof v !== 'string') throw new ContractError(`${path}: expected string, got ${typeof v}`);
}
/** String OR null — the observed live prior-instance nullability class
 *  (RunSummary/CausalChain automationName, CausalTrigger.type; see contract.ts).
 *  Absent is still a contract error; only an explicit null is tolerated. */
function strOrNull(v: unknown, path: string): void {
  if (v !== null && typeof v !== 'string') {
    throw new ContractError(`${path}: expected string or null, got ${typeof v}`);
  }
}
/** Optional field: absent is fine (additive-tolerant, freeze §A C8); present must be a string. */
function optStr(o: Record<string, unknown>, key: string, path: string): void {
  if (key in o && typeof o[key] !== 'string') {
    throw new ContractError(`${path}.${key}: optional field present but not a string`);
  }
}
function isNum(v: unknown, path: string): asserts v is number {
  if (typeof v !== 'number') throw new ContractError(`${path}: expected number, got ${typeof v}`);
}
function isBool(v: unknown, path: string): asserts v is boolean {
  if (typeof v !== 'boolean') throw new ContractError(`${path}: expected boolean, got ${typeof v}`);
}

function meta(v: unknown, path: string) {
  if (!isObj(v)) throw new ContractError(`${path}: meta must be object`);
  isNum(req(v, 'viewPosition', path), `${path}.viewPosition`);
  isStr(req(v, 'timestamp', path), `${path}.timestamp`);
}
function subjectRef(v: unknown, path: string) {
  if (!isObj(v)) throw new ContractError(`${path}: subjectRef must be object`);
  isStr(req(v, 'type', path), `${path}.type`);
  isStr(req(v, 'id', path), `${path}.id`);
}

type Validator = (body: unknown) => void;

/** Per-endpoint validators (validate the full response body, envelope included). */
export const validators: Record<EndpointId, Validator> = {
  'A1:entities': (b) => {
    if (!isObj(b)) throw new ContractError('A1: body must be object');
    const data = req(b, 'data', 'A1');
    if (!Array.isArray(data)) throw new ContractError('A1.data must be array');
    data.forEach((e, i) => {
      const p = `A1.data[${i}]`;
      if (!isObj(e)) throw new ContractError(`${p}: must be object`);
      isStr(req(e, 'entityId', p), `${p}.entityId`);
      optStr(e, 'name', p);
      oneOf(req(e, 'availability', p), AVAILABILITY, `${p}.availability`);
      isBool(req(e, 'stale', p), `${p}.stale`);
    });
    meta(req(b, 'meta', 'A1'), 'A1.meta');
  },
  'A2:entity': (b) => {
    if (!isObj(b)) throw new ContractError('A2: body must be object');
    const d = req(b, 'data', 'A2');
    if (!isObj(d)) throw new ContractError('A2.data must be object');
    isStr(req(d, 'entityId', 'A2.data'), 'A2.data.entityId');
    optStr(d, 'name', 'A2.data');
    oneOf(req(d, 'availability', 'A2.data'), AVAILABILITY, 'A2.data.availability');
    if (!isObj(req(d, 'attributes', 'A2.data'))) throw new ContractError('A2.data.attributes must be object');
    meta(req(b, 'meta', 'A2'), 'A2.meta');
  },
  'A3:entityState': (b) => {
    if (!isObj(b)) throw new ContractError('A3: body must be object');
    const d = req(b, 'data', 'A3');
    if (!isObj(d)) throw new ContractError('A3.data must be object');
    isStr(req(d, 'entityId', 'A3.data'), 'A3.data.entityId');
    optStr(d, 'name', 'A3.data');
    oneOf(req(d, 'availability', 'A3.data'), AVAILABILITY, 'A3.data.availability');
    const attrs = req(d, 'attributes', 'A3.data');
    if (!isObj(attrs)) throw new ContractError('A3.data.attributes must be object');
    for (const [k, tv] of Object.entries(attrs)) {
      if (!isObj(tv)) throw new ContractError(`A3.data.attributes.${k} must be {t,v}`);
      isStr(req(tv, 't', `A3.attributes.${k}`), `A3.attributes.${k}.t`);
      req(tv, 'v', `A3.attributes.${k}`);
    }
    isNum(req(d, 'stateVersion', 'A3.data'), 'A3.data.stateVersion');
    isBool(req(d, 'stale', 'A3.data'), 'A3.data.stale');
    meta(req(b, 'meta', 'A3'), 'A3.meta');
  },
  'A4:projection': (b) => {
    // Enveloped since M7.5c-a (v1.1.1 DRIFT-1). Frozen four required; the ruled
    // additive extras (entityCount, ready) type-checked when present.
    if (!isObj(b)) throw new ContractError('A4: body must be object');
    const d = req(b, 'data', 'A4');
    if (!isObj(d)) throw new ContractError('A4.data must be object');
    oneOf(req(d, 'mode', 'A4.data'), PROJECTION_MODE, 'A4.data.mode');
    isNum(req(d, 'viewPosition', 'A4.data'), 'A4.data.viewPosition');
    isNum(req(d, 'lagEvents', 'A4.data'), 'A4.data.lagEvents');
    isNum(req(d, 'projectionVersion', 'A4.data'), 'A4.data.projectionVersion');
    if ('entityCount' in d) isNum(d.entityCount, 'A4.data.entityCount');
    if ('ready' in d) isBool(d.ready, 'A4.data.ready');
    meta(req(b, 'meta', 'A4'), 'A4.meta');
  },
  'A5:dlq': (b) => {
    // Enveloped since M7.5c-a (v1.1.1 DRIFT-1). parkedSubscribers = subscriber
    // IDS (the ratified shape, consistent with B2); subscribers[] is the ruled
    // additive per-subscriber detail, type-checked when present.
    if (!isObj(b)) throw new ContractError('A5: body must be object');
    const d = req(b, 'data', 'A5');
    if (!isObj(d)) throw new ContractError('A5.data must be object');
    isNum(req(d, 'depth', 'A5.data'), 'A5.data.depth');
    const parked = req(d, 'parkedSubscribers', 'A5.data');
    if (!Array.isArray(parked)) throw new ContractError('A5.data.parkedSubscribers must be array');
    parked.forEach((s, i) => isStr(s, `A5.data.parkedSubscribers[${i}]`));
    if ('subscribers' in d) {
      if (!Array.isArray(d.subscribers)) throw new ContractError('A5.data.subscribers must be array');
      d.subscribers.forEach((s, i) => {
        const p = `A5.data.subscribers[${i}]`;
        if (!isObj(s)) throw new ContractError(`${p}: must be object`);
        isStr(req(s, 'subscriberId', p), `${p}.subscriberId`);
        isStr(req(s, 'mode', p), `${p}.mode`);
        isNum(req(s, 'dlqDepth', p), `${p}.dlqDepth`);
      });
    }
    meta(req(b, 'meta', 'A5'), 'A5.meta');
  },
  'B1:events': (b) => {
    if (!isObj(b)) throw new ContractError('B1: body must be object');
    const data = req(b, 'data', 'B1');
    if (!Array.isArray(data)) throw new ContractError('B1.data must be array');
    data.forEach((e, i) => {
      const p = `B1.data[${i}]`;
      if (!isObj(e)) throw new ContractError(`${p}: must be object`);
      isStr(req(e, 'eventId', p), `${p}.eventId`);
      isStr(req(e, 'type', p), `${p}.type`);
      isStr(req(e, 'occurredAt', p), `${p}.occurredAt`);
      isNum(req(e, 'viewPosition', p), `${p}.viewPosition`);
      subjectRef(req(e, 'subjectRef', p), `${p}.subjectRef`);
      oneOf(req(e, 'origin', p), ORIGIN, `${p}.origin`);
      isStr(req(e, 'summary', p), `${p}.summary`);
    });
    meta(req(b, 'meta', 'B1'), 'B1.meta');
  },
  'B2:health': (b) => {
    if (!isObj(b)) throw new ContractError('B2: body must be object');
    const d = req(b, 'data', 'B2');
    if (!isObj(d)) throw new ContractError('B2.data must be object');
    isStr(req(d, 'phase', 'B2.data'), 'B2.data.phase');
    const proj = req(d, 'projection', 'B2.data');
    if (!isObj(proj)) throw new ContractError('B2.data.projection must be object');
    oneOf(req(proj, 'mode', 'B2.projection'), PROJECTION_MODE, 'B2.projection.mode');
    const integrations = req(d, 'integrations', 'B2.data');
    if (!Array.isArray(integrations)) throw new ContractError('B2.data.integrations must be array');
    integrations.forEach((it, i) => {
      const p = `B2.integrations[${i}]`;
      if (!isObj(it)) throw new ContractError(`${p}: must be object`);
      isStr(req(it, 'id', p), `${p}.id`);
      oneOf(req(it, 'health', p), INTEGRATION_HEALTH, `${p}.health`);
    });
    meta(req(b, 'meta', 'B2'), 'B2.meta');
  },
  'B3:runs': (b) => {
    if (!isObj(b)) throw new ContractError('B3runs: body must be object');
    const data = req(b, 'data', 'B3runs');
    if (!Array.isArray(data)) throw new ContractError('B3runs.data must be array');
    data.forEach((r, i) => {
      const p = `B3runs.data[${i}]`;
      if (!isObj(r)) throw new ContractError(`${p}: must be object`);
      isStr(req(r, 'runId', p), `${p}.runId`);
      isStr(req(r, 'automationId', p), `${p}.automationId`);
      strOrNull(req(r, 'automationName', p), `${p}.automationName`); // observed live null (prior-instance runs)
      isStr(req(r, 'triggeredAt', p), `${p}.triggeredAt`);
      oneOf(req(r, 'status', p), RUN_STATUS, `${p}.status`);
    });
    meta(req(b, 'meta', 'B3runs'), 'B3runs.meta');
  },
  'B3:causalChain': (b) => {
    if (!isObj(b)) throw new ContractError('B3chain: body must be object');
    const d = req(b, 'data', 'B3chain');
    if (!isObj(d)) throw new ContractError('B3chain.data must be object');
    isStr(req(d, 'runId', 'B3chain.data'), 'B3chain.data.runId');
    strOrNull(req(d, 'automationName', 'B3chain.data'), 'B3chain.data.automationName'); // observed live null
    const trigger = req(d, 'trigger', 'B3chain.data');
    if (!isObj(trigger)) throw new ContractError('B3chain.trigger must be object');
    strOrNull(req(trigger, 'type', 'B3chain.trigger'), 'B3chain.trigger.type'); // observed live null
    strOrNull(req(trigger, 'firingValue', 'B3chain.trigger'), 'B3chain.trigger.firingValue'); // observed live null (all eras, 2026-07-27)
    subjectRef(req(trigger, 'subjectRef', 'B3chain.trigger'), 'B3chain.trigger.subjectRef');
    const conditions = req(d, 'conditions', 'B3chain.data');
    if (!Array.isArray(conditions)) throw new ContractError('B3chain.conditions must be array');
    conditions.forEach((c, i) => {
      const p = `B3chain.conditions[${i}]`;
      if (!isObj(c)) throw new ContractError(`${p}: must be object`);
      isStr(req(c, 'expression', p), `${p}.expression`);
      isBool(req(c, 'evaluated', p), `${p}.evaluated`);
      isBool(req(c, 'result', p), `${p}.result`);
      if (!Array.isArray(req(c, 'observedState', p))) throw new ContractError(`${p}.observedState must be array`);
    });
    const actions = req(d, 'actions', 'B3chain.data');
    if (!Array.isArray(actions)) throw new ContractError('B3chain.actions must be array');
    actions.forEach((a, i) => {
      const p = `B3chain.actions[${i}]`;
      if (!isObj(a)) throw new ContractError(`${p}: must be object`);
      isStr(req(a, 'command', p), `${p}.command`);
      oneOf(req(a, 'outcome', p), ACTION_OUTCOME, `${p}.outcome`);
      // v1.1.2 ADDITIVE keys (SKIP-VIS DP-1/DP-4 GO). Absence is lawful (a
      // pre-v1.1.2 payload — the deployed surface may predate the landing);
      // presence is validated: resultOutcome is string|null, settled is boolean.
      if ('resultOutcome' in a) strOrNull(a.resultOutcome, `${p}.resultOutcome`);
      if ('settled' in a) isBool(a.settled, `${p}.settled`);
    });
    if (!isObj(req(d, 'outcome', 'B3chain.data'))) throw new ContractError('B3chain.outcome must be object');
    meta(req(b, 'meta', 'B3chain'), 'B3chain.meta');
  },
  'B3:nonFiring': (b) => {
    if (!isObj(b)) throw new ContractError('B3nf: body must be object');
    const d = req(b, 'data', 'B3nf');
    if (!isObj(d)) throw new ContractError('B3nf.data must be object');
    isStr(req(d, 'automationId', 'B3nf.data'), 'B3nf.data.automationId');
    isStr(req(d, 'automationName', 'B3nf.data'), 'B3nf.data.automationName');
    isBool(req(d, 'enabled', 'B3nf.data'), 'B3nf.data.enabled');
    oneOf(req(d, 'verdict', 'B3nf.data'), VERDICT, 'B3nf.data.verdict');
    isStr(req(d, 'explanation', 'B3nf.data'), 'B3nf.data.explanation');
    isStr(req(d, 'triggerSummary', 'B3nf.data'), 'B3nf.data.triggerSummary');
    // v1.1 base field, OBSERVED OBJECT-NULL ON THE LIVE WIRE (2026-08-16, G1
    // rehearsal §4.5 / DX-16): the deployed server serves "lastEvaluation": null.
    // Object-OR-null; when object, `at`/`conditionsResult` are string|null.
    // This validator previously skipped the field entirely — the omission that
    // let the always-populated mock conceal the divergence (H8's origin).
    const le = req(d, 'lastEvaluation', 'B3nf.data');
    if (le !== null) {
      if (!isObj(le)) throw new ContractError('B3nf.data.lastEvaluation: expected object or null');
      strOrNull(req(le, 'at', 'B3nf.lastEvaluation'), 'B3nf.lastEvaluation.at');
      strOrNull(req(le, 'conditionsResult', 'B3nf.lastEvaluation'), 'B3nf.lastEvaluation.conditionsResult');
    }
    // v1.1.2 ADDITIVE (SKIP-VIS DP-2): noCommandsIssued serializes as true or
    // JSON null — NEVER false (absent ≠ false is the additive-nullable idiom;
    // the core constructs only Boolean.TRUE or null). Absence is lawful
    // (pre-v1.1.2 payload); a false on the wire is contract drift — fail it.
    if ('noCommandsIssued' in d && d.noCommandsIssued !== true && d.noCommandsIssued !== null) {
      throw new ContractError(
        `B3nf.data.noCommandsIssued: expected true or null (never false), got ${String(d.noCommandsIssued)}`,
      );
    }
    meta(req(b, 'meta', 'B3nf'), 'B3nf.meta');
  },
  'B3:automations': (b) => {
    if (!isObj(b)) throw new ContractError('B3auto: body must be object');
    const data = req(b, 'data', 'B3auto');
    if (!Array.isArray(data)) throw new ContractError('B3auto.data must be array');
    data.forEach((a, i) => {
      const p = `B3auto.data[${i}]`;
      if (!isObj(a)) throw new ContractError(`${p}: must be object`);
      isStr(req(a, 'automationId', p), `${p}.automationId`);
      isStr(req(a, 'name', p), `${p}.name`);
      isBool(req(a, 'enabled', p), `${p}.enabled`);
      if (!Array.isArray(req(a, 'components', p))) throw new ContractError(`${p}.components must be array`);
    });
    meta(req(b, 'meta', 'B3auto'), 'B3auto.meta');
  },
};

/** Validate a response body for an endpoint. Throws ContractError on mismatch. */
export function validateAgainstContract(endpoint: EndpointId, body: unknown): void {
  validators[endpoint](body);
}

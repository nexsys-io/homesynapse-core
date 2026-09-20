/*
 * Contract-coverage gate (dispatch §4). A cheap, dependency-free check that the
 * client still declares a validator for every endpoint of the FROZEN read-API
 * contract. The deep per-shape validation is the vitest contract.test.ts; this is
 * the fast structural tripwire (e.g. someone deletes an endpoint descriptor).
 */
import { readFileSync } from 'node:fs';

const SHAPES = 'src/lib/api/shapes.ts';
// v1.1.4 (HERO-0 §3 / `EXPLAIN: three`; landed core-side 2026-09-12 EXPLAIN-114a 5f918c7 and
// 2026-09-13 EXPLAIN-114b fed99e8; the freeze doc's amendment date is the 13th): SEVEN ADDITIVE
// keys across THREE reads (actions[].settledAt / confirmedAt, causal-chain definitionKey,
// nonFiring disabledAt / disabledReason / definitionKey, automations[].definitionKey) + the
// verdict value FIRED_CONFIRMED; v1.1.3 = four ADDITIVE keys (entities[].deviceId,
// entities[].lastReported, nonFiring triggerRef, automations components[].ref); v1.1.2 = three
// (actions[].resultOutcome, actions[].settled, nonFiring noCommandsIssued); the v1.1 base stays
// byte-stable. This pin, contract.test.ts's and v113-additive.test.ts's move together (FE-114 D0).
// v1.1.5 (Nick's `EXPLAIN: three`; landed core-side 2026-09-14 EXPLAIN-114c e56f555; the FE mirror is FE-115,
// 2026-09-19): ONE ADDITIVE key on ONE read — causal-chain conditions[].definition (object-or-null, recursive
// through children). The pin has FIVE homes now (contract.ts · contract.test.ts · v113-additive.test.ts ·
// v114-additive.test.ts · this file) — they move together (FE-115 D0).
const EXPECTED_VERSION = 'v1.1.5-2026-09-19';
// v1.1.1: the ratified problem-type URI prefix (Doc 09 §3.8 / ProblemType.TYPE_URI_PREFIX).
const EXPECTED_PROBLEM_PREFIX = 'https://homesynapse.local/problems/';
const REQUIRED = [
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
];

let src;
try {
  src = readFileSync(SHAPES, 'utf8');
} catch {
  console.error(`✗ Cannot read ${SHAPES}`);
  process.exit(1);
}

const missing = REQUIRED.filter((id) => !src.includes(`'${id}'`));
if (missing.length > 0) {
  console.error(`✗ Frozen contract drift: shapes.ts is missing validators for: ${missing.join(', ')}`);
  console.error('  Any contract change is a CROSS-LANE EVENT — raise it to the hub, do not diverge.');
  process.exit(1);
}

const versionFile = readFileSync('src/lib/api/contract.ts', 'utf8');
if (!versionFile.includes(EXPECTED_VERSION)) {
  console.error(`✗ CONTRACT_VERSION is not ${EXPECTED_VERSION} — confirm the freeze the client builds against.`);
  process.exit(1);
}
if (!versionFile.includes(EXPECTED_PROBLEM_PREFIX)) {
  console.error(`✗ PROBLEM_TYPE_URI_PREFIX missing or drifted — clients key on the slug suffix of ${EXPECTED_PROBLEM_PREFIX}<slug> (v1.1.1).`);
  process.exit(1);
}

console.log(`✓ Contract coverage complete: ${REQUIRED.length} endpoints, version ${EXPECTED_VERSION}.`);

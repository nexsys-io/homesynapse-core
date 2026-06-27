/*
 * Contract-coverage gate (dispatch §4). A cheap, dependency-free check that the
 * client still declares a validator for every endpoint of the FROZEN read-API
 * contract. The deep per-shape validation is the vitest contract.test.ts; this is
 * the fast structural tripwire (e.g. someone deletes an endpoint descriptor).
 */
import { readFileSync } from 'node:fs';

const SHAPES = 'src/lib/api/shapes.ts';
const EXPECTED_VERSION = 'v1.1-2026-06-21';
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

console.log(`✓ Contract coverage complete: ${REQUIRED.length} endpoints, version ${EXPECTED_VERSION}.`);

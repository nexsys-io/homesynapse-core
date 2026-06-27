/*
 * Bundle-budget gate (C13-01 / LTD-18). The total gzipped weight of the initial
 * dashboard bundle MUST stay under 100 KB. Exceeding it is a BUILD FAILURE, not a
 * warning — this is what keeps the dashboard shippable in a jlink image to a Pi.
 *
 * "Initial bundle" = index.html + all JS/CSS that are NOT lazy chunks. Lazy chunks
 * (e.g. the uPlot charting chunk, loaded on demand) are reported but excluded from
 * the hard budget, matching Doc 13 §3.1's allocation model.
 */
import { gzipSync } from 'node:zlib';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const DIST = 'dist';
const ASSETS = join(DIST, 'assets');
const BUDGET_KB = 100;

// Lazy/async chunk name fragments excluded from the *initial* budget.
const LAZY = ['uplot'];

function gzipKb(path) {
  return gzipSync(readFileSync(path), { level: 9 }).length / 1024;
}

function walk(dir) {
  let files = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) files = files.concat(walk(p));
    else files.push(p);
  }
  return files;
}

let files;
try {
  files = [join(DIST, 'index.html'), ...walk(ASSETS)];
} catch {
  console.error('✗ No build output found. Run `npm run build` first.');
  process.exit(1);
}

let initial = 0;
let lazy = 0;
const rows = [];
for (const f of files) {
  const kb = gzipKb(f);
  const isLazy = LAZY.some((frag) => f.includes(frag));
  if (isLazy) lazy += kb;
  else initial += kb;
  rows.push({ file: f.replace(DIST + '/', ''), kb, isLazy });
}

rows.sort((a, b) => b.kb - a.kb);
console.log('Gzipped sizes:');
for (const r of rows) {
  console.log(`  ${r.kb.toFixed(1).padStart(6)} KB  ${r.file}${r.isLazy ? '  (lazy, excluded)' : ''}`);
}
console.log('  ------');
console.log(`  ${initial.toFixed(1).padStart(6)} KB  initial bundle`);
if (lazy > 0) console.log(`  ${lazy.toFixed(1).padStart(6)} KB  lazy chunks (excluded from budget)`);
console.log(`  budget: ${BUDGET_KB} KB`);

if (initial > BUDGET_KB) {
  console.error(`\n✗ BUDGET EXCEEDED: initial bundle ${initial.toFixed(1)} KB > ${BUDGET_KB} KB.`);
  process.exit(1);
}
console.log(`\n✓ Within budget (${initial.toFixed(1)} KB / ${BUDGET_KB} KB, ${(BUDGET_KB - initial).toFixed(1)} KB headroom).`);

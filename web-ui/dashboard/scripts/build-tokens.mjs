#!/usr/bin/env node
/*
 * HomeSynapse — design-token generator (FE-0, D-FE-8).
 * -----------------------------------------------------------------------------
 * Reads the platform-neutral W3C Design Tokens source (src/styles/tokens/tokens.dtcg.json)
 * and GENERATES src/styles/tokens.css. tokens.css is a build artifact — never hand-edit it;
 * edit the JSON and re-run `npm run tokens`.
 *
 * Why a tiny in-repo generator (and not Style Dictionary as a dependency): the durable,
 * future-proofing asset D-FE-8 wants is the *neutral source file* — this JSON. It is valid
 * DTCG, so a Style Dictionary config (for native iOS/Android or B2B re-theme outputs) can
 * consume this exact file later; adopting it is a generator swap, not a re-author. Meanwhile
 * this script adds zero runtime/build dependencies — on-brand for a jlink-to-a-Pi product with
 * a 100 KB budget, and reliable in any environment.
 *
 * Output structure is theme-default aware:
 *   --theme-default=dark  (DEFAULT, the shipping V1 structure, FE-2):
 *       :root { core + dark }                                   ← dark is the recorded default + fallback
 *       @media (prefers-color-scheme: light){ :root:not([data-theme]){ light } }  ← OS-light, unset toggle
 *       :root[data-theme='light']{ light }                      ← explicit light
 *       :root[data-theme='dark'] { dark }                       ← explicit dark (re-asserts intent)
 *   --theme-default=light (the pre-FE-2 structure; used to prove FE-0 is a faithful refactor):
 *       :root { core + light }
 *       :root[data-theme='dark']{ dark }
 *
 * Flags:
 *   --theme-default=dark|light   choose output structure (default: dark)
 *   --out=<path>                 write to a path other than src/styles/tokens.css
 *   --check                      generate in memory and compare (normalized) to the committed
 *                                tokens.css; exit 1 on drift. The CI drift-guard.
 *   --stdout                     print to stdout instead of writing.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..');
const SRC = resolve(ROOT, 'src/styles/tokens/tokens.dtcg.json');
const DEFAULT_OUT = resolve(ROOT, 'src/styles/tokens.css');

const args = new Map(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.includes('=') ? a.split(/=(.*)/s) : [a, true];
    return [k.replace(/^--/, ''), v];
  }),
);
const themeDefault = args.get('theme-default') === 'light' ? 'light' : 'dark';

/* --- Flatten the DTCG tree into ordered token records keyed by dotted path. --- */
function flatten(node, path, out) {
  if (node && typeof node === 'object' && '$value' in node) {
    const varName = node.$extensions?.hs?.var;
    if (!varName) throw new Error(`Token ${path.join('.')} is missing $extensions.hs.var`);
    out.push({ path: path.join('.'), group: path[0], section: path[1], varName, value: String(node.$value) });
    return;
  }
  for (const key of Object.keys(node)) {
    if (key.startsWith('$')) continue;
    flatten(node[key], [...path, key], out);
  }
}

const dtcg = JSON.parse(readFileSync(SRC, 'utf8'));
const tokens = [];
for (const top of ['core', 'light', 'dark']) {
  if (dtcg[top]) flatten(dtcg[top], [top], tokens);
}
const byPath = new Map(tokens.map((t) => [t.path, t]));

/* --- Resolve {a.b.c} references to var(--hs-…), preserving the cascade. --- */
function resolveValue(value) {
  return value.replace(/\{([^}]+)\}/g, (_, ref) => {
    const target = byPath.get(ref.trim());
    if (!target) throw new Error(`Unknown token reference {${ref}}`);
    return `var(--${target.varName})`;
  });
}

/* Human labels for section-comment headers (keeps the generated CSS readable). */
const SECTION_LABEL = {
  neutral: 'Neutral ramp (architectural, slightly cool gray)',
  accent: 'The single cool accent (UniFi-class blue; calm, not hype)',
  textOnAccent: 'Foreground on accent',
  font: 'Typography — system stacks (local-first, no web fonts, C13-06)',
  textSize: 'Type scale',
  weight: 'Font weights',
  leading: 'Line heights',
  space: 'Space scale (4px base)',
  radius: 'Radius',
  borderWidth: 'Border width',
  motion: 'Motion (honors prefers-reduced-motion in global.css)',
  layout: 'Layout',
  bg: 'Semantic surface + text roles',
};
const STATUS_SECTIONS = new Set(['ok500', 'okBg', 'warn500', 'warnBg', 'error500', 'errorBg', 'info500', 'infoBg', 'unknown500', 'unknownBg']);
const SHADOW_SECTIONS = new Set(['shadowSm', 'shadowMd', 'shadowDrawer']);

function emitDeclarations(groupKeys, indent) {
  const pad = ' '.repeat(indent);
  const lines = [];
  let lastLabel = null;
  const wanted = tokens.filter((t) => groupKeys.includes(t.group));
  for (const t of wanted) {
    let label = SECTION_LABEL[t.section] ?? null;
    if (STATUS_SECTIONS.has(t.section)) label = 'Status / health (color + SHAPE + LABEL — never color alone, WCAG 1.4.1)';
    else if (SHADOW_SECTIONS.has(t.section)) label = 'Shadows';
    if (label && label !== lastLabel) {
      lines.push(`${pad}/* ${label} */`);
      lastLabel = label;
    }
    lines.push(`${pad}--${t.varName}: ${resolveValue(t.value)};`);
  }
  return lines.join('\n');
}

function block(selector, groupKeys, { atRule } = {}) {
  if (atRule) {
    return `${atRule} {\n  ${selector} {\n${emitDeclarations(groupKeys, 4)}\n  }\n}`;
  }
  return `${selector} {\n${emitDeclarations(groupKeys, 2)}\n}`;
}

const HEADER = `/*
 * HomeSynapse — Design Tokens  ·  GENERATED FILE — DO NOT EDIT BY HAND.
 * Source of truth: src/styles/tokens/tokens.dtcg.json (W3C Design Tokens / DTCG).
 * Regenerate: \`npm run tokens\`  ·  Drift-guard in CI: \`npm run tokens:check\`.
 *
 * The visual foundation. "Infrastructure-grade software, consumer-grade calm." Calm-neutral
 * surface, architectural near-black/white neutrals, a SINGLE cool accent, warmth kept OUT of
 * the UI (W-9). Health + command-outcome semantics are conveyed by color + SHAPE + LABEL,
 * never color alone (WCAG 1.4.1). All color pairs meet WCAG AA on their intended surface.
 *
 * Theme model (FE-2, D-FE-1): DARK is the recorded default; \`prefers-color-scheme\` is honored
 * on first load for users who have not chosen; a persistent dark/light/system toggle sets
 * [data-theme] on <html>. Light is first-class. Same token names in every theme, so no
 * component changes between themes.
 */`;

const HEADER_LIGHT = HEADER.replace(
  'Theme model (FE-2, D-FE-1): DARK is the recorded default;',
  'Theme model (pre-FE-2 / equivalence build): LIGHT default;',
);

function generate(mode) {
  const parts = [mode === 'light' ? HEADER_LIGHT : HEADER, ''];
  if (mode === 'light') {
    parts.push(block(':root', ['core', 'light']));
    parts.push('');
    parts.push('/* Dark theme — opt-in via <html data-theme="dark">. */');
    parts.push(block(":root[data-theme='dark']", ['dark']));
  } else {
    parts.push('/* Default = dark (recorded brand default). Also the fallback for system mode. */');
    parts.push(block(':root', ['core', 'dark']));
    parts.push('');
    parts.push('/* System mode (no explicit choice) + OS prefers light → light. */');
    parts.push(block(':root:not([data-theme])', ['light'], { atRule: '@media (prefers-color-scheme: light)' }));
    parts.push('');
    parts.push('/* Explicit light choice (toggle). */');
    parts.push(block(":root[data-theme='light']", ['light']));
    parts.push('');
    parts.push('/* Explicit dark choice (toggle) — re-asserts dark over a system-light match. */');
    parts.push(block(":root[data-theme='dark']", ['dark']));
  }
  return parts.join('\n') + '\n';
}

/* Normalize for drift comparison: drop comments/blank lines, collapse whitespace. */
function normalize(css) {
  return css
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((l) => l.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .join('\n');
}

const css = generate(themeDefault);

if (args.has('stdout')) {
  process.stdout.write(css);
} else if (args.has('check')) {
  const outPath = args.get('out') ? resolve(ROOT, args.get('out')) : DEFAULT_OUT;
  let committed = '';
  try {
    committed = readFileSync(outPath, 'utf8');
  } catch {
    console.error(`tokens:check — no committed file at ${outPath}`);
    process.exit(1);
  }
  if (normalize(committed) !== normalize(css)) {
    console.error('tokens:check — DRIFT: tokens.css is out of sync with tokens.dtcg.json. Run `npm run tokens`.');
    process.exit(1);
  }
  console.log('tokens:check — OK (tokens.css matches the token source).');
} else {
  const outPath = args.get('out') ? resolve(ROOT, args.get('out')) : DEFAULT_OUT;
  writeFileSync(outPath, css, 'utf8');
  const count = tokens.length;
  console.log(`tokens — wrote ${outPath} (${count} tokens, theme-default=${themeDefault}).`);
}

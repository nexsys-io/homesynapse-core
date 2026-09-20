import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/* FE-114 D4 (IR-12): the literal lint's ONE sentence pattern — a capitalised word, then at least one
 * more word; apostrophes (' and ’) inside a word; one of , ; : — - tolerated between words. */
const HERO_SENTENCE = "[A-Z][a-z’']+[,;:—-]? [a-z’']+";
const HERO_LINT_MESSAGE = 'hero copy lives in i18n.ts (HERO-1d)';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: { ...globals.browser },
    },
    rules: {
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/consistent-type-imports': 'warn',
      'no-console': ['warn', { allow: ['warn', 'error'] }],
    },
  },
  {
    files: ['scripts/**/*.mjs'],
    languageOptions: { globals: { ...globals.node } },
  },
  {
    /* HERO-1d D0 (2026-09-13) — the literal lint (IR-8's instrument): no hero SENTENCE lives outside
     * src/lib/i18n.ts. Scoped to the six hero SOURCE files only — never the tests, never the catalog.
     * FE-114 D4 (2026-09-14, IR-12 — the pattern's reach): a "sentence" is now a capitalised run of TWO
     * or more words (apostrophes ' and ’ inside a word; , ; : — - tolerated between words) in a JSX text
     * node, a template-literal quasi (or a capitalised word adjacent to `${` at either end of a quasi),
     * a title / lede / label / emptyLabel / placeholder attribute string, or an OBJECT-PROPERTY string
     * (the pills). Baseline at HEAD 6bd8508 with this rule: 6 hits (the hub's list); the returned tree:
     * 0; the same rule over all non-test src/ reads in the hundreds (the catalog and the mock lead) and
     * is REPORTED, not fixed — the scope stays these six files by law. Still unseen, by design: a bare
     * Literal in a return / conditional (actionPhrase's verbs) and one-word labels. Counts:
     * nexsys-hivemind/context/audits/2026-09-14_FE-114_return.md §0. */
    files: [
      'src/components/CausalChain.tsx',
      'src/views/ExplainHubView.tsx',
      'src/views/WhyNotView.tsx',
      'src/views/RunsView.tsx',
      'src/views/RunChainView.tsx',
      'src/components/Resource.tsx',
    ],
    rules: {
      'no-restricted-syntax': [
        'error',
        { selector: `JSXText[value=/${HERO_SENTENCE}/]`, message: HERO_LINT_MESSAGE },
        { selector: `TemplateLiteral > TemplateElement[value.raw=/${HERO_SENTENCE}|[A-Z][a-z’']+ ?$|^ ?[A-Z][a-z’']+/]`, message: HERO_LINT_MESSAGE },
        {
          selector: `JSXAttribute[name.name=/^(title|lede|label|emptyLabel|placeholder)$/] > Literal[value=/${HERO_SENTENCE}/]`,
          message: HERO_LINT_MESSAGE,
        },
        { selector: `Property > Literal[value=/${HERO_SENTENCE}/]`, message: HERO_LINT_MESSAGE },
        /* FE-115 D4 (2026-09-19): the reach closed on the two blind spots FE-114 named — a bare Literal returned from a
         * function (actionPhrase's verbs) or sitting in a conditional. Baseline at HEAD d1c2cbc with these two: 2 hits
         * (CausalChain.tsx :369 "Turned on" · :371 "Turned off"; "Dimmed" is one word and outside the pattern, keyed
         * anyway); the returned tree: 0. The scope stays the six files by law (format.ts's EMPTY_CHAIN_NOTE was keyed
         * by the twin-fold, not by this rule). Counts: nexsys-hivemind/context/audits/<CT-date>_FE-115_return.md §0. */
        { selector: `ReturnStatement > Literal[value=/${HERO_SENTENCE}/]`, message: HERO_LINT_MESSAGE },
        { selector: `ConditionalExpression > Literal[value=/${HERO_SENTENCE}/]`, message: HERO_LINT_MESSAGE },
      ],
    },
  },
);

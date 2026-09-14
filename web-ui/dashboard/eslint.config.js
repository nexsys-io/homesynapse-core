import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

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
     * A "sentence" is a capitalised run of three or more words in a JSX text node, a template-literal
     * quasi, or a title / lede / label / emptyLabel / placeholder attribute string. Baseline and
     * after-count: nexsys-hivemind/context/audits/2026-09-13_HERO-1d_return.md §0. */
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
        { selector: 'JSXText[value=/[A-Z][a-z]+ [a-z]+ [a-z]+/]', message: 'hero copy lives in i18n.ts (HERO-1d)' },
        { selector: 'TemplateLiteral > TemplateElement[value.raw=/[A-Z][a-z]+ [a-z]+ [a-z]+/]', message: 'hero copy lives in i18n.ts (HERO-1d)' },
        {
          selector: 'JSXAttribute[name.name=/^(title|lede|label|emptyLabel|placeholder)$/] > Literal[value=/[A-Z][a-z]+ [a-z]+ [a-z]+/]',
          message: 'hero copy lives in i18n.ts (HERO-1d)',
        },
      ],
    },
  },
);

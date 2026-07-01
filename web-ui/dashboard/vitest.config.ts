import { defineConfig } from 'vitest/config';

// Unit tests run in jsdom. Logic tests are plain .ts; the axe-core a11y suite renders real Preact
// components (.tsx). Rather than the Vite plugin (whose Plugin type conflicts with vitest's bundled
// Vite copy), we point esbuild's JSX transform at Preact's automatic runtime — mirrors tsconfig's
// jsx settings, type-safe, and transforms the imported .tsx the same way.
export default defineConfig({
  esbuild: { jsx: 'automatic', jsxImportSource: 'preact' },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
  },
});

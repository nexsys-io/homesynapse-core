import { defineConfig } from 'vitest/config';

// Unit tests run in jsdom. Test files are plain .ts (pure logic: contract
// conformance, plain-language formatting, routing) so no JSX plugin is needed.
export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
  },
});

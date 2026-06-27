import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

// HomeSynapse Core — V1 Observability Dashboard build config.
//
// Local-first (INV-LF-01 / C13-06): NO external CDNs, fonts, or analytics — every
// asset ships inside the jlink image. Served by Javalin at /dashboard/ (Doc 13),
// so base is set accordingly and routing is hash-based (no server route config).
//
// Bundle budget (C13-01 / LTD-18): the gzipped initial bundle must stay < 100 KB.
// The hard gate is scripts/check-bundle-size.mjs (CI + npm run verify). Charting
// (uPlot) is a lazy import in SparkChart, so it code-splits out of the initial
// bundle automatically.
export default defineConfig({
  base: '/dashboard/',
  plugins: [preact()],
  build: {
    outDir: 'dist',
    target: 'es2020',
    cssCodeSplit: false,
    reportCompressedSize: true,
  },
  server: { port: 5173 },
});

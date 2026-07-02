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
// FE-1 dev-mode live integration: the dev server proxies /api + /internal to the local
// Core origin so the browser stays same-origin (Core sets no CORS headers — by design;
// loopback default per contract §0). Served builds need no proxy (Javalin serves the
// bundle, so window.location.origin IS Core). Override the target with VITE_CORE_ORIGIN
// (e.g. an SSH-tunnelled Pi); default is the contract's loopback default.
const coreOrigin = process.env.VITE_CORE_ORIGIN ?? 'http://127.0.0.1:7070';

export default defineConfig({
  base: '/dashboard/',
  plugins: [preact()],
  build: {
    outDir: 'dist',
    target: 'es2020',
    cssCodeSplit: false,
    reportCompressedSize: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: coreOrigin, changeOrigin: true },
      '/internal': { target: coreOrigin, changeOrigin: true },
    },
  },
});

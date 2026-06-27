# HomeSynapse — V1 Observability Dashboard

A small, local-first **Preact + TypeScript** SPA. It answers three questions for a homeowner or developer: *is the system healthy?*, *what just happened?*, and the differentiator — *why did (or didn't) this happen, and did the device actually confirm?*

Served as static files by Javalin at `/dashboard/`. No server-side rendering, no WebSocket, no phone-home. Ships inside the jlink image to a Raspberry Pi.

## Quick start

```bash
npm install
npm run dev        # Vite dev server (mock API by default)
npm run verify     # lint + typecheck + test + build + bundle-budget + contract-check
```

Open the dev server, paste any non-empty token at the gate (the mock accepts anything except `invalid`).

## Scripts
| Script | What it does |
|---|---|
| `dev` | Vite dev server (mock transport) |
| `build` | Production build to `dist/` |
| `typecheck` | `tsc --noEmit` (strict) |
| `lint` | ESLint |
| `test` | Vitest unit tests |
| `check:bundle` | **Hard-fails** if the initial bundle > 100 KB gzipped |
| `check:contract` | Verifies the client still covers every frozen-contract endpoint |
| `verify` | All of the above — the CI gate |

## Layout
```
src/
  styles/      tokens.css (design system) + global.css
  lib/
    api/       contract.ts (frozen-contract types) · client.ts · endpoints.ts
               realTransport.ts · mock/ · shapes.ts (validators) · index.ts (the switch)
    auth.ts    in-memory session token (AB-1)
    poll.tsx   one coalesced poll loop on meta.viewPosition (no WebSocket)
    router.ts  hash router
    format.ts  plain-language "mom-test" copy (locked by format.test.ts)
  components/  StatusPill, Card/Page, DataTable, Drawer, CausalChain (hero), AppShell, AuthGate, …
  views/       Overview, Devices, Health, Events, Automations, Explain hub, Runs, RunChain, WhyNot
```

## Switching mock → real
One switch: `src/lib/api/index.ts` (`VITE_USE_MOCKS=true|false`; default mock in dev, real in prod). A-class endpoints are live; B-class are mocked to the frozen shapes until Core delivers them.

## Rules that bind this module
See **`FRONTEND_DOCTRINE.md`** (the principles) and **`MODULE_CONTEXT.md`** (the locked constraints). In short: Preact + Vite + uPlot + CSS Modules (Locked); 100 KB gzip budget (build-enforced); poll, don't WebSocket; in-memory token only; local-first (no runtime CDNs); build against the frozen contract and route any change to the hub.

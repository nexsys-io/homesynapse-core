# dashboard — Preact SPA — V1 observability dashboard, static files served from Javalin at /dashboard/, <100 KB gzipped initial bundle, separate npm/Vite build pipeline

*Frontend-dev lane, first beat delivered 2026-06-26. Was scaffold-only; now a buildable TypeScript Preact SPA (shell + design system + auth + device/health/event views + the explainability hero, built against the frozen read-API contract).*

*2026-06-29 beat (FE-0/FE-2/FE-3, per the master-plan amendment-1): design tokens are now **generated** from a platform-neutral W3C-DTCG source (D-FE-8); **dark is the recorded default** theme with a dark/light/system toggle honoring `prefers-color-scheme` (D-FE-1); copy is **name-light + i18n-keyed** (D-FE-9). D-FE-10 typeface RULED + landed: self-hosted subset Inter-variable (~25 KB woff2), system stack fallback. See Gotchas.*

## Design Doc Reference
- `homesynapse-core-docs/design/13-web-ui-observability-mvp.md` (Locked) — governs stack + UX scope.
- `nexsys-hivemind/context/decisions/2026-06-21_dashboard-read-API-contract-freeze.md` (FROZEN v1.1) — the read-API contract this builds against; mirrored field-for-field in `src/lib/api/contract.ts`.
- `FRONTEND_DOCTRINE.md` (this module) — the lean, reusable frontend doctrine (candidate for hub promotion).

## Dependencies
- Runtime: consumes the Core HTTP surface (REST) over loopback — A-class endpoints live; B-class (events, health, runs/causal-chain/non-firing/automations) mocked to frozen shapes until Core delivers them. No Java dependency; communicates only over HTTP.
- Build: Node + npm + Vite (dev/build only; not on the Pi at runtime). Preact 10, uPlot (lazy), TypeScript, Vitest, ESLint.

## Consumers
- The distribution / jlink image: Javalin serves the built static assets from `src/main/resources/dashboard/` at `/dashboard/` (Doc 13 §3.2–§3.3). Gradle `:web-ui:dashboard:assemble` runs the npm build and stages `dist/` into resources.

## Constraints (locked — do not re-litigate)
- **Stack:** Preact 10 + Vite + uPlot + CSS Modules + TypeScript (Doc 13, Locked). The dispatch's older "Svelte/React" framing is superseded.
- **Bundle budget:** 100 KB gzipped initial bundle, build-enforced hard fail (`scripts/check-bundle-size.mjs`). Current estimate ~22–26 KB.
- **No WebSocket in V1 (firm):** poll REST at 1–2s, coalesced on `meta.viewPosition` (`src/lib/poll.tsx`). Supersedes Doc 13's WebSocket-era sections (§1, §3.5, §3.8, §8.2, parts of §4/§6/§11) — flagged to the hub as a Doc 13 currency note.
- **Auth (AB-1):** in-memory session token only (no localStorage/cookies). Paste the pairing token from `config/initial_api_token`. Handle 401 → prompt, 403 → reject.
- **Local-first (C13-06):** no runtime CDNs, fonts-from-Google, or analytics. Everything ships in the image.
- **Write-isolation:** this lane writes ONLY under `web-ui/dashboard/…` + its lane return under `context/audits/`. Contract changes are cross-lane events routed to the hub.

## Gotchas
- **One switch flips mock/real:** `src/lib/api/index.ts` (`VITE_USE_MOCKS`; default mock in dev, real in prod). Endpoints are transport-agnostic; only the transport changes as Core lands B-class.
- **Contract drift fails CI, not the demo:** runtime validators in `src/lib/api/shapes.ts` + `contract.test.ts` + `scripts/contract-check.mjs`.
- **Plain-language copy is centralized** in `src/lib/format.ts` and locked by `format.test.ts` (the stranger/"mom" test).
- **Build/test require Node** and run as the frontend CI gate (`ci/frontend.yml`, npm `verify`). The Core lane's `./gradlew check` is intentionally NOT coupled to Node (npm tasks hang off `assemble`, never `check`).
- **CI wiring is a cross-lane item:** `ci/frontend.yml` is delivered here, ready for the hub to place into `.github/workflows/`.
- The contract carries **no entity display-name field**; the UI humanizes `entityId` (`labelFor`). A `name`/`label` field is a candidate additive contract change (raised in the lane return).
- **Design tokens are GENERATED — never hand-edit `tokens.css`.** Source of truth: `src/styles/tokens/tokens.dtcg.json` (W3C Design Tokens). Regenerate with `npm run tokens`; the drift-guard `npm run tokens:check` is wired into `verify` (and `prebuild` regenerates). The generator (`scripts/build-tokens.mjs`) is zero-dependency; the source is DTCG, so Style Dictionary can be adopted later for native/B2B outputs without re-authoring (D-FE-8).
- **Dark is the recorded default theme (D-FE-1).** First paint is set pre-CSS by an inline boot script in `index.html` (no FOUC); `prefers-color-scheme` is honored for "system"; a persistent dark/light/system toggle (`src/lib/theme.ts` + `src/components/ThemeToggle.tsx`) sets `[data-theme]` on `<html>`. The theme preference is stored in `localStorage` — this is non-secret UI state and is deliberately distinct from the in-memory-only auth token (AB-1).
- **Name-light + i18n-keyed copy (FE-3, D-FE-9).** The product name comes from `BRAND.productName` (`src/lib/i18n.ts`) — never hardcode it (the unratified rename flips one token). User-facing strings are a keyed catalog via `t()`; V1 ships English; locale/RTL/Intl-format seams are reserved. (Most enum-label copy still lives in `format.ts`; migrating those maps behind `t()` is the same pattern and can follow. The static `index.html` `<title>`/`<noscript>` remain as the no-JS fallback; `document.title` is set from the brand token at runtime.)
- **UI font is self-hosted (FE-3/D-FE-10), not a runtime web font.** `src/styles/fonts/inter-variable-subset.woff2` (~25 KB, Inter variable `wght` axis, tight UI subset) ships in the image and is served from loopback — **never a CDN** (INV-LF-01). Wired in `src/styles/fonts.css` (`@font-face`, `font-display: swap`); `--hs-font-sans` leads with `"Inter Variable"`, system stack as fallback. Reproducible subset: `src/styles/fonts/README.md`. (Same surface as the dashboard↔website type-coherence goal.)

---

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2); the "scaffold only" note below is now superseded by the 2026-06-26 first-beat delivery above.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries.

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state.

# dashboard — Preact SPA — V1 observability dashboard, static files served from Javalin at /dashboard/, <100 KB gzipped initial bundle, separate npm/Vite build pipeline

*Frontend-dev lane, first beat delivered 2026-06-26. Was scaffold-only; now a buildable TypeScript Preact SPA (shell + design system + auth + device/health/event views + the explainability hero, built against the frozen read-API contract).*

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

---

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2); the "scaffold only" note below is now superseded by the 2026-06-26 first-beat delivery above.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries.

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state.

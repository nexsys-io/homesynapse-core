# FE-1 go-live — realTransport audit + the one-sitting swap checklist

**Purpose (sprint T1.3):** retire live-integration's risk *now*, so the day Core is up on loopback,
"hero on real data" (the mid-Aug gate) is a **transport swap + a smoke test — hours, not a build.**
This audits the real data path against the FROZEN v1.1 contract §0 (cross-cutting), verified against
the mock **scenario engine's** injected conditions (T1.2), then gives the mechanical go-live steps.

The success test: flip `VITE_USE_MOCKS=false`, point at loopback, smoke the endpoints, fix nothing
structural, demo the hero on real data.

---

## Part A — realTransport / client audit against contract §0

Each cross-cutting concern, where it lives, and how it was exercised against a scenario condition
(DevPanel → Transport). **Legend:** ✅ ready · ⚠️ ready, with a noted follow-up · 🔲 gap to close in FE-1.

| §0 concern | Where | Status | Notes / how tested |
|---|---|---|---|
| **Bearer auth (AB-1)** | `realTransport` adds `Authorization: Bearer {token}`; in-memory only (`auth.ts`) | ✅ | The shell only mounts the poll loop when authed — no pre-auth enumeration. |
| **401 → re-prompt** | `client` → `ApiProblem.isAuthRequired` → poll/useApi `auth`; gate shown when no token | ✅ | `mock=auth-required` reproduces it. |
| **403 → reject** | `client.onAuthError` (index) clears the token → `AuthGate` with the rejection message | ✅ | `mock=forbidden` reproduces it (the panel lives outside the gate, so you can recover). |
| **503 `state-store-replaying` + backoff** | `client.isReplaying`; poll → `replaying` phase + ×1.5 backoff (cap 5s); `ReplayingBanner` | ✅ | `mock=replaying` returns projection `mode:REPLAY` **and** 503 on `/api/*` — both signal catch-up. Keyed on `problem.type`, so `integration-unhealthy` (also 503) is correctly *not* treated as replaying. |
| **ETag / If-None-Match / 304** | `realTransport` sends `If-None-Match`, skips body on 304; `client` caches by full path, returns cached on 304 | ⚠️ | `mock=etag` returns a generation-stable ETag → 304 on revalidate, refresh on scenario change. Follow-up: the client's ETag cache is unbounded (fine for the finite endpoint set; cap later). |
| **RFC 9457 `problem+json`** | `realTransport` parses JSON (incl. error bodies); `client.toProblem` → typed `ApiProblem` | ⚠️ | All error conditions carry a typed problem. Follow-up: `Accept` is `application/json`; add `application/problem+json` during FE-1 for strict content-negotiation (servers return it regardless). |
| **Cursor pagination echo** | endpoints accept `since`/`cursor`; `client` passes them through; **UI does not yet follow pages** | 🔲 | The contract says echo `nextCursor` as `since`/`cursor`, never construct one. The UI currently reads a single page. **The one real FE-1/FE-5 gap:** implement cursor-follow for large real sets (events tail `since=<nextCursor>`) + virtualization (T2.3). Not gating for a curated mid-Aug set (single page); the `large` scenario stresses render-at-scale on one page today. |
| **Poll on `meta.viewPosition`** | `poll.tsx` polls A4 every 1.5s; refetch when the cursor advances (no WebSocket, D-OPEN-3) | ✅ | Real Core advances `viewPosition` only on change → *fewer* refetches than the mock (which advances every response). The UI handles both. |
| **Malformed envelope** | `client` throws `internal-error` 502 if `{data,meta}` missing | ✅ | Defensive; real Core should never hit it. |
| **Offline / unreachable** | `client` wraps a transport throw as `network-unreachable` → `isOffline` → `OfflineState` | ✅ | Added T1.2 session; `mock=offline` throws a fetch-like error → the calm degraded state. Real fetch network failure takes the same path. |
| **Runtime shape validation of LIVE responses** | `shapes.ts` validators exist; used by `contract.test` + available for dev runtime; **not wired into `client`** | 🔲 | Recommend a **dev-only** validation hook in `client.get` during FE-1 (gate on a `VITE_VALIDATE` flag) so Core drift is caught at the moment of integration. Any live validator failure = a **cross-lane event to the hub**, never a client patch. |

**Verdict:** the data path is contract-faithful and already handles every value + transport
condition the scenario engine can inject. Two follow-ups to close during FE-1 (cursor-follow for
large sets; optional dev-runtime validation) and two nice-to-haves (ETag cache cap; `problem+json`
Accept). None is structural; none blocks a curated-set demo.

---

## Part B — the go-live checklist (do in one sitting when Core is up)

1. **Core up on loopback.** Start Core; note the served origin (Javalin serves the built dashboard
   at `/dashboard/`, so `window.location.origin` is already correct in a served build). Grab the
   pairing token from `config/initial_api_token`.
2. **Point the UI at real.** Served build defaults to real (`API_MODE` = real in prod). For a dev
   check against Core: run with `VITE_USE_MOCKS=false`. (One switch — `src/lib/api/index.ts`.)
3. **Authenticate.** Paste the token in the gate → bearer header on every request.
4. **Smoke the reads** (expect 200 + the rendered view, no console contract errors):
   - A-class (live today): `/api/v1/entities`, `/{id}`, `/{id}/state`, `/internal/projection`, `/internal/dlq`.
   - B3 (now real): `/api/v1/runs`, `/runs/{id}/causal-chain`, `/automations/{id}/non-firing`, `/automations`.
   - B1/B2 (`/events`, `/health`) — swap from mock when Core lands them (M7.5c); until then leave mocked.
5. **Exercise the cross-cutting paths on real Core:** reload during boot (→ calm replaying state);
   let it go LIVE; wrong/expired token (→ gate); pull the network (→ offline state); confirm poll
   updates as `viewPosition` advances.
6. **(If wired) enable dev-runtime validation** (`VITE_VALIDATE=true`) for the first integration
   pass — catch any Core shape drift immediately. A failure is a hub escalation, not a client edit.
7. **Real-data surprises should be near-zero** — the scenario engine already rendered real `UNKNOWN`
   origins, `UNCONFIRMED` outcomes, every terminal reason, empty + 300/500-row sets. Spot-check the
   hero on a real `RunCausalChain`, both "why did / why didn't it fire", and the honest outcome pill.
8. **Large real sets:** if `pagination.hasMore` is true, confirm cursor-follow + virtualization
   (T2.3) before relying on it; a curated set is single-page and fine for the gate.

**Result:** FE-1 is the flip + this smoke. FE-7 (real-device `CONFIRMED`/`UNCONFIRMED` on real
silicon) then lights up the moat's lead claim as measured fact — gated only by bench first-light.

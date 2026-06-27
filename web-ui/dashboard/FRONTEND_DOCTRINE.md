<!--
file: homesynapse-core/web-ui/dashboard/FRONTEND_DOCTRINE.md
purpose: The lean, durable doctrine for ALL HomeSynapse frontend / web work, "from now on." The V1 observability dashboard in this module is its first concrete instantiation. Authored by the frontend-dev lane 2026-06-26.
status: PROPOSED (frontend-dev lane). Lives in-module per write-isolation; CANDIDATE FOR HUB PROMOTION to a shared design-system home (and, later, a loadable `nexsys-frontend` skill analogous to nexsys-coder / nexsys-project-manager).
scope: the dashboard SPA, the homesynapse.com marketing site, and any future UI surface. Thin on purpose — the regret-proof core, not a design-org bible.
anchors: design/13-web-ui-observability-mvp.md (Locked stack) · context/decisions/2026-06-21_dashboard-read-API-contract-freeze.md (the contract) · context/assessments/2026-06-21_explainability-UX-competitive-research.md · website/design-system/* (brand DNA) · context/assessments/2026-06-26_causal-chain-presentation-UX-research (in this lane return)
-->

# HomeSynapse Frontend Doctrine (lean)

The one-line filter for every screen, component, and sentence:

> **Would a technical buyer who distrusts marketing — or a stranger who has never seen the system — read this as evidence they understand, or as decoration?** If decoration, cut it.

Seven principles. Each is regret-proof: cheap to hold now, expensive to retrofit later.

## 1. Inherit the brand DNA — calm-neutral, evidence over decoration
**Infrastructure-grade software presented with consumer-grade calm.** Architectural near-black/white neutrals, a single cool accent, generous whitespace, restraint. Warmth is allowed to be *felt* (texture, the occasional brand moment) and **never allowed to make a claim** — it stays out of the UI surface, the type, and the status layer (brand ruling W-9). References: Ubiquiti/UniFi (the closest analog — premium-consumer presentation of prosumer infrastructure), Apple (pacing and economy, one idea per viewport), Stripe (reveal depth on demand), Framework (ownership voice). Reject hype, mascots, and decorative heroes over empty claims.

## 2. Explainability is the product — design backward from the stranger
The hero is the causal chain: *"why did it fire?"* **and**, co-equally, *"why didn't it?"*, plus *"did the device actually confirm?"*. Build every explanation to these rules (from the competitive + presentation-UX research):
- **Device-backward, plain language.** Start from what the person noticed and walk back: *"Hallway Light turned on because Hallway Motion detected motion at 9:42 pm."* Never index paths (`conditions/0/conditions/1`), never internal jargon. ≤ ~20 words per sentence, ~8th-grade reading level.
- **Two disclosure levels only.** A plain sentence + a bounded, linear step chain at a glance; the technical fact (expression, observed state, params) one expand away. Never a free graph that degrades into spaghetti.
- **Show command outcome, not just intent.** `Confirmed | Sent, not confirmed | Failed`, in the severity palette + a shape + a label — never the optimistic lie every competitor ships. `UNCONFIRMED` is calm amber and honest, not an alarm and not a fake success.
- **Never a silent blank.** Every event carries an origin; `UNKNOWN` is an explicit, honest value distinct from "nothing caused it."
- **Lead on "never evicted."** The explanation is a projection of the immutable log — the run you need is always reconstructable. Promote that; it is the structural moat.

## 3. The stranger ("mom") test is the acceptance bar
A person who has never seen HomeSynapse reads the explanation aloud and is **right**. Not "a power user can decode it." Plain-language copy lives in one place (`src/lib/format.ts`) and is locked by tests (`format.test.ts`) so no one can quietly make it more technical.

## 4. Accessibility is non-negotiable and mostly free
WCAG AA contrast on every pair. Status is conveyed by **color + shape + text**, never color alone (1.4.1). Causal chains render as semantic ordered lists (`<ol>`), not custom `role="tree"` widgets (simpler, robust for screen readers). Live state transitions use polite `role="status"` regions. Full keyboard reachability; visible focus rings; honor `prefers-reduced-motion`. Short sentences are themselves a cognitive-accessibility feature.

## 5. Local-first, constrained-hardware discipline (hard constraints, build-enforced)
- **Stack is Locked (Doc 13):** Preact 10 + Vite + uPlot + CSS Modules + TypeScript. Don't re-litigate it.
- **100 KB gzipped initial-bundle budget is a build FAILURE when exceeded** (`scripts/check-bundle-size.mjs`), not a warning. Heavy/optional things (charting) lazy-load and stay out of the initial bundle.
- **No phone-home:** no runtime CDNs, no Google Fonts, no analytics, no external resources. Everything ships in the jlink image. The dashboard works identically with the internet unplugged.
- **No WebSocket in V1 (firm):** poll the REST at 1–2s, coalesced on the `meta.viewPosition` cursor; one poll loop, no storms. (This supersedes Doc 13's WebSocket-era sections — see the lane return's Doc 13 currency note.)

## 6. Build against the frozen contract; drift is a cross-lane event
A single typed API client mirrors the frozen read-API contract field-for-field. A-class endpoints are consumed live; B-class are mocked to the exact frozen shapes behind **one switch**, and Core implements *to* them. Runtime validators + a CI contract-check make Core drift fail CI, not the demo. Any needed contract change is raised to the hub — never diverged unilaterally.

## 7. Tokens and components first; ship the gate with the code
A small token-based design system (color/space/type as CSS custom properties, dark-ready) and a component kit (table, card, status pill, detail drawer, causal-chain tree) precede the views, so the hero is polished, not bolted on. CI is the gate of record: lint + typecheck + unit tests + production build + the bundle-budget + the contract-check run on every change; run them locally before handoff (shift-left).

---

*Lean by design. When a future UI surface needs a rule this doesn't cover, add the smallest principle that prevents the regret — and keep it to the stranger-test filter at the top.*

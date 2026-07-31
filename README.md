# HomeSynapse Core

The on-device runtime for HomeSynapse — a local-first, event-sourced smart home
platform that runs entirely on hardware in the house. A Raspberry Pi 5 is the
reference target; a Pi 4 (4 GB) is the validation floor. Nothing degrades when
the internet drops, because nothing depends on it.

The differentiator is not integration count. It is that the system can answer,
from a durable record rather than a trace buffer, three questions that most
platforms cannot answer at all:

- **Why did this automation fire?** — the trigger, every condition and its
  result, every action and its outcome, reconstructed as a causal chain.
- **Why didn't it fire?** — a real verdict (`CONDITION_NOT_MET`,
  `NEVER_TRIGGERED`, `ACTED_BUT_UNCONFIRMED`, `DISABLED`), not silence.
- **Did the device actually do it?** — a command is reported `CONFIRMED` only
  when the device's own state report confirms it. When we don't know, the
  system says `UNCONFIRMED` rather than guessing.

That last one is a hard invariant, not a goal. At the July 2026 certification
close the bench had recorded roughly 1,700 command verdicts against real Zigbee
hardware with zero false confirmations.

---

## Quick start

### Prerequisites

| Requirement | Notes |
|---|---|
| **JDK 21** | Required and **must already be installed**. There is no toolchain auto-download configured — Gradle will fail rather than fetch a JDK. CI uses Amazon Corretto 21; any JDK 21 works. |
| **Node 20+ and npm** | Only needed for tasks that touch the web dashboard (`build`, `run`, `installDist`). Not needed for `check`. |
| **Git** | — |

Gradle itself is not a prerequisite — the wrapper (`./gradlew`) fetches Gradle 8.8.

### Run the gate

```bash
./gradlew check
```

This is the authoritative build: compile, ~3,000 unit tests, Spotless license
headers, and the ArchUnit architecture rules. It is exactly what CI runs, and it
is deliberately **Node-free** — `:app:homesynapse-app` excludes the dashboard jar
from `testRuntimeClasspath` so the Java gate never invokes npm.

`./gradlew build` additionally assembles every jar, which builds the dashboard
SPA and therefore **does** require Node.

### Run the application

```bash
export HOMESYNAPSE_HOME=~/.homesynapse-dev    # see the warning below
./gradlew :app:homesynapse-app:run
```

> **Set `HOMESYNAPSE_HOME`.** Without it the runtime base directory falls back to
> `<working dir>/.homesynapse`, and under Gradle that working directory is the
> project dir — so the event database, home id and API token get written into
> `app/homesynapse-app/.homesynapse/` inside your source tree.

**No Zigbee hardware is required.** The HTTP surface comes up in startup phase 5
and integrations in phase 6; if no coordinator is attached, the Zigbee adapter
fails as a `PermanentIntegrationException`, the supervisor marks it failed, and
boot continues. You get a running system with no devices.

Once it's up, the dashboard is at **<http://127.0.0.1:7070/dashboard/>** and `/`
redirects there.

### Talk to the API

Every endpoint except the static dashboard shell requires a bearer token. One
full-access token is minted on first boot:

```bash
TOKEN=$(cat "$HOMESYNAPSE_HOME/config/initial_api_token")
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:7070/api/v1/entities
```

If you get `503 state-store-replaying`, the state projection is still catching
up on the event log. Wait and retry — `/api/*` is gated until it reaches LIVE.

### Work on the dashboard alone

```bash
cd web-ui/dashboard
npm ci
npm run dev      # http://localhost:5173, mock data by default
npm run verify   # the frontend gate: tokens, lint, typecheck, test, build, bundle + contract check
```

The SPA runs standalone against a scenario-driven mock backend, so you do not
need a running Core to develop UI. `npm run dev` serves mocks by default — both
variables are required to talk to a real one, because `VITE_CORE_ORIGIN` only
sets the dev-server proxy target and transport selection is separate:

```bash
VITE_USE_MOCKS=false VITE_CORE_ORIGIN=http://127.0.0.1:7070 npm run dev
```

---

## How it works

Every change to the system is an **event**. Events are the only source of truth;
everything else is derived.

```
                          ┌─────────────────────────────┐
  Zigbee dongle ──────────►  Integration adapter        │
  (EZSP / ASH serial)     │  normalizes to domain events│
                          └──────────────┬──────────────┘
                                         │ publish
                          ┌──────────────▼──────────────┐
                          │  EventPublisher (single     │   append-only,
                          │  writer) ──► SQLite WAL     │   total order via
                          └──────────────┬──────────────┘   global_position
                                         │ notify
                          ┌──────────────▼──────────────┐
                          │  InProcessEventBus          │   pull-based; each
                          │  (virtual threads)          │   subscriber keeps
                          └──────────────┬──────────────┘   its own checkpoint
                    ┌────────────────────┼────────────────────┐
          ┌─────────▼────────┐  ┌─────────▼────────┐  ┌────────▼─────────┐
          │ StateProjection  │  │ Automation engine│  │ Registry         │
          │ → EntityState    │  │ → Runs           │  │ projection       │
          └─────────┬────────┘  └─────────┬────────┘  └────────┬─────────┘
                    └─────────────────────┬────────────────────┘
                          ┌──────────────▼──────────────┐
                          │  REST API (Javalin)         │
                          │  → Preact dashboard         │
                          └─────────────────────────────┘
```

**The writes are serialized.** One publisher, one SQLite writer, one total order.
`publish` returns only after the WAL commit, so an acknowledged event is a
durable event. There is no second path to actuation.

**Reads are projections.** Current state is not stored authoritatively — it is
folded out of the log into a materialized state store and can be rebuilt from
position zero. The device and entity registries are projections too.

**Explanations are projections as well.** There is no parallel trace store.
`ExplanationService` reconstructs causal chains by reading the log through
correlation and causation ids, which is why explanations survive restarts and
have nothing to evict.

The canonical chain for "motion turns on a light" reads end to end as:

```
state_reported → state_changed → automation_triggered → command_issued
  → command_dispatched → command_result → state_reported → state_changed
```

### Vocabulary you need before reading the code

| Term | Meaning |
|---|---|
| **Device** vs **Entity** | A Device is a physical product on a protocol network. An Entity is the smallest addressable unit of state or control within it — one light channel, one relay, one sensor. Automations bind to Entities, never Devices. |
| **Capability / Attribute / Command** | A typed contract describing what an Entity can do; a named piece of state inside it; a named *intent* to change it. Commands are requests that produce events — never direct mutations. |
| **`*_ref` (ULID)** | The only identifiers used for machine binding. Immutable, opaque, time-ordered. Slugs and paths are for display and are never binding keys. |
| **`global_position` / `subject_sequence`** | Cross-subject total order (SQLite rowid) and a per-subject monotonic counter. The latter's uniqueness constraint gives optimistic concurrency. |
| **`correlation_id` / `causation_id`** | The whole causal conversation, and the single immediately-preceding event. Together they make the chain reconstructable. |
| **`EventOrigin`** | Evidence-based enum (`PHYSICAL`, `USER_COMMAND`, `AUTOMATION`, …). Defaults to `UNKNOWN`; the system never infers origin heuristically. |
| **Projection / checkpoint** | A derived read model, and the saved log position it was built to. |
| **Adoption** | Detection → proposal → adoption. Discovery does *not* create identity; until adoption a device carries only hardware identifiers. |
| **Run** | One execution of an automation, trigger through completion. Carries a status and a `RunCausalChain` of ancestors. |
| **Pending command ledger** | The subscriber that correlates `command_issued` against later state reports and emits `state_confirmed` or `command_confirmation_timed_out`. This is what makes "did it actually happen?" answerable. |
| **LTD-nn / INV-xx-nn / AMD-nn** | Locked Technical Decision, Architecture Invariant, ratified design Amendment. Code, tests and ArchUnit rules cite these by identifier; they resolve in the docs repo (see [Documentation](#documentation)). |

---

## Repository map

22 Gradle subprojects: 19 production modules, 2 test-support, 1 spike. Every one
except the spike has a `MODULE_CONTEXT.md` at its root — **read it before
touching the module**; those files are the ground truth for type inventories and
local constraints.

| Group | Modules | Responsibility |
|---|---|---|
| `platform/` | `platform-api`, `platform-systemd` | OS abstraction: health reporting, filesystem locations, ULID identity types. The dependency leaf. |
| `core/` | `event-model`, `value-model`, `device-model`, `state-store`, `persistence`, `event-bus`, `automation` | The event-sourced heart: event envelopes and payloads, the attribute/value hierarchy, device and entity models, the SQLite store and migrations, the in-process bus, the state projection, and the trigger/condition/action engine. |
| `integration/` | `integration-api`, `integration-runtime`, `integration-zigbee` | The adapter contract, the supervisor that hosts adapters with health and restart handling, and the one shipped adapter (Zigbee over EZSP/ASH). |
| `config/` | `configuration` | YAML loading, JSON Schema composition and validation, `!include` / `!env` / `!secret` tags, hot reload. |
| `api/` | `rest-api`, `websocket-api` | The HTTP surface — auth, rate limiting, readiness gating, RFC 9457 problem responses, endpoints. `websocket-api` is scaffold (see [Current state](#current-state)). |
| `observability/` | `observability` | Health aggregation, trace queries, metrics. Interfaces only today. |
| `web-ui/` | `dashboard` | Preact + TypeScript + Vite SPA, packaged as a resources-only jar and served from the classpath. |
| `lifecycle/` | `lifecycle` | The composition root. Ordered 7-phase startup, the health/watchdog loop, graceful shutdown. |
| `app/` | `homesynapse-app` | `com.homesynapse.app.Main`, dependency wiring, logging backend, and the ArchUnit rules (the only module that sees the whole graph). |
| `testing/` | `test-support`, `integration-tests` | Shared fixtures (`TestClock`, `SynchronousEventBus`, `EventCollector`); the Pi-constrained suite, off by default. |
| `spike/` | `wal-validation` | Throwaway benchmarks. Never promoted into production code. |

Also at the root: `build-logic/` (Gradle convention plugins), `distribution/`
(jlink image, `.deb`, installer, systemd unit, smoke harness), `docs/`,
`specs/`, `scripts/`.

---

## Runtime

### Directories and configuration

The runtime base directory is `$HOMESYNAPSE_HOME`, falling back to
`<working dir>/.homesynapse`. Under the packaged systemd service it is
`/var/lib/homesynapse`.

```
$HOMESYNAPSE_HOME/
├── config/
│   ├── homesynapse.yaml          # the root config document (optional)
│   ├── integrations/             # the only legal !include target
│   ├── schemas/config.schema.json # regenerated for IDE tooling; a cache, not authority
│   ├── home_id                   # minted on first boot
│   ├── initial_api_token         # the one-time pairing token, first boot only
│   └── api_tokens                # token records; the token itself only as a SHA-256 hash
└── data/
    ├── homesynapse-events.db     # the event log (SQLite, WAL)
    └── zigbee/
```

**There is no example config in the repo, and that is deliberate.** An absent,
empty, or comment-only `homesynapse.yaml` is valid — schema defaults produce a
complete model, and the Zigbee adapter is documented to run with zero
configuration. Top-level sections are `schema_version`, `automation`, and
`integrations`.

### HTTP surface

Base URL `http://127.0.0.1:7070`.

| Method | Path |
|---|---|
| GET | `/` → 302 `/dashboard/` |
| GET | `/dashboard/**` — the SPA, with fallback to `index.html` |
| GET | `/api/v1/entities`, `/api/v1/entities/{id}`, `/api/v1/entities/{id}/state` |
| POST | `/api/v1/entities/{id}/commands` → 202 Accepted |
| GET | `/api/v1/commands/{commandId}` |
| GET | `/api/v1/runs`, `/api/v1/runs/{runId}/causal-chain` |
| GET | `/api/v1/automations`, `/api/v1/automations/{id}/non-firing` |
| GET | `/internal/dlq`, `/internal/projection` |

Auth is an opaque bearer token checked against a local file-backed store.
Missing or malformed header → 401; invalid token → 403; over the rate limit
(300/min, burst 50) → 429. Errors are `application/problem+json` with a
correlation id.

**Exactly one auth exemption exists**: `GET`/`HEAD` on `/`, `/dashboard`, and
`/dashboard/**` — the inert static shell. Path-traversal rejection runs *before*
the exemption is evaluated, and that ordering is a security property with tests
pinning it. No data route is ever unauthenticated.

### Known runtime constraints

- **Bind address and port are fixed at `127.0.0.1:7070`.** `Main` hard-wires
  `HomeSynapseConfig.HOME_DEFAULT`, and nothing reads a bind host or port from
  config, env, or system properties. The `HOMESYNAPSE_BIND_HOST` and
  `HOMESYNAPSE_HTTP_PORT` keys in `distribution/systemd/homesynapse.env.example`
  are inert placeholders for that future wiring.
- **There is no unauthenticated health endpoint.** The smoke harness
  authenticates against `GET /api/v1/entities` instead.
- The packaged systemd unit ships with `PrivateDevices=yes`, so **the installed
  service cannot see a USB dongle** until that is relaxed. See the comments in
  `distribution/systemd/homesynapse.service`.

---

## Current state

The foundation is built and hardware-proven; the surfaces above it are uneven.
Read this before assuming a subsystem exists.

**Working end to end, demonstrated on real hardware.** Event store, event bus,
state and registry projections, configuration, the automation engine (runs,
conditions, actions, dispatch, the pending-command ledger), the explainability
read projection, the Zigbee adapter, the 7-phase lifecycle, and one-command
install.

Certification closed 13–14 July 2026 on a two-device Wave-1 bench (a Hue light
and a SNZB-03P motion sensor): a 72-hour soak that ran ~84 h unbroken, plus the
bench acceptance run and scenario suite, for a cumulative ~1,728 recorded
verdicts with zero false confirmations — holding identity across restarts,
`kill -9`, and Zigbee network re-formation. The bench has since grown to six
devices across five confirmed device classes.

**Built and landed, not yet demonstrated live.** The dashboard serves and
authenticates, and the causal-chain read API is verified healthy on the wire —
fresh runs return populated causal chains. What is not yet demonstrated is the
SPA rendering one: a null field in the chain view throws during render, and the
throw takes the polling loop down with it. The cause is identified and the fix
is the first queued frontend item — null-guards, an error boundary, and an
honest empty state.

**Scaffold only — interfaces, no implementation, not wired.**

- `api/websocket-api` — no WebSocket route is registered anywhere. "No WebSocket
  runtime in V1" is a firm decision; the dashboard polls REST at 1–2 s instead.
- `observability/observability` — health aggregation, trace query, metrics
  registry and log-level control are all interfaces. Startup reports a
  structural HEALTHY.
- Telemetry ring-buffer storage — types exist, no implementation.

**Not built.**

- `GET /api/v1/events` and `GET /api/v1/health` are in the frozen dashboard
  contract but return 404. The SPA degrades honestly.
- `specs/openapi/` and `specs/asyncapi/` are empty.
- Automation Tier-2 features throw `UnsupportedOperationException`
  (`ActivateSceneAction`, `InvokeIntegrationAction`, `ParallelAction`,
  `ZoneCondition` — and `zone` is rejected at load time). Time, sun, presence,
  calendar and webhook triggers evaluate to `false`; today only `state_change`,
  `state`, `availability`, `numeric_threshold`, `event`, `reachability` and
  `manual` triggers fire.
- Zigbee is the only protocol integration. Z-Wave and Matter appear in design
  documents; no code exists.
- Tamper-evidence is designed but inert — every event's `chain_hash` is
  currently 32 zero bytes and the audit projection defaults off.

**Scope.** V1 targets a curated device set, not scale: 50-device validation,
federation, the enterprise audit projection, component-authoring UX and full
observability are all deliberately deferred. The launch target is 25 Nov 2026,
with a readiness gate in mid-August 2026.

---

## Working on the code

### The gates

| Gate | Command | Runs in CI as |
|---|---|---|
| Core | `./gradlew check` | `ci.yml` — pushes to `main`/`develop` and PRs to `main`. **The gate of record.** |
| Frontend | `npm run verify` (in `web-ui/dashboard`) | `frontend.yml`, path-filtered to `web-ui/dashboard/**` |
| Install | `distribution/image/build-image.sh` → `.deb` → smoke | `install-smoke.yml`, path-filtered to `distribution/`, `app/`, `lifecycle/`, `api/`, `gradle/`, `build-logic/` |

A local green run is not the verdict. **CI on the pushed commit is.**

`check` covers Spotless (license header, unused imports, whitespace) and the 14
ArchUnit rules in `app/homesynapse-app`, which enforce the invariants that keep
this codebase honest — among them: no `synchronized` methods, no direct time
access, no reverse dependencies, no `ServiceLoader`, no direct filesystem access
in core, no Jackson in the domain model, read-only query services, no event
publishing from REST endpoints outside the sanctioned write surface, and registry
mutation only via projection. The root `build.gradle.kts` additionally asserts
the module dependency graph — allowed and restricted edges, maximum depth 8.

The Pi-constrained integration suite is off by default:

```bash
./gradlew :testing:integration-tests:test -PpiProfile=throttled
```

### Conventions that will fail your build if you miss them

- **`-Xlint:all -Werror`** everywhere. Every warning is a build failure, which
  in practice means explicit constructors on all non-record classes.
- **No `synchronized`** — use `ReentrantLock`. ArchUnit catches synchronized
  *methods*; synchronized *blocks* are invisible to it and rest on convention.
- **No direct time access** — inject `Clock`. `Instant.now()`,
  `System.currentTimeMillis()`, `System.nanoTime()`, `Clock.systemUTC()` and
  `Clock.systemDefaultZone()` are all rejected outside `app` and `platform`.
- **ULIDs for identity**, stored as `BLOB(16)`.
- **All sqlite-jdbc calls go through bounded platform-thread executors.**
- Implementation classes are package-private by default.
- Spotless enforces this exact header on every Java file (`$YEAR` is substituted):

  ```java
  /*
   * HomeSynapse Core
   * Copyright (c) $YEAR NexSys. All rights reserved.
   */
  ```

Run `./gradlew spotlessApply` before committing.

### How work reaches the repo

This project is developed by a small set of agents under a strict protocol, and
the repo's `CLAUDE.md` reflects it: **agents produce files; Nick reviews,
compiles, and commits.** Design documents are locked before code is written;
implementation follows a written coding instruction with a pre-verification pass;
tests are red-first with named mutation kills; every touched module's
`MODULE_CONTEXT.md` is updated as part of closing a work unit; and each commit
names its exact file paths and count so drift is visible before it lands.

If you are joining as a human developer, the short version is: read the module's
`MODULE_CONTEXT.md` first, treat every count and status claim in any document as
something to re-derive from source, and don't declare a gate closed that you did
not watch go green.

---

## Documentation

| Where | What |
|---|---|
| `docs/ARCHITECTURE.md` | Architecture overview and dependency direction |
| `docs/TESTING.md` | Test tiers and conventions (partly stale — verify names at source) |
| `docs/decisions/` | ADRs |
| `*/MODULE_CONTEXT.md` | Per-module ground truth. The most current documentation in the repo. |
| [`homesynapse-core-docs`](https://github.com/nexsys-io/homesynapse-core-docs) | The 18 locked design documents, the glossary, the Locked Decisions register (`LTD-nn`), the Architecture Invariants register (`INV-xx-nn`), and the amendment register (`AMD-nn`). **Authoritative for all architectural constraints.** |

---

## License

Proprietary. Copyright (c) 2026 NexSys. All rights reserved. See [LICENSE](LICENSE).

> A move to Apache 2.0 for the core is a ratified strategic decision but has
> **not** landed in this repository. Until the `LICENSE` file changes, this
> repository is proprietary. The consumer-facing product name is also under
> trademark review; the Java namespace `com.homesynapse.*` is unaffected either
> way.

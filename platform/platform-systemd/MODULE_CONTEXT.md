# platform-systemd — `com.homesynapse.platform.systemd` — Scaffold — sd_notify integration, watchdog heartbeat, LinuxSystemPaths

## Design Doc Reference
<!-- Link to the governing design document in homesynapse-core-docs -->

## Dependencies
<!-- Which modules this module depends on and why -->

## Consumers
<!-- Which modules depend on this module -->

## Constraints
<!-- Locked decisions, invariants, and rules that apply to this module -->

## Gotchas
<!-- Non-obvious implementation details, known quirks, things to watch for -->


---

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2). Phase 3 implementation is active — M2.5 `SqliteEventStore` landed 2026-04-11 (commit `5279e7a`), next milestone M2.6 + M2.7 (combined) pending from Nick.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **Scaffold only** — *no Java source yet*: this module remains a scaffold in Phase 3; no MODULE_CONTEXT implementation notes beyond placeholder until a systemd adapter is planned

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for recent Phase 3 pattern discoveries (especially the 2026-04-10 entries on `NO_DIRECT_TIME_ACCESS` and JUnit 5 `@BeforeEach` ordering).

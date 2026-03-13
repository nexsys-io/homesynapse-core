# HomeSynapse Core — Project Context

This file orients developers and AI agents working in this repository.

## What Is This?

HomeSynapse Core is the on-device runtime for HomeSynapse, a local-first smart
home operating system. It runs on Raspberry Pi hardware (Pi 4 floor, Pi 5
recommended) and provides event-sourced device management, automation, and a
local web dashboard — all without requiring a cloud connection.

## Key Architectural Decisions

- **Java 21 LTS** on Amazon Corretto, targeting constrained hardware (4 GB RAM)
- **Event-sourced** with an append-only SQLite event store (WAL mode)
- **Multi-module Gradle project** (19 modules) with strict dependency direction
- **Local-first**: all functionality works offline; cloud is optional
- **Package namespace**: `com.homesynapse.*`
- **GroupId**: `com.homesynapse`

## Repository Layout

- `build-logic/` — Gradle convention plugins (shared build config)
- `platform/` — OS abstraction layer
- `core/` — Event model, device model, state store, persistence, event bus, automation
- `integration/` — Protocol adapter API + implementations (Zigbee in MVP)
- `config/` — YAML configuration loading and validation
- `api/` — REST and WebSocket APIs
- `observability/` — Health aggregation, JFR, traces
- `web-ui/` — Preact dashboard
- `lifecycle/` — Startup/shutdown orchestration
- `app/` — Application assembly (main class, jlink)
- `testing/` — Shared test infrastructure
- `docs/` — Architecture docs, ADRs, traceability maps
- `specs/` — OpenAPI and AsyncAPI specifications
- `spike/` — Throwaway spike code

## Governance

Design documents and locked decisions live in the companion `homesynapse-core-docs`
repository. The locked decisions register (`HomeSynapse_Core_Locked_Decisions.md`)
is the authoritative source for all architectural constraints.

## Build

```bash
./gradlew check    # compile + test + Spotless + architecture checks
./gradlew build    # full build including JARs
```

No Gradle or JDK installation required beyond `git clone` — the Gradle wrapper
downloads the correct Gradle version, and the Java toolchain downloads the
correct JDK.

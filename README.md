# HomeSynapse Core

The on-device runtime for HomeSynapse — a local-first, event-sourced smart home
operating system built for Raspberry Pi hardware.

## Prerequisites

- **JDK 21** (Amazon Corretto recommended; Gradle will auto-download via toolchain if missing)
- **Git**

No Gradle installation required — the wrapper (`./gradlew`) handles it.

## Build

```bash
./gradlew build
```

## Run Tests

```bash
./gradlew check
```

## Project Structure

This is a multi-module Gradle project. See `settings.gradle.kts` for the full
module list and `docs/ARCHITECTURE.md` for the architecture overview.

| Directory       | Purpose                                         |
|-----------------|-------------------------------------------------|
| `platform/`     | OS abstraction (health, paths)                  |
| `core/`         | Event model, device model, state, persistence   |
| `integration/`  | Protocol adapters (Zigbee, future Z-Wave, etc.) |
| `config/`       | YAML configuration and schema validation        |
| `api/`          | REST and WebSocket APIs                         |
| `observability/`| Health, JFR events, trace queries               |
| `web-ui/`       | Preact dashboard (static frontend)              |
| `lifecycle/`    | Startup/shutdown orchestration, watchdog        |
| `app/`          | Application assembly and entry point            |
| `testing/`      | Shared test infrastructure                      |

## Documentation

Design documents and governance live in the companion
[homesynapse-core-docs](https://github.com/YOUR_ORG/homesynapse-core-docs)
repository.

## License

Proprietary. Copyright (c) 2026 NexSys. All rights reserved.
See [LICENSE](LICENSE) for details.

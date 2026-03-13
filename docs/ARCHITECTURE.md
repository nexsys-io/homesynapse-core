# Architecture Overview

HomeSynapse Core is an event-sourced, local-first smart home runtime designed
for constrained hardware (Raspberry Pi 4/5).

## Module Dependency Direction

Dependencies flow inward: integration and API modules depend on core modules,
never the reverse. This is enforced by `modules-graph-assert` at build time
and ArchUnit at test time.

```
app
 └── lifecycle, api, integration, observability, web-ui
      └── core (event-model, device-model, state-store, persistence, event-bus, automation)
           └── platform-api
```

## Key Patterns

- **Event sourcing**: all state changes are captured as immutable events in an
  append-only SQLite store. Current state is derived by projecting events.
- **CQRS**: commands go through the event model; queries go through the state store.
- **Virtual threads** (Java 21): the in-process event bus dispatches subscribers
  on virtual threads, avoiding the complexity of an external message broker.
- **Protocol abstraction**: the integration-api module defines adapter contracts.
  Protocol-specific code (Zigbee, future Z-Wave) lives in isolated modules that
  depend only on integration-api.

## Design Documents

The full design documents live in the companion `homesynapse-core-docs` repository:

| Doc | Subsystem                  |
|-----|----------------------------|
| 01  | Event Model                |
| 02  | Device Model               |
| 03  | State Store                |
| 04  | Persistence                |
| 05  | Integration Runtime        |
| 06  | Configuration              |
| 07  | Automation Engine          |
| 08  | Zigbee Adapter             |
| 09  | REST API                   |
| 10  | WebSocket API              |
| 11  | Observability              |
| 12  | Lifecycle                  |
| 13  | Web UI                     |
| 14  | Master Architecture        |

## Locked Decisions

See `homesynapse-core-docs/governance/HomeSynapse_Core_Locked_Decisions.md` for
the authoritative register of all architectural constraints.

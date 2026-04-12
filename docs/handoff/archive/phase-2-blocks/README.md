# Phase 2 Block Handoffs — Archive

This directory contains the 17 Phase 2 block handoff documents (blocks B through S) used during the interface-specification phase of HomeSynapse Core development. These documents directed the Coder agent during Phase 2 and are preserved here as historical engineering artifacts.

**Status:** Archived 2026-04-11. Phase 2 was declared complete on 2026-03-20; all blocks referenced here landed before that date. `TEMPLATE.md` remains in `docs/handoff/` as the reference template for future handoff-style work.

## Canonical Record

For the authoritative Phase 2 execution record — block status, commit SHAs, subsystem coverage, and the FROZEN status marker — see:

> `nexsys-hivemind/context/planning/phase-2-block-backlog.md`

That file is the source of truth. The individual block handoffs in this directory are the raw instructions the PM issued to the Coder; the backlog is the curated summary.

## Contents

| File | Block | Subsystem |
|---|---|---|
| `block-b-event-envelope.md` | B | Event envelope + core event records |
| `block-d-publisher-store.md` | D | EventPublisher + EventStore interfaces |
| `block-e-event-bus.md` | E | Event bus + subscription model |
| `block-f-platform-api.md` | F | Platform API (identity, ULID, Clock) |
| `block-g-device-model.md` | G | Device + entity + capability model |
| `block-h-state-store.md` | H | State store + materialized views |
| `block-i-integration-api.md` | I | Integration API |
| `block-j-persistence.md` | J | Persistence interfaces |
| `block-k-configuration.md` | K | YAML configuration + secrets |
| `block-l-automation.md` | L | Automation (triggers, conditions, actions) |
| `block-m-rest-api.md` | M | REST API surface |
| `block-n-websocket-api.md` | N | WebSocket API surface |
| `block-o-integration-runtime.md` | O | Integration runtime |
| `block-p-integration-zigbee.md` | P | Zigbee integration |
| `block-q-observability.md` | Q | Observability (tracing, metrics, health) |
| `block-r-lifecycle.md` | R | Lifecycle (startup, shutdown, supervision) |
| `block-s-homesynapse-app.md` | S | App assembly module |

(Block A — scaffold — and Block C — reserved/skipped — have no corresponding handoff files.)

## Related

- `nexsys-hivemind/context/audits/2026-03-20_block-n-bcp-audit.md` — BCP benchmark post-mortem for Block N (first block executed under the Block Completion Protocol)
- `nexsys-hivemind/context/protocols/work-unit-completion-protocol.md` — WUCP, which generalized the original BCP used during Phase 2

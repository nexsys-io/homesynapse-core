# websocket-api

## Purpose

The WebSocket API module is HomeSynapse's real-time event streaming interface — the complement to the stateless REST API (Doc 09). It delivers events to connected clients as they occur, with sub-second latency from persistence to client frame. Where the REST API serves synchronous request-response interactions, the WebSocket API bridges the Event Bus's internal notification model and external browser dashboards, companion apps, and developer tools.

This Phase 2 specification defines the complete WebSocket message protocol (WsMessage sealed hierarchy with 13 subtypes), the three-stage backpressure model (DeliveryMode), subscription filter types, per-connection state records, and 6 service interfaces. The module imports shared authentication types (ApiKeyIdentity, ApiException) from rest-api.

## Design Doc Reference

**Doc 10 — WebSocket API** is the governing design document:
- §3.3: Message protocol — JSON text frames with `type` field discriminator
- §3.4: Subscription management — filter fields, AND/OR semantics, materialized subject resolution
- §3.5: Authentication — shared with REST API's AuthMiddleware, 5-second timeout
- §3.6: Event Relay — single Event Bus subscriber distributing to N clients
- §3.7: Backpressure — three-stage model (NORMAL → BATCHED → COALESCED)
- §3.8: Keepalive — application-level ping/pong with server time sync
- §3.9: Reconnection — admission control, sequential replay serialization
- §3.10: Per-connection rate limiting (not per-IP, not per-API-key)
- §4.1–4.3: Event delivery, state snapshots, subscription state
- §5: Close codes — 4000–4999 application range (RFC 6455 §7.4.2)
- §6.6: Malformed message escalation
- §8.1–8.2: Service interfaces and message type specifications

## JPMS Module

```
module com.homesynapse.api.ws {
    requires transitive com.homesynapse.api.rest;
    exports com.homesynapse.api.ws;
}
```

`requires transitive` (not just `requires`) because `ApiKeyIdentity` from rest-api appears in `WsClientState`'s record components, and `ApiException` appears in `MessageCodec.decode()` and `SubscriptionManager.subscribe()/unsubscribe()` throws clauses. Per Block K JPMS lesson.

## Package Structure

**`com.homesynapse.api.ws`** — Single flat package. 2 enums, 1 filter record, 1 sealed interface + 13 subtypes, 2 state records, 6 service interfaces, module-info.java = 26 Java files.

## Complete Type Inventory

### Enums (2)

| Type | Purpose |
|---|---|
| `DeliveryMode` (3 values) | NORMAL, BATCHED, COALESCED — backpressure delivery strategy |
| `WsCloseCode` (5 values) | AUTH_FAILED(4403), AUTH_TIMEOUT(4408), CLIENT_TOO_SLOW(4429), SUBSCRIPTION_LIMIT(4409), MALFORMED_MESSAGES(4400) |

### Filter Record (1)

| Type | Purpose |
|---|---|
| `WsSubscriptionFilter` (10 nullable fields) | Wire-format subscription filter. 6 list fields, 1 String, 1 Boolean, 2 Integer. AND across fields, OR within arrays. |

### WsMessage Sealed Hierarchy (1 interface + 13 subtypes)

| Type | Direction | Fields |
|---|---|---|
| `WsMessage` | — | Sealed interface, `Integer id()` accessor |
| `AuthenticateMsg` | Client→Server | id (Integer), apiKey (String) |
| `SubscribeMsg` | Client→Server | id, filter, fromGlobalPosition (Long?), includeInitialState (Boolean?) |
| `UnsubscribeMsg` | Client→Server | id, subscriptionId |
| `PingMsg` | Client→Server | id |
| `AuthResultMsg` | Server→Client | id, success, connectionId?, serverTime?, errorType?, errorDetail? |
| `SubscriptionConfirmedMsg` | Server→Client | id, subscriptionId, filter, replayFrom? |
| `EventsMsg` | Server→Client | id (null), subscriptionId, deliveryMode, events (List\<Object\>) |
| `StateSnapshotMsg` | Server→Client | id (null), subscriptionId, viewPosition, entities (List\<Object\>) |
| `DeliveryModeChangedMsg` | Server→Client | id (null), subscriptionId, oldMode, newMode, reason |
| `ErrorMsg` | Server→Client | id?, errorType, detail, fatal |
| `PongMsg` | Server→Client | id, serverTime |
| `SubscriptionEndedMsg` | Server→Client | id (null), subscriptionId, reason, lastGlobalPosition? |
| `ReplayQueuedMsg` | Server→Client | id (null), subscriptionId, positionInQueue, estimatedWaitMs, lastSeenPosition |

### State Records (2)

| Type | Purpose |
|---|---|
| `WsSubscription` (8 fields) | Active subscription state: subscriptionId, connectionId, filter, deliveryMode, replayCursor?, stateChangeOnly, minIntervalMs?, maxIntervalMs? |
| `WsClientState` (7 fields) | Per-connection snapshot: connectionId, apiKeyIdentity (ApiKeyIdentity from rest-api), authenticatedAt, activeSubscriptions (Map), bufferBytes, deliveryMode, malformedMessageCount |

### Service Interfaces (6)

| Type | Purpose | Key Methods |
|---|---|---|
| `WebSocketHandler` | Connection event callbacks | onConnect, onMessage (raw JSON String), onClose, onError |
| `MessageCodec` | JSON↔WsMessage serialization | decode(String) throws ApiException, encode(WsMessage) |
| `ClientConnection` | Per-connection send/close/state | connectionId, state, send, close, isAuthenticated, isOpen |
| `SubscriptionManager` | Subscription lifecycle | subscribe throws ApiException, unsubscribe throws ApiException, subscriptions, removeAll |
| `EventRelay` | Single bus subscriber → N clients | start, stop, addClient, removeClient, currentPosition, connectedClientCount |
| `WebSocketLifecycle` | Startup/shutdown for Doc 12 | start (Phase 5), stop (shutdown step 3) |

## Dependencies

### Phase 2: rest-api only

`com.homesynapse.api.rest` — `ApiKeyIdentity` (WsClientState record component), `ApiException` (MessageCodec and SubscriptionManager throws clauses).

### Phase 3 will add:

event-model, event-bus, state-store, device-model, Jackson.

### Gradle (build.gradle.kts)

```kotlin
api(project(":api:rest-api"))              // Public API types
implementation(project(":core:event-model"))  // Phase 3
implementation(project(":core:event-bus"))     // Phase 3
```

## Consumers

### Current: None
### Planned:
- **lifecycle** — `WebSocketLifecycle` for startup (Phase 5) and shutdown (step 3, before REST)
- **dashboard** — may import WsMessage types
- **observability** — JFR event types

## Cross-Module Contracts

- **Shared authentication:** WebSocket and REST use same AuthMiddleware and ApiKeyIdentity.
- **Shared error model:** ErrorMsg.errorType uses ProblemType slug strings from rest-api.
- **`EventsMsg.events` is `List<Object>`:** Avoids event-model dependency leak. Phase 3 delivers Jackson ObjectNode.
- **Commands NOT accepted over WebSocket (Doc 10 §2.2).** REST only.
- **WebSocket does NOT produce domain events (Doc 10 §2.2).** Logging/JFR only.
- **WebSocket shutdown before REST shutdown (Doc 12 §3.9).**
- **Per-connection rate limiting, not per-IP (Doc 10 §3.10).**

## Constraints

| Constraint | Description |
|---|---|
| LTD-01 | Virtual threads for all connection handling |
| LTD-04 | All wire-boundary IDs are Crockford Base32 strings |
| LTD-08 | Jackson JSON, SNAKE_CASE. Phase 2 uses Object/String to avoid leak |
| LTD-11 | No synchronized — ReentrantLock only |
| INV-ES-04 | Events only after durable persistence |

## Gotchas

**`WsMessage.id()` returns `Integer`, not `int`.** Nullable boxed type for server-initiated messages.

**`EventsMsg.events` is `List<Object>`.** Do not change to `List<EventEnvelope>`.

**`WsSubscriptionFilter` compact constructor null-checks.** Use `field != null ? List.copyOf(field) : null`. `List.copyOf(null)` throws NPE.

**Old scaffold `package-info.java` at `com.homesynapse.api.websocket`.** Different package from new code (`com.homesynapse.api.ws`). Should be deleted when possible. Benign for compilation.

**`requires transitive` not `requires` for rest-api.** Handoff said `requires` but compiler needs `requires transitive` per Block K JPMS lesson.

**`ErrorMsg.errorType` is String, not ProblemType enum.** Wire format uses slug strings.

## Phase 3 Notes

- Event Relay: single EventBus subscriber, checkpoint in checkpoint table, replay from EventStore on client virtual thread
- MessageCodec: Jackson ObjectMapper with SNAKE_CASE, `type` field discriminator
- Backpressure: three buffer thresholds, per-subscription DeliveryMode state machine
- Authentication: call AuthMiddleware.authenticate() from rest-api, 5s timeout
- Filter resolution: areaRefs/labelRefs/entityTypes/capabilities → materialized subject refs via EntityRegistry/CapabilityRegistry, cached, max 500
- Replay admission: max 1 concurrent replay (FIFO queue), ReplayQueuedMsg, live events during wait
- Shutdown: SubscriptionEndedMsg "server_shutting_down", drain, close 1001
- Testing: integration tests for message lifecycle, unit tests for MessageCodec round-trips, backpressure tests with slow consumers

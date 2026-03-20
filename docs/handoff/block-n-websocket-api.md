# Block N — WebSocket API

**Module:** `api/websocket-api`
**Package:** `com.homesynapse.api.ws`
**Design Doc:** Doc 10 — WebSocket API (§3, §4, §5, §8) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :api:websocket-api:compileJava`

---

## Strategic Context

The WebSocket API is the streaming complement to the REST API (Doc 09, Block M). Where the REST API serves synchronous request-response interactions — state queries, command dispatch, event history retrieval — the WebSocket API delivers events to connected clients as they occur, with sub-second latency from persistence to client frame. It bridges the Event Bus's internal notification model and external browser dashboards, companion apps, and developer tools.

This subsystem exists because polling the REST API for state updates is wasteful on constrained hardware and introduces latency that undermines the explainability battlefield. When a user asks "why did the porch light turn on?", the answer must appear in the trace viewer as the causal chain unfolds — not seconds later on the next poll cycle.

The WebSocket API introduces HomeSynapse's most complex sealed type hierarchy: `WsMessage`, a sealed interface with 13 permitted record subtypes representing every client-to-server and server-to-client message type. It also introduces the three-stage backpressure model (`NORMAL → BATCHED → COALESCED`) with explicit client notification, and the subscription filter model with materialized subject resolution.

**Critical architectural distinction from Block M:** Unlike the REST API module (which has zero cross-module dependencies in Phase 2), the WebSocket API's Phase 2 type signatures directly reference types from three other modules: `rest-api` (shared authentication types, error model), `event-model` (EventPriority for filter specification, EventEnvelope fields in Javadoc), and `event-bus` (no direct type references in Phase 2 public signatures — the EventRelay is Phase 3 internal). However, careful analysis of what actually appears in Phase 2 type *signatures* — not Phase 3 implementation — reveals that the cross-module dependencies are narrower than they first appear. See the Cross-Module Type Dependencies section for the precise analysis.

## Scope

**IN:** All public-facing interfaces, sealed type hierarchies, records, and enums from Doc 10 §8 that define the WebSocket API's internal architecture and shared contracts. The `WsMessage` sealed hierarchy with all 13 subtypes. `DeliveryMode` and `WsCloseCode` enums. `WsSubscription` and `WsClientState` records. `WebSocketLifecycle`, `WebSocketHandler`, `EventRelay`, `ClientConnection`, `SubscriptionManager`, and `MessageCodec` interfaces. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references. `module-info.java` with correct exports and requires.

**OUT:** Implementation code. Tests. Javalin WebSocket upgrade handler setup. Jackson message serialization/deserialization logic. Event Relay bus subscriber registration and checkpoint management. Per-client filter evaluation. Backpressure buffer accounting and stage transitions. Keepalive timer scheduling. Idle timeout enforcement. Authentication timeout enforcement. Replay queue admission control logic. Rate limit sliding window counter implementation. Connection resource tracking. WebSocket ping/pong frame handling. Close code transmission. State snapshot query assembly. Coalescing interval timer management. Event deduplication logic. Virtual thread dispatch. Structured logging. JFR metrics events. AsyncAPI 3.0 specification. CORS configuration. TLS termination. HTML fragment delivery (Web UI's concern, Doc 13).

---

## Locked Decisions

1. **The WebSocket module imports shared types from rest-api (Block M).** The REST API already defines `AuthMiddleware`, `ApiKeyIdentity`, `ProblemDetail`, `ProblemType`, and `FieldError`. The WebSocket API uses the *same* authentication infrastructure — `AuthMiddleware.authenticate()` is called with the API key from the `authenticate` WebSocket message (Doc 10 §3.5). `ProblemType` enum values are reused for WebSocket error types (e.g., `authentication-required`, `forbidden`, `rate-limited`, `invalid-parameters`) per Doc 10 §3.3. Do NOT duplicate these types — import them from `com.homesynapse.api.rest`. This requires `requires com.homesynapse.api.rest` in module-info.

2. **`WsMessage` is a sealed interface with 13 permitted record subtypes.** Doc 10 §8.2 specifies the full message hierarchy. Client-to-server: `AuthenticateMsg`, `SubscribeMsg`, `UnsubscribeMsg`, `PingMsg` (4 types). Server-to-client: `AuthResultMsg`, `SubscriptionConfirmedMsg`, `EventsMsg`, `StateSnapshotMsg`, `DeliveryModeChangedMsg`, `ErrorMsg`, `PongMsg`, `SubscriptionEndedMsg`, `ReplayQueuedMsg` (9 types). All subtypes are records implementing `WsMessage`. The sealed hierarchy enables exhaustive pattern matching in the message dispatcher (Phase 3). Every message carries an `id` field (Integer, nullable — null for server-initiated messages).

3. **`WsMessage.id` is `Integer` (nullable boxed type), not `int`.** Server-initiated messages (`EventsMsg`, `DeliveryModeChangedMsg`, `SubscriptionEndedMsg`, `ReplayQueuedMsg`, `PongMsg`) carry `id: null` per Doc 10 §3.3. Client-to-server messages carry a client-assigned non-null integer for request-response correlation. Using boxed `Integer` with Javadoc-documented nullability (nullable for server-initiated, non-null for client-initiated) avoids introducing a sentinel value like `-1`.

4. **`DeliveryMode` is a NEW enum in the websocket-api module.** Values: `NORMAL`, `BATCHED`, `COALESCED` (Doc 10 §3.7). This is distinct from any event-model concept — it describes the client-facing delivery strategy, not an internal processing mode. It lives in `com.homesynapse.api.ws`, not in event-model.

5. **`WsCloseCode` is a NEW enum in the websocket-api module.** Values: `AUTH_FAILED(4403)`, `AUTH_TIMEOUT(4408)`, `CLIENT_TOO_SLOW(4429)`, `SUBSCRIPTION_LIMIT(4409)`, `MALFORMED_MESSAGES(4400)` (Doc 10 §3.5, §3.7, §3.8, §6.6). Each value carries an `int code` field. These are application-specific WebSocket close codes in the 4000–4999 range reserved for applications (RFC 6455 §7.4.2).

6. **`SubscribeMsg.filter` is a record (`WsSubscriptionFilter`), not a Map.** The subscription filter (Doc 10 §3.4) has typed fields: `eventTypes` (List\<String\>), `subjectRefs` (List\<String\>), `areaRefs` (List\<String\>), `labelRefs` (List\<String\>), `entityTypes` (List\<String\>), `capabilities` (List\<String\>), `minPriority` (String, nullable), `stateChangeOnly` (Boolean, nullable), `minIntervalMs` (Integer, nullable), `maxIntervalMs` (Integer, nullable). All fields are nullable — an empty filter receives all events. **All ID fields are `String` at the wire boundary** (same principle as Block M Locked Decision 1). Phase 3 resolves these to typed IDs and materialized subject ref sets. The `minPriority` field is a String (e.g., `"NORMAL"`, `"DIAGNOSTIC"`) — Phase 3 validates and converts to `EventPriority`.

7. **`WsSubscription` uses `String` for subscriptionId and connectionId.** Internal operational identifiers follow `"sub_" + ULID` and `"ws_" + ULID` format (Doc 10 §8.2). These are NOT typed ULID wrappers — they are subsystem-internal operational identifiers that are not registered in the Identity and Addressing Model.

8. **`EventsMsg.events` is `List<Object>`, not `List<EventEnvelope>`.** This is the same principle as Block M's `ApiRequest.body` being `Object` instead of `JsonNode`. The WebSocket API's Phase 2 types avoid importing event-model types into the message protocol's type signatures. Phase 3 delivers `List<Map<String, Object>>` or Jackson `ArrayNode` instances representing serialized event envelopes. The Javadoc documents the expected runtime type. This avoids a `requires transitive com.homesynapse.event` that would force every consumer of `WsMessage` to depend on the event-model.

9. **`StateSnapshotMsg.entities` is `List<Object>` for the same reason.** Phase 3 delivers a list of entity state maps (Doc 10 §4.2). The Phase 2 type signature uses `Object` to avoid leaking domain types into the wire protocol type hierarchy.

10. **The WebSocket module needs `requires com.homesynapse.api.rest` only.** Analysis of Phase 2 type signatures:
    - `WsClientState` holds `ApiKeyIdentity` (from rest-api) → requires rest-api
    - `ErrorMsg` references `ProblemType` concept, but the `errorType` field is `String` at the wire level (Doc 10 §3.3 shows `"error_type": "forbidden"`, not a ProblemType enum) → no additional require
    - No Phase 2 type signature directly imports from event-model or event-bus — those are Phase 3 implementation imports (EventRelay registers with EventBus, reads from EventStore)
    - `WsSubscriptionFilter.minPriority` is `String`, not `EventPriority` → no event-model require

    Therefore: `module com.homesynapse.api.ws { requires com.homesynapse.api.rest; exports com.homesynapse.api.ws; }`. The rest-api's own transitive dependencies (none in Phase 2) do not propagate additional requires.

11. **No cross-module updates.** Block N does not modify any files in other modules. The WebSocket API is a consumer of rest-api types but does not contribute types that rest-api or other modules embed in their records. The `build.gradle.kts` needs updates to add the rest-api dependency (see Build Configuration section).

12. **`WsClientState` is a record, not a mutable class.** Despite tracking mutable state conceptually (buffer metrics, rate limit counters), the Phase 2 type definition captures the state snapshot contract. Phase 3 may use a mutable class internally, but the public type signature is a record for consistency with the rest of the codebase. Fields that are inherently mutable state (e.g., `bufferBytes`, `rateLimitCounters`) are captured as point-in-time snapshots.

13. **`ClientConnection` and `SubscriptionManager` are interfaces, not classes.** Doc 10 §8.1 lists them as subsystem-internal, but they define testable contracts. Phase 2 defines the interface signatures; Phase 3 implements them. `MessageCodec` is also an interface.

14. **`WebSocketHandler` callback methods use Java standard types.** The `onMessage` callback receives `String` (the raw JSON text frame), not a `WsMessage`. Deserialization from JSON to `WsMessage` subtypes happens inside the handler (Phase 3) using `MessageCodec`. This keeps the handler interface independent of Jackson.

15. **`build.gradle.kts` needs `api(project(":api:rest-api"))` added.** The existing scaffold has `implementation(project(":core:event-model"))` and `implementation(project(":core:event-bus"))`. Add `api(project(":api:rest-api"))` because rest-api types (`ApiKeyIdentity`) appear in public API signatures (`WsClientState`). The event-model and event-bus dependencies remain as `implementation` — they are Phase 3 internal imports, not Phase 2 public API surface. **Do NOT change existing dependencies to `api`** — they are correctly scoped for Phase 3.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Enums (no dependencies)

| File | Type | Notes |
|------|------|-------|
| `DeliveryMode.java` | enum (3 values) | Doc 10 §3.7: `NORMAL` (events delivered individually as they arrive, buffer below threshold), `BATCHED` (events accumulated for up to `batch_window_ms` and delivered in multi-event messages, buffer > `batched_threshold_kb`), `COALESCED` (coalescable DIAGNOSTIC events deduplicated per `(subject_ref, attribute_key)` tuple, buffer > `coalesced_threshold_kb`; CRITICAL and NORMAL events still delivered individually). Javadoc: documents each stage, the transition triggers (buffer size thresholds), and the recovery transition (buffer drains below `recovery_threshold_kb`). References Doc 10 §3.7 and the three coalescable event types from Doc 01 §3.6: `state_reported`, `presence_signal`, `telemetry_summary`. |
| `WsCloseCode.java` | enum (5 values) | Doc 10 §3.5, §3.7, §3.8, §6.6: `AUTH_FAILED(4403)` — invalid API key (aligned with HTTP 403), `AUTH_TIMEOUT(4408)` — no authenticate message within timeout, `CLIENT_TOO_SLOW(4429)` — send buffer exceeded hard ceiling (aligned with HTTP 429), `SUBSCRIPTION_LIMIT(4409)` — attempted to exceed max subscriptions per connection, `MALFORMED_MESSAGES(4400)` — consecutive malformed messages exceeded threshold. Single field: `code` (int). Javadoc: application-specific WebSocket close codes in the 4000–4999 range (RFC 6455 §7.4.2). Each value documents the condition that triggers it and the client's expected recovery behavior. |

### Group 2: Subscription Filter Record (no dependencies)

| File | Type | Notes |
|------|------|-------|
| `WsSubscriptionFilter.java` | record (10 fields) | Doc 10 §3.4: `eventTypes` (List\<String\>, **@Nullable** — event type names to match; null or empty = all types), `subjectRefs` (List\<String\>, **@Nullable** — ULID strings of specific subjects to match), `areaRefs` (List\<String\>, **@Nullable** — ULID strings of areas; resolved to subject refs at subscription creation), `labelRefs` (List\<String\>, **@Nullable** — ULID strings of labels; resolved like areaRefs), `entityTypes` (List\<String\>, **@Nullable** — entity type names like `"light"`, `"sensor"`), `capabilities` (List\<String\>, **@Nullable** — capability names like `"on_off"`, `"temperature_measurement"`), `minPriority` (String, **@Nullable** — `"CRITICAL"`, `"NORMAL"`, or `"DIAGNOSTIC"`; null = all priorities), `stateChangeOnly` (Boolean, **@Nullable** — suppress redundant `state_reported` events; null = false), `minIntervalMs` (Integer, **@Nullable** — coalescing floor in milliseconds), `maxIntervalMs` (Integer, **@Nullable** — coalescing ceiling in milliseconds). Javadoc: wire-format subscription filter as received from the client. All fields are nullable — an empty/null filter receives all events. Fields combine with AND semantics across fields and OR semantics within array fields. Phase 3 resolves `areaRefs`, `labelRefs`, `entityTypes`, and `capabilities` to a materialized set of subject ref ULIDs at subscription creation time (cached, not dynamically updated per Glossary §1.5 label resolution determinism). If the resolved set exceeds `max_resolved_subjects` (default 500), the subscription is rejected with `filter-too-broad`. Compact constructor: make all non-null lists unmodifiable via `List.copyOf()`. |

### Group 3: WsMessage Sealed Hierarchy (no dependencies beyond Group 1)

| File | Type | Notes |
|------|------|-------|
| `WsMessage.java` | sealed interface | Doc 10 §8.2. Root of the WebSocket message type hierarchy. Single accessor method: `Integer id()` — client-assigned correlation integer for request-response pairing; null for server-initiated messages. Permits: `AuthenticateMsg`, `SubscribeMsg`, `UnsubscribeMsg`, `PingMsg`, `AuthResultMsg`, `SubscriptionConfirmedMsg`, `EventsMsg`, `StateSnapshotMsg`, `DeliveryModeChangedMsg`, `ErrorMsg`, `PongMsg`, `SubscriptionEndedMsg`, `ReplayQueuedMsg`. Javadoc: all WebSocket communication uses JSON text frames (LTD-08). Every message follows a common envelope with `id` and `type` fields. The `type` field discriminates subtypes during deserialization (Phase 3 MessageCodec responsibility). Thread-safe (all subtypes are immutable records). |

**Client-to-server messages (4 types):**

| File | Type | Notes |
|------|------|-------|
| `AuthenticateMsg.java` | record implements WsMessage | Doc 10 §3.5: `id` (Integer, non-null — client-assigned), `apiKey` (String, non-null — the raw API key value for validation). Javadoc: MUST be the first message after WebSocket connection establishment. The server validates the API key against the same key store used by the REST API's AuthMiddleware. On success: `AuthResultMsg` with `success: true`. On failure: `AuthResultMsg` with `success: false` followed by connection close with `WsCloseCode.AUTH_FAILED`. If not received within the authentication timeout (default 5s), the server closes with `WsCloseCode.AUTH_TIMEOUT`. The raw key value is validated against bcrypt hashes — it is NEVER stored or logged. Compact constructor: both fields non-null. |
| `SubscribeMsg.java` | record implements WsMessage | Doc 10 §3.4: `id` (Integer, non-null), `filter` (WsSubscriptionFilter, non-null — the subscription filter specification), `fromGlobalPosition` (Long, **@Nullable** — resume from this event store position; null = live-only), `includeInitialState` (Boolean, **@Nullable** — deliver state snapshot before events; null = false). Javadoc: creates a new subscription on the connection. Each connection may maintain at most `max_subscriptions_per_connection` (default 10) concurrent subscriptions. When `fromGlobalPosition` is present, the server replays events from the EventStore before switching to live tailing (replay/live transition at Doc 01 §9 `replay_to_live_threshold`). When `includeInitialState` is true, a `StateSnapshotMsg` is delivered before event streaming begins. Compact constructor: `id` and `filter` non-null. |
| `UnsubscribeMsg.java` | record implements WsMessage | Doc 10 §3.4: `id` (Integer, non-null), `subscriptionId` (String, non-null — the `subscription_id` returned by `SubscriptionConfirmedMsg`). Javadoc: removes an active subscription from the connection. After removal, no further events are delivered for that subscription. If the subscription does not exist, the server responds with `ErrorMsg` (type `invalid-parameters`, fatal: false). Compact constructor: both fields non-null. |
| `PingMsg.java` | record implements WsMessage | Doc 10 §3.8: `id` (Integer, non-null). Javadoc: client-initiated keepalive. The server responds with `PongMsg`. This is an application-level ping (JSON message), distinct from the WebSocket protocol-level ping/pong frames that the server sends for connection liveness detection. Application-level ping provides clock synchronization via `serverTime` in the pong response. Rate-limited to `ping_limit` messages per second (default 2). Compact constructor: `id` non-null. |

**Server-to-client messages (9 types):**

| File | Type | Notes |
|------|------|-------|
| `AuthResultMsg.java` | record implements WsMessage | Doc 10 §3.5: `id` (Integer, non-null — echoes the client's `AuthenticateMsg.id`), `success` (boolean), `connectionId` (String, **@Nullable** — `"ws_" + ULID` format; present only when `success` is true), `serverTime` (String, **@Nullable** — ISO 8601 timestamp; present only when `success` is true), `errorType` (String, **@Nullable** — RFC 9457 error type slug from ProblemType, e.g. `"forbidden"`; present only when `success` is false), `errorDetail` (String, **@Nullable** — human-readable error description; present only when `success` is false). Javadoc: authentication outcome. On success, provides the server-assigned `connectionId` used for logging and debugging. On failure, the server closes the connection with `WsCloseCode.AUTH_FAILED` after sending this message. Compact constructor: `id` non-null. |
| `SubscriptionConfirmedMsg.java` | record implements WsMessage | Doc 10 §3.4: `id` (Integer, non-null — echoes the client's `SubscribeMsg.id`), `subscriptionId` (String, non-null — `"sub_" + ULID` format), `filter` (WsSubscriptionFilter, non-null — echoed back), `replayFrom` (Long, **@Nullable** — the `from_global_position` if resuming; null if live-only). Javadoc: confirms subscription creation. The `subscriptionId` is used for `UnsubscribeMsg` and appears on all subsequent `EventsMsg` deliveries for this subscription. Compact constructor: `id`, `subscriptionId`, `filter` non-null. |
| `EventsMsg.java` | record implements WsMessage | Doc 10 §4.1: `id` (Integer, **@Nullable** — null; server-initiated), `subscriptionId` (String, non-null), `deliveryMode` (DeliveryMode, non-null — `NORMAL`, `BATCHED`, or `COALESCED`), `events` (List\<Object\>, non-null — list of serialized event envelope maps; Phase 3 delivers Jackson ObjectNode instances). Javadoc: event delivery message. The `events` list contains one or more serialized EventEnvelope objects. During `NORMAL` delivery, typically contains a single event. During `BATCHED` delivery (§3.7 Stage 2), contains multiple events accumulated within the batch window. During `COALESCED` delivery (§3.7 Stage 3), coalescable DIAGNOSTIC events may carry a `coalesced: true` flag indicating intermediate values were not delivered. All events are ordered by `global_position`. Compact constructor: `subscriptionId` non-null, `deliveryMode` non-null, `events` non-null and unmodifiable via `List.copyOf()`. |
| `StateSnapshotMsg.java` | record implements WsMessage | Doc 10 §4.2: `id` (Integer, **@Nullable** — null; server-initiated), `subscriptionId` (String, non-null), `viewPosition` (long — State Store projection version at snapshot time), `entities` (List\<Object\>, non-null — serialized entity state objects; Phase 3 delivers Map or ObjectNode instances per Doc 10 §4.2 JSON schema). Javadoc: initial state snapshot delivered when `includeInitialState: true` on the subscription. Delivered before event streaming begins. The `viewPosition` indicates the State Store version at snapshot time — event delivery begins from this position (or `fromGlobalPosition` if specified and earlier), ensuring no state transitions are missed. Compact constructor: `subscriptionId` non-null, `entities` non-null and unmodifiable via `List.copyOf()`. |
| `DeliveryModeChangedMsg.java` | record implements WsMessage | Doc 10 §3.7: `id` (Integer, **@Nullable** — null; server-initiated), `subscriptionId` (String, non-null), `oldMode` (DeliveryMode, non-null), `newMode` (DeliveryMode, non-null), `reason` (String, non-null — e.g. `"client_buffer_exceeded"`, `"buffer_drained"`). Javadoc: notification that the delivery mode for this subscription has changed due to backpressure state transitions. The client always knows whether it is receiving the full stream or a degraded view. Transitions: `NORMAL → BATCHED` (buffer > `batched_threshold_kb`), `BATCHED → COALESCED` (buffer > `coalesced_threshold_kb`), recovery back to `NORMAL` when buffer drains below `recovery_threshold_kb`. Compact constructor: `subscriptionId`, `oldMode`, `newMode`, `reason` non-null. |
| `ErrorMsg.java` | record implements WsMessage | Doc 10 §3.3, §6.6: `id` (Integer, **@Nullable** — echoes client message `id` if in response to a client request; null for server-initiated errors), `errorType` (String, non-null — RFC 9457 error type slug, e.g. `"authentication-required"`, `"forbidden"`, `"rate-limited"`, `"invalid-parameters"`, `"subscription-limit-exceeded"`, `"filter-too-broad"`, `"replay-queue-full"`), `detail` (String, non-null — human-readable description), `fatal` (boolean — if true, the server will close the connection after sending this message). Javadoc: protocol-level error. Non-fatal errors allow the connection to continue (the client corrects and retries). Fatal errors are followed by connection closure with an appropriate `WsCloseCode`. Error types follow Doc 09's RFC 9457 pattern for consistency across the API surface. Compact constructor: `errorType` and `detail` non-null. |
| `PongMsg.java` | record implements WsMessage | Doc 10 §3.8: `id` (Integer, non-null — echoes the client's `PingMsg.id`), `serverTime` (String, non-null — ISO 8601 timestamp). Javadoc: response to client-initiated `PingMsg`. Provides the server's current time for client-side clock synchronization. This is an application-level pong (JSON message), distinct from WebSocket protocol-level pong frames. Compact constructor: both fields non-null. |
| `SubscriptionEndedMsg.java` | record implements WsMessage | Doc 10 §3.4, §3.8, §3.9: `id` (Integer, **@Nullable** — null; server-initiated), `subscriptionId` (String, non-null), `reason` (String, non-null — e.g. `"server_shutting_down"`, `"replay_limit_exceeded"`, `"subscription_removed"`), `lastGlobalPosition` (Long, **@Nullable** — the `global_position` where event delivery stopped; present when `reason` is `"replay_limit_exceeded"` so the client can use REST API event history to catch up). Javadoc: server-initiated subscription termination. The client should not expect further events for this subscriptionId. For `replay_limit_exceeded`, the client should use `GET /api/v1/events?after_position={lastGlobalPosition}` to fill the gap via REST, then resubscribe with a closer `fromGlobalPosition`. For `server_shutting_down`, the client should implement reconnection with exponential backoff (initial 1s, max 30s, jitter ±500ms per §3.8). Compact constructor: `subscriptionId` and `reason` non-null. |
| `ReplayQueuedMsg.java` | record implements WsMessage | Doc 10 §3.9: `id` (Integer, **@Nullable** — null; server-initiated), `subscriptionId` (String, non-null), `positionInQueue` (int — FIFO position, 1-indexed), `estimatedWaitMs` (long — best-effort estimate based on average replay throughput), `lastSeenPosition` (long — echoes the client's requested `fromGlobalPosition`, confirming the server registered the replay starting point). Javadoc: sent when a subscription's replay request is queued during post-restart admission control (§3.9). Replays are served sequentially — at most `max_concurrent_replays` (default 1) active at a time. While queued, the client receives LIVE events normally — the subscription is active for new events even before replay fills the gap. The client deduplicates events that arrive via both live and replay streams using `global_position`. Compact constructor: `subscriptionId` non-null. |

### Group 4: Subscription and Client State Records (depends on Groups 1–3)

| File | Type | Notes |
|------|------|-------|
| `WsSubscription.java` | record (8 fields) | Doc 10 §4.3, §8.2: `subscriptionId` (String, non-null — `"sub_" + ULID` format), `connectionId` (String, non-null — `"ws_" + ULID` format), `filter` (WsSubscriptionFilter, non-null — the client-specified filter), `deliveryMode` (DeliveryMode, non-null — current delivery mode for this subscription), `replayCursor` (Long, **@Nullable** — current position in replay; null if not replaying), `stateChangeOnly` (boolean — resolved from filter), `minIntervalMs` (Integer, **@Nullable** — coalescing floor), `maxIntervalMs` (Integer, **@Nullable** — coalescing ceiling). Javadoc: active subscription state. Phase 2 captures the data contract; Phase 3 manages the mutable lifecycle (delivery mode transitions, replay cursor advancement, coalescing timer state). The `subscriptionId` format provides human-readable disambiguation in log entries (Doc 10 §8.2). Compact constructor: `subscriptionId`, `connectionId`, `filter`, `deliveryMode` non-null. |
| `WsClientState.java` | record (7 fields) | Doc 10 §8.2, §3.10: `connectionId` (String, non-null — `"ws_" + ULID` format), `apiKeyIdentity` (ApiKeyIdentity, non-null — imported from `com.homesynapse.api.rest`; the authenticated caller identity), `authenticatedAt` (Instant, non-null — timestamp of successful authentication), `activeSubscriptions` (Map\<String, WsSubscription\>, non-null — keyed by subscriptionId, unmodifiable), `bufferBytes` (long — current send buffer size in bytes for backpressure tracking), `deliveryMode` (DeliveryMode, non-null — connection-level delivery mode), `malformedMessageCount` (int — consecutive malformed messages for §6.6 escalation). Javadoc: per-connection state snapshot. Captures the authenticated identity, active subscriptions, send buffer metrics, and rate limit state at a point in time. The `apiKeyIdentity` is obtained from the REST API's `AuthMiddleware` — WebSocket and REST share the same authentication infrastructure (Doc 10 §3.5). Thread-safe (immutable record). Compact constructor: `connectionId`, `apiKeyIdentity`, `authenticatedAt`, `deliveryMode` non-null; `activeSubscriptions` unmodifiable via `Map.copyOf()`. |

### Group 5: Service Interfaces (depends on Groups 1–4)

| File | Type | Notes |
|------|------|-------|
| `WebSocketHandler.java` | interface | Doc 10 §8.1: Four callback methods: `void onConnect(String connectionId)` — called when a WebSocket connection is established (before authentication), `void onMessage(String connectionId, String message)` — called when a text frame is received; `message` is raw JSON, `void onClose(String connectionId, int closeCode, String reason)` — called when the connection closes (any reason), `void onError(String connectionId, Throwable error)` — called on transport error. Javadoc: handles individual WebSocket connection events. Each connection runs on a virtual thread (LTD-01). The handler is responsible for authentication timeout enforcement, message deserialization (via MessageCodec), and dispatch to the appropriate processing logic. The `onMessage` callback receives raw JSON (String), not a deserialized WsMessage — deserialization is the handler's responsibility (via MessageCodec) to keep this interface transport-agnostic. Thread-safety: one handler instance shared across all connections; methods must be thread-safe. |
| `MessageCodec.java` | interface | Doc 10 §8.1: Two methods: `WsMessage decode(String json) throws ApiException` — deserializes a JSON text frame to the appropriate WsMessage subtype based on the `type` field; throws `ApiException` with `ProblemType.INVALID_PARAMETERS` if the JSON is malformed or the `type` field is unrecognized, `String encode(WsMessage message)` — serializes a WsMessage subtype to a JSON text frame. Javadoc: serializes and deserializes WebSocket messages using the shared Jackson ObjectMapper (LTD-08, SNAKE_CASE). The `decode` method inspects the `type` field to determine which WsMessage record subtype to instantiate. Field names follow the wire format (snake_case per LTD-08), not Java naming (camelCase) — Jackson handles the translation. Thread-safe and stateless. Note: `ApiException` is imported from `com.homesynapse.api.rest`. |
| `ClientConnection.java` | interface | Doc 10 §8.1: Methods: `String connectionId()` — the connection's identifier (`"ws_" + ULID`), `WsClientState state()` — current state snapshot, `void send(WsMessage message)` — enqueue a message for delivery to the client (adds to send buffer; Phase 3 serializes via MessageCodec and writes to the WebSocket), `void close(WsCloseCode closeCode, String reason)` — close the connection with the specified close code after sending a final error message if appropriate, `boolean isAuthenticated()` — whether authentication has completed successfully, `boolean isOpen()` — whether the connection is still open. Javadoc: per-connection abstraction for send buffer management, backpressure tracking, and connection lifecycle. The `send` method enqueues messages — it does not block on client consumption. If the send buffer exceeds the hard ceiling (`hard_ceiling_kb`, default 128 KB), the connection is closed with `WsCloseCode.CLIENT_TOO_SLOW`. Thread-safe. |
| `SubscriptionManager.java` | interface | Doc 10 §8.1: Methods: `WsSubscription subscribe(String connectionId, SubscribeMsg request) throws ApiException` — create a new subscription (resolves filter, enforces subscription limit, initiates replay if requested), `void unsubscribe(String connectionId, String subscriptionId) throws ApiException` — remove a subscription, `List<WsSubscription> subscriptions(String connectionId)` — list active subscriptions for a connection, `void removeAll(String connectionId)` — remove all subscriptions for a connection (called on disconnect). Javadoc: manages subscription lifecycle on a connection. Filter resolution (areaRefs, labelRefs, entityTypes, capabilities → materialized subject ref set) is performed at subscription creation time and cached (Glossary §1.5). Subscription limit: `max_subscriptions_per_connection` (default 10). Throws `ApiException` with `ProblemType.INVALID_PARAMETERS` for invalid filter, or ErrorMsg-specific error types `"subscription-limit-exceeded"` and `"filter-too-broad"`. Thread-safe. Note: `ApiException` is imported from `com.homesynapse.api.rest`. |
| `EventRelay.java` | interface | Doc 10 §3.6, §8.1: Methods: `void start()` — register as a single Event Bus subscriber and begin distributing events to connected clients, `void stop()` — unregister from the Event Bus and stop distribution, `void addClient(ClientConnection client)` — register a client connection for event distribution, `void removeClient(String connectionId)` — unregister a client connection, `long currentPosition()` — the relay's current checkpoint position in the event log, `int connectedClientCount()` — number of currently registered clients. Javadoc: single Event Bus subscriber that distributes events to connected WebSocket clients (Doc 10 §3.6). Maintains one bus subscription with a broad filter (all event types, all priorities). For each event batch received, evaluates it against every active client subscription's materialized filter. Matching events are serialized once per unique filter match and enqueued in each matching client's send buffer. The relay advances its checkpoint after all matched events have been enqueued (not after clients consume them). Replay handling: when a client subscribes with `fromGlobalPosition` behind the relay's checkpoint, the relay reads historical events from EventStore on the client's virtual thread. Thread-safe. |
| `WebSocketLifecycle.java` | interface | Doc 10 §8.1: Two methods: `void start()` — register WebSocket upgrade handler on the shared HTTP server, start the Event Relay, and begin accepting connections, `void stop()` — initiate graceful shutdown: send `SubscriptionEndedMsg` with reason `"server_shutting_down"` to all active subscriptions, drain connections for `shutdown_drain_seconds` (default 5), close all connections with close code 1001 (Going Away), stop the Event Relay. Javadoc: lifecycle interface consumed by the Startup, Lifecycle & Shutdown module (Doc 12). The `start()` method is called during system initialization after the Event Bus, REST API HTTP server, and State Store are ready (Doc 12 Phase 5 startup sequence). The `stop()` method is called during shutdown step 3 (Doc 12 §3.9) — before the REST API shutdown, because WebSocket connections should drain before HTTP connections. Thread-safe. |

### Group 6: Module Descriptor and Build Configuration

| File | Notes |
|------|-------|
| `module-info.java` | `module com.homesynapse.api.ws { requires com.homesynapse.api.rest; exports com.homesynapse.api.ws; }`. Single `requires` directive for rest-api. Phase 3 will add `requires com.homesynapse.event` (for EventEnvelope, EventStore), `requires com.homesynapse.event.bus` (for EventBus, SubscriberInfo, SubscriptionFilter, CheckpointStore), `requires com.homesynapse.state` (for StateQueryService), `requires com.homesynapse.device` (for EntityRegistry, CapabilityRegistry), and `requires com.fasterxml.jackson.databind` (for ObjectMapper, JsonNode). |

**Build configuration update (before compiling):**

Add the rest-api dependency to `api/websocket-api/build.gradle.kts`:

```kotlin
dependencies {
    api(project(":api:rest-api"))  // ApiKeyIdentity, ApiException, ProblemType in public signatures
    implementation(project(":core:event-model"))
    implementation(project(":core:event-bus"))
}
```

The `api()` configuration (not `implementation()`) is required because `ApiKeyIdentity` appears in `WsClientState`'s public signature and `ApiException` appears in `MessageCodec` and `SubscriptionManager` throws clauses. Any downstream module consuming `com.homesynapse.api.ws` types needs transitive access to `com.homesynapse.api.rest`.

---

## File Placement

All WebSocket API types go in: `api/websocket-api/src/main/java/com/homesynapse/api/ws/`
Module info: `api/websocket-api/src/main/java/module-info.java` (create new)

Delete the existing `package-info.java` scaffold at `api/websocket-api/src/main/java/com/homesynapse/api/ws/package-info.java` if it exists. If no scaffold exists (current state: no source files), no deletion is needed.

---

## Cross-Module Type Dependencies

**Phase 2 imports from `com.homesynapse.api.rest`:**
- `ApiKeyIdentity` — used in `WsClientState.apiKeyIdentity` field
- `ApiException` — thrown by `MessageCodec.decode()` and `SubscriptionManager.subscribe()/unsubscribe()`

**Phase 2: NO imports from event-model or event-bus.** All event-related types in the message protocol use `Object` (for `EventsMsg.events`, `StateSnapshotMsg.entities`) or `String` (for `WsSubscriptionFilter.minPriority`, `ErrorMsg.errorType`) at the wire boundary. The `WsSubscriptionFilter` uses `String` for priority, `List<String>` for subject refs, and primitive types for intervals — no `EventPriority`, `SubjectRef`, or other event-model types in Phase 2 signatures.

**Phase 3 will add imports for:**
- `com.homesynapse.event` — EventEnvelope, EventStore, EventPublisher, EventPriority, SubjectRef, EventTypes
- `com.homesynapse.event.bus` — EventBus, SubscriberInfo, SubscriptionFilter, CheckpointStore
- `com.homesynapse.state` — StateQueryService (for initial state snapshots)
- `com.homesynapse.device` — EntityRegistry, CapabilityRegistry (for filter resolution)
- `com.fasterxml.jackson.databind` — ObjectMapper, ObjectNode, JsonNode (for message serialization)

**Exported to (downstream consumers):**
- `com.homesynapse.lifecycle` (lifecycle module) — WebSocketLifecycle (start/stop during system initialization and shutdown)
- `web-ui/dashboard` (if it imports WS message types for type-safe event handling)
- Future: `com.homesynapse.observability` — JFR event types referencing WS types (if needed)

---

## Javadoc Standards

Per Sprint 1–2 lessons (Blocks A–M), plus WebSocket-specific requirements:

1. Every `@param` documents nullability (`never {@code null}` or `{@code null} if...`)
2. Every type has `@see` cross-references to related types within the module
3. Thread-safety explicitly stated on all interfaces ("Thread-safe" or "Not thread-safe")
4. Class-level Javadoc explains the "why" — what role this type plays in the WebSocket API architecture
5. Reference Doc 10 sections in class-level Javadoc (e.g., `@see <a href="...">Doc 10 §3.7 Backpressure</a>`)
6. `WsMessage` Javadoc: document the sealed hierarchy and exhaustive pattern matching for message dispatch
7. Each `WsMessage` subtype: document the wire format `type` string (e.g., `"authenticate"`, `"subscribe"`, `"events"`)
8. `DeliveryMode` Javadoc: document each stage's trigger condition, buffer threshold, and coalescable event types
9. `WsCloseCode` Javadoc: document each close code's trigger, the RFC 6455 close code range, and recovery guidance
10. `EventRelay` Javadoc: document the single-subscriber architecture rationale (avoids N redundant EventStore reads)
11. `WebSocketLifecycle` Javadoc: document startup dependencies (Event Bus, HTTP server, State Store must be ready)
12. `WsClientState` Javadoc: document that `apiKeyIdentity` comes from the REST API's AuthMiddleware
13. `EventsMsg` Javadoc: document that `events` contains serialized EventEnvelope maps (not typed `EventEnvelope` objects), why this is `Object` (avoid leaking event-model dependency), and the three delivery mode behaviors
14. `WsSubscriptionFilter` Javadoc: document AND/OR filter semantics, resolution cost control (max 500 subjects), and one-time resolution at subscription creation

---

## Key Design Details for Javadoc Accuracy

1. **The WebSocket API is an external subscriber, not a separate event system (Doc 10 §1).** It does not introduce a second delivery path or consistency model. Events are delivered only after durable persistence (INV-ES-04).

2. **Commands are NOT accepted over WebSocket (Doc 10 §2.2).** Commands flow through `POST /api/v1/entities/{id}/commands` (REST API) only. This keeps the command lifecycle — validation, EventPublisher.publishRoot(), four-phase tracking — in a single code path. WebSocket-based command dispatch is deferred to Tier 3 (Doc 10 §14).

3. **Subscription filters are evaluated once at creation time (Doc 10 §3.4, §5).** Filters referencing areas, labels, entity types, or capabilities are resolved to a materialized set of subject ref ULIDs. The set does not update dynamically — entities added after subscription creation are not included (Glossary §1.5 label resolution determinism).

4. **Coalescing never drops CRITICAL or NORMAL events (Doc 10 §5, Doc 01 §3.6 D10).** Only three DIAGNOSTIC event types are coalescable: `state_reported`, `presence_signal`, `telemetry_summary`. Coalescing happens at the `(subject_ref, attribute_key)` granularity — only the most recent value per tuple is retained.

5. **The Event Relay is a single Event Bus subscriber (Doc 10 §3.6).** It reads each event once and distributes in-memory. This avoids N redundant EventStore reads for N connected clients. The relay's checkpoint is stored in the Event Bus's checkpoint table.

6. **Authentication uses the REST API's AuthMiddleware (Doc 10 §3.5).** The WebSocket module does not implement its own authentication — it calls `AuthMiddleware.authenticate()` with the API key from the `authenticate` message. Both REST and WebSocket share the same key store.

7. **Reconnection admission control serializes replays (Doc 10 §3.9).** After server restart, at most `max_concurrent_replays` (default 1) replay streams are active at a time. Clients receive `ReplayQueuedMsg` with queue position while waiting. Live events are delivered normally during queue wait — the client deduplicates by `global_position`.

8. **Connection closure always includes a close code and reason (Doc 10 §5).** Close codes in 4000–4999 carry HomeSynapse-specific semantics. The client never encounters a silent disconnection without diagnostic information.

9. **The WebSocket API does NOT produce domain events (Doc 10 §2.2).** Connection lifecycle and subscription management are captured through structured logging and JFR metrics, not through the event log.

10. **Per-connection rate limiting, not per-IP or per-API-key (Doc 10 §3.10).** Each connection is independently rate-limited. Per-IP limiting is inappropriate for a LAN system where all clients share the same subnet.

---

## Constraints

1. **Java 21** — use records, sealed interfaces, enums as appropriate
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **Only import from `com.homesynapse.api.rest`** in Phase 2 — `ApiKeyIdentity` and `ApiException` only
5. **Javadoc on every public type, method, and constructor**
6. **All types go in `com.homesynapse.api.ws` package** within api/websocket-api module
7. **Do NOT create implementations** — interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files in other modules** — no cross-module updates in Block N
10. **Collections in records must be unmodifiable** — use `Map.copyOf()`, `List.copyOf()` in compact constructors
11. **Do NOT reference Javalin, Jackson, or event-model types** in any Phase 2 type signature (use `Object` for serialized data, `String` for wire-format identifiers)
12. **`WsMessage` must be sealed** with exactly 13 permitted record subtypes — no more, no fewer
13. **Update `build.gradle.kts`** before writing any Java files — add `api(project(":api:rest-api"))` dependency

---

## Compile Gate

```bash
./gradlew :api:websocket-api:compileJava
```

Must pass with `-Xlint:all -Werror`. Then run full project gate:

```bash
./gradlew compileJava
```

All modules must still compile (no regressions from module-info changes or build.gradle.kts updates).

**Common pitfalls:**
- The `module-info.java` has `requires com.homesynapse.api.rest`. If the rest-api module's Phase 2 is not complete, the compiler will fail. Block M MUST be complete before Block N compiles.
- `ApiKeyIdentity` import requires that `com.homesynapse.api.rest` exports its package — verify rest-api's module-info contains `exports com.homesynapse.api.rest`.
- `WsMessage` is a sealed interface — all 13 permitted subtypes MUST exist in the same module and package, or the sealed `permits` clause will fail to compile.
- Sealed interface `permits` clause requires all permitted types to be listed explicitly: `sealed interface WsMessage permits AuthenticateMsg, SubscribeMsg, ...`. Do NOT rely on implicit permits (same-file compilation unit) — HomeSynapse uses explicit permits for documentation clarity.
- `WsMessage.id()` returns `Integer` (boxed), not `int`. Records with nullable boxed fields need the field type declared as `Integer id`, not `int id`.
- `EventsMsg.events` is `List<Object>` — this compiles cleanly but may generate unchecked warnings if incorrectly cast at call sites. Phase 2 does not have call sites — only the type signature.
- The `build.gradle.kts` update from `implementation` to `api` for rest-api is critical — without it, `ApiKeyIdentity` won't be visible to downstream consumers of websocket-api in Phase 3.
- `WsSubscriptionFilter` has 10 nullable fields. The compact constructor must only call `List.copyOf()` on non-null list fields — calling `List.copyOf(null)` throws NPE. Use a conditional pattern: `this.eventTypes = eventTypes != null ? List.copyOf(eventTypes) : null;`
- The `ApiException` import is from `com.homesynapse.api.rest`, not a local class. Verify the import resolves correctly through the JPMS requires directive.
- Unused imports: do NOT import `ProblemType`, `ProblemDetail`, or `FieldError` unless they appear in a Phase 2 type signature. They are referenced in Javadoc `{@link}` and `@see` tags which do not require imports. Use fully-qualified references in Javadoc: `{@link com.homesynapse.api.rest.ProblemType}`.

---

## Execution Order

1. Update `build.gradle.kts` — add `api(project(":api:rest-api"))`
2. Create `DeliveryMode.java`
3. Create `WsCloseCode.java`
4. Create `WsSubscriptionFilter.java`
5. Create `WsMessage.java`
6. Create `AuthenticateMsg.java`
7. Create `SubscribeMsg.java`
8. Create `UnsubscribeMsg.java`
9. Create `PingMsg.java`
10. Create `AuthResultMsg.java`
11. Create `SubscriptionConfirmedMsg.java`
12. Create `EventsMsg.java`
13. Create `StateSnapshotMsg.java`
14. Create `DeliveryModeChangedMsg.java`
15. Create `ErrorMsg.java`
16. Create `PongMsg.java`
17. Create `SubscriptionEndedMsg.java`
18. Create `ReplayQueuedMsg.java`
19. Create `WsSubscription.java`
20. Create `WsClientState.java`
21. Create `WebSocketHandler.java`
22. Create `MessageCodec.java`
23. Create `ClientConnection.java`
24. Create `SubscriptionManager.java`
25. Create `EventRelay.java`
26. Create `WebSocketLifecycle.java`
27. Create `module-info.java`
28. Compile gate: `./gradlew :api:websocket-api:compileJava`
29. Full compile gate: `./gradlew compileJava`

---

## Summary of New Files

| File | Module | Kind | Components/Methods |
|------|--------|------|--------------------|
| `DeliveryMode.java` | api/websocket-api | enum (3 values) | NORMAL, BATCHED, COALESCED |
| `WsCloseCode.java` | api/websocket-api | enum (5 values) | AUTH_FAILED(4403), AUTH_TIMEOUT(4408), CLIENT_TOO_SLOW(4429), SUBSCRIPTION_LIMIT(4409), MALFORMED_MESSAGES(4400) |
| `WsSubscriptionFilter.java` | api/websocket-api | record (10 fields) | eventTypes, subjectRefs, areaRefs, labelRefs, entityTypes, capabilities, minPriority, stateChangeOnly, minIntervalMs, maxIntervalMs |
| `WsMessage.java` | api/websocket-api | sealed interface | id(); permits 13 subtypes |
| `AuthenticateMsg.java` | api/websocket-api | record implements WsMessage | id, apiKey |
| `SubscribeMsg.java` | api/websocket-api | record implements WsMessage | id, filter, fromGlobalPosition, includeInitialState |
| `UnsubscribeMsg.java` | api/websocket-api | record implements WsMessage | id, subscriptionId |
| `PingMsg.java` | api/websocket-api | record implements WsMessage | id |
| `AuthResultMsg.java` | api/websocket-api | record implements WsMessage | id, success, connectionId, serverTime, errorType, errorDetail |
| `SubscriptionConfirmedMsg.java` | api/websocket-api | record implements WsMessage | id, subscriptionId, filter, replayFrom |
| `EventsMsg.java` | api/websocket-api | record implements WsMessage | id, subscriptionId, deliveryMode, events |
| `StateSnapshotMsg.java` | api/websocket-api | record implements WsMessage | id, subscriptionId, viewPosition, entities |
| `DeliveryModeChangedMsg.java` | api/websocket-api | record implements WsMessage | id, subscriptionId, oldMode, newMode, reason |
| `ErrorMsg.java` | api/websocket-api | record implements WsMessage | id, errorType, detail, fatal |
| `PongMsg.java` | api/websocket-api | record implements WsMessage | id, serverTime |
| `SubscriptionEndedMsg.java` | api/websocket-api | record implements WsMessage | id, subscriptionId, reason, lastGlobalPosition |
| `ReplayQueuedMsg.java` | api/websocket-api | record implements WsMessage | id, subscriptionId, positionInQueue, estimatedWaitMs, lastSeenPosition |
| `WsSubscription.java` | api/websocket-api | record (8 fields) | subscriptionId, connectionId, filter, deliveryMode, replayCursor, stateChangeOnly, minIntervalMs, maxIntervalMs |
| `WsClientState.java` | api/websocket-api | record (7 fields) | connectionId, apiKeyIdentity, authenticatedAt, activeSubscriptions, bufferBytes, deliveryMode, malformedMessageCount |
| `WebSocketHandler.java` | api/websocket-api | interface | onConnect(), onMessage(), onClose(), onError() |
| `MessageCodec.java` | api/websocket-api | interface | decode(String), encode(WsMessage) |
| `ClientConnection.java` | api/websocket-api | interface | connectionId(), state(), send(), close(), isAuthenticated(), isOpen() |
| `SubscriptionManager.java` | api/websocket-api | interface | subscribe(), unsubscribe(), subscriptions(), removeAll() |
| `EventRelay.java` | api/websocket-api | interface | start(), stop(), addClient(), removeClient(), currentPosition(), connectedClientCount() |
| `WebSocketLifecycle.java` | api/websocket-api | interface | start(), stop() |
| `module-info.java` | api/websocket-api | module descriptor | requires com.homesynapse.api.rest; exports com.homesynapse.api.ws |

**Modified files (1):**

| File | Module | Change |
|------|--------|--------|
| `build.gradle.kts` | api/websocket-api | Add `api(project(":api:rest-api"))` dependency |

**Total: 26 new files + 1 modified = 27 file operations.**

---

## Estimated Size

~25 types + module-info, approximately 1000–1400 lines. This is a large block — the largest sealed hierarchy in the project (13 subtypes). The primary complexity is in the `WsMessage` sealed interface and its 13 record subtypes (each with detailed Javadoc documenting wire format, protocol semantics, and Phase 3 runtime behavior). The subscription filter record has 10 nullable fields requiring careful compact constructor null-checking. The interfaces are straightforward. Expect 3–4 hours.

---

## Notes

- **Block M (rest-api) MUST be complete before Block N compiles.** Block N's module-info requires `com.homesynapse.api.rest`. If the rest-api module has not been built, the JPMS requires directive will fail. This is a hard compilation dependency, not just a logical one.
- **The `build.gradle.kts` already has scaffold dependencies** on event-model and event-bus. Leave these as `implementation` — they are Phase 3 dependencies. Add `api(project(":api:rest-api"))` as the only new dependency.
- **`WsMessage` sealed permits clause must list ALL 13 subtypes.** Java's sealed interface requires all permitted subtypes to be accessible. Since all subtypes are in the same package and module, this compiles cleanly. Order the permits clause to match the protocol documentation: client-to-server first, then server-to-client.
- **`WsSubscriptionFilter` compact constructor conditional null-check.** For each nullable list field, use: `this.fieldName = fieldName != null ? List.copyOf(fieldName) : null;`. Do NOT call `List.copyOf()` on null — it throws NPE.
- **`EventsMsg.events` as `List<Object>` follows the same rationale as Block M's `ApiRequest.body` as `Object`.** This prevents leaking event-model or Jackson types into the wire protocol type hierarchy. Phase 3 delivers typed instances at runtime.
- **No `SubscriptionErrorMsg` type.** Doc 10 §3.3 shows `subscription_error` as a server-to-client message type, but it is functionally equivalent to `ErrorMsg` with subscription-specific error types (`subscription-limit-exceeded`, `filter-too-broad`). Use `ErrorMsg` for all protocol-level errors. The `subscription_error` wire type can be mapped to `ErrorMsg` in the Phase 3 MessageCodec.
- **The `WsCloseCode` values align with HTTP status semantics.** 4403 ↔ 403 Forbidden, 4408 ↔ 408 Request Timeout, 4429 ↔ 429 Too Many Requests, 4409 ↔ 409 Conflict, 4400 ↔ 400 Bad Request. This alignment is intentional (Doc 10 §3.5).
- **`SubscriptionConfirmedMsg` echoes the `filter` back.** This confirms to the client what filter was registered. Phase 3 may enrich the echo with resolved metadata (e.g., resolved subject count).
- **The lifecycle module (Doc 12) will depend on `WebSocketLifecycle`.** Similar to `RestApiLifecycle` from Block M, the lifecycle module calls `WebSocketLifecycle.start()` during startup and `WebSocketLifecycle.stop()` during shutdown. WebSocket shutdown happens before REST API shutdown per Doc 12 §3.9.
- **No MODULE_CONTEXT.md population in this block.** MODULE_CONTEXT.md is populated after the block is complete, as a follow-up task.

---

## Context Delta (post-completion)

**Files created (26):**
- `DeliveryMode.java` — enum (3 values: NORMAL, BATCHED, COALESCED)
- `WsCloseCode.java` — enum (5 values: AUTH_FAILED through MALFORMED_MESSAGES)
- `WsSubscriptionFilter.java` — record (10 fields, all nullable)
- `WsMessage.java` — sealed interface (13 permitted subtypes)
- `AuthenticateMsg.java` — record implements WsMessage (2 fields)
- `SubscribeMsg.java` — record implements WsMessage (4 fields)
- `UnsubscribeMsg.java` — record implements WsMessage (2 fields)
- `PingMsg.java` — record implements WsMessage (1 field)
- `AuthResultMsg.java` — record implements WsMessage (6 fields)
- `SubscriptionConfirmedMsg.java` — record implements WsMessage (4 fields)
- `EventsMsg.java` — record implements WsMessage (4 fields)
- `StateSnapshotMsg.java` — record implements WsMessage (4 fields)
- `DeliveryModeChangedMsg.java` — record implements WsMessage (5 fields)
- `ErrorMsg.java` — record implements WsMessage (4 fields)
- `PongMsg.java` — record implements WsMessage (2 fields)
- `SubscriptionEndedMsg.java` — record implements WsMessage (4 fields)
- `ReplayQueuedMsg.java` — record implements WsMessage (5 fields)
- `WsSubscription.java` — record (8 fields)
- `WsClientState.java` — record (7 fields, imports ApiKeyIdentity from rest-api)
- `WebSocketHandler.java` — interface (4 methods)
- `MessageCodec.java` — interface (2 methods)
- `ClientConnection.java` — interface (6 methods)
- `SubscriptionManager.java` — interface (4 methods)
- `EventRelay.java` — interface (6 methods)
- `WebSocketLifecycle.java` — interface (2 methods)
- `module-info.java` — `requires com.homesynapse.api.rest; exports com.homesynapse.api.ws`

**Files modified (1):**
- `build.gradle.kts` — added `api(project(":api:rest-api"))`

**Decisions made during execution:**
- {Coder fills in after block compiles}

**What the next block needs to know:**
- The WebSocket API module requires `com.homesynapse.api.rest` in Phase 2. Any module that depends on websocket-api types will transitively get access to rest-api types (because of the `api()` configuration in build.gradle.kts).
- `WsMessage` is the largest sealed hierarchy in the project (13 subtypes). Pattern matching on WsMessage is exhaustive — all 13 subtypes must be handled.
- `WsClientState.apiKeyIdentity` is an `ApiKeyIdentity` record from rest-api — this creates a compile-time dependency chain: websocket-api → rest-api.
- `DeliveryMode` is a websocket-api enum, NOT an event-model or event-bus type. It is websocket-specific.
- No other module needs to add `requires com.homesynapse.api.ws` in Phase 2 (the consumers are lifecycle and web-ui, both not yet specified for their Phase 2).
- MODULE_CONTEXT.md should be populated after this block completes.

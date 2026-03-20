# Block M — REST API

**Module:** `api/rest-api`
**Package:** `com.homesynapse.api.rest`
**Design Doc:** Doc 09 — REST API (§3, §4, §8) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :api:rest-api:compileJava`

---

## Strategic Context

The REST API is HomeSynapse's primary external interface — the translation layer between the event-sourced internals and HTTP clients. Every interaction from the Web UI, the future Companion App, third-party integrations, and the NexSys cloud relay passes through this module. It is a stateless translation layer: every response is assembled from queries against internal interfaces (StateQueryService, EntityRegistry, EventStore, AutomationRegistry, PendingCommandLedger), and the API holds zero persistent state.

The REST API introduces HomeSynapse's strongest competitive differentiator at the API surface: four-phase command lifecycle visibility. No existing smart home platform exposes the full `accepted → dispatched → acknowledged → confirmed` lifecycle through its API. The event-sourced architecture produces these events naturally; this module makes them accessible via standard HTTP semantics.

This block defines **public API infrastructure types** — the request/response records, service interfaces, pagination contracts, authentication types, RFC 9457 error model, and ETag/caching contracts that the endpoint handlers (Phase 3) and the WebSocket API module (Block N) compile against. The actual endpoint handlers that query internal subsystem interfaces are Phase 3. This block defines the architecture of the HTTP layer itself.

The REST API module has the broadest dependency footprint of any module in HomeSynapse — it reads from 8 internal subsystem interfaces. However, for Phase 2 interface specification, **none of these dependencies appear in the module's own type signatures**. The REST API types use Java standard library types exclusively (Strings for IDs, Maps for parameters, Instants for timestamps). The subsystem dependencies are Phase 3 imports used by endpoint handler implementations.

## Scope

**IN:** All public-facing interfaces, records, enums, and exception types from Doc 09 §8 that define the REST API's internal architecture and shared contracts with the WebSocket API module. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references. `module-info.java` with correct exports. RFC 9457 Problem Detail types with the full problem type registry from §3.8. Four-phase command lifecycle types from §3.4 and §4.5. Cursor-based pagination types from §3.5. Authentication types from §12.1. Rate limiting interface from §12.5. ETag computation interface from §3.7. Idempotency entry record from §3.4 (AMD-08).

**OUT:** Implementation code. Tests. Javalin HTTP server setup. Jackson serialization configuration. Route registration. Request parsing and dispatch. Endpoint handler implementations (entity queries, command issuance, event history, automation reads, system health). SQLite queries. API key generation, storage, or bcrypt hashing. Rate limiter token bucket implementation. Cursor Base64 encoding/decoding logic. ETag computation algorithms. CORS middleware. TLS termination. Request body size enforcement. OpenAPI 3.1 specification generation. Structured logging. JFR metrics events. JSON response shapes from §4 (EntityResponse, DeviceResponse, etc. — these are assembled by Phase 3 handlers from domain types, not dedicated API records). Webhook subscription types (§14 — Tier 2).

---

## Locked Decisions

1. **All API-boundary IDs are `String`, not typed ID wrappers.** The REST API serializes ULIDs as 26-character Crockford Base32 strings at the wire boundary (Doc 09 §3.10, LTD-04). Phase 2 types use `String` for all identifier fields (`entityId`, `deviceId`, `commandId`, `correlationId`, `apiKeyId`, etc.). Phase 3 endpoint handlers perform `String` ↔ typed ID conversion (e.g., `EntityId.of(string)`) when calling internal subsystem interfaces. This keeps the REST API types independent of the `com.homesynapse.platform` module in Phase 2.

2. **`ApiRequest.body` is `Object`, not `JsonNode`.** Phase 2 avoids leaking Jackson types (`com.fasterxml.jackson.databind.JsonNode`) into the module's public API signatures. The `body` field is typed as `@Nullable Object` with Javadoc documenting that Phase 3 implementation delivers a `JsonNode` instance. This avoids a `requires transitive com.fasterxml.jackson.databind` declaration that would force every consumer of ApiRequest to depend on Jackson. Phase 3 endpoint handlers cast to `JsonNode` as documented.

3. **`ProblemType` is an enum, not a constants class.** The 13 RFC 9457 problem types from §3.8 are represented as a Java enum with three derived properties: `typeUri()` (the full URI under `https://homesynapse.local/problems/`), `title()` (human-readable), and `defaultStatus()` (the HTTP status code). The enum provides compile-time type safety for error handling and prevents misspelled type URIs. Future Tier 2 additions extend the enum.

4. **`CommandStatusResponse.lifecycle` uses `Map<CommandLifecyclePhase, LifecyclePhaseDetail>`.** This matches the JSON structure (§4.5) where only completed phases appear as keys in the `lifecycle` object. An absent key means the phase has not yet occurred. `Map` naturally expresses optional phase presence without sentinel values.

5. **`ApiException` carries a `ProblemDetail` for structured error propagation.** This is a `RuntimeException` subclass that allows middleware (auth, rate limiting, validation) to throw structured errors with full RFC 9457 metadata. The error handling layer converts `ApiException` to HTTP responses. `ProblemDetailMapper` handles mapping exceptions from downstream subsystems (HomeSynapseException subtypes, etc.); `ApiException` handles errors generated by the REST API layer itself.

6. **No `requires` directives in module-info for Phase 2.** All Phase 2 types use Java standard library types exclusively (`String`, `int`, `long`, `boolean`, `Instant`, `Map`, `List`, `Optional`, `Set`, `Object`). Dependencies on other HomeSynapse modules and external libraries (Jackson, Javalin) exist in `build.gradle.kts` for Phase 3 but are not declared in `module-info.java` until Phase 3 types actually import from them. This is a unique characteristic of the REST API module — it is a pure consumer, and its own contracts are self-contained.

7. **`RestApiServer` abstracts the HTTP server choice.** Doc 09 §3.9 evaluates three options (JDK HttpServer, Javalin 6.x, Eclipse Jetty 12). Phase 2 defines the `RestApiServer` interface; Phase 3 implements it against the chosen library. No Javalin, Jetty, or `com.sun.net.httpserver` types appear in any Phase 2 type signature. The interface expresses: route registration, server binding, lifecycle control.

8. **`PagedResponse<T>` is a generic record.** Java 21 supports generic records. The generic parameter provides type safety for endpoint handlers — `PagedResponse<EntitySummary>` vs `PagedResponse<EventSummary>` are distinct types at compile time even though the pagination mechanics are shared.

9. **`RateLimiter` returns `RateLimitResult`, not just `boolean`.** The `429 Too Many Requests` response requires a `Retry-After` header with the seconds until the limit resets (§12.5). A boolean alone cannot convey this. The `RateLimitResult` record carries both `allowed` (boolean) and `retryAfterSeconds` (long).

10. **`ETagProvider` methods return `String`.** ETags are serialized as strings in HTTP headers (weak: `W/"42850"`, strong: `"01HV..."`). The provider computes them from various source types (`long` viewPosition, `String` eventId, `String` definitionHash) and returns the correctly formatted ETag string including weak/strong prefix. Formatting belongs in the provider, not the caller.

11. **`CursorToken` includes `SortDirection`.** Cursors must encode the pagination direction to support both ascending (default for entities) and descending (default for events) without ambiguity when decoding a cursor from a subsequent request.

12. **`IdempotencyEntry` is a Phase 2 record.** Although §3.4 describes it as an internal cache entry for the command endpoint, defining the data structure in Phase 2 provides a clear contract for the idempotency cache implementation in Phase 3. It documents the fields that the cache must track per AMD-08.

13. **No cross-module updates.** Unlike Blocks J and K (which updated IntegrationContext in integration-api), Block M does not modify any files in other modules. The REST API is a pure consumer — it reads from other modules' interfaces but does not contribute types that other modules embed in their records.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Enums (no dependencies)

| File | Type | Notes |
|------|------|-------|
| `ProblemType.java` | enum (13 values) | Doc 09 §3.8: `NOT_FOUND` (404), `ENTITY_DISABLED` (409), `INTEGRATION_UNHEALTHY` (503), `INVALID_COMMAND` (422), `INVALID_PARAMETERS` (400), `AUTHENTICATION_REQUIRED` (401), `FORBIDDEN` (403), `RATE_LIMITED` (429), `COMMAND_NOT_FOUND` (404), `STATE_STORE_REPLAYING` (503), `INTERNAL_ERROR` (500), `IDEMPOTENCY_KEY_CONFLICT` (409, AMD-08), `DEVICE_ORPHANED` (503, AMD-17). Three fields per value: `slug` (String, e.g. `"not-found"`), `defaultStatus` (int), `title` (String). Method: `String typeUri()` returning `"https://homesynapse.local/problems/" + slug`. Javadoc: each value documents the condition that triggers it and references the design doc section. |
| `CommandLifecyclePhase.java` | enum (5 values) | Doc 09 §3.4, §4.5: `ACCEPTED`, `DISPATCHED`, `ACKNOWLEDGED`, `CONFIRMED`, `CONFIRMATION_TIMED_OUT`. Javadoc: documents the four-phase command lifecycle. `CONFIRMATION_TIMED_OUT` is the terminal failure phase (a `command_confirmation_timed_out` event exists instead of `state_confirmed`). Phase ordering matches the event chronology. |
| `SortDirection.java` | enum (2 values) | Doc 09 §3.6: `ASC`, `DESC`. Javadoc: pagination sort direction. Entity/device lists default to ASC (ULID order). Event lists default to DESC (newest first). The `sort` query parameter overrides the default. |

### Group 2: Error Model Records (depends on ProblemType)

| File | Type | Notes |
|------|------|-------|
| `FieldError.java` | record (2 fields) | Doc 09 §3.8 validation error detail: `field` (String — JSON path, e.g. `"parameters.level"`), `message` (String — human-readable). Javadoc: per-field validation error included in the `errors` extension array of Problem Detail responses for 400 and 422 status codes. Compact constructor: `Objects.requireNonNull(field)`, `Objects.requireNonNull(message)`. |
| `ProblemDetail.java` | record (7 fields) | Doc 09 §3.8, RFC 9457: `type` (ProblemType), `title` (String), `status` (int), `detail` (String), `instance` (String, **@Nullable** — the request path), `correlationId` (String — AMD-15, always present per §3.8), `errors` (List\<FieldError\>, **@Nullable** — present only for 400/422 validation errors). Javadoc: immutable RFC 9457 Problem Details representation. The `type` field provides the machine-readable error identifier; `typeUri()` on the enum produces the full URI for JSON serialization. The `correlationId` links to structured log entries (§3.11). Compact constructor: `Objects.requireNonNull(type)`, `Objects.requireNonNull(title)`, `Objects.requireNonNull(detail)`, `Objects.requireNonNull(correlationId)`, make `errors` list unmodifiable via `List.copyOf()` if non-null. |

### Group 3: Authentication and Rate Limiting Types (no internal deps)

| File | Type | Notes |
|------|------|-------|
| `ApiKeyIdentity.java` | record (3 fields) | Doc 09 §8.2, §12.1: `keyId` (String — the key's identifier, NEVER the raw key value), `displayName` (String — human-readable name set at creation), `createdAt` (Instant). Javadoc: authenticated caller identity extracted by AuthMiddleware from the `Authorization: Bearer {key}` header. The raw key value is never stored or returned — only the bcrypt hash exists in the key store. `keyId` is used for rate limiting, structured logging, and audit. Thread-safe (immutable record). Compact constructor: all fields non-null. |
| `RateLimitResult.java` | record (2 fields) | Doc 09 §12.5: `allowed` (boolean — true if the request should proceed), `retryAfterSeconds` (long — seconds until the rate limit resets; meaningful only when `allowed` is false). Javadoc: result of a rate limit check against the per-key token bucket. When `allowed` is false, the API returns `429 Too Many Requests` with `Retry-After: {retryAfterSeconds}`. |

### Group 4: Pagination Types (depends on SortDirection)

| File | Type | Notes |
|------|------|-------|
| `CursorToken.java` | record (3 fields) | Doc 09 §3.5, §8.2: `sortValue` (String — the sort key value of the last item on the previous page; for events this is `global_position` as a string, for entities this is `entity_id`), `sortDimension` (String — the field name being sorted on, e.g. `"global_position"` or `"entity_id"`), `direction` (SortDirection). Javadoc: decoded cursor representing a pagination position. Encoded as opaque URL-safe Base64 for wire transport by PaginationCodec. The content is opaque to clients — they pass the encoded string from `next_cursor` to the `cursor` query parameter. Compact constructor: all fields non-null. |
| `PaginationMeta.java` | record (3 fields) | Doc 09 §3.5 response envelope: `nextCursor` (String, **@Nullable** — URL-safe Base64 cursor for the next page; null when `hasMore` is false), `hasMore` (boolean — true if additional pages exist), `limit` (int — the page size used for this response). Javadoc: pagination metadata in paginated API responses. Corresponds to the `pagination` object in the JSON response envelope. |
| `ResponseMeta.java` | record (2 fields) | Doc 09 §3.5 response envelope: `viewPosition` (long — the State Store's current view position at response time; present on State Query plane responses only), `timestamp` (Instant — response generation time). Javadoc: response-level metadata included in paginated and non-paginated State Query plane responses. The `viewPosition` enables staleness detection (§3.7) — clients compare it against a previously returned position to determine whether the projection has caught up. Event History plane responses do not include `viewPosition` (they are strongly consistent from the event store). Compact constructor: `Objects.requireNonNull(timestamp)`. |

### Group 5: Request/Response Infrastructure (depends on Groups 2–4)

| File | Type | Notes |
|------|------|-------|
| `ApiRequest.java` | record (7 fields) | Doc 09 §8.2, §3.3: `method` (String — HTTP method, e.g. `"GET"`, `"POST"`), `pathPattern` (String — route pattern, e.g. `"/api/v1/entities/{entity_id}"`), `pathParams` (Map\<String, String\> — resolved path parameters, unmodifiable), `queryParams` (Map\<String, List\<String\>\> — query parameters supporting repeatable keys, unmodifiable), `body` (Object, **@Nullable** — parsed request body; Phase 3 delivers a Jackson `JsonNode` instance; null for requests without a body), `identity` (ApiKeyIdentity — the authenticated caller; never null, authentication is mandatory per INV-SE-02), `correlationId` (String — request-scoped correlation ID for tracing; either propagated from the `X-Correlation-ID` request header or generated as a ULID per §3.11). Javadoc: parsed and authenticated HTTP request presented to endpoint handlers. All validation (auth, rate limiting, parameter parsing) is complete before the handler receives this record. Thread-safe (immutable). Compact constructor: `Objects.requireNonNull` on method, pathPattern, identity, correlationId; make maps unmodifiable via `Map.copyOf()`. |
| `ApiResponse.java` | record (5 fields) | Doc 09 §8.2, §3.3: `statusCode` (int — HTTP status code), `headers` (Map\<String, String\> — response headers including `Cache-Control`, `ETag`, `X-View-Position`, `X-Correlation-ID`, unmodifiable), `body` (Object, **@Nullable** — response object to be serialized to JSON; null for 204/304 responses), `eTag` (String, **@Nullable** — the ETag value if applicable; included in `headers` but also exposed as a field for middleware access), `cacheControl` (String, **@Nullable** — the Cache-Control directive if applicable). Javadoc: response from an endpoint handler before HTTP serialization. The response is serialized to JSON by the shared Jackson ObjectMapper (LTD-08, SNAKE_CASE). Compact constructor: make `headers` unmodifiable via `Map.copyOf()`. |
| `PagedResponse.java` | record (3 fields, generic) | Doc 09 §8.2, §3.5: `PagedResponse<T>` with `data` (List\<T\> — the page items, unmodifiable), `pagination` (PaginationMeta), `meta` (ResponseMeta, **@Nullable** — present for State Query plane responses, null for Event History plane). Javadoc: paginated response envelope. The generic parameter T is the item type (e.g., entity summary records, event records). Corresponds to the JSON response envelope with `data`, `pagination`, and `meta` top-level objects. Compact constructor: `Objects.requireNonNull(data)`, `Objects.requireNonNull(pagination)`, make data list unmodifiable via `List.copyOf()`. |

### Group 6: Command Types (depends on CommandLifecyclePhase, Groups 2–3)

| File | Type | Notes |
|------|------|-------|
| `CommandRequest.java` | record (3 fields) | Doc 09 §4.3, §8.2: `capability` (String — capability identifier, e.g. `"level_control"`), `command` (String — command name, e.g. `"set_level"`), `parameters` (Map\<String, Object\> — command parameters, unmodifiable). Javadoc: typed command input received by `POST /api/v1/entities/{entity_id}/commands`. All three fields are required. The `capability` and `command` are validated against the entity's declared capabilities via `CommandValidator` (Doc 02 §8.1). The `parameters` map is validated against the command's parameter schema from `CommandDefinition`. Compact constructor: `Objects.requireNonNull` on all three, make parameters map unmodifiable. |
| `CommandAcceptedResponse.java` | record (6 fields) | Doc 09 §4.4: `commandId` (String — the event's `event_id`, used for lifecycle tracking), `correlationId` (String — the event's `correlation_id`, set by `publishRoot()` to the event's own ID per Doc 01 §8.3), `entityId` (String — the target entity), `status` (String — always `"accepted"` for this response), `acceptedAt` (Instant — timestamp of the `command_issued` event), `viewPosition` (long — the event store position at which the command was persisted). Javadoc: the `202 Accepted` response for successful command issuance. "Accepted" means the command is validated and durably persisted — it does NOT mean the device has received or executed the command. Clients track the full lifecycle via `GET /api/v1/commands/{command_id}`. Compact constructor: all fields non-null except none are nullable. |
| `LifecyclePhaseDetail.java` | record (3 fields) | Doc 09 §4.5: `at` (Instant — when this phase completed), `eventId` (String — the event that represents this phase), `details` (Map\<String, Object\>, **@Nullable** — phase-specific details; for `dispatched`: includes `integration_id`; for `acknowledged`: includes `result`; for `confirmed`: includes `match_type`; null for `accepted`). Javadoc: detail record for a single phase in the four-phase command lifecycle. Present in CommandStatusResponse only for phases that have completed. Compact constructor: `Objects.requireNonNull(at)`, `Objects.requireNonNull(eventId)`, make details map unmodifiable if non-null. |
| `CommandStatusResponse.java` | record (7 fields) | Doc 09 §4.5, §8.2: `commandId` (String), `correlationId` (String), `entityId` (String), `capability` (String), `command` (String), `lifecycle` (Map\<CommandLifecyclePhase, LifecyclePhaseDetail\> — only completed phases present, unmodifiable), `currentPhase` (CommandLifecyclePhase — the latest completed phase), `terminal` (boolean — true when the lifecycle is complete: `CONFIRMED`, `CONFIRMATION_TIMED_OUT`, or a rejected/timed-out `ACKNOWLEDGED`). Javadoc: four-phase command lifecycle status assembled from EventStore queries by correlation_id. This visibility is a capability no existing smart home platform API exposes (§3.4). Compact constructor: `Objects.requireNonNull` on all String fields and lifecycle map, make lifecycle unmodifiable. |
| `IdempotencyEntry.java` | record (5 fields) | Doc 09 §3.4 (AMD-08): `idempotencyKey` (String — client-provided, max 128 characters), `commandId` (String — the event's event_id from the original command), `correlationId` (String), `viewPosition` (long), `createdAt` (Instant — for TTL calculation, 24-hour expiry). Javadoc: entry in the in-memory idempotency LRU cache (max 10,000 entries, 24-hour TTL per §9). When a command request includes an `Idempotency-Key` header, the API checks this cache before validation. If the key exists with the same request body, the cached response is replayed without publishing a new event. If the key exists with a DIFFERENT request body, `409 Conflict` with `IDEMPOTENCY_KEY_CONFLICT` is returned. Compact constructor: all fields non-null. |

### Group 7: Exception (depends on ProblemDetail)

| File | Type | Notes |
|------|------|-------|
| `ApiException.java` | exception | Extends `RuntimeException`. Single field: `problemDetail` (ProblemDetail — the structured error to return). Two constructors: `(ProblemDetail problemDetail)` and `(ProblemDetail problemDetail, Throwable cause)`. Getter: `ProblemDetail problemDetail()`. Javadoc: thrown by REST API middleware and validators to signal a structured HTTP error. The error handling layer catches `ApiException` and serializes the contained `ProblemDetail` as `application/problem+json`. This is distinct from `ProblemDetailMapper`, which maps exceptions from downstream subsystems (e.g., `HomeSynapseException` subtypes) to `ProblemDetail` responses. `ApiException` handles errors generated by the REST API layer itself (authentication failures, rate limiting, parameter validation). |

### Group 8: Service Interfaces (depends on Groups 2–7)

| File | Type | Notes |
|------|------|-------|
| `EndpointHandler.java` | interface (functional) | Doc 09 §8.1: `@FunctionalInterface`. Single abstract method: `ApiResponse handle(ApiRequest request) throws ApiException`. Javadoc: functional interface for HTTP request handling. Each endpoint registers one handler. Handlers receive a fully parsed, authenticated `ApiRequest` and return an `ApiResponse` for serialization. Handlers may throw `ApiException` for structured error responses. Handlers run on virtual threads (LTD-01) — each request gets its own virtual thread, so blocking I/O (e.g., SQLite reads via EventStore) is acceptable without explicit async handling. Thread-safety: handlers must be stateless or thread-safe, as concurrent requests invoke the same handler instance. |
| `AuthMiddleware.java` | interface | Doc 09 §8.1, §12.1: Single method: `ApiKeyIdentity authenticate(String authorizationHeader) throws ApiException`. Javadoc: extracts and validates API keys from the `Authorization: Bearer {key}` request header. Returns the authenticated identity on success. Throws `ApiException` with `ProblemType.AUTHENTICATION_REQUIRED` (401) if the header is missing or malformed. Throws `ApiException` with `ProblemType.FORBIDDEN` (403) if the key is invalid, expired, or revoked. Every request must pass through authentication (INV-SE-02). The raw key value is validated against bcrypt hashes in the key store — the raw value is never stored or logged. Thread-safe. |
| `RateLimiter.java` | interface | Doc 09 §8.1, §12.5: Single method: `RateLimitResult check(String apiKeyId)`. Javadoc: per-key token bucket rate limiter. Checks whether a request from the given API key should proceed or be throttled. The token bucket allows short bursts (§9 `burst_size: 50`) while enforcing a sustained rate ceiling (§9 `requests_per_minute: 300`). When `RateLimitResult.allowed()` is false, the API returns `429 Too Many Requests` with `Retry-After: {retryAfterSeconds}`. Phase 3 implements with a `ConcurrentHashMap` of lightweight token buckets (one long for token count, one long for last-refill timestamp per key). Thread-safe. |
| `ETagProvider.java` | interface | Doc 09 §8.1, §3.7: Three methods: `String fromViewPosition(long viewPosition)` — returns weak ETag `W/"{viewPosition}"` for State Query plane responses; `String fromEventId(String eventId)` — returns strong ETag `"{eventId}"` for single immutable event responses; `String fromDefinitionHash(String definitionHash)` — returns weak ETag `W/"{definitionHash}"` for Automation plane responses. Javadoc: computes ETag strings for HTTP conditional request handling. State Query ETags enable `304 Not Modified` when the projection has not advanced (§3.7). Event ETags enable aggressive caching (`Cache-Control: max-age=31536000, immutable`) because events never change (INV-ES-01). Automation ETags enable short-lived caching that invalidates on hot-reload. Thread-safe and stateless. |
| `PaginationCodec.java` | interface | Doc 09 §8.1, §3.5: Two methods: `String encode(CursorToken token)` — encodes a cursor position as a URL-safe Base64 string; `CursorToken decode(String cursor)` — decodes a cursor string back to a CursorToken, throwing `ApiException` with `ProblemType.INVALID_PARAMETERS` if the cursor is malformed, tampered, or expired. Javadoc: encodes and decodes opaque pagination cursors. Cursor content is opaque to clients — they receive a `next_cursor` string from a paginated response and pass it as the `cursor` query parameter on the next request. The cursor contains the sort key value, sort dimension, and direction needed for efficient keyset pagination. Thread-safe and stateless. |
| `ProblemDetailMapper.java` | interface | Doc 09 §8.1, §3.8: Single method: `ProblemDetail map(Exception exception, String correlationId, String requestPath)`. Javadoc: maps internal exceptions from downstream subsystems to RFC 9457 Problem Detail responses. This handles exceptions that are NOT `ApiException` — for example, `HomeSynapseException` subtypes from the event model, `ConcurrentModificationException` from the configuration write path, and unexpected `RuntimeException` instances. The mapper extracts a meaningful `ProblemType`, `status`, and `detail` from the exception. For unrecognized exceptions, it returns `ProblemType.INTERNAL_ERROR` (500) with a generic detail (stack traces are NEVER included in API responses — §12.4). The `correlationId` links the error response to server-side log entries for diagnosis. Thread-safe. |
| `RestApiServer.java` | interface | Doc 09 §8.1, §3.9: Five methods: `void registerRoute(String method, String pathPattern, EndpointHandler handler)` — register an endpoint handler for a method + path pattern combination; `void start(String host, int port)` — bind and start the HTTP server; `void stop(int drainSeconds)` — graceful shutdown with connection drain (§9 `shutdown_drain_seconds: 10`); `boolean isRunning()` — current server state; `int port()` — the bound port (may differ from configured port if `0` was used for system-assigned). Javadoc: abstract HTTP server operations that isolate the HTTP server implementation choice (§3.9). Phase 3 implements this against Javalin 6.x (expected path), JDK HttpServer, or Eclipse Jetty 12. Route path patterns use `{param}` syntax for path parameters (e.g., `/api/v1/entities/{entity_id}`). The implementation dispatches each request on a virtual thread (LTD-01). Thread-safe. |
| `RestApiLifecycle.java` | interface | Doc 09 §8.1: Two methods: `void start()` — configure authentication, register all routes, bind the HTTP server, and begin accepting requests (Phase 5 of Doc 12 startup sequence); `void stop()` — initiate graceful shutdown: stop accepting new connections, drain in-flight requests (configurable timeout per §9), unbind the port. Javadoc: lifecycle interface consumed by the Startup, Lifecycle & Shutdown module (Doc 12) for startup sequencing and graceful shutdown. The `start()` method is called during system initialization after the Configuration System, Event Bus, State Store, and subsystem registries are ready. The `stop()` method is called during shutdown step 4 (Doc 12 §3.9). Thread-safe. |

### Group 9: Module Descriptor

| File | Notes |
|------|-------|
| `module-info.java` | `module com.homesynapse.api.rest { exports com.homesynapse.api.rest; }`. No `requires` directives for Phase 2 — all types use Java standard library types exclusively. Phase 3 will add `requires` for event-model, device-model, state-store, automation, integration-api, persistence, configuration, observability, Jackson, and the chosen HTTP server library as endpoint handler implementations are added. |

---

## File Placement

All REST API types go in: `api/rest-api/src/main/java/com/homesynapse/api/rest/`
Module info: `api/rest-api/src/main/java/module-info.java` (create new)

Delete the existing `package-info.java` file at `api/rest-api/src/main/java/com/homesynapse/api/rest/package-info.java` — it is a scaffold placeholder that will be replaced by real types.

---

## Cross-Module Type Dependencies

**Phase 2: NONE.** The REST API module has zero imports from other HomeSynapse modules in Phase 2. All types are self-contained using Java standard library types (`String`, `int`, `long`, `boolean`, `Instant`, `Map`, `List`, `Optional`, `Set`, `Object`). This is unique among HomeSynapse modules — it reflects the REST API's nature as a translation layer that consumes internal interfaces (Phase 3) but defines its own contracts independently.

**Phase 3 will add requires for (not in Phase 2 scope):**
- `com.homesynapse.event` — EventStore, EventPublisher, EventEnvelope, HomeSynapseException
- `com.homesynapse.platform` — EntityId, DeviceId, Ulid, typed ID wrappers
- `com.homesynapse.device` — EntityRegistry, DeviceRegistry, CapabilityRegistry, CommandValidator
- `com.homesynapse.state` — StateQueryService
- `com.homesynapse.automation` — AutomationRegistry, RunManager, PendingCommandLedger
- `com.homesynapse.integration` — IntegrationSupervisor (health interface)
- `com.homesynapse.persistence` — TelemetryQueryService
- `com.homesynapse.config` — ConfigurationAccess
- `com.fasterxml.jackson.databind` — JsonNode, ObjectMapper

**Exported to (downstream consumers):**
- `com.homesynapse.api.ws` (websocket-api, Block N) — AuthMiddleware, ApiKeyIdentity, ProblemDetail, ProblemType, FieldError, RestApiServer (shared Javalin instance per §3.9 and Doc 10 §3.1)
- `com.homesynapse.lifecycle` (lifecycle module) — RestApiLifecycle (start/stop during system initialization and shutdown)
- Future: `com.homesynapse.observability` — JFR event types referencing API types (if needed)

---

## Javadoc Standards

Per Sprint 1–2 Blocks A–K lessons, plus REST API-specific requirements:

1. Every `@param` documents nullability (`never {@code null}` or `{@code null} if...`)
2. Every type has `@see` cross-references to related types within the module
3. Thread-safety explicitly stated on all interfaces ("Thread-safe" or "Not thread-safe — instances are confined to a single request")
4. Class-level Javadoc explains the "why" — what role this type plays in the REST API architecture
5. Reference Doc 09 sections in class-level Javadoc (e.g., `@see <a href="...">Doc 09 §3.8 Error Response Model</a>`)
6. ProblemType Javadoc should document each enum value with its trigger condition and the relevant doc section
7. ApiRequest Javadoc should document the request lifecycle pipeline (§3.3): by the time a handler receives it, auth, rate limiting, and parameter parsing are complete
8. CommandStatusResponse Javadoc should explain the four-phase lifecycle and why this visibility is a competitive differentiator
9. RestApiLifecycle Javadoc should document the startup sequencing — it depends on Config, Event Bus, State Store, and registries being ready
10. ProblemDetailMapper Javadoc should explain the distinction between ApiException (REST-layer errors) and mapped exceptions (downstream subsystem errors)
11. IdempotencyEntry Javadoc should reference AMD-08 and document the cache eviction semantics (LRU, TTL)
12. EndpointHandler Javadoc should document that handlers run on virtual threads (LTD-01) and may block on I/O

---

## Key Design Details for Javadoc Accuracy

1. **The REST API holds zero persistent state (§1, §5).** If the API process crashes and restarts, the next request returns data consistent with the current state of underlying subsystems. No API-layer caching, session state, or accumulated data exists. The only mutable state is the in-memory idempotency cache (LRU, lost on restart — acceptable per §3.4).

2. **Command acceptance (202) does NOT mean command execution (§3.4, §5).** A `202 Accepted` response means the `command_issued` event is durably persisted (INV-ES-04). It carries no guarantee that the device has received, acknowledged, or executed the command. This is the most important behavioral contract to document accurately.

3. **The `viewPosition` enables staleness detection (§3.7, §5).** Every State Query plane response includes the State Store's current `viewPosition`. Clients that issue a command (which returns a `viewPosition`) and then query state can compare versions to determine whether the projection has caught up. This satisfies INV-TO-03 (no hidden state) at the API boundary.

4. **Event history responses are immutable (§3.7, §5, INV-ES-01).** A single event's JSON response is byte-identical on every subsequent request. This justifies `Cache-Control: max-age=31536000, immutable`. Paginated event lists are NOT immutable (page boundaries shift as new events append).

5. **Authentication is mandatory on every request (INV-SE-02).** No endpoint is accessible without a valid API key. There is no "local trust" exception for LAN clients.

6. **Rate limiting uses a per-key token bucket (§12.5).** Defaults: 300 requests/minute sustained, 50 burst. The token bucket is lightweight — one long for token count, one long for last-refill timestamp per key, bounded by the number of active API keys (typically < 10).

7. **`X-Correlation-ID` request header is propagated but NOT injected into event envelopes (§3.11).** The event `correlation_id` is always set by `publishRoot()` to the event's own `event_id` per Doc 01 §8.3. Client-provided correlation IDs are logged alongside the event's correlation ID but remain separate. Both appear in the command response.

8. **Idempotency cache is not persisted (§3.4, AMD-08).** The LRU cache is lost on process restart. Post-restart retries with a stale key simply issue a new command — the device handles duplicate commands gracefully via the four-phase lifecycle. The 24-hour TTL covers the vast majority of retry scenarios.

9. **The five operational planes have distinct consistency contracts (§3.2).** State Query: eventually consistent (projection-derived). Command: accepted ≠ confirmed. Event History: strongly consistent (immutable log). Automation: configuration-consistent. System: real-time.

10. **`ProblemType.DEVICE_ORPHANED` (AMD-17) is a 503.** A command issued to an entity whose parent device is orphaned (lost integration connection) returns 503 because the integration adapter is unavailable to deliver the command. This is distinct from `ENTITY_DISABLED` (409) which is a user-initiated state.

---

## Constraints

1. **Java 21** — use records, sealed interfaces, enums as appropriate
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies in Phase 2 types** — only Java standard library types
5. **Javadoc on every public type, method, and constructor**
6. **All types go in `com.homesynapse.api.rest` package** within api/rest-api module
7. **Do NOT create implementations** — interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files in other modules** — no cross-module updates in Block M
10. **Collections in records must be unmodifiable** — use `Map.copyOf()`, `List.copyOf()` in compact constructors
11. **Do NOT reference Javalin, Jetty, or Jackson types** in any Phase 2 type signature
12. **ApiRequest.body must be `Object`, not `JsonNode`** — avoid leaking Jackson dependency

---

## Compile Gate

```bash
./gradlew :api:rest-api:compileJava
```

Must pass with `-Xlint:all -Werror`. Then run full project gate:

```bash
./gradlew compileJava
```

All modules must still compile (no regressions from module-info changes).

**Common pitfalls:**
- The module-info has NO `requires` directives. If you accidentally import a type from another HomeSynapse module, the compiler will fail with "package X is not visible." Remove the import and use Java standard types instead.
- `@Nullable` annotation: use Javadoc `{@code null}` documentation, not a `@Nullable` annotation import. HomeSynapse does not have a nullability annotations dependency.
- `ApiResponse.headers` uses `Map<String, String>` (single-valued). If multi-valued headers are needed (e.g., `Set-Cookie`), that's a Phase 3 concern — Phase 2 uses the simpler single-valued model.
- `PagedResponse<T>` is a generic record. Ensure the generic parameter is correctly declared: `public record PagedResponse<T>(List<T> data, PaginationMeta pagination, ResponseMeta meta) {}`.
- The existing `build.gradle.kts` has `implementation` dependencies on other modules and libraries (event-model, device-model, javalin, jackson). Leave these as-is — they are scaffold dependencies for Phase 3 and do not affect Phase 2 compilation. They sit on the module path but are unused until Phase 3 types import from them.
- `ProblemType` enum values need three constructor parameters: `slug`, `defaultStatus`, `title`. The `typeUri()` method derives the full URI from the slug. Do NOT hard-code full URIs in each enum value.

---

## Execution Order

1. Delete `package-info.java` scaffold
2. Create `ProblemType.java`
3. Create `CommandLifecyclePhase.java`
4. Create `SortDirection.java`
5. Create `FieldError.java`
6. Create `ProblemDetail.java`
7. Create `ApiKeyIdentity.java`
8. Create `RateLimitResult.java`
9. Create `CursorToken.java`
10. Create `PaginationMeta.java`
11. Create `ResponseMeta.java`
12. Create `ApiRequest.java`
13. Create `ApiResponse.java`
14. Create `PagedResponse.java`
15. Create `CommandRequest.java`
16. Create `CommandAcceptedResponse.java`
17. Create `LifecyclePhaseDetail.java`
18. Create `CommandStatusResponse.java`
19. Create `IdempotencyEntry.java`
20. Create `ApiException.java`
21. Create `EndpointHandler.java`
22. Create `AuthMiddleware.java`
23. Create `RateLimiter.java`
24. Create `ETagProvider.java`
25. Create `PaginationCodec.java`
26. Create `ProblemDetailMapper.java`
27. Create `RestApiServer.java`
28. Create `RestApiLifecycle.java`
29. Create `module-info.java`
30. Compile gate: `./gradlew :api:rest-api:compileJava`
31. Full compile gate: `./gradlew compileJava`

---

## Summary of New Files

| File | Module | Kind | Components/Methods |
|------|--------|------|--------------------|
| `ProblemType.java` | api/rest-api | enum (13 values) | NOT_FOUND, ENTITY_DISABLED, INTEGRATION_UNHEALTHY, INVALID_COMMAND, INVALID_PARAMETERS, AUTHENTICATION_REQUIRED, FORBIDDEN, RATE_LIMITED, COMMAND_NOT_FOUND, STATE_STORE_REPLAYING, INTERNAL_ERROR, IDEMPOTENCY_KEY_CONFLICT, DEVICE_ORPHANED |
| `CommandLifecyclePhase.java` | api/rest-api | enum (5 values) | ACCEPTED, DISPATCHED, ACKNOWLEDGED, CONFIRMED, CONFIRMATION_TIMED_OUT |
| `SortDirection.java` | api/rest-api | enum (2 values) | ASC, DESC |
| `FieldError.java` | api/rest-api | record (2 fields) | field, message |
| `ProblemDetail.java` | api/rest-api | record (7 fields) | type, title, status, detail, instance, correlationId, errors |
| `ApiKeyIdentity.java` | api/rest-api | record (3 fields) | keyId, displayName, createdAt |
| `RateLimitResult.java` | api/rest-api | record (2 fields) | allowed, retryAfterSeconds |
| `CursorToken.java` | api/rest-api | record (3 fields) | sortValue, sortDimension, direction |
| `PaginationMeta.java` | api/rest-api | record (3 fields) | nextCursor, hasMore, limit |
| `ResponseMeta.java` | api/rest-api | record (2 fields) | viewPosition, timestamp |
| `ApiRequest.java` | api/rest-api | record (7 fields) | method, pathPattern, pathParams, queryParams, body, identity, correlationId |
| `ApiResponse.java` | api/rest-api | record (5 fields) | statusCode, headers, body, eTag, cacheControl |
| `PagedResponse.java` | api/rest-api | record (3 fields, generic) | data, pagination, meta |
| `CommandRequest.java` | api/rest-api | record (3 fields) | capability, command, parameters |
| `CommandAcceptedResponse.java` | api/rest-api | record (6 fields) | commandId, correlationId, entityId, status, acceptedAt, viewPosition |
| `LifecyclePhaseDetail.java` | api/rest-api | record (3 fields) | at, eventId, details |
| `CommandStatusResponse.java` | api/rest-api | record (8 fields) | commandId, correlationId, entityId, capability, command, lifecycle, currentPhase, terminal |
| `IdempotencyEntry.java` | api/rest-api | record (5 fields) | idempotencyKey, commandId, correlationId, viewPosition, createdAt |
| `ApiException.java` | api/rest-api | exception | extends RuntimeException, carries ProblemDetail |
| `EndpointHandler.java` | api/rest-api | interface (functional) | handle(ApiRequest) → ApiResponse |
| `AuthMiddleware.java` | api/rest-api | interface | authenticate(String) → ApiKeyIdentity |
| `RateLimiter.java` | api/rest-api | interface | check(String) → RateLimitResult |
| `ETagProvider.java` | api/rest-api | interface | fromViewPosition(long), fromEventId(String), fromDefinitionHash(String) |
| `PaginationCodec.java` | api/rest-api | interface | encode(CursorToken), decode(String) |
| `ProblemDetailMapper.java` | api/rest-api | interface | map(Exception, String, String) → ProblemDetail |
| `RestApiServer.java` | api/rest-api | interface | registerRoute(), start(), stop(), isRunning(), port() |
| `RestApiLifecycle.java` | api/rest-api | interface | start(), stop() |
| `module-info.java` | api/rest-api | module descriptor | exports com.homesynapse.api.rest |

**Deleted files (1):**

| File | Module | Reason |
|------|--------|--------|
| `package-info.java` | api/rest-api | Scaffold placeholder replaced by real types |

**Total: 28 new files + 1 deleted = 29 file operations.**

---

## Estimated Size

~27 types + module-info, approximately 900–1200 lines. This is a medium-large block. The primary complexity is in the ProblemType enum (13 values with full Javadoc per value), the CommandStatusResponse lifecycle map pattern, and maintaining Javadoc accuracy across 8 interfaces with specific behavioral contracts. The types themselves are straightforward records and interfaces — no sealed hierarchies, no generics beyond PagedResponse, no cross-module updates. Expect 2–3 hours.

---

## Notes

- **Block L (automation) does NOT need to be complete before Block M.** The REST API's Phase 2 types have zero imports from the automation module. The automation types (AutomationRegistry, RunManager, etc.) are consumed by Phase 3 endpoint handlers, not by Phase 2 infrastructure types. Block M can execute in parallel with or before Block L.
- **The `build.gradle.kts` already has scaffold dependencies** on event-model, device-model, state-store, automation, observability, Javalin, and Jackson. Leave these as-is — they are Phase 3 dependencies and do not interfere with Phase 2 compilation. Additional dependencies (integration-api, persistence, configuration) will be added in Phase 3.
- **`CommandStatusResponse` has 8 field components** in the record header (commandId, correlationId, entityId, capability, command, lifecycle, currentPhase, terminal) even though the Summary table says 7 fields. Count carefully: the `terminal` boolean is the 8th component.
- **`ApiResponse.eTag` and `ApiResponse.cacheControl` duplicate information in `headers`.** This is intentional — they are exposed as named fields for middleware convenience (e.g., ETag evaluation for 304 responses) while also being present in the headers map for response serialization. The Javadoc should document this redundancy.
- **The WebSocket API module (Block N) will depend on several types from this module.** Design the shared types (AuthMiddleware, ApiKeyIdentity, ProblemDetail, ProblemType, FieldError, RestApiServer) with awareness that they serve both HTTP and WebSocket contexts. Keep them protocol-agnostic where possible.
- **No MODULE_CONTEXT.md population in this block.** MODULE_CONTEXT.md is populated after the block is complete, as a follow-up task.
- **`ProblemDetail.errors` is nullable, not an empty list.** The `errors` array is present only for validation errors (400 and 422). For other error types, `errors` is null. This matches the RFC 9457 convention — extension members are omitted when not applicable, not included as empty.

---

## Context Delta (post-completion)

**Files created (28):**
- `ProblemType.java` — enum (13 values: NOT_FOUND through DEVICE_ORPHANED)
- `CommandLifecyclePhase.java` — enum (5 values: ACCEPTED through CONFIRMATION_TIMED_OUT)
- `SortDirection.java` — enum (2 values: ASC, DESC)
- `FieldError.java` — record (2 fields)
- `ProblemDetail.java` — record (7 fields, RFC 9457)
- `ApiKeyIdentity.java` — record (3 fields)
- `RateLimitResult.java` — record (2 fields)
- `CursorToken.java` — record (3 fields)
- `PaginationMeta.java` — record (3 fields)
- `ResponseMeta.java` — record (2 fields)
- `ApiRequest.java` — record (7 fields)
- `ApiResponse.java` — record (5 fields)
- `PagedResponse.java` — generic record (3 fields)
- `CommandRequest.java` — record (3 fields)
- `CommandAcceptedResponse.java` — record (6 fields)
- `LifecyclePhaseDetail.java` — record (3 fields)
- `CommandStatusResponse.java` — record (8 fields)
- `IdempotencyEntry.java` — record (5 fields)
- `ApiException.java` — extends RuntimeException (carries ProblemDetail)
- `EndpointHandler.java` — functional interface (1 method)
- `AuthMiddleware.java` — interface (1 method)
- `RateLimiter.java` — interface (1 method)
- `ETagProvider.java` — interface (3 methods)
- `PaginationCodec.java` — interface (2 methods)
- `ProblemDetailMapper.java` — interface (1 method)
- `RestApiServer.java` — interface (5 methods)
- `RestApiLifecycle.java` — interface (2 methods)
- `module-info.java` — `exports com.homesynapse.api.rest;` (no requires)

**Files modified (0):**
No cross-module updates.

**Files deleted (1):**
- `package-info.java` — scaffold placeholder

**What the next block needs to know:**
- The REST API module's Phase 2 types are self-contained — no other module needs to add `requires com.homesynapse.api.rest` in Phase 2 (the consumers are websocket-api and lifecycle, both not yet specified).
- `AuthMiddleware`, `ApiKeyIdentity`, `ProblemDetail`, `ProblemType`, `FieldError`, and `RestApiServer` are shared with the WebSocket API module (Block N). Block N's handoff should reference these types as imports from `com.homesynapse.api.rest`, not duplicates.
- `ProblemDetail.type` is a `ProblemType` enum, not a String. WebSocket error messages should use the same enum.
- `RestApiServer` abstracts the HTTP server. Block N (WebSocket) needs to share the same HTTP server instance for the WebSocket upgrade handler (Doc 10 §3.1). The `RestApiServer` interface may need an additional method for WebSocket registration — evaluate during Block N handoff production.
- The module has no `requires` in Phase 2. Phase 3 will add ~9 `requires` directives and corresponding `api()` or `implementation()` dependencies in build.gradle.kts.
- MODULE_CONTEXT.md should be populated after this block completes.

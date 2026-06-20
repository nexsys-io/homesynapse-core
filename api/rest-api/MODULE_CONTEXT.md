# rest-api — `com.homesynapse.api.rest` — Phase 3 transition (M3.6e.2) — HTTP command interface, RFC 9457 errors, 4-phase command lifecycle, idempotency keys, ReadinessFilter (Javalin before-handler), entity query endpoints + admin endpoints (M3.6e.2)

## Purpose

The REST API module is HomeSynapse's primary external interface — the stateless translation layer between the event-sourced internals and HTTP clients. Every interaction from the Web UI, the future Companion App, third-party integrations, and the NexSys cloud relay passes through this module. It reads from 8 internal subsystem interfaces (StateQueryService, EntityRegistry, EventStore, AutomationRegistry, PendingCommandLedger, IntegrationSupervisor, TelemetryQueryService, ConfigurationAccess) and holds zero persistent state. If the API process crashes and restarts, the next request returns data consistent with the current state of underlying subsystems.

The module's defining competitive differentiator is four-phase command lifecycle visibility. No existing smart home platform exposes the full `accepted → dispatched → acknowledged → confirmed` lifecycle through its API. The event-sourced architecture produces these lifecycle events naturally; this module makes them accessible via standard HTTP semantics through `POST /api/v1/entities/{entity_id}/commands` (returns `202 Accepted` with a `commandId`) and `GET /api/v1/commands/{command_id}` (returns the full lifecycle status).

This Phase 2 specification defines the public API infrastructure types — request/response records, service interfaces, pagination contracts, authentication types, RFC 9457 error model, ETag/caching contracts, and the four-phase command lifecycle types. The actual endpoint handlers that query internal subsystem interfaces are Phase 3. Phase 2 types use Java standard library types exclusively — no imports from other HomeSynapse modules or external libraries (Jackson, Javalin).

## Design Doc Reference

**Doc 09 — REST API** is the governing design document:
- §1: Purpose — stateless translation layer, zero persistent state
- §3.2: Five operational planes — State Query (eventually consistent), Command (accepted ≠ confirmed), Event History (strongly consistent), Automation (configuration-consistent), System (real-time)
- §3.3: Request processing pipeline — authentication → rate limiting → parameter parsing → handler dispatch
- §3.4: Command lifecycle — four-phase (`accepted → dispatched → acknowledged → confirmed`), idempotency (AMD-08)
- §3.5: Cursor-based pagination — keyset pagination with opaque Base64 cursors
- §3.6: Sort direction — ASC/DESC with per-endpoint defaults
- §3.7: ETag and conditional requests — weak ETags for State Query, strong ETags for immutable events, automation ETags for hot-reload
- §3.8: RFC 9457 Problem Detail error model — 13 problem types, structured `application/problem+json` responses
- §3.9: HTTP server abstraction — Javalin 6.x (expected), JDK HttpServer, or Eclipse Jetty 12
- §3.10: Wire-boundary ID format — ULIDs as 26-character Crockford Base32 strings (LTD-04)
- §3.11: Correlation ID propagation — `X-Correlation-ID` header, separate from event `correlation_id`
- §4.3: Command issuance endpoint — `POST /api/v1/entities/{entity_id}/commands`
- §4.4: Command accepted response — `202 Accepted` with commandId, correlationId, viewPosition
- §4.5: Command status endpoint — `GET /api/v1/commands/{command_id}`, four-phase lifecycle map
- §8.1: Interface specifications — EndpointHandler, AuthMiddleware, RateLimiter, ETagProvider, PaginationCodec, ProblemDetailMapper, RestApiServer, RestApiLifecycle
- §8.2: Request/response records — ApiRequest, ApiResponse, PagedResponse, ApiKeyIdentity
- §9: Configuration defaults — `burst_size: 50`, `requests_per_minute: 300`, `shutdown_drain_seconds: 10`
- §12.1: Authentication — `Authorization: Bearer {key}`, bcrypt hash validation, mandatory on every request (INV-SE-02)
- §12.4: Security — stack traces never included in API responses
- §12.5: Rate limiting — per-key token bucket

## JPMS Module

```
module com.homesynapse.api.rest {
    requires transitive com.homesynapse.state;
    requires com.homesynapse.event.bus;

    requires io.javalin;
    requires org.slf4j;

    exports com.homesynapse.api.rest;
}
```

**M3.6e.1 update:** The rest-api module's Phase 2 zero-`requires` state ended when `ReadinessFilter` landed — it consumes the state-store `ReadinessSource`, observes `SubscriberMode` from event-bus, implements `io.javalin.http.Handler`, and logs rejections via SLF4J. `requires transitive com.homesynapse.state` is the load-bearing edge — `ReadinessSource` appears in `ReadinessFilter`'s public constructor signature. `requires com.homesynapse.event.bus` is non-transitive — `SubscriberMode` is only consumed inside the filter's body. Both `io.javalin` and `org.slf4j` are non-transitive implementation concerns.

## Package Structure

- **`com.homesynapse.api.rest`** — All types in a single flat package. Contains: 3 enums (ProblemType, CommandLifecyclePhase, SortDirection), 12 data records (FieldError, ProblemDetail, ApiKeyIdentity, RateLimitResult, CursorToken, PaginationMeta, ResponseMeta, ApiRequest, ApiResponse, PagedResponse, CommandRequest, CommandAcceptedResponse, LifecyclePhaseDetail, CommandStatusResponse, IdempotencyEntry), 1 exception (ApiException), 8 service interfaces (EndpointHandler, AuthMiddleware, RateLimiter, ETagProvider, PaginationCodec, ProblemDetailMapper, RestApiServer, RestApiLifecycle), and module-info.java.

## Complete Type Inventory

### Enums

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ProblemType` | enum (13 values) | Machine-readable RFC 9457 error type identifiers | Values: `NOT_FOUND` (404), `ENTITY_DISABLED` (409), `INTEGRATION_UNHEALTHY` (503), `INVALID_COMMAND` (422), `INVALID_PARAMETERS` (400), `AUTHENTICATION_REQUIRED` (401), `FORBIDDEN` (403), `RATE_LIMITED` (429), `COMMAND_NOT_FOUND` (404), `STATE_STORE_REPLAYING` (503), `INTERNAL_ERROR` (500), `IDEMPOTENCY_KEY_CONFLICT` (409, AMD-08), `DEVICE_ORPHANED` (503, AMD-17). Three fields per value: `slug` (String), `defaultStatus` (int), `title` (String). Method: `typeUri()` → `"https://homesynapse.local/problems/" + slug`. Static prefix constant avoids per-call allocation. |
| `CommandLifecyclePhase` | enum (5 values) | Phases in the four-phase command lifecycle | Values: `ACCEPTED`, `DISPATCHED`, `ACKNOWLEDGED`, `CONFIRMED`, `CONFIRMATION_TIMED_OUT`. Ordering matches event chronology. `CONFIRMATION_TIMED_OUT` is the terminal failure phase (replaces CONFIRMED when the expected state change does not materialize within the configured timeout). |
| `SortDirection` | enum (2 values) | Pagination sort direction | Values: `ASC` (default for entity/device lists — ULID chronological order), `DESC` (default for event lists — newest first). The `sort` query parameter overrides the default. Direction is encoded into `CursorToken` for subsequent pages. |

### Error Model Records

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `FieldError` | record (2 fields) | Per-field validation error for 400/422 responses | Fields: `field` (String — JSON path, e.g., `"parameters.level"`), `message` (String — human-readable). Compact constructor: both fields non-null via `Objects.requireNonNull`. |
| `ProblemDetail` | record (7 fields) | Immutable RFC 9457 Problem Details representation | Fields: `type` (ProblemType — enum, not String), `title` (String), `status` (int), `detail` (String), `instance` (String, nullable — request path), `correlationId` (String — AMD-15, always present), `errors` (List\<FieldError\>, nullable — present only for 400/422). Compact constructor: type, title, detail, correlationId non-null; errors made unmodifiable via `List.copyOf()` if non-null. **`errors` is nullable, not empty list** — omitted when not applicable per RFC 9457 convention. |

### Authentication and Rate Limiting Types

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ApiKeyIdentity` | record (3 fields) | Authenticated caller identity extracted by AuthMiddleware | Fields: `keyId` (String — public identifier, NEVER the raw key value), `displayName` (String), `createdAt` (Instant). All fields non-null. The raw key value is never stored or returned — only the bcrypt hash exists in the key store. Used for rate limiting, structured logging, and audit. |
| `RateLimitResult` | record (2 fields) | Result of a per-key token bucket rate limit check | Fields: `allowed` (boolean), `retryAfterSeconds` (long — meaningful only when `allowed` is false). No compact constructor validation — primitives only. |

### Pagination Types

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `CursorToken` | record (3 fields) | Decoded cursor representing a pagination position | Fields: `sortValue` (String — sort key value of last item on previous page), `sortDimension` (String — field name being sorted on), `direction` (SortDirection). All fields non-null. Encoded as opaque URL-safe Base64 for wire transport by PaginationCodec. |
| `PaginationMeta` | record (3 fields) | Pagination metadata in paginated API responses | Fields: `nextCursor` (String, nullable — null when `hasMore` is false), `hasMore` (boolean), `limit` (int — page size used). No compact constructor validation — nullable nextCursor and primitives. |
| `ResponseMeta` | record (2 fields) | Response-level metadata for State Query plane responses | Fields: `viewPosition` (long — State Store's current view position for staleness detection, §3.7), `timestamp` (Instant — response generation time). Compact constructor: timestamp non-null. Event History plane responses do NOT include this metadata (they are strongly consistent). |

### Request/Response Infrastructure

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ApiRequest` | record (7 fields) | Parsed and authenticated HTTP request for endpoint handlers | Fields: `method` (String), `pathPattern` (String, e.g., `"/api/v1/entities/{entity_id}"`), `pathParams` (Map\<String, String\>, unmodifiable), `queryParams` (Map\<String, List\<String\>\>, unmodifiable — supports repeatable keys), `body` (Object, nullable — Phase 3 delivers `JsonNode`; typed as `Object` to avoid Jackson dependency leak), `identity` (ApiKeyIdentity — never null, INV-SE-02), `correlationId` (String — from `X-Correlation-ID` header or generated ULID). Compact constructor: method, pathPattern, identity, correlationId non-null; maps made unmodifiable via `Map.copyOf()`. |
| `ApiResponse` | record (5 fields) | Response from endpoint handler before HTTP serialization | Fields: `statusCode` (int), `headers` (Map\<String, String\>, single-valued, unmodifiable), `body` (Object, nullable — null for 204/304), `eTag` (String, nullable), `cacheControl` (String, nullable). `eTag` and `cacheControl` intentionally duplicate information in `headers` for middleware convenience. Compact constructor: headers made unmodifiable via `Map.copyOf()`. |
| `PagedResponse` | record (3 fields, generic `<T>`) | Paginated response envelope for list endpoints | Fields: `data` (List\<T\>, unmodifiable), `pagination` (PaginationMeta), `meta` (ResponseMeta, nullable — present for State Query plane, null for Event History plane). Java 21 generic records provide compile-time type safety: `PagedResponse<EntitySummary>` vs `PagedResponse<EventSummary>` are distinct types. Compact constructor: data and pagination non-null; data made unmodifiable via `List.copyOf()`. |

### Command Types

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `CommandRequest` | record (3 fields) | Typed command input for `POST /api/v1/entities/{entity_id}/commands` | Fields: `capability` (String, e.g., `"level_control"`), `command` (String, e.g., `"set_level"`), `parameters` (Map\<String, Object\>, unmodifiable). All fields required and non-null. Validated against entity's declared capabilities via `CommandValidator` (Doc 02 §8.1). |
| `CommandAcceptedResponse` | record (6 fields) | The `202 Accepted` response for successful command issuance | Fields: `commandId` (String — event_id of the `command_issued` event), `correlationId` (String), `entityId` (String), `status` (String — always `"accepted"`), `acceptedAt` (Instant), `viewPosition` (long). **"Accepted" means durably persisted, NOT executed.** |
| `LifecyclePhaseDetail` | record (3 fields) | Detail for a single completed phase in the command lifecycle | Fields: `at` (Instant), `eventId` (String — the event representing this phase), `details` (Map\<String, Object\>, nullable — null for ACCEPTED; includes `integration_id` for DISPATCHED, `result` for ACKNOWLEDGED, `match_type` for CONFIRMED). Compact constructor: at and eventId non-null; details made unmodifiable via `Map.copyOf()` if non-null. |
| `CommandStatusResponse` | record (8 fields) | Four-phase command lifecycle status from EventStore correlation queries | Fields: `commandId` (String), `correlationId` (String), `entityId` (String), `capability` (String), `command` (String), `lifecycle` (Map\<CommandLifecyclePhase, LifecyclePhaseDetail\>, unmodifiable — only completed phases present), `currentPhase` (CommandLifecyclePhase), `terminal` (boolean — true for CONFIRMED, CONFIRMATION_TIMED_OUT, or rejected ACKNOWLEDGED). **8 fields, not 7** — the `terminal` boolean is the 8th component. Compact constructor: all non-primitives non-null; lifecycle made unmodifiable via `Map.copyOf()`. |
| `IdempotencyEntry` | record (5 fields) | Entry in the in-memory idempotency LRU cache (AMD-08) | Fields: `idempotencyKey` (String — client-provided, max 128 chars), `commandId` (String), `correlationId` (String), `viewPosition` (long), `createdAt` (Instant — for 24-hour TTL). Cache: max 10,000 entries LRU, 24-hour TTL, not persisted (lost on restart). All non-primitives non-null. |

### Exception

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ApiException` | class extends RuntimeException | Structured HTTP error thrown by REST API middleware and validators | Single field: `problemDetail` (ProblemDetail, `transient`). Two constructors: `(ProblemDetail)` and `(ProblemDetail, Throwable cause)`. Both call `super(problemDetail.detail())` to preserve the human-readable message in the superclass. Getter: `problemDetail()`. Distinct from `ProblemDetailMapper` which maps downstream subsystem exceptions — `ApiException` handles errors generated by the REST API layer itself. |

### M3.6e.1 — Readiness gate (2026-05-22)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `RestFilters` | **public** final utility class (no state) | Public gateway for installing REST-layer request filters AND endpoint handlers onto the embedded HTTP server. The actual filter and handler implementations are **package-private** per DEC-M3-16 — their Javalin-specific signatures must not appear in the rest-api module's exported public API because `requires io.javalin` is **non-transitive** (re-exporting Javalin via `requires transitive` would chain it into websocket-api and homesynapse-app, where it has no business). M3.6e.2 added two new gateway methods. | Private constructor (utility class). Methods: `static void installReadinessGate(Object javalinApp, ReadinessSource readinessSource)`, `static void installEntityQueryEndpoints(Object javalinApp, StateQueryService queryService, LongSupplier viewPositionSupplier, Clock clock)` (M3.6e.2 — registers the three `/api/v1/entities*` GET routes), `static void installAdminEndpoints(Object javalinApp, Object bus, ReadinessSource readinessSource, StateQueryService queryService, LongSupplier viewPositionSupplier)` (M3.6e.2 — registers the two `/internal/*` admin routes). The `javalinApp` and `bus` parameters are typed as `Object` so the method signatures do not leak `io.javalin.Javalin` or `com.homesynapse.event.bus.EventBus` (both non-transitive `requires` in rest-api's module-info); methods cast internally. The composition root in the lifecycle module declares the underlying modules independently, making each cast safe at the call site. |
| `ReadinessFilter` | **package-private** final class implements `io.javalin.http.Handler` | Javalin `before` handler that gates `/api/*` traffic with `503 Service Unavailable` until the State Projection reaches `SubscriberMode.LIVE`. Constructed only by `RestFilters.installReadinessGate(...)`. | Package-private constructor: `ReadinessFilter(ReadinessSource)`. `handle(Context)` reads `readinessSource.mode()` and, when it is anything other than LIVE, writes status 503, headers `X-HomeSynapse-Projection-State: <MODE>` + `Retry-After: 5`, and an RFC 9457 problem detail body keyed by `ProblemType.STATE_STORE_REPLAYING`. Returns without calling `ctx.next()` — Javalin treats a status-set `before` handler as the terminal response. Logs at DEBUG when rejecting (includes mode). Stateless beyond the immutable `ReadinessSource` reference; thread-safe. Pure decision logic lives in package-private `apply(Responder)` so unit tests can drive the filter through a recording stub instead of mocking Javalin's thick `Context` interface. The narrow `Responder` SPI (`status`, `header`, `json`) and its production `ContextResponder` adapter are package-private nested types. **Visibility downgrade (2026-05-22 fix):** originally landed as `public final class`, which produced `-Xlint:exports` errors because Javalin types (`Handler`, `Context`) appeared in the public API surface of a module that requires Javalin non-transitively. The fix made the class package-private and routed external construction through `RestFilters`. |

### M3.6e.2 — Entity query endpoints + admin endpoints (2026-05-22)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `EndpointContext` | **package-private** interface (5 methods) | Narrow request/response SPI used by the M3.6e.2 endpoint handlers. Mirrors the subset of `io.javalin.http.Context` actually consumed (`pathParam`, `queryParam`, `status`, `header`, `json`). Same testability discipline as `ReadinessFilter.Responder` but extended with request inputs so handlers can be driven by a recording stub. | Methods: `String pathParam(String)`, `String queryParam(String)` (nullable when absent), `void status(int)`, `void header(String, String)`, `void json(Object)`. Package-private — not part of exported API. |
| `JavalinEndpointContext` | **package-private** final class implements `EndpointContext` | Production adapter from Javalin's `Context` to `EndpointContext`. Forwards each call to the equivalent `Context` method. | Single field: `Context ctx`. Constructor: `JavalinEndpointContext(Context)`. Visibility discipline matches `ReadinessFilter.ContextResponder` — Javalin types must not leak. |
| `EndpointResponses` | **package-private** final utility class (no state) | Shared RFC 9457 problem-detail construction so all M3.6e.2 handlers produce identical error bodies. | Private constructor. Methods: `static void problem(EndpointContext, ProblemType, String detail)`, `static Map<String, Object> problemBody(ProblemType, String detail)`. Stable field order via `LinkedHashMap` (matches `ReadinessFilter`'s pattern: `type`, `status`, `title`, `detail`). |
| `ListEntitiesEndpoint` | **package-private** final class implements `io.javalin.http.Handler` | `GET /api/v1/entities` — paginated entity summary listing. | Constructor: `(StateQueryService, LongSupplier viewPositionSupplier, Clock)`. Query params: `limit` (default 50, silently clamped to `[1, 100]`, malformed → default), `sort` (`ASC`/`DESC`, default ASC, case-insensitive). Sorts by entity ULID string (lexicographic = chronological per LTD-04). Pulls a fully-consistent `StateSnapshot`; returns empty `data` array (NOT 404) when no entities exist. Returns 200 + JSON body `{data: [...summaries...], meta: {viewPosition, timestamp}}` + header `X-HomeSynapse-View-Position: {viewPosition}`. Summary fields: `entityId`, `availability`, `stale` (PLAN-M3 §10.6 also listed `subjectType` — omitted because `EntityState` carries no such field; documented in handler Javadoc and Completion Report). Constants: `DEFAULT_LIMIT = 50`, `MAX_LIMIT = 100`, `VIEW_POSITION_HEADER = "X-HomeSynapse-View-Position"`. Stateless. |
| `GetEntityEndpoint` | **package-private** final class implements `io.javalin.http.Handler` | `GET /api/v1/entities/{entityId}` — single-entity lookup. | Constructor: `(StateQueryService, LongSupplier viewPositionSupplier, Clock)`. Path param: `entityId` (Crockford Base32 ULID). Parses via `EntityId.of(Ulid.parse(entityId))`; on `IllegalArgumentException` returns 400 RFC 9457 problem detail (`ProblemType.INVALID_PARAMETERS`). On `Optional.empty()` from query service returns 404 (`ProblemType.NOT_FOUND`). On success returns 200 + `{data: <EntityState record>, meta: {...}}` + view-position header. Delegates RFC 9457 body construction to `EndpointResponses.problem`. Stateless. |
| `GetEntityStateEndpoint` | **package-private** final class implements `io.javalin.http.Handler` | `GET /api/v1/entities/{entityId}/state` — detailed entity state. | Same shape as `GetEntityEndpoint` for MVP per PLAN-M3 §10.6 ("distinction is cosmetic"). Kept as a separate class so the planned M5+ divergence (summary vs full record) lands as a single-file edit. Stateless. |
| `DlqStatusEndpoint` | **package-private** final class implements `io.javalin.http.Handler` | `GET /internal/dlq` — per-subscriber DLQ status. NOT gated by `ReadinessFilter` (operational tooling). | Constructor: `(EventBus)`. Calls `EventBus.subscribers()` → `List<SubscriberSnapshot>` and projects each snapshot to `{subscriberId, mode, dlqDepth, crashCount}`. Returns 200 + `{subscribers: [...entries...]}`. Empty subscribers list yields empty array (not 404). Response shape deviates from the brief's sketch (`parkedCount`, `oldestParkedAt`) — those fields are not on `SubscriberSnapshot` (5 fields: `subscriberId`, `mode`, `checkpoint`, `dlqDepth`, `crashCount`). The brief's "Coder Pushback Welcome" section explicitly invited this adjustment. Stateless. |
| `ProjectionStatusEndpoint` | **package-private** final class implements `io.javalin.http.Handler` | `GET /internal/projection` — state-projection lifecycle status. NOT gated by `ReadinessFilter` (operational tooling). | Constructor: `(ReadinessSource, StateQueryService, LongSupplier viewPositionSupplier)`. Returns 200 + `{mode, viewPosition, entityCount, ready}`. `mode` from `readinessSource.mode().name()`, `viewPosition` from the supplier, `entityCount` from `queryService.getSnapshot().states().size()`, `ready` is `mode == LIVE`. Stateless. |

### Service Interfaces

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `EndpointHandler` | @FunctionalInterface | HTTP request handling — one handler per endpoint | `handle(ApiRequest) → ApiResponse throws ApiException`. Handlers run on virtual threads (LTD-01). Must be stateless or thread-safe. May block on I/O (e.g., SQLite reads). |
| `AuthMiddleware` | interface | API key extraction and validation from Authorization header | `authenticate(String authorizationHeader) → ApiKeyIdentity throws ApiException`. Throws AUTHENTICATION_REQUIRED (401) for missing/malformed header, FORBIDDEN (403) for invalid/expired/revoked key. Thread-safe. |
| `RateLimiter` | interface | Per-key token bucket rate limiter | `check(String apiKeyId) → RateLimitResult`. Phase 3 implementation: `ConcurrentHashMap` of lightweight token buckets (one long token count + one long last-refill per key). Thread-safe. |
| `ETagProvider` | interface | Computes ETag strings for HTTP conditional requests | `fromViewPosition(long) → String` (weak: `W/"{viewPosition}"`), `fromEventId(String) → String` (strong: `"{eventId}"`), `fromDefinitionHash(String) → String` (weak: `W/"{definitionHash}"`). Formatting belongs in the provider. Thread-safe and stateless. |
| `PaginationCodec` | interface | Encodes/decodes opaque pagination cursors | `encode(CursorToken) → String`, `decode(String) → CursorToken throws ApiException`. Throws INVALID_PARAMETERS on malformed/tampered/expired cursors. Thread-safe and stateless. |
| `ProblemDetailMapper` | interface | Maps internal exceptions to RFC 9457 Problem Detail responses | `map(Exception, String correlationId, String requestPath) → ProblemDetail`. Handles non-ApiException exceptions (HomeSynapseException subtypes, ConcurrentModificationException, unexpected RuntimeException). Unrecognized exceptions → INTERNAL_ERROR (500). Stack traces NEVER in responses (§12.4). Thread-safe. |
| `RestApiServer` | interface | Abstract HTTP server operations (isolates server implementation choice) | `registerRoute(String method, String pathPattern, EndpointHandler)`, `start(String host, int port)`, `stop(int drainSeconds)`, `isRunning() → boolean`, `port() → int`. Path patterns: `{param}` syntax. Virtual thread dispatch (LTD-01). Thread-safe. |
| `RestApiLifecycle` | interface | Lifecycle management consumed by Doc 12 startup/shutdown module | `start()` — Phase 5 of startup (after Config, Event Bus, State Store, registries). `stop()` — shutdown step 4 (drain in-flight requests, unbind port). Thread-safe. |

**Total: 28 public types + 9 package-private + 1 module-info.java = 38 Java files.** (M3.6e.1 added 1 public + 1 package-private — `RestFilters` and `ReadinessFilter`. M3.6e.2 added 8 package-private types — `EndpointContext`, `JavalinEndpointContext`, `EndpointResponses`, `ListEntitiesEndpoint`, `GetEntityEndpoint`, `GetEntityStateEndpoint`, `DlqStatusEndpoint`, `ProjectionStatusEndpoint`. M3.6e.2 also added two new public methods on `RestFilters` — `installEntityQueryEndpoints` and `installAdminEndpoints` — but no new public types.)

### AB-1 — Authentication impls + opaque-token store (2026-06-20)

The C1 close: the first production `AuthMiddleware`/`RateLimiter` implementations and a config-resident opaque-bearer-token store. **Auth scheme (RULED, A2 2026-06-19): opaque bearer tokens** — random 256-bit (`SecureRandom`) tokens, URL-safe Base64 (43 chars), presented as `Authorization: Bearer {token}`, validated against a local store that holds only a **JDK-native SHA-256 hash** of the raw token (NOT bcrypt — there is no bcrypt in the version catalog and a 256-bit random token needs no work factor; the Doc 09 §12.1 "bcrypt" wording + the `AuthMiddleware`/`ApiKeyIdentity` "bcrypt hash" Javadoc are currency nits). **Zero new module edges, zero new dependencies, no `module-info`/`build.gradle.kts` change** — every new type's public surface names only `java.base` + the module's own exported types.

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `StandardAuthMiddleware` | **public** final class implements `AuthMiddleware` | Validates `Authorization: Bearer {token}` against `OpaqueTokenStore`. | Ctor `(OpaqueTokenStore)`. Missing/blank/non-`Bearer` header → `ApiException(AUTHENTICATION_REQUIRED)` 401 (the `WWW-Authenticate: Bearer` header is set by the filter's exception handler). Absent/revoked/expired token → `ApiException(FORBIDDEN)` 403. Valid → `ApiKeyIdentity` (never the raw token). Scheme name matched case-insensitively (RFC 6750). Raw token never logged. Builds its `ApiException` via package-private `RestFilters.problem(...)`. |
| `StandardRateLimiter` | **public** final class implements `RateLimiter` | Per-key token bucket (Doc 09 §9: 300 rpm / burst 50). | Ctors `(Clock)` and `(Clock, int requestsPerMinute, int burstSize)`. `check(keyId)` uses `ConcurrentHashMap.compute` for atomic per-key remap (no `synchronized`, LTD-11), clock-injected (no `Instant.now()`/`System.nanoTime()`). **Bounded by authenticated keys** — the filter calls `check` only AFTER auth succeeds with the resolved `key_id`, so the map cannot grow from attacker input. Constants `DEFAULT_REQUESTS_PER_MINUTE=300`, `DEFAULT_BURST_SIZE=50`. |
| `OpaqueTokenStore` | **public** final class | File-backed store: `{token_hash(hex) → (key_id, display_name, created_at, expires_at?, scopes[], site_id, revoked)}` under `configDir/api_tokens` (hashes only). | Ctor `(Path configDir, Clock)`. `validate(rawToken) → Optional<ApiKeyIdentity>`, `claimsFor(keyId) → Optional<ApiKeyClaims>`, `mint(displayName, scopes, siteId) → String rawToken` (shown once), `ensureInitialToken() → Optional<String>` (first-run pairing: mints one full-access token, writes the one-time `initial_api_token` artifact + WARN log, no-op thereafter — R-δ AX-9, no network call), `revoke(keyId) → boolean` (rotation = mint-new + revoke-old; no in-place secret mutation), `activeKeyCount() → int`. java.base-only persistence (tab-separated, Base64'd free-text fields — no Jackson edge); mutations under a `ReentrantLock`, reads on a `ConcurrentHashMap`. Malformed store lines are skipped with a WARN (a dropped token fails closed; never bricks startup). |
| `ApiKeyClaims` | **public** record (2 fields) | Per-token authorization claims (enterprise per-scope/per-site hook). | `(List<String> scopes, String siteId)`; `SCOPE_ALL="*"`; `fullAccess()`, `grants(scope)`, `fullAccess(siteId)` factory. **Designed now, MVP enforcement BINARY** — a valid token grants every route at Tier 1; claims are resolved + stored but not consulted by the filter. Kept SEPARATE from `ApiKeyIdentity` (which is shared with `api.ws`) to avoid reshaping that cross-module type. `[REVIEW]` new public type. |

**`RestFilters.installAuth(Object javalinApp, AuthMiddleware, RateLimiter)`** (new public gateway method): registers an `app.exception(ApiException.class, …)` handler that serializes any `ApiException` as RFC 9457 `application/problem+json` (with a `correlation_id` from `X-Correlation-ID` or a generated UUID, and `WWW-Authenticate: Bearer` for 401), and a **catch-all `before(*)`** auth handler that (1) **canonicalizes the path** and rejects `..`/encoded-traversal/backslash/whitespace/NUL before the auth decision (`isPathSafe`, R-δ AX-1 / CVE-2023-27482) → 400, (2) authenticates → 401/403, (3) rate-limits the authenticated key → 429 + `Retry-After`. The handler **throws** `ApiException` to halt the pipeline (a status-set-only `before` would not skip the endpoint), so an unauthenticated request never reaches a handler. `installAuth` must be registered FIRST (before `installReadinessGate`) so the catch-all auth runs ahead of the `/api/*` readiness gate and covers `/api/*` + `/internal/*` + every path (INV-SE-02). `javalinApp` is `Object`-erased like the other gateway methods (keeps `io.javalin` off the exported signature); `AuthMiddleware`/`RateLimiter` are the module's own exported types so they appear directly. Package-private helpers: `static ApiException problem(ProblemType, String detail)` (used by the filter AND `StandardAuthMiddleware`), `static boolean isPathSafe(String)`, plus private `authorize`/`writeProblem`/`resolveCorrelationId`/`problemBody`. Constant `IDENTITY_ATTRIBUTE = "hs.api.identity"` (the authenticated identity attached to the request for downstream handlers).

## M3.6e.2 Phase 3 Note (2026-05-22)

M3.6e.2 closes the M3.6 composition-root milestone by delivering the first query/admin surface on top of the M3.6e.1 plumbing. The three entity query endpoints (`GET /api/v1/entities`, `GET /api/v1/entities/{entityId}`, `GET /api/v1/entities/{entityId}/state`) live under `/api/*` and inherit the `ReadinessFilter` gate — they return `503` until the State Projection reaches `LIVE`. The two admin endpoints (`GET /internal/dlq`, `GET /internal/projection`) live under `/internal/*` and are NOT gated — operators need them during REPLAY (SD-5).

**Module-info impact:** zero. All required edges (`requires transitive com.homesynapse.state`, `requires com.homesynapse.event.bus`, `requires io.javalin`, `requires org.slf4j`) were already in place from M3.6e.1. M3.6e.2 added 8 package-private classes that import only types already on the module's compile classpath.

**Gateway pattern (DEC-M3-16) extended.** `RestFilters` gained two new public methods (`installEntityQueryEndpoints`, `installAdminEndpoints`). The `bus` parameter on `installAdminEndpoints` is typed `Object` for the same reason `javalinApp` is `Object` — `com.homesynapse.event.bus.EventBus` flows in through a non-transitive `requires`, so the type must not appear on the exported method signature. The composition root declares its own `requires com.homesynapse.event.bus` (transitively via state-store), so the cast is safe at the call site.

**Testability pattern (Responder SPI) generalised.** M3.6e.1's per-handler `Responder` nested interface evolved into a top-level package-private `EndpointContext` that carries both request inputs (`pathParam`, `queryParam`) and response outputs (`status`, `header`, `json`). One file, five handlers. Tests drive the handlers through a recording `EndpointContext` stub (`RecordingEndpointContext` in test sources) without instantiating Javalin's thick `Context` interface. End-to-end HTTP coverage (real Jetty, real client) is deferred to a future milestone that runs the full composition root behind a real socket.

**ArchUnit rule additions.** Two new rules in `homesynapse-app`'s `HomeSynapseArchRules` enforce the read-only nature of the REST surface: `QUERY_SERVICE_READ_ONLY` (rest-api must not access persistence directly) and `REST_ENDPOINTS_NO_EVENT_PUBLISHING` (rest-api must not depend on `EventPublisher`). Both are defense-in-depth on top of the existing JPMS visibility — rest-api's module-info already lacks `requires com.homesynapse.persistence` and `requires com.homesynapse.event`, but the rules catch accidental classpath leaks (e.g., a future test source set that pulls those modules transitively).

## M3.6e.1 Phase 3 Note (2026-05-22)

`RestFilters` + `ReadinessFilter` are the rest-api module's first production HTTP plumbing — they bridge the historical Phase 2 type vocabulary to the live runtime that lifecycle's composition root brings up. Three module-info edges were added with them: `requires transitive com.homesynapse.state` (carries `ReadinessSource` on `RestFilters.installReadinessGate`'s signature and `ReadinessFilter`'s constructor), `requires com.homesynapse.event.bus` (the filter inspects `SubscriberMode` internally), and `requires io.javalin` (the filter implements `io.javalin.http.Handler`). `requires org.slf4j` was also added for DEBUG-level rejection logs (LTD-15).

**DEC-M3-16 application:** `ReadinessFilter` is package-private because its public superinterface (`io.javalin.http.Handler`) and its `handle(Context)` parameter come from a non-transitive Javalin `requires`. Exposing the class publicly would force `requires transitive io.javalin`, which would chain Javalin into the websocket-api → homesynapse-app dependency graph where it does not belong. The fix landed as a follow-up to the initial M3.6e.1 commit and is the canonical pattern for any future REST handler in this module: filter implementations stay package-private; the public gateway is `RestFilters`.

End-to-end HTTP testing of the filter (real Jetty, real client) is deferred to M3.6e.2 / M3.7 alongside the first endpoint handlers. The M3.6e.1 unit tests in `ReadinessFilterTest` exercise the pure `apply(Responder)` decision logic through a recording stub — Javalin's `Context` is too thick to mock at the unit level and `JavalinTest` would require spinning up Jetty per test, which is overkill for a 30-line `before` handler.

## Dependencies

### Phase 2: NONE

All Phase 2 types use Java standard library types exclusively. The module-info has zero `requires` directives. This is unique among HomeSynapse modules.

### Phase 3 will add (not in current scope):

| Module | Why | Specific Types Used |
|---|---|---|
| **event-model** (`com.homesynapse.event`) | Event queries, publishing, exception mapping | `EventStore`, `EventPublisher`, `EventEnvelope`, `HomeSynapseException` subtypes |
| **platform-api** (`com.homesynapse.platform`) | Typed ID conversion at API boundary | `EntityId`, `DeviceId`, `Ulid` — `String` ↔ typed ID conversion in endpoint handlers |
| **device-model** (`com.homesynapse.device`) | Entity queries, command validation | `EntityRegistry`, `DeviceRegistry`, `CapabilityRegistry`, `CommandValidator` |
| **state-store** (`com.homesynapse.state`) | State query plane responses | `StateQueryService` |
| **automation** (`com.homesynapse.automation`) | Automation plane responses | `AutomationRegistry`, `RunManager`, `PendingCommandLedger` |
| **integration-api** (`com.homesynapse.integration`) | Integration health endpoint | `IntegrationSupervisor` (health interface) |
| **persistence** (`com.homesynapse.persistence`) | Telemetry query endpoint | `TelemetryQueryService` |
| **configuration** (`com.homesynapse.config`) | Configuration read/write/reload endpoints | `ConfigurationAccess` |
| **Jackson** (`com.fasterxml.jackson.databind`) | JSON serialization | `JsonNode`, `ObjectMapper` |

### Gradle Dependencies (build.gradle.kts)

```kotlin
dependencies {
    implementation(project(":core:event-model"))
    implementation(project(":core:device-model"))
    implementation(project(":core:state-store"))
    implementation(project(":core:automation"))
    implementation(project(":observability:observability"))

    implementation(libs.javalin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
}
```

These are scaffold Phase 3 dependencies. They sit on the module path but are unused in Phase 2 — no Phase 2 type imports from them. Additional dependencies (integration-api, persistence, configuration) will be added in Phase 3.

## Consumers

### Current consumers:
None — no module declares `requires com.homesynapse.api.rest` in Phase 2.

### Planned consumers (from design doc dependency graph):
- **websocket-api** (`com.homesynapse.api.ws`, Block N) — Will import `AuthMiddleware`, `ApiKeyIdentity`, `ProblemDetail`, `ProblemType`, `FieldError`, `RestApiServer` (shared Javalin instance per §3.9 and Doc 10 §3.1). WebSocket and HTTP share the same authentication model and error format.
- **lifecycle** (`com.homesynapse.lifecycle`) — Will import `RestApiLifecycle` for startup sequencing (start at Phase 5) and graceful shutdown (stop at step 4).
- **Future: observability** — May reference API types for JFR metric events.

## Cross-Module Contracts

- **All API-boundary IDs are `String`, not typed ID wrappers.** ULIDs are serialized as 26-character Crockford Base32 strings at the wire boundary (LTD-04). Phase 3 endpoint handlers perform `String` ↔ typed ID conversion (e.g., `EntityId.of(string)`) when calling internal interfaces. Phase 2 types are independent of `com.homesynapse.platform`.
- **`ApiRequest.body` is `Object`, not `JsonNode`.** This avoids leaking Jackson types into the module's public API, preventing a `requires transitive com.fasterxml.jackson.databind` that would force every consumer of ApiRequest to depend on Jackson. Phase 3 endpoint handlers cast to `JsonNode` as documented.
- **Command acceptance (202) does NOT mean command execution.** A `202 Accepted` response means the `command_issued` event is durably persisted (INV-ES-04). It carries NO guarantee that the device has received, acknowledged, or executed the command. This is the most important behavioral contract at the command API boundary.
- **The `viewPosition` enables staleness detection at the API boundary.** Every State Query plane response includes the State Store's current `viewPosition`. Clients that issue a command (which returns a `viewPosition`) and then query state can compare the two to determine whether the projection has caught up. This satisfies INV-TO-03 (no hidden state).
- **Event history responses are immutable (INV-ES-01).** A single event's JSON response is byte-identical on every subsequent request. This justifies `Cache-Control: max-age=31536000, immutable`. Paginated event lists are NOT immutable (page boundaries shift as new events append).
- **Authentication is mandatory on every request (INV-SE-02).** No endpoint is accessible without a valid API key. There is no "local trust" exception for LAN clients.
- **`X-Correlation-ID` request header is propagated but NOT injected into event envelopes.** The event `correlation_id` is always set by `publishRoot()` to the event's own `event_id` per Doc 01 §8.3. Client-provided correlation IDs are logged alongside but remain separate.
- **`ProblemDetail.type` is a `ProblemType` enum, not a String.** The WebSocket API module (Block N) should import and use the same enum for error messages, not duplicate the problem type registry as strings.
- **`ApiException` carries `transient ProblemDetail`.** The `ProblemDetail` field is transient because `ApiException` inherits `Serializable` from `RuntimeException` but is never serialized over a wire protocol — it is caught in-process and converted to an HTTP response. This follows the `SequenceConflictException` precedent (`transient SubjectRef subjectRef`). The human-readable detail message is preserved in the superclass via `super(problemDetail.detail())`.
- **`RestApiServer` may need extension for WebSocket.** Block N (WebSocket) shares the same HTTP server instance for the WebSocket upgrade handler (Doc 10 §3.1). The `RestApiServer` interface may need an additional method for WebSocket registration — evaluate during Block N handoff production.

## Idempotency-Key Contract

The REST API accepts an `Idempotency-Key` header on command endpoints to enable safe client retries. The contract has important limitations that must be documented in the OpenAPI spec:

- **Best-effort, not guaranteed across process restarts.** The idempotency key cache is an in-memory LRU structure. If HomeSynapse restarts between the client's first request and its retry, the key is lost and the command may execute again.
- **Cache is lost on restart by design.** This is acceptable for the local-first architecture — the home hub restarts infrequently and the cost of an occasional duplicate command is low compared to the complexity of persisting idempotency keys to SQLite.
- **For non-idempotent commands (`toggle`), clients should use explicit target state.** Instead of `toggle`, clients should use `turn_on` or `turn_off` for reliable retry. The API should encourage this pattern in documentation and deprecation warnings.
- **The OpenAPI spec should document this limitation** when it is written, including guidance on which commands are inherently idempotent (e.g., `set_brightness 75` is idempotent, `toggle` is not).

## Constraints

| Constraint | Description |
|---|---|
| **LTD-01** | Virtual threads for all request handling. Each incoming request dispatched on its own virtual thread. Blocking I/O (SQLite reads) is acceptable. |
| **LTD-04** | All identifiers at API boundary are Crockford Base32 strings. Phase 3 performs `String` ↔ typed ID conversion. |
| **LTD-08** | Jackson JSON for all response serialization. `SNAKE_CASE` property naming convention. Phase 2 types use `Object` (not `JsonNode`) to avoid dependency leak. |
| **LTD-11** | No `synchronized` blocks — `ReentrantLock` only. Rate limiter Phase 3 implementation uses `ConcurrentHashMap` for thread-safe token buckets. |
| **INV-SE-02** | Authentication mandatory on every request. No "local trust" exception. AuthMiddleware validates before any endpoint handler receives the request. |
| **INV-ES-01** | Events are immutable after persistence. Justifies strong ETags and aggressive caching for single-event responses. |
| **INV-ES-04** | Write-ahead persistence. Command acceptance (202) guarantees the `command_issued` event is durable. |
| **INV-TO-03** | No hidden state. The `viewPosition` in API responses enables clients to detect staleness at the API boundary. |

## Sealed Hierarchies

None. This module contains no sealed types.

## Key Design Decisions

1. **All API-boundary IDs are `String`, not typed ULID wrappers.** Phase 2 types use `String` for all identifier fields. Phase 3 endpoint handlers perform conversion when calling internal subsystem interfaces. This keeps the REST API types independent of `com.homesynapse.platform` in Phase 2 and avoids a `requires` directive that would be unnecessary for consumers (like websocket-api) that only need the API contract types. Reference: Doc 09 §3.10, LTD-04.

2. **`ApiRequest.body` is `Object`, not `JsonNode`.** Avoids leaking `com.fasterxml.jackson.databind.JsonNode` into the module's public API, which would force a `requires transitive com.fasterxml.jackson.databind` on every consumer. Phase 3 endpoint handlers cast to `JsonNode` at usage site. Reference: Doc 09 §8.2.

3. **`ProblemType` is an enum, not a constants class.** The 13 problem types are a closed set for Phase 2 with compile-time type safety. Each value carries three derived properties (`slug`, `defaultStatus`, `title`) and a computed `typeUri()`. Prevents misspelled type URIs at compile time. Future Tier 2 additions extend the enum. Reference: Doc 09 §3.8.

4. **`CommandStatusResponse.lifecycle` uses `Map<CommandLifecyclePhase, LifecyclePhaseDetail>`.** This matches the JSON structure where only completed phases appear as keys. An absent key means the phase has not yet occurred — no sentinel values. `Map` naturally expresses optional phase presence. Reference: Doc 09 §4.5.

5. **`ApiException` carries a `transient ProblemDetail`.** The field is `transient` because `RuntimeException` is `Serializable` but `ProblemDetail` (a record) is not. HomeSynapse never serializes exceptions over a wire protocol — they are caught in-process and converted to HTTP responses. This follows the established `SequenceConflictException` pattern (`transient SubjectRef subjectRef`). The detail message is preserved in the superclass via `super(problemDetail.detail())`. Reference: `-Xlint:all -Werror` compliance, `SequenceConflictException` precedent.

6. **No `requires` directives in `module-info.java` for Phase 2.** All types use Java standard library types exclusively. Dependencies on other HomeSynapse modules and external libraries exist in `build.gradle.kts` for Phase 3 but are not declared in `module-info.java` until Phase 3 types actually import from them. This is unique among HomeSynapse modules. Reference: Doc 09 §8.

7. **`RestApiServer` abstracts the HTTP server choice.** Phase 2 defines the interface; Phase 3 implements against the chosen library. No Javalin, Jetty, or `com.sun.net.httpserver` types appear in any Phase 2 signature. Reference: Doc 09 §3.9.

8. **`PagedResponse<T>` is a generic record.** Java 21 supports generic records. The generic parameter provides compile-time type safety for endpoint handlers — `PagedResponse<EntitySummary>` and `PagedResponse<EventSummary>` are distinct types. Reference: Doc 09 §8.2, §3.5.

9. **`RateLimiter` returns `RateLimitResult`, not just `boolean`.** The `429 Too Many Requests` response requires a `Retry-After` header with seconds until reset. A boolean cannot convey this. The result record carries both `allowed` and `retryAfterSeconds`. Reference: Doc 09 §12.5.

10. **`ETagProvider` methods return formatted `String`.** ETags are serialized as strings in HTTP headers. The provider formats them with the correct weak/strong prefix (`W/"..."` or `"..."`). Formatting responsibility belongs in the provider, not the caller. Reference: Doc 09 §3.7.

11. **`CursorToken` includes `SortDirection`.** Cursors encode the pagination direction so subsequent pages maintain ordering without requiring the client to re-specify direction. Reference: Doc 09 §3.5.

12. **`IdempotencyEntry` is a Phase 2 record.** Although it describes an internal cache entry, defining the structure in Phase 2 provides a clear contract for the Phase 3 implementation (cache fields, TTL semantics, eviction behavior). Reference: AMD-08, Doc 09 §3.4.

13. **`ApiResponse.eTag` and `ApiResponse.cacheControl` duplicate headers.** These are exposed as named fields for middleware convenience (ETag evaluation for 304 responses) while also being present in the headers map for response serialization. Intentional redundancy. Reference: Doc 09 §8.2.

14. **`ProblemDetail.errors` is nullable, not an empty list.** The `errors` array is present only for validation errors (400 and 422). For other error types, `errors` is null. This follows the RFC 9457 convention — extension members are omitted when not applicable. Reference: Doc 09 §3.8.

15. **The five operational planes have distinct consistency contracts.** State Query: eventually consistent (projection-derived). Command: accepted ≠ confirmed. Event History: strongly consistent (immutable log). Automation: configuration-consistent. System: real-time. Phase 3 endpoint handlers must respect these contracts. Reference: Doc 09 §3.2.

## Gotchas

**GOTCHA: `CommandStatusResponse` has 8 fields, not 7.** The handoff summary table says 7 fields but the detailed specification lists 8. The `terminal` boolean is the 8th component. Count carefully: commandId, correlationId, entityId, capability, command, lifecycle, currentPhase, terminal.

**GOTCHA: `ApiRequest.body` is `Object`, not `JsonNode`.** Phase 3 endpoint handlers must cast to `JsonNode` at the usage site. Do not change this to `JsonNode` — it would force a `requires transitive com.fasterxml.jackson.databind` on every consumer.

**GOTCHA: `ProblemDetail.errors` is nullable, not an empty list.** Do not convert null errors to `List.of()` in the compact constructor. The null/non-null distinction carries semantic meaning: null means "no validation errors" (non-validation error type), non-null means "these specific fields failed validation."

**GOTCHA: `ApiException.problemDetail` is `transient`.** The field will be null after Java deserialization. This is intentional — HomeSynapse never serializes exceptions. The `detail()` text is preserved in the superclass `message` field via the `super(problemDetail.detail())` constructor call. This follows the `SequenceConflictException` pattern.

**GOTCHA: `ProblemType.DEVICE_ORPHANED` is 503, not 409.** A command to an orphaned device (lost integration connection) is 503 because the integration adapter is unavailable. This is distinct from `ENTITY_DISABLED` (409) which is a user-initiated state. Do not confuse these.

**GOTCHA: `X-Correlation-ID` is NOT the event `correlation_id`.** The client-provided correlation ID from the HTTP header is logged alongside the event's `correlation_id` but they remain separate. The event `correlation_id` is always set by `publishRoot()` to the event's own `event_id` (Doc 01 §8.3). Both appear in the command response.

**GOTCHA: `ApiResponse.headers` is `Map<String, String>` (single-valued).** If multi-valued headers (e.g., `Set-Cookie`) are needed in Phase 3, the model must be changed. Phase 2 uses the simpler single-valued model.

**GOTCHA: `ApiResponse.eTag` and `ApiResponse.cacheControl` duplicate information in `headers`.** This is intentional for middleware convenience. The values in these fields and the headers map must be consistent.

**GOTCHA: No `@Nullable` annotations — use Javadoc instead.** HomeSynapse does not have a nullability annotations dependency. Nullable fields are documented in Javadoc with `{@code null} if...` patterns. Do not add `org.jetbrains.annotations` or `javax.annotation` imports.

**GOTCHA: The `build.gradle.kts` has scaffold dependencies on other modules and Javalin/Jackson.** Leave these as-is — they are Phase 3 dependencies. They sit on the module path but are unused until Phase 3 types import from them. Do not remove them thinking they are unnecessary.

**GOTCHA: Idempotency cache is not persisted.** The LRU cache is lost on process restart. Post-restart retries with a stale key simply issue a new command. The device handles duplicate commands gracefully via the four-phase lifecycle. The 24-hour TTL covers the vast majority of retry scenarios.

**GOTCHA: `module-info.java` has NO `requires` directives.** If you accidentally import a type from another HomeSynapse module in Phase 2, the compiler will fail with "package X is not visible." Remove the import and use Java standard types instead.

## Phase 3 Notes

- **Endpoint handler implementations needed:** Entity CRUD, device CRUD, command issuance, command status, event history, automation reads, system health, configuration read/write/reload. Each handler queries internal subsystem interfaces and assembles ApiResponse.
- **Javalin (or chosen server) integration needed:** `RestApiServer` implementation against Javalin 6.x (expected path). Route registration, virtual thread dispatch, request parsing, response serialization.
- **AuthMiddleware implementation needed:** bcrypt hash validation against key store. API key generation, storage, and revocation are Phase 3 concerns.
- **RateLimiter implementation needed:** `ConcurrentHashMap` of lightweight token buckets. One `long` for token count, one `long` for last-refill timestamp per key. Bounded by number of active API keys (typically < 10).
- **ETagProvider implementation needed:** String formatting for three ETag categories. Stateless — no caching within the provider itself.
- **PaginationCodec implementation needed:** URL-safe Base64 encoding/decoding of CursorToken. Include validation against tampering and expiration.
- **ProblemDetailMapper implementation needed:** Map `HomeSynapseException` subtypes using `errorCode()` and `suggestedHttpStatus()`. Map `ConcurrentModificationException` for config write conflicts. Generic fallback to INTERNAL_ERROR (500) for unrecognized exceptions.
- **IdempotencyEntry cache needed:** In-memory LRU cache (max 10,000 entries, 24-hour TTL). Check `Idempotency-Key` header before command validation. Same key + same body → replay cached response. Same key + different body → 409 IDEMPOTENCY_KEY_CONFLICT.
- **Jackson ObjectMapper configuration needed:** SNAKE_CASE property naming, JavaTimeModule, custom serializers for API types. The ObjectMapper is shared across all endpoint handlers.
- **module-info.java will need ~9 `requires` directives:** event-model, platform-api, device-model, state-store, automation, integration-api, persistence, configuration, Jackson.
- **OpenAPI 3.1 spec generation (Tier 2):** Not in Phase 3 MVP scope. The types defined here will eventually drive OpenAPI generation.
- **CORS middleware (Phase 3):** Not defined in Phase 2. Web UI access will require CORS headers.
- **Testing strategy:** Integration tests for full request lifecycle (parse → auth → rate limit → handle → serialize). Unit tests for ProblemDetailMapper (each HomeSynapseException subtype → correct ProblemType). Performance targets from Doc 09: p99 response time for State Query endpoints.


---


## Phase 3 Cross-Module Context

*Updated 2026-05-17 (Post-M3.1 refresh). Phase 3 active — M3.1 `InProcessEventBus` landed 2026-05-17. Next milestone: M3.5a (StateProjection vertical slice). M3 governance: AMD-41/42/43 APPLIED. See `homesynapse-core-docs/design/HomeSynapse_Core_M3_Implementation_Plan_PLAN-M3-CONSOLIDATED-02.md` for the full M3 implementation plan.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: REST API serializes events by type string, not by sealed hierarchy position
- **D-05** — *`@EventType` on every event record*: event type strings used in REST filter parameters and response payloads

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for Phase 3 pattern discoveries (including M3.1 entries on default interface methods, contract test capability hooks, and JPMS-enforced JDBC-free constraints).

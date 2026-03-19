# configuration

## Purpose

The Configuration System is the single authority for reading, validating, reloading, and persisting the canonical HomeSynapse configuration. It translates human intent expressed in YAML files into validated, typed runtime state that governs every other subsystem. Every subsystem — Event Bus, Device Model, State Store, Persistence Layer, Integration Runtime, Automation Engine — receives its configuration from this module's `ConfigModel` rather than parsing YAML independently. The module also manages the encrypted secrets store, JSON Schema composition from core and integration fragments, hot reload with per-key change classification, the UI/API write path with optimistic concurrency, and forward-only schema migration. This Phase 2 specification defines the public API contracts (interfaces, records, enums, exceptions) that external modules compile against. Implementation code (SnakeYAML Engine parsing, JSON Schema validation, AES-256-GCM encryption, reload diff computation, atomic file writes) is Phase 3.

## Design Doc Reference

**Doc 06 — Configuration System** is the governing design document:
- §0: Purpose — single authority for configuration, eliminates HA's dual-storage split
- §1: Design principles — P1 (single canonical representation), P2 (schema is the contract), P3 (secrets never plaintext at rest), P4 (comprehensive not fatal validation), P5 (reload classification in schema not code), P6 (zero-config is valid)
- §3.1: Configuration loading pipeline — 6 stages (file read → YAML parse → tag resolve → default merge → schema validation → model construction)
- §3.2: Schema composition — core schemas (static, bundled) + integration schemas (runtime, from IntegrationDescriptor)
- §3.3: Reload mechanism — re-parse, diff, classify (HOT/INTEGRATION_RESTART/PROCESS_RESTART), atomicity rule, config_changed events
- §3.4: Secret store — AES-256-GCM encrypted, !secret and !env tag resolution, per-operation backups (AMD-16)
- §3.5: UI/API write path — optimistic concurrency via fileModifiedAt, ReentrantLock serialization, atomic flush
- §3.6: Validation error model — three-tier (FATAL/ERROR/WARNING), startup vs reload semantics
- §3.7: Configuration migration framework (AMD-13) — ConfigMigrator, MigrationResult, MigrationPreview, linear version chain
- §4: Data model — ConfigModel (§4.1), ConfigSection (§4.2), ConfigChangeSet/ConfigChange (§4.3), config_changed event (§4.4), config_error event (§4.5), ConfigIssue/Severity (§4.6), ConfigMutation (§4.7), SecretEntry (§4.8)
- §5: Contracts and invariants — C1 through C7 (canonical representation, zero-config, secrets encryption, validated-before-consumed, reload atomicity, config_changed events durable, composed schema consistency)
- §8: Interface specifications — ConfigurationService (§8.1/§8.3), ConfigurationAccess (§8.4), SecretStore (§8.5), SchemaRegistry (§8.6), ConfigValidator (§8.1)

## JPMS Module

```
module com.homesynapse.config {
    requires transitive com.homesynapse.event;

    exports com.homesynapse.config;
}
```

The `requires transitive com.homesynapse.event` declaration is required because `HomeSynapseException` (from event-model) appears in the public API as the superclass of `ConfigurationLoadException` and `ConfigurationReloadException`, and `ConfigurationValidationException` (from event-model) appears in the throws clause of `ConfigurationService.write()`. Any downstream module that catches configuration exceptions or calls the write path needs these event-model types on its module path.

**Note:** The original Block K handoff (Locked Decision #7) specified `requires` (non-transitive), reasoning that no event-model types appeared in public API signatures. This was incorrect — the compiler flagged it with `-Xlint:all -Werror` (`[exports]` warning). Changed to `requires transitive` to satisfy the compiler and JPMS hygiene requirements.

## Package Structure

- **`com.homesynapse.config`** — All types in a single flat package. Contains: 3 enums (Severity, ReloadClassification, ChangeType), 11 data records (ConfigIssue, SecretEntry, ConfigMutation, ConfigSection, ConfigChange, MigrationChange, ConfigChangeSet, MigrationResult, MigrationPreview, ConfigModel, ReloadResult), 2 exceptions (ConfigurationLoadException, ConfigurationReloadException), 6 service interfaces (ConfigurationService, ConfigurationAccess, SecretStore, ConfigValidator, ConfigMigrator, SchemaRegistry), and package-info.java.

## Complete Type Inventory

### Enums

| Type | Kind | Purpose | Values |
|---|---|---|---|
| `Severity` | enum (3 values) | Validation issue severity for the three-tier error model (§3.6) | `FATAL` (prevents startup, rejects reload), `ERROR` (key reverts to default on startup, rejects reload), `WARNING` (informational, value accepted) |
| `ReloadClassification` | enum (3 values) | Runtime impact of a config change, derived from `x-reload` JSON Schema annotation (§3.3) | `HOT` (immediate effect), `INTEGRATION_RESTART` (restart affected integration), `PROCESS_RESTART` (full restart, default for unannotated properties) |
| `ChangeType` | enum (5 values) | Kind of modification applied by a ConfigMigrator (§3.7) | `KEY_RENAMED`, `KEY_ADDED`, `KEY_REMOVED`, `VALUE_TRANSFORMED`, `SECTION_RESTRUCTURED` |

### Data Records

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ConfigIssue` | record (6 fields) | A single validation finding from the loading or reload pipeline | Fields: `severity` (Severity), `path` (String — JSON Schema path), `message` (String), `invalidValue` (Object, nullable — null for missing-key issues), `appliedDefault` (Object, nullable — null for FATAL/WARNING), `yamlLine` (Integer, nullable). Compact constructor validates severity, path, message non-null. |
| `SecretEntry` | record (4 fields) | Internal representation within the decrypted secrets store | Fields: `key` (String), `value` (String — plaintext after decryption), `createdAt` (Instant), `updatedAt` (Instant). All fields non-null. |
| `ConfigMutation` | record (3 fields) | A single key-level mutation for the UI/API write path | Fields: `sectionPath` (String), `key` (String), `newValue` (Object, nullable — null means remove key, revert to default). |
| `ConfigSection` | record (3 fields) | A subtree of the configuration identified by dotted path | Fields: `path` (String, e.g., "persistence.retention"), `values` (Map<String, Object>, unmodifiable via Map.copyOf()), `defaults` (Map<String, Object>, unmodifiable via Map.copyOf()). |
| `ConfigChange` | record (5 fields) | A single key change detected during reload diff (§4.3) | Fields: `sectionPath` (String), `key` (String), `oldValue` (Object, nullable — null for new keys), `newValue` (Object, nullable — null for removed keys), `reload` (ReloadClassification). |
| `MigrationChange` | record (5 fields) | A single modification applied by a ConfigMigrator (§3.7) — renamed from Doc 06's second `ConfigChange` to avoid collision | Fields: `type` (ChangeType), `path` (String), `oldValue` (Object, nullable), `newValue` (Object, nullable), `reason` (String). |
| `ConfigChangeSet` | record (2 fields) | Complete diff between two ConfigModels from the reload pipeline | Fields: `timestamp` (Instant), `changes` (List<ConfigChange>, unmodifiable via List.copyOf()). Convenience filter methods (hot(), integrationRestart(), processRestart()) are Phase 3. |
| `MigrationResult` | record (2 fields) | Output of a single ConfigMigrator.migrate() invocation | Fields: `migratedConfig` (Map<String, Object>, unmodifiable), `changes` (List<MigrationChange>, unmodifiable). |
| `MigrationPreview` | record (4 fields) | Dry-run report of migration changes without modifying the file | Fields: `fromVersion` (int), `toVersion` (int), `plannedChanges` (List<MigrationChange>, unmodifiable), `requiresUserReview` (boolean — true when migrations remove keys or transform values in lossy ways). |
| `ConfigModel` | record (5 fields) | Immutable, validated in-memory configuration — the single source of truth at runtime | Fields: `schemaVersion` (int), `loadedAt` (Instant), `fileModifiedAt` (Instant — optimistic concurrency token for write path), `sections` (Map<String, ConfigSection>, unmodifiable), `rawMap` (Map<String, Object>, unmodifiable). **Phase 2 simplification:** uses Map-based sections, not typed subsystem records (EventBusConfig etc. are Phase 3). |
| `ReloadResult` | record (3 fields) | Result of a configuration reload — new model + diff + issues | Fields: `newModel` (ConfigModel), `changeSet` (ConfigChangeSet), `issues` (List<ConfigIssue>, unmodifiable — only WARNING issues survive, since FATAL/ERROR cause rejection). |

### Exceptions

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ConfigurationLoadException` | class extends HomeSynapseException | Thrown by ConfigurationService.load() on FATAL issues | Error code: `config.load_failed`. HTTP: 503 (Service Unavailable). Two constructors: (String message) and (String message, Throwable cause). |
| `ConfigurationReloadException` | class extends HomeSynapseException | Thrown by ConfigurationService.reload() when candidate is invalid | Error code: `config.reload_failed`. HTTP: 422 (Unprocessable Entity). Two constructors: (String message) and (String message, Throwable cause). |

### Service Interfaces

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `ConfigurationService` | interface | Central coordination point for config lifecycle (§8.1, §8.3) | `load()` → ConfigModel (throws ConfigurationLoadException), `reload()` → ReloadResult (throws ConfigurationReloadException), `getCurrentModel()` → ConfigModel, `getSection(String)` → Optional<ConfigSection>, `write(List<ConfigMutation>, Instant)` (throws ConfigurationValidationException, ConcurrentModificationException) |
| `ConfigurationAccess` | interface | Read-only, integration-scoped configuration access (Doc 05 §3.8, Doc 06 §8.4) | `getConfig()` → Map<String, Object> (unmodifiable), `getString(String)` → Optional<String>, `getInt(String)` → Optional<Integer>, `getBoolean(String)` → Optional<Boolean> |
| `SecretStore` | interface | Manages encrypted secrets store (§3.4, §8.5) | `resolve(String)` → String (throws IllegalArgumentException if not found), `set(String, String)`, `remove(String)` (throws if not found), `list()` → Set<String> (unmodifiable) |
| `ConfigValidator` | interface | Pure validation function — validates parsed config against composed schema (§8.1) | `validate(Map<String, Object>, String)` → List<ConfigIssue> (unmodifiable). String parameter is JSON text, not a file path. |
| `ConfigMigrator` | interface | Forward-only migration from one schema version to the next (§3.7) | `fromVersion()` → int, `toVersion()` → int, `migrate(Map<String, Object>)` → MigrationResult. Idempotent. Does not modify input map. |
| `SchemaRegistry` | interface | Manages JSON Schema composition for validation (§8.6) | `registerCoreSchema(String, String)`, `registerIntegrationSchema(String, String)`, `getComposedSchema()` → String, `writeComposedSchema(Path)`. All schema parameters are String (JSON text), NOT JsonSchema library type. |

**Total: 22 public types + 1 module-info.java + 1 package-info.java = 24 Java files.**

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **event-model** (`com.homesynapse.event`) | `requires transitive` — Exception base class appears in public API (superclass of both config exceptions); ConfigurationValidationException in ConfigurationService.write() throws clause | `HomeSynapseException` (base class for ConfigurationLoadException, ConfigurationReloadException), `ConfigurationValidationException` (throws clause on ConfigurationService.write()), `EventPublisher` (Javadoc @see reference only — config_changed events) |

### Gradle Dependencies

```kotlin
dependencies {
    api(project(":core:event-model"))
    implementation(libs.snakeyaml.engine)
}
```

The `api` scope for event-model ensures the `HomeSynapseException` and `ConfigurationValidationException` types are transitively available to consumers. SnakeYAML Engine is `implementation` scope — it is not exposed in the public API (Phase 3 internal dependency). `networknt:json-schema-validator` is NOT yet in the version catalog — flagged as [REVIEW] for Phase 3 addition.

## Consumers

### Current consumers:
- **integration-api** — Imports `ConfigurationAccess` for the `IntegrationContext` record. Every integration adapter receives a non-null `ConfigurationAccess` instance scoped to its own configuration section. The integration-api module declares `requires transitive com.homesynapse.config` and `api(project(":config:configuration"))` because `ConfigurationAccess` appears in IntegrationContext's public API signature.

### Planned consumers (from design doc dependency graph):
- **integration-runtime** — Will instantiate `ConfigurationAccess` implementations scoped per integration type and inject them into `IntegrationContext`. Will call `ConfigurationService.load()` indirectly via lifecycle ordering. Will register integration schemas via `SchemaRegistry.registerIntegrationSchema()`.
- **rest-api** — Will use `ConfigurationService` for config read/write/reload endpoints (Doc 09). `getCurrentModel()` for reads, `write()` for mutations, `reload()` for explicit reloads.
- **lifecycle** — Will call `ConfigurationService.load()` as the FIRST subsystem initialization step during startup (Doc 12). Configuration must be loaded before any other subsystem starts.
- **automation** — Will use `SchemaRegistry.registerCoreSchema()` for automation schema registration (Doc 07 §3.2).
- **observability** — Will use `ConfigModel` for configuration state dashboards. Will surface `ConfigIssue` lists for diagnostic display.
- **CLI commands** — `validate-config` uses `ConfigValidator` + `SchemaRegistry.getComposedSchema()`. `secrets` uses `SecretStore`. `migrate-config` uses `ConfigMigrator` chain + `MigrationPreview`.

## Cross-Module Contracts

- **`ConfigurationService.load()` is called exactly once during startup.** It is the FIRST subsystem initialization step (Doc 12). All other subsystems receive their configuration from the loaded model. Subsequent access uses `getCurrentModel()`.
- **`ConfigurationService.getCurrentModel()` is non-blocking.** It returns the most recently loaded or reloaded model via a volatile reference. Concurrent reads from multiple subsystems are safe.
- **`ConfigurationService.write()` throws two different exception types.** `ConfigurationValidationException` (from event-model, checked) if the mutated config fails schema validation. `ConcurrentModificationException` (from java.util, runtime) if the file was externally modified since the model was loaded. Callers must handle both.
- **`ConfigModel.fileModifiedAt` is the optimistic concurrency token.** The UI/API write path checks this against the file's current mtime before writing. If they differ, an external edit occurred (text editor, scp, Git pull) and the write is rejected.
- **Reload atomicity: FATAL or ERROR in candidate → entire reload rejected.** The active ConfigModel remains unchanged. This is stricter than startup, where ERROR keys revert to defaults. The distinction exists because on startup there is no prior good state to preserve.
- **`ConfigurationAccess` is ALWAYS provided to every integration adapter.** Unlike `TelemetryWriter`, `SchedulerService`, and `ManagedHttpClient` which are optional and gated by `RequiredService` declarations, `ConfigurationAccess` is always non-null in `IntegrationContext`. Even adapters with no configuration get an empty section (INV-CE-02 — zero-config is valid).
- **`ConfigurationAccess.getConfig()` returns only the adapter's own section.** The map contains only keys under `integrations.{type}:`. The adapter cannot see global configuration or other integrations' sections. All `!secret` and `!env` values are already resolved.
- **`SchemaRegistry` parameters are JSON text strings, not library types.** This is a deliberate JPMS hygiene decision — the `networknt:json-schema-validator` library type `JsonSchema` is never exposed in the public API. Phase 3 implementation parses JSON strings internally.
- **`ConfigValidator.validate()` is a pure function with no side effects.** It validates parsed config against composed JSON Schema in allErrors mode (all issues collected in a single pass). Thread-safe and stateless.
- **`ConfigMigrator` implementations must be idempotent.** Applying the same migration twice to the same input produces the same output. The `migrate()` method does not modify the input map — it returns a new map via `MigrationResult`.
- **`config_changed` events are produced via `EventPublisher.publishRoot()`** with `EventOrigin.SYSTEM` and NORMAL priority. These events are durable (WAL commit before return per INV-ES-04) before any subscriber is notified of the config change.
- **`config_error` diagnostic events are produced for each ERROR-severity issue at startup.** These are `EventOrigin.SYSTEM`, DIAGNOSTIC priority, using the well-known system subject reference.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-09** | YAML 1.2 via SnakeYAML Engine, JSON Schema validation via `networknt:json-schema-validator`, config in `/etc/homesynapse/`. YAML 1.2 eliminates the NO→false, on→boolean coercion bugs of YAML 1.1. |
| **LTD-11** | No `synchronized` blocks — use `ReentrantLock`. The write path uses a single ReentrantLock for serialization (§3.5). |
| **LTD-15** | SLF4J structured logging. Resolved secret values must be redacted in log output (fields annotated `x-sensitive: true` in the schema). |
| **INV-CE-01** | The configuration file is the sole source of truth. ConfigModel is always derived from the YAML file on disk. No runtime state modifies configuration outside the write path. |
| **INV-CE-02** | Zero-configuration first run. Every config key has a schema default. Empty or absent file → complete ConfigModel from defaults. ConfigurationAccess returns empty section for unconfigured integrations. |
| **INV-CE-03** | Schema is the contract. Every config option defined in JSON Schema. IDE auto-completion, CLI validation, and runtime enforcement all derive from the same schema. |
| **INV-CE-06** | Migration tooling accompanies schema evolution. ConfigMigrator provides forward-only migration. Backup-before-migrate is mandatory. |
| **INV-SE-03** | Secrets encrypted at rest (AES-256-GCM). Resolved values exist only in memory within ConfigModel fields. Never written back to YAML or logged. |
| **INV-RF-06** | Graceful degradation. On startup, ERROR keys revert to defaults (system starts degraded). On reload, ERROR causes full rejection (preserves prior good state). |
| **INV-ES-04** | Write-ahead persistence. config_changed events are durable (WAL commit) before subscribers are notified. |

## Sealed Hierarchies

None. This module contains no sealed types.

## Key Design Decisions

1. **ConfigModel uses `Map<String, ConfigSection>` and `Map<String, Object>` in Phase 2, not typed subsystem records.** Doc 06 §4.1 shows typed fields like `EventBusConfig`, `DeviceModelConfig`, etc. These are Phase 3 implementation details that depend on each subsystem's §9 schema content. Phase 2 carries `sections` (Map<String, ConfigSection>) for structured access and `rawMap` (Map<String, Object>) for the complete parsed-and-validated map. This gives ConfigurationAccess and ConfigurationService everything they need without depending on per-subsystem types.

2. **ConfigChange name collision resolved: reload version keeps `ConfigChange`, migration version renamed to `MigrationChange`.** Doc 06 §4.3 defines `ConfigChange(sectionPath, key, oldValue, newValue, reload)` for the reload diff. Doc 06 §3.7 defines a different `ConfigChange(type, path, oldValue, newValue, reason)` for migration. The reload version is the primary consumer-facing type (used in ConfigChangeSet, REST API, subscribers). The migration version is renamed to `MigrationChange`.

3. **SchemaRegistry uses `String` (JSON text), not `JsonSchema` library type.** Doc 06 §8.6 references `JsonSchema` in method signatures, which is a `networknt:json-schema-validator` library type. Leaking external library types in a public JPMS module API violates JPMS hygiene. Phase 2 signatures use `String` for all schema parameters. Phase 3 parses JSON strings into `JsonSchema` internally.

4. **ConfigurationAccess is REQUIRED (non-null) in IntegrationContext.** Unlike TelemetryWriter, SchedulerService, and ManagedHttpClient which are optional, ConfigurationAccess is always provided. An adapter always has configuration access for its own section — even if that section is empty (INV-CE-02).

5. **Two NEW exceptions in `com.homesynapse.config`, not in event-model.** `ConfigurationLoadException` (config.load_failed, HTTP 503) and `ConfigurationReloadException` (config.reload_failed, HTTP 422) are domain-specific to the Configuration System. They follow the exact same pattern as `ConfigurationValidationException` in event-model but live in this module's package. `ConfigurationValidationException` already exists in event-model and is NOT duplicated.

6. **`ConfigMigrator` is an interface, not an abstract class.** Doc 06 §3.7 shows it as an interface with three methods. This allows implementation flexibility in Phase 3 (lambda implementations for simple migrations, full classes for complex ones).

7. **`SecretStore.resolve()` exception type deferred to Phase 3.** The Javadoc documents `@throws IllegalArgumentException` as a placeholder. Phase 3 may introduce a `SecretNotFoundException` or reuse an existing exception type.

8. **`requires transitive com.homesynapse.event` (corrected from handoff).** The original handoff (Locked Decision #7) specified non-transitive, reasoning no event-model types appeared in public API. The compiler disagreed: `HomeSynapseException` is the superclass of both config exceptions, and `ConfigurationValidationException` is in ConfigurationService.write()'s throws clause. Changed to `requires transitive` to satisfy `-Xlint:all -Werror`.

9. **`ReloadResult` is a new record not shown in Doc 06 §4 but referenced in §8.3.** The `reload()` method needs to return the new model, the change set, and any issues. ReloadResult captures this triple.

10. **`ConfigChangeSet` convenience methods are Phase 3.** Doc 06 §4.3 shows `hot()`, `integrationRestart()`, and `processRestart()` filter methods. These are implementation logic (stream filtering), not interface specification. Phase 2 defines the record with `timestamp` and `changes` fields only.

## Gotchas

**GOTCHA: `ConfigurationValidationException` is in event-model, NOT in this module.** Do not duplicate it. `ConfigurationService.write()` references it via import from `com.homesynapse.event`. Both `ConfigurationLoadException` and `ConfigurationReloadException` are in `com.homesynapse.config`.

**GOTCHA: `ConfigChange` and `MigrationChange` are two different types.** Doc 06 uses the name `ConfigChange` for both the reload diff record (§4.3) and the migration record (§3.7). The codebase resolves this collision by renaming the migration version to `MigrationChange`. Do not confuse them — they have different fields and different purposes.

**GOTCHA: `ConfigModel` has NO typed subsystem records in Phase 2.** Doc 06 §4.1 shows `EventBusConfig`, `DeviceModelConfig`, etc. as fields. Phase 2 uses `Map<String, ConfigSection>` and `Map<String, Object>` instead. Typed records are Phase 3 additions.

**GOTCHA: `ConfigurationAccess` is non-null in IntegrationContext.** Unlike TelemetryWriter/SchedulerService/ManagedHttpClient which are nullable and gated by RequiredService, configAccess is always present. The IntegrationContext compact constructor validates it with `Objects.requireNonNull`. An adapter with no config gets an empty section.

**GOTCHA: `SchemaRegistry` parameters are JSON text strings, not file paths.** The Javadoc explicitly notes this. `registerCoreSchema("persistence", schemaJsonString)` — not `registerCoreSchema("persistence", "/path/to/schema.json")`. Phase 3 implementation parses the JSON string internally.

**GOTCHA: `json-schema-validator` is NOT in the version catalog.** The `networknt:json-schema-validator` library is NOT yet in `gradle/libs.versions.toml`. This does not affect Phase 2 (no implementation code). It MUST be added before Phase 3 implementation of `ConfigValidator` and `SchemaRegistry`.

**GOTCHA: IntegrationContext field ordering changed in Block K.** `configAccess` was inserted at position 7 (after `healthReporter`, before `schedulerService`) to group all required (non-null) fields together before optional (nullable) fields. Any code constructing IntegrationContext must include the new field.

**GOTCHA: `ConcurrentModificationException` in ConfigurationService.write() is a RuntimeException.** The Block K handoff described both exceptions as "checked" — `ConfigurationValidationException` is checked (extends HomeSynapseException extends Exception), but `ConcurrentModificationException` is unchecked (extends RuntimeException). It's declared in the throws clause for documentation purposes but does not require a catch block.

**GOTCHA: `module-info.java` uses `requires transitive`, not `requires`.** The original Locked Decision #7 was incorrect. The compiler flags it as an `[exports]` warning promoted to error by `-Werror`. Three types from event-model leak through the public API: `HomeSynapseException` (superclass ×2) and `ConfigurationValidationException` (throws clause).

**GOTCHA: `package-info.java` was repurposed, not deleted.** The handoff said to delete the scaffold. Due to VM disk space constraints preventing bash execution, it was repurposed as a proper package-level Javadoc file. This is benign and arguably better practice.

## Phase 3 Notes

- **ConfigurationService implementation needed:** `YamlConfigurationService` (or similar) implementing the full loading pipeline (§3.1), reload mechanism (§3.3), and write path (§3.5). Thread-safe with a single `ReentrantLock` for write serialization. The active ConfigModel is held via a `volatile` reference for lock-free reads.
- **ConfigurationAccess implementation needed:** Scoped implementation that wraps a section of the raw map, filtered to `integrations.{type}:` keys. Constructed by the Integration Supervisor when provisioning adapter contexts. Immutable after construction.
- **ConfigValidator implementation needed:** Wraps `networknt:json-schema-validator` in allErrors mode. Takes `String` (JSON text) parameters and parses them into `JsonSchema` internally. Returns unmodifiable `List<ConfigIssue>`.
- **SchemaRegistry implementation needed:** Collects core and integration schema fragments. Composes them into a single root schema document with `$ref` pointers. Writes composed schema to `/etc/homesynapse/schema/config.schema.json` for VS Code auto-completion.
- **SecretStore implementation needed:** AES-256-GCM encryption/decryption of `secrets.enc`. Key file at `/etc/homesynapse/.secret-key` with POSIX 0400 permissions. Per-operation backup rotation (AMD-16, max 5 backups).
- **ConfigMigrator implementations needed:** One concrete class per schema version transition. Each operates on raw YAML map before validation. Must be idempotent. Must include round-trip tests (Phase 3 testing requirement).
- **Typed subsystem records needed:** `EventBusConfig`, `DeviceModelConfig`, `StateStoreConfig`, `PersistenceConfig`, `IntegrationRuntimeConfig`, `ConfigSystemConfig` — each mirrors the JSON Schema for its section. These replace the Phase 2 `Map<String, ConfigSection>` approach on `ConfigModel`.
- **ConfigChangeSet convenience methods needed:** `hot()`, `integrationRestart()`, `processRestart()` — stream filters on the changes list by ReloadClassification.
- **Event production:** Implementation must produce `config_changed` events via `EventPublisher.publishRoot()` with `EventOrigin.SYSTEM` and NORMAL priority on reload, and `config_error` events with DIAGNOSTIC priority for each ERROR issue at startup.
- **`json-schema-validator` dependency:** Must be added to `gradle/libs.versions.toml` before implementation. Use `implementation` scope (not `api`) to keep it out of the public module API.
- **Testing strategy:** Unit tests for record construction, field validation, Map.copyOf() defensive copying. Integration tests for full loading pipeline round-trip, reload atomicity (ERROR in candidate → active model unchanged), write path optimistic concurrency, secret resolution, schema composition. Performance targets from Doc 06 §10 should be investigation triggers for implementation.
- **SecretStore.resolve() exception type:** Phase 3 decision — either introduce `SecretNotFoundException` or reuse `IllegalArgumentException`. Document in the implementation.

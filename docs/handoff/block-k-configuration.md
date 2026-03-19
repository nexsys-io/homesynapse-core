# Block K — Configuration System

**Module:** `config/configuration`
**Package:** `com.homesynapse.config`
**Design Doc:** Doc 06 — Configuration System (§3, §4, §8) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :config:configuration:compileJava`

---

## Strategic Context

The Configuration System is the single authority for reading, validating, reloading, and persisting canonical configuration in HomeSynapse. It translates human intent expressed in YAML files into validated, typed runtime state that governs every other subsystem. Every subsystem — Event Bus, Device Model, State Store, Persistence Layer, Integration Runtime, Automation Engine — receives its configuration from this module's `ConfigModel` rather than parsing YAML independently.

The configuration module contains **public API interfaces, records, and enums** consumed by other modules. The **implementation** (SnakeYAML Engine parsing, JSON Schema validation, secret store encryption, reload orchestration, write path serialization) is Phase 3. This block defines the contracts that other modules compile against.

The most critical cross-module deliverable is **ConfigurationAccess** — the integration-scoped configuration interface defined in Doc 05 §3.8. Once Block K produces `ConfigurationAccess`, `IntegrationContext` in the integration-api module must be updated to add the `configAccess` field. Unlike `TelemetryWriter` (which was an Object placeholder), `ConfigurationAccess` is a new field addition — it does not replace an existing placeholder.

## Scope

**IN:** All public-facing interfaces, records, and enums from Doc 06 §4 and §8 that external modules consume. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references. `module-info.java` with correct exports and requires. Two new exception types extending `HomeSynapseException`. Cross-module `IntegrationContext` update.

**OUT:** Implementation code. Tests. SnakeYAML Engine parsing. JSON Schema validation logic (`networknt:json-schema-validator`). Secret store AES-256-GCM encryption/decryption. Tag constructor registration. Default merge logic. Reload diff computation. Atomic file write mechanics. Schema composition logic. `ConfigModel` subsystem-specific typed records (`EventBusConfig`, `DeviceModelConfig`, etc. — Phase 3). CLI commands (`validate-config`, `secrets`, `migrate-config`).

---

## Locked Decisions

1. **ConfigModel uses `Map<String, Object>` for `rawMap`, not typed subsystem records in Phase 2.** The design doc §4.1 shows `EventBusConfig`, `DeviceModelConfig`, `StateStoreConfig`, etc. as typed record fields. These are Phase 3 implementation details — they depend on each subsystem's §9 schema content. In Phase 2, ConfigModel carries: `schemaVersion` (int), `loadedAt` (Instant), `fileModifiedAt` (Instant), `sections` (Map<String, ConfigSection>, unmodifiable), and `rawMap` (Map<String, Object>, unmodifiable). The `rawMap` is the complete parsed-and-validated map; the `sections` map provides structured access by section path. This gives ConfigurationAccess and ConfigurationService everything they need without depending on per-subsystem typed records.

2. **ConfigChange name collision resolved: reload version keeps `ConfigChange`, migration version renamed to `MigrationChange`.** Doc 06 §4.3 defines `ConfigChange(sectionPath, key, oldValue, newValue, reload:ReloadClassification)` for the reload diff. Doc 06 §3.7 defines a different `ConfigChange(type:ChangeType, path, oldValue, newValue, reason:String)` for migration. The reload version is the primary consumer-facing type (used in ConfigChangeSet, consumed by REST API and subscribers). The migration version is renamed to `MigrationChange` to avoid the collision. The `ChangeType` enum and `MigrationResult`/`MigrationPreview` records reference `MigrationChange`.

3. **SchemaRegistry public API uses `String` (JSON text), not `JsonSchema` library type.** Doc 06 §8.6 references `JsonSchema` in method signatures. This is a `networknt:json-schema-validator` library type. Leaking external library types in a public module API violates good JPMS hygiene. Phase 2 signatures use `String` for schema parameters (JSON text) and return types. Phase 3 implementation will parse the JSON String into `JsonSchema` internally. The `json-schema-validator` library stays as an `implementation` dependency, not `api`.

4. **ConfigurationAccess is a REQUIRED (non-null) field in IntegrationContext.** Unlike `TelemetryWriter`, `SchedulerService`, and `ManagedHttpClient` which are optional and gated by `RequiredService` declarations, `ConfigurationAccess` is always provided to every integration adapter. An adapter always has configuration access for its own section — even if the section is empty (zero-config default per INV-CE-02). The compact constructor validates it as non-null.

5. **Two new exceptions: `ConfigurationLoadException` and `ConfigurationReloadException`.** Both extend `HomeSynapseException` from event-model, following the same pattern as `ConfigurationValidationException` (which already exists in event-model). `ConfigurationLoadException` (error code `config.load_failed`, HTTP 503) is thrown by `ConfigurationService.load()` on FATAL issues. `ConfigurationReloadException` (error code `config.reload_failed`, HTTP 422) is thrown by `ConfigurationService.reload()` when the candidate is invalid. Both go in the `com.homesynapse.config` package (not event-model — they are configuration-domain exceptions, unlike the generic `ConfigurationValidationException` which is used by multiple subsystems).

6. **`ConfigMigrator` is an interface, not abstract class.** Doc 06 §3.7 shows it as an interface with `fromVersion()`, `toVersion()`, and `migrate(Map<String, Object>)`. Phase 2 defines the interface and the `MigrationResult`/`MigrationPreview`/`MigrationChange`/`ChangeType` types it references. Phase 3 implements concrete migrators.

7. **Module requires: `com.homesynapse.event` only, no `requires transitive`.** The configuration module `requires com.homesynapse.event` for `HomeSynapseException` (base class of the two new exceptions) and for Javadoc `@see` references to `EventPublisher`. No types from event-model appear in the public API signatures of configuration types — `ConfigModel`, `ConfigurationService`, `ConfigurationAccess`, etc. all use Java standard types (Instant, Map, Optional, String, int, boolean, List). Therefore no `requires transitive` is needed. Downstream modules that read `com.homesynapse.config` do NOT automatically get event-model access — they must declare it themselves if they need it.

8. **`json-schema-validator` is NOT in the version catalog.** The `networknt:json-schema-validator` library is listed in the implementation plan as a dependency but is NOT yet in `gradle/libs.versions.toml`. This does NOT affect Phase 2 (no implementation code uses it). Flag as `[REVIEW]` for Phase 3 — it will need to be added to the version catalog before implementation begins.

9. **`ConfigValidator` uses `List<ConfigIssue>` as validation return type.** Doc 06 §8.1 lists ConfigValidator as validating parsed config against composed schema. The interface takes `Map<String, Object> parsedConfig` and `String composedSchema` (JSON text) and returns `List<ConfigIssue>`. This is a pure validation function with no side effects.

10. **`ReloadResult` is a new record not shown in Doc 06 §4 but referenced in §8.3.** The `reload()` method returns a result containing the new ConfigModel, the ConfigChangeSet, and any ConfigIssues. ReloadResult captures this triple: `newModel` (ConfigModel), `changeSet` (ConfigChangeSet), `issues` (List<ConfigIssue>, unmodifiable).

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Enums (no dependencies)

| File | Type | Notes |
|------|------|-------|
| `Severity.java` | enum (3 values) | Doc 06 §4.6: `FATAL`, `ERROR`, `WARNING`. Validation issue severity for the three-tier error model (§3.6). Javadoc: FATAL prevents startup/rejects reload, ERROR reverts key to schema default, WARNING is informational. |
| `ReloadClassification.java` | enum (3 values) | Doc 06 §4.3: `HOT`, `INTEGRATION_RESTART`, `PROCESS_RESTART`. Derived from the `x-reload` JSON Schema annotation (§3.3). Javadoc: HOT takes effect on next ConfigModel access, INTEGRATION_RESTART requires restarting the affected integration, PROCESS_RESTART requires full process restart. Default for unannotated properties is PROCESS_RESTART. |
| `ChangeType.java` | enum (5 values) | Doc 06 §3.7: `KEY_RENAMED`, `KEY_ADDED`, `KEY_REMOVED`, `VALUE_TRANSFORMED`, `SECTION_RESTRUCTURED`. Used in `MigrationChange` to classify what a migration step did. |

### Group 2: Data Records (depends on enums)

| File | Type | Notes |
|------|------|-------|
| `ConfigIssue.java` | record (6 fields) | Doc 06 §4.6: `severity` (Severity), `path` (String — JSON Schema path), `message` (String), `invalidValue` (Object, **@Nullable**), `appliedDefault` (Object, **@Nullable**), `yamlLine` (Integer, **@Nullable**). Javadoc: a single validation finding from the loading or reload pipeline. `invalidValue` is null for missing-key issues. `appliedDefault` is null for FATAL and WARNING severities. `yamlLine` is null when the parser cannot determine the line number. Compact constructor: `Objects.requireNonNull(severity)`, `Objects.requireNonNull(path)`, `Objects.requireNonNull(message)`. |
| `SecretEntry.java` | record (4 fields) | Doc 06 §4.8: `key` (String), `value` (String), `createdAt` (Instant), `updatedAt` (Instant). Javadoc: internal representation within the decrypted secrets store. All fields non-null. Compact constructor validates all four non-null. |
| `ConfigMutation.java` | record (3 fields) | Doc 06 §4.7: `sectionPath` (String), `key` (String), `newValue` (Object, **@Nullable** — null means remove the key, reverting to schema default). Javadoc: a single key-level mutation for the UI/API write path (§3.5). Compact constructor: `Objects.requireNonNull(sectionPath)`, `Objects.requireNonNull(key)`. |
| `ConfigSection.java` | record (3 fields) | Doc 06 §4.2: `path` (String — dotted path, e.g. `"persistence.retention"`), `values` (Map<String, Object>, unmodifiable), `defaults` (Map<String, Object>, unmodifiable). Javadoc: a subtree of the configuration identified by dotted path. Compact constructor: `Objects.requireNonNull(path)`, `Objects.requireNonNull(values)`, `Objects.requireNonNull(defaults)`, make both maps unmodifiable via `Map.copyOf()`. |
| `ConfigChange.java` | record (5 fields) | Doc 06 §4.3: `sectionPath` (String), `key` (String), `oldValue` (Object, **@Nullable**), `newValue` (Object, **@Nullable**), `reload` (ReloadClassification). Javadoc: a single key change detected during the reload diff. `oldValue` is null for newly added keys; `newValue` is null for removed keys. Compact constructor: `Objects.requireNonNull(sectionPath)`, `Objects.requireNonNull(key)`, `Objects.requireNonNull(reload)`. |
| `MigrationChange.java` | record (5 fields) | Doc 06 §3.7 (renamed from `ConfigChange` to avoid collision): `type` (ChangeType), `path` (String), `oldValue` (Object, **@Nullable**), `newValue` (Object, **@Nullable**), `reason` (String). Javadoc: a single modification applied by a ConfigMigrator during schema migration. Compact constructor: `Objects.requireNonNull(type)`, `Objects.requireNonNull(path)`, `Objects.requireNonNull(reason)`. |
| `ConfigChangeSet.java` | record (2 fields) | Doc 06 §4.3: `timestamp` (Instant), `changes` (List<ConfigChange>, unmodifiable). Javadoc: the complete diff between two ConfigModels, produced by the reload pipeline. The design doc shows convenience filter methods (`hot()`, `integrationRestart()`, `processRestart()`) — these are Phase 3 implementation. Phase 2 defines the record with the two fields only. Compact constructor: `Objects.requireNonNull(timestamp)`, `Objects.requireNonNull(changes)`, make list unmodifiable via `List.copyOf()`. |
| `MigrationResult.java` | record (2 fields) | Doc 06 §3.7: `migratedConfig` (Map<String, Object>, unmodifiable), `changes` (List<MigrationChange>, unmodifiable). Javadoc: the output of a single ConfigMigrator.migrate() invocation. Compact constructor: non-null validation, make both unmodifiable. |
| `MigrationPreview.java` | record (4 fields) | Doc 06 §3.7: `fromVersion` (int), `toVersion` (int), `plannedChanges` (List<MigrationChange>, unmodifiable), `requiresUserReview` (boolean). Javadoc: dry-run report of migration changes without modifying the file. `requiresUserReview` is true when migrations remove keys or transform values in lossy ways. Compact constructor: non-null validation on plannedChanges, make list unmodifiable. |
| `ConfigModel.java` | record (5 fields) | Simplified Phase 2 version of Doc 06 §4.1: `schemaVersion` (int), `loadedAt` (Instant), `fileModifiedAt` (Instant), `sections` (Map<String, ConfigSection>, unmodifiable), `rawMap` (Map<String, Object>, unmodifiable). Javadoc: immutable, validated in-memory configuration. `fileModifiedAt` is the file's mtime at read time — the optimistic concurrency token for the write path (§3.5). `sections` provides structured access by dotted path. `rawMap` is the complete parsed-and-validated map for ConfigurationAccess. Compact constructor: `Objects.requireNonNull(loadedAt)`, `Objects.requireNonNull(fileModifiedAt)`, `Objects.requireNonNull(sections)`, `Objects.requireNonNull(rawMap)`, make both maps unmodifiable. |
| `ReloadResult.java` | record (3 fields) | Derived from Doc 06 §8.3 reload() return description: `newModel` (ConfigModel), `changeSet` (ConfigChangeSet), `issues` (List<ConfigIssue>, unmodifiable). Javadoc: the result of a configuration reload, containing the new model, the diff from the previous model, and any validation warnings encountered. Compact constructor: non-null validation on all three, make issues list unmodifiable. |

### Group 3: Exceptions (depends on event-model HomeSynapseException)

| File | Type | Notes |
|------|------|-------|
| `ConfigurationLoadException.java` | exception | Extends `HomeSynapseException`. Error code: `config.load_failed`. HTTP status: 503 (Service Unavailable — the system cannot start because configuration is invalid). Two constructors: `(String message)` and `(String message, Throwable cause)`. Javadoc: thrown by `ConfigurationService.load()` when the loading pipeline (§3.1) encounters FATAL validation issues that prevent startup. Follow exact same pattern as `ConfigurationValidationException` in event-model. |
| `ConfigurationReloadException.java` | exception | Extends `HomeSynapseException`. Error code: `config.reload_failed`. HTTP status: 422 (Unprocessable Entity — the candidate configuration is semantically invalid). Two constructors: `(String message)` and `(String message, Throwable cause)`. Javadoc: thrown by `ConfigurationService.reload()` when the candidate configuration contains FATAL or ERROR validation issues. The active configuration remains unchanged (§3.3 atomicity guarantee). |

### Group 4: Service Interfaces (depends on records and exceptions)

| File | Type | Notes |
|------|------|-------|
| `ConfigurationService.java` | interface | Doc 06 §8.1 + §8.3. Five methods: `ConfigModel load() throws ConfigurationLoadException` — execute full loading pipeline, called once during startup; `ReloadResult reload() throws ConfigurationReloadException` — execute reload pipeline; `ConfigModel getCurrentModel()` — return active ConfigModel, non-blocking; `Optional<ConfigSection> getSection(String path)` — return section by dotted path; `void write(List<ConfigMutation> mutations, Instant fileModifiedAt) throws ConfigurationValidationException, java.util.ConcurrentModificationException` — apply mutations through the write path. Javadoc: central coordination point consumed by Startup (Doc 12), REST API (Doc 09), and CLI. Thread-safe — concurrent reads of `getCurrentModel()` and `getSection()` are safe. |
| `ConfigurationAccess.java` | interface | Doc 06 §8.4 (contract defined in Doc 05 §3.8, implemented by configuration module). Four methods: `Map<String, Object> getConfig()` — return the validated configuration subtree for this integration type as an unmodifiable map; `Optional<String> getString(String key)` — convenience accessor; `Optional<Integer> getInt(String key)` — convenience accessor; `Optional<Boolean> getBoolean(String key)` — convenience accessor. Javadoc: read-only, integration-scoped access. The map contains only keys under `integrations.{type}:`. All `!secret` and `!env` values are resolved. Scoped at construction time by the Configuration System. Thread-safe and immutable after construction. |
| `SecretStore.java` | interface | Doc 06 §8.5. Four methods: `String resolve(String key)` — return decrypted value or throw (see Gotchas for exception type); `void set(String key, String value)` — store or update, creates store on first use; `void remove(String key)` — remove by key; `Set<String> list()` — return unmodifiable set of key names. Javadoc: manages the encrypted secrets store (§3.4). Used internally during tag resolution. `resolve()` throws a runtime exception (Phase 3 will define `SecretNotFoundException` or reuse an existing exception type — for Phase 2, document the throw contract in Javadoc without committing to an exception type). `remove()` throws if key not found. Thread-safe. |
| `ConfigValidator.java` | interface | Doc 06 §8.1. Single method: `List<ConfigIssue> validate(Map<String, Object> parsedConfig, String composedSchemaJson)` — validate parsed config against composed JSON Schema. Returns all issues in a single pass (allErrors mode). The returned list is unmodifiable. Javadoc: pure validation function with no side effects. Consumed by loading pipeline (§3.1), reload pipeline (§3.3), and validate-config CLI. Thread-safe. |
| `ConfigMigrator.java` | interface | Doc 06 §3.7. Three methods: `int fromVersion()` — source schema version; `int toVersion()` — target schema version; `MigrationResult migrate(Map<String, Object> rawConfig)` — apply migration to the raw YAML map. Javadoc: forward-only migration from one schema version to the next. Migrations form a linear chain (1→2, 2→3, etc.). Each implementation operates on the raw YAML map (parsed but not yet validated). Thread-safe and idempotent. |
| `SchemaRegistry.java` | interface | Doc 06 §8.6. Four methods: `void registerCoreSchema(String sectionName, String schemaJson)` — register a static core subsystem schema (JSON text); `void registerIntegrationSchema(String integrationType, String schemaJson)` — register an integration's schema fragment (JSON text); `String getComposedSchema()` — return the fully composed JSON Schema as a JSON string; `void writeComposedSchema(java.nio.file.Path outputPath) throws java.io.IOException` — serialize composed schema to disk. Javadoc: manages schema composition. Core schemas registered during startup; integration schemas contributed at adapter registration. Uses String (JSON text) parameters — not the `JsonSchema` library type — to avoid leaking external dependencies. Thread-safe. |

### Group 5: Module Descriptor

| File | Notes |
|------|-------|
| `module-info.java` | `module com.homesynapse.config { requires com.homesynapse.event; exports com.homesynapse.config; }`. The `requires com.homesynapse.event` provides access to `HomeSynapseException` (base class for the two exceptions). No `requires transitive` needed — all public API types use Java standard types only. |

### Group 6: IntegrationContext Update (cross-module)

| File | Notes |
|------|-------|
| `IntegrationContext.java` (in integration-api) | Add a `configAccess` field of type `ConfigurationAccess` after the `healthReporter` field (position 7, before `schedulerService`). This is a REQUIRED (non-null) field — add `Objects.requireNonNull(configAccess, "configAccess must not be null")` to the compact constructor. Update the class-level Javadoc to document `configAccess` as always-provided (not gated by RequiredService). Update the `@param` list to include the new field. |
| `module-info.java` (in integration-api) | Add `requires transitive com.homesynapse.config;` — the ConfigurationAccess type appears in IntegrationContext's public API signature, so downstream modules need transitive access. |
| `build.gradle.kts` (in integration-api) | Add `api(project(":config:configuration"))` to dependencies. |

---

## File Placement

All configuration types go in: `config/configuration/src/main/java/com/homesynapse/config/`
Module info: `config/configuration/src/main/java/module-info.java` (create new)

Delete the existing `package-info.java` file at `config/configuration/src/main/java/com/homesynapse/config/package-info.java` — it's a scaffold placeholder that will be replaced by real types.

---

## Cross-Module Type Dependencies

The configuration module imports types from one existing module:

**From `com.homesynapse.event` (event-model):**
- `HomeSynapseException` — base class for `ConfigurationLoadException` and `ConfigurationReloadException`

**The following types from event-model are referenced in Javadoc `@see` tags only (not in API signatures):**
- `EventPublisher` — referenced in ConfigurationService Javadoc (config_changed events)
- `ConfigurationValidationException` — referenced in ConfigurationService.write() throws clause (this exception lives in event-model, not in this module)

**Exported to (downstream consumers):**
- `com.homesynapse.integration` (integration-api) — IntegrationContext uses ConfigurationAccess
- Future: `com.homesynapse.rest` (rest-api) — ConfigurationService for config read/write/reload endpoints
- Future: `com.homesynapse.lifecycle` — ConfigurationService.load() during startup sequencing
- Future: `com.homesynapse.automation` — SchemaRegistry for automation schema registration

---

## Javadoc Standards

Per Sprint 1 Blocks G–J lessons:
1. Every `@param` documents nullability (`never {@code null}` or `{@code null} if...`)
2. Every type has `@see` cross-references to related types
3. Thread-safety explicitly stated on all interfaces
4. Class-level Javadoc explains the "why" — what role this type plays in HomeSynapse
5. Reference Doc 06 sections in class-level Javadoc
6. ConfigurationService Javadoc should document the startup-vs-reload error handling distinction (§3.6)
7. ConfigurationAccess Javadoc should document integration scoping — adapter can only see its own section
8. ConfigModel Javadoc should document the concurrency token pattern (`fileModifiedAt` for write path)
9. ConfigIssue Javadoc should document the three-severity model with specific examples
10. SchemaRegistry Javadoc should explicitly note that String parameters are JSON text, not file paths
11. Both exception classes should include `@see` to the interface methods that throw them

---

## Key Design Details for Javadoc Accuracy

1. **ConfigModel.fileModifiedAt is the optimistic concurrency token.** The UI/API write path (§3.5) checks this against the file's current mtime before writing. If they differ, an external edit occurred and the write is rejected with `ConcurrentModificationException`.

2. **ConfigurationAccess.getConfig() returns an unmodifiable map.** The map contains only keys under `integrations.{type}:` — the adapter cannot see global configuration or other integrations' sections. All `!secret` and `!env` values are already resolved. The map is typed according to the integration's registered JSON Schema.

3. **ConfigurationService.load() is called exactly once during startup.** It is the first subsystem initialization step (Doc 12). All other subsystems receive their configuration from the loaded model. Subsequent access uses `getCurrentModel()`.

4. **ConfigurationService.write() throws two different exception types.** `ConfigurationValidationException` (from event-model) if the mutated config fails schema validation. `ConcurrentModificationException` (from java.util) if the file was externally modified. Both are checked exceptions.

5. **Reload atomicity: FATAL or ERROR in candidate → entire reload rejected.** The active ConfigModel remains unchanged. This is stricter than startup behavior, where ERROR keys revert to defaults. The distinction exists because on startup there is no prior good state to preserve.

6. **ConfigChangeSet convenience methods are Phase 3.** Doc 06 §4.3 shows `hot()`, `integrationRestart()`, and `processRestart()` filter methods. These are implementation logic (stream filtering), not interface specification. Phase 2 defines the record with `timestamp` and `changes` fields only.

7. **SecretStore.resolve() exception type is deferred to Phase 3.** The Javadoc should document that the method throws when the key is not found, but the specific exception type (a new `SecretNotFoundException` or reuse of an existing type) is a Phase 3 decision. Use `@throws IllegalArgumentException` as a placeholder in the Javadoc contract.

8. **ConfigurationAccess is always provided, not gated by RequiredService.** This is different from TelemetryWriter, SchedulerService, and ManagedHttpClient. Every adapter gets configuration access for its own section, even if that section is empty (INV-CE-02 — zero-config is valid).

---

## Constraints

1. **Java 21** — use records, sealed interfaces, enums as appropriate
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies in public API** — only types from existing HomeSynapse modules and Java standard library
5. **Javadoc on every public type, method, and constructor**
6. **All types go in `com.homesynapse.config` package** within config/configuration module
7. **Do NOT create implementations** — interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files** except the three IntegrationContext cross-module updates (IntegrationContext.java, integration-api module-info.java, integration-api build.gradle.kts)
10. **Collections in records must be unmodifiable** — use `Map.copyOf()`, `List.copyOf()`, `Set.copyOf()` in compact constructors

---

## Compile Gate

```bash
./gradlew :config:configuration:compileJava
```

Must pass with `-Xlint:all -Werror`. After the IntegrationContext update, run full project gate:

```bash
./gradlew compileJava
```

All modules must still compile (no regressions from module-info changes or the IntegrationContext field addition).

**Common pitfalls:**
- `ConfigurationValidationException` lives in `com.homesynapse.event`, NOT in `com.homesynapse.config`. The configuration module `requires com.homesynapse.event` to access it. ConfigurationService.write() references it in its throws clause — make sure the import resolves.
- `ConcurrentModificationException` is `java.util.ConcurrentModificationException` — it's in java.base, no additional requires needed.
- The `@Nullable` annotation: use Javadoc `{@code null}` documentation, not a `@Nullable` annotation import. HomeSynapse does not have a nullability annotations dependency.
- `java.nio.file.Path` used in SchemaRegistry.writeComposedSchema() and `java.io.IOException` in its throws clause — both in java.base, no additional requires needed.
- Integration-api's `module-info.java` must add `requires transitive com.homesynapse.config;` because ConfigurationAccess appears in IntegrationContext's public API signature.
- IntegrationContext field ordering matters for the record constructor. Insert `configAccess` after `healthReporter` (position 7) to group required fields together before optional fields.

---

## Execution Order

1. Create `Severity.java`
2. Create `ReloadClassification.java`
3. Create `ChangeType.java`
4. Create `ConfigIssue.java`
5. Create `SecretEntry.java`
6. Create `ConfigMutation.java`
7. Create `ConfigSection.java`
8. Create `ConfigChange.java`
9. Create `MigrationChange.java`
10. Create `ConfigChangeSet.java`
11. Create `MigrationResult.java`
12. Create `MigrationPreview.java`
13. Create `ConfigModel.java`
14. Create `ReloadResult.java`
15. Create `ConfigurationLoadException.java`
16. Create `ConfigurationReloadException.java`
17. Create `ConfigurationService.java`
18. Create `ConfigurationAccess.java`
19. Create `SecretStore.java`
20. Create `ConfigValidator.java`
21. Create `ConfigMigrator.java`
22. Create `SchemaRegistry.java`
23. Create `module-info.java` (config/configuration)
24. Update `IntegrationContext.java` (integration-api) — add configAccess field
25. Update `module-info.java` (integration-api) — add requires transitive com.homesynapse.config
26. Update `build.gradle.kts` (integration-api) — add api(project(":config:configuration"))
27. Delete `package-info.java` scaffold
28. Compile gate: `./gradlew :config:configuration:compileJava`
29. Full compile gate: `./gradlew compileJava`

---

## Summary of New Files

| File | Module | Kind | Components/Methods |
|------|--------|------|--------------------|
| `Severity.java` | config/configuration | enum | FATAL, ERROR, WARNING |
| `ReloadClassification.java` | config/configuration | enum | HOT, INTEGRATION_RESTART, PROCESS_RESTART |
| `ChangeType.java` | config/configuration | enum | KEY_RENAMED, KEY_ADDED, KEY_REMOVED, VALUE_TRANSFORMED, SECTION_RESTRUCTURED |
| `ConfigIssue.java` | config/configuration | record (6 fields) | severity, path, message, invalidValue, appliedDefault, yamlLine |
| `SecretEntry.java` | config/configuration | record (4 fields) | key, value, createdAt, updatedAt |
| `ConfigMutation.java` | config/configuration | record (3 fields) | sectionPath, key, newValue |
| `ConfigSection.java` | config/configuration | record (3 fields) | path, values, defaults |
| `ConfigChange.java` | config/configuration | record (5 fields) | sectionPath, key, oldValue, newValue, reload |
| `MigrationChange.java` | config/configuration | record (5 fields) | type, path, oldValue, newValue, reason |
| `ConfigChangeSet.java` | config/configuration | record (2 fields) | timestamp, changes |
| `MigrationResult.java` | config/configuration | record (2 fields) | migratedConfig, changes |
| `MigrationPreview.java` | config/configuration | record (4 fields) | fromVersion, toVersion, plannedChanges, requiresUserReview |
| `ConfigModel.java` | config/configuration | record (5 fields) | schemaVersion, loadedAt, fileModifiedAt, sections, rawMap |
| `ReloadResult.java` | config/configuration | record (3 fields) | newModel, changeSet, issues |
| `ConfigurationLoadException.java` | config/configuration | exception | error code config.load_failed, HTTP 503 |
| `ConfigurationReloadException.java` | config/configuration | exception | error code config.reload_failed, HTTP 422 |
| `ConfigurationService.java` | config/configuration | interface | load(), reload(), getCurrentModel(), getSection(), write() |
| `ConfigurationAccess.java` | config/configuration | interface | getConfig(), getString(), getInt(), getBoolean() |
| `SecretStore.java` | config/configuration | interface | resolve(), set(), remove(), list() |
| `ConfigValidator.java` | config/configuration | interface | validate() |
| `ConfigMigrator.java` | config/configuration | interface | fromVersion(), toVersion(), migrate() |
| `SchemaRegistry.java` | config/configuration | interface | registerCoreSchema(), registerIntegrationSchema(), getComposedSchema(), writeComposedSchema() |
| `module-info.java` | config/configuration | module descriptor | exports com.homesynapse.config |

**Modified files (3):**

| File | Module | Change |
|------|--------|--------|
| `IntegrationContext.java` | integration-api | Add configAccess (ConfigurationAccess) field, non-null |
| `module-info.java` | integration-api | Add requires transitive com.homesynapse.config |
| `build.gradle.kts` | integration-api | Add api(project(":config:configuration")) |

**Deleted files (1):**

| File | Module | Reason |
|------|--------|--------|
| `package-info.java` | config/configuration | Scaffold placeholder replaced by real types |

**Total: 23 new files + 3 modified + 1 deleted = 27 file operations.**

---

## Estimated Size

~22 types + module-info, approximately 800–1100 lines. This is a medium-large block — the largest Phase 2 block to date. The primary complexity is getting ConfigModel's Phase 2 simplification right (Map-based instead of typed subsystem records), correctly wiring the ConfigurationAccess → IntegrationContext cross-module update, and maintaining Javadoc accuracy across 6 interfaces with specific behavioral contracts. Expect 2.5–3 hours.

---

## Notes

- `ConfigurationValidationException` already exists in `com.homesynapse.event` (event-model). Do NOT duplicate it in this module. ConfigurationService.write() references it via import from event-model.
- `ConfigurationLoadException` and `ConfigurationReloadException` are NEW and go in `com.homesynapse.config` — they are domain-specific to the Configuration System, unlike the generic `ConfigurationValidationException`.
- The Doc 06 §4.1 ConfigModel shows typed subsystem records (`EventBusConfig`, etc.) — these are Phase 3. Phase 2 uses `Map<String, ConfigSection>` and `Map<String, Object>`.
- The Doc 06 §4.3 ConfigChange name collides with §3.7's migration ConfigChange — the migration version is renamed to `MigrationChange` per Locked Decision #2.
- Doc 06 §8.6 SchemaRegistry uses `JsonSchema` type in signatures — replace with `String` (JSON text) per Locked Decision #3.
- `json-schema-validator` is NOT in the version catalog. This does NOT affect Phase 2 compilation. Flag as `[REVIEW]` for Phase 3 preparation.
- ConfigurationAccess is REQUIRED (non-null) in IntegrationContext, unlike TelemetryWriter/SchedulerService/ManagedHttpClient which are optional.
- The `package-info.java` scaffold should be deleted before creating real types.
- After creating ConfigurationAccess, the Coder MUST update IntegrationContext in integration-api to add the `configAccess` field. This is a cross-module edit requiring updates to module-info and build.gradle in integration-api.

---

## Context Delta (post-completion)

<!-- The Coder fills this in after the block compiles. -->

**Files created:**
- {list}

**Decisions made during execution:**
- {list any deviations or clarifications}

**What the next block needs to know:**
- {anything that affects downstream work}

# device-model — `com.homesynapse.device` — 58 types (+3 AB-3 InMemory registry impls) — Entity/Device/Capability model, spatial Floor/Area aggregates, EntityRole UX-role axis, sealed hierarchies, registries, discovery pipeline

> **M9.5-DUR (2026-07-08, AMD-99): +`RegistryProjection` + `RegistryEventMapper` (public 58→60) — REG-INV-1: the registries are projections of the event log; `module-info` PROMOTED `requires com.homesynapse.event` → `requires transitive` (the M9.1/M9.4b [exports] lockstep — both new types name event payloads on PUBLIC methods of the exported package; Gradle was already `api`).** **`RegistryProjection`** is THE single apply path (enforced by the app-side `REGISTRY_MUTATION_ONLY_VIA_PROJECTION` ArchUnit rule + its permanent teeth test): `applyDeviceRegistered`/`applyEntityRegistered` = upsert in log order (DP-8: absent⇒create, equal⇒no-op — the live self-delivery of an adoption-time event, different⇒replace — the F1 re-emit), `applyDeviceRemoved(DeviceId, DeviceRemovedEvent)` = tombstone (device + ALL its entities; absent = no-op; re-register resurrects) — deviceId rides the envelope SubjectRef, extracted by the caller. One `ReentrantLock` (LTD-11) serializes applies (adoption thread + registry-subscriber VT); DELIBERATELY SILENT (device-model has no SLF4J edge — observability rides the lifecycle subscriber); `deviceCount()`/`entityCount()` feed the subscriber's positive-evidence INFO. **`RegistryEventMapper`** (stateless): `toPayload(Device)`/`toPayload(Entity)` (emit; a helper entity with null deviceId is REJECTED — registration describes device-backed entities) and `toDevice`/`toEntity` (apply; enums via `valueOf` — an unknown name from a FUTURE log fails LOUDLY with IAE → the bus DLQ path; never coerced). Write-ahead contract: callers publish FIRST (durable at return, INV-ES-04), apply second; apply consumes ONLY the payload so live-apply ≡ replay-apply. **The C2 mirrors-track-schema gotcha applies on this side too:** a new component on any of the nine mirrored domain types requires a mirror field + mapper legs, or an explicit exclusion ruling. NOTE: registry-held Numbers are the canonical Integer/Long/Double pair post-apply (the mirror boundary canonicalizes — `PayloadMirrors`, event-model).

> **M9.4b (2026-07-04): +`Identify` (public capability record, SD-3 — public count 57→58; `Capability` permits 16→17, 16 records + `CustomCapability`) and the SD-2 brightness canonical root-correction.** (1) **`Identify`** (Doc 02 §3.8): issuable-but-inherently-unconfirmable — NO attributes (nothing to confirm, by construction); one `identify` command with an OPTIONAL `duration_s` param (INT 0–300, a chosen sane cap on ZCL's u16; adapters default it); `ConfirmationPolicy(DISABLED, [], null, 5000L)` at the capability ROOT so the ledger never tracks it (AMD-97-INV-01 structural) — the issuing adapter owns the immediate honest verdict (SD-3 pinned: "an immediate rendered UNCONFIRMED verdict with recorded reason, not the silent DISABLED bypass; never-tracked and honestly-verdicted are different promises"). Rides `StandardCapabilities.all()` (now sixteen) and `identify()`. Census deltas: `CapabilityTest` 17 permits/16 records; `StandardCapabilitiesTest` `hasSize(16)`. (2) **Brightness attribute is CANONICAL 0–254** (Doc 08 §3.5: "percentage derived at query time") — the 0–100 bounds contradicted the Locked canonical and made confirmation compare a percent target against a level report (the F-3 false-fail class). The `set_brightness` `level` PARAMETER stays percent 0–100 (the user-facing domain); the ±2 tolerance is LEVEL units (Doc 08 §392).

> **AB-3 (2026-06-19): minimal production registry impls landed.** `InMemoryEntityRegistry` (9 methods), `InMemoryDeviceRegistry` (7), and `InMemoryAreaRegistry` (4, read-only) are the **MVP substrate** the composition root instantiates at app-bootstrap so the runtime boots zero-configuration (INV-CE-02). They are Map-backed and **start-empty**; Entity/Device use copy-on-write under a `ReentrantLock` (LTD-11, lock-free reads), Area is immutable-after-construction (read-only; a seed ctor exists for tests/future synthetic areas). They are deliberately minimal — **no AMD-44 capability-composition validation on `createEntity`, no Floor/EntityRole breadth, no cross-registry cascade** (`removeDevice` removes only the device record); the integration-backed SQLite registries (M9/M14, in `core:persistence`) supersede them. `FloorRegistry`/`CapabilityRegistry` were NOT implemented (verified: nothing in the automation chain references them — PD-2 contained-fold holds).

## Purpose

The device-model module defines the complete Device, Entity, and Capability type system for HomeSynapse Core. It models the physical world: devices are containers for hardware, entities are the atomic units of behavior that automations target, and capabilities are typed behavioral contracts that define what an entity can do (attributes it reports, commands it accepts). The module also provides registries (DeviceRegistry, EntityRegistry, CapabilityRegistry), the discovery pipeline for device adoption, the validation framework for attribute values and commands, and the command confirmation system (Expectation hierarchy). This is the second-largest module in the system and defines the domain vocabulary that integrations, automations, and the state store all operate against.

## Design Doc Reference

**Doc 02 — Device Model & Capability System** is the governing design document:
- §3: Device/Entity/Capability data model, sealed hierarchies, entity type classification
- §4: Discovery pipeline, device adoption workflow, device replacement semantics
- §8: Interface specifications for registries, validators, and service interfaces

The Identity & Addressing Model (foundations) also governs DeviceId/EntityId lifecycle and hardware identifier mapping rules (§4.1, §5, §6).

## JPMS Module

```
module com.homesynapse.device {
    requires transitive com.homesynapse.value;
    requires com.homesynapse.event;
    requires transitive com.homesynapse.platform;

    exports com.homesynapse.device;
}
```

`requires transitive com.homesynapse.value` (M4.0b-4a) re-exports the `AttributeValue` hierarchy + `AttributeType` that device-model **used to own** and now depends on — `AttributeSchema`, the `Expectation` hierarchy, `StandardCapabilities`, and the capability records all name them on device-model's public API, so any module reading `com.homesynapse.device` continues to resolve the value types transitively (unchanged from before the relocation, when they lived in this module). The `requires transitive com.homesynapse.platform` declaration likewise re-exports all identity types (`DeviceId`, `EntityId`, etc.). Event-model is non-transitive because no event-model types (`EventEnvelope`, `EventPublisher`, etc.) appear in device-model's public API signatures — only Javadoc `@see` cross-references (and that edge is now vestigial; see Gotchas). value-model and platform-api are the two `requires transitive` edges.

## Package Structure

- **`com.homesynapse.device`** — All types in a single flat package. Contains: core domain records (Device, Entity), sealed capability hierarchy (15 standard records + CustomCapability), the `AttributeValueUpcaster` SPI (AMD-47 — stays here; imports `AttributeValue`/`DegradedAttributeValue` from value-model), sealed Expectation hierarchy (4 records), schema/definition records (incl. `AttributeSchema` — stays here; imports `AttributeType`/`DegradedAttributeValue` from value-model), validation interfaces, registry interfaces, discovery pipeline types, and supporting enums. **The `AttributeValue` sealed hierarchy (8 records + the `AttributeValue` interface) and the `AttributeType` enum relocated to the new `com.homesynapse.value` leaf module at M4.0b-4a (AMD-52 §11 erratum) — they are no longer owned here; this module re-exports them via `requires transitive com.homesynapse.value`.**

## Complete Type Inventory

### Sealed Capability Hierarchy (1 sealed interface + 15 standard records + 1 final class)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Capability` | sealed interface (permits 17 types — M9.4b added `Identify`) | Contract that capabilities implement — defines attributes, commands, and confirmation policy | Methods: `capabilityId()`, `version()`, `namespace()`, `attributeSchemas()` → `Map<String, AttributeSchema>`, `commandDefinitions()` → `Map<String, CommandDefinition>`, `confirmationPolicy()` → `ConfirmationPolicy`. |
| `OnOff` | record implements `Capability` | Binary on/off control | Attribute: `on` (boolean). Commands: `turn_on`, `turn_off`, `toggle`. Confirmation: EXACT_MATCH. Required for LIGHT, SWITCH, PLUG. |
| `Brightness` | record implements `Capability` | Brightness level control (canonical 0–254, M9.4b/SD-2) | Attribute: `brightness` (int, 0–254 — Doc 08 §3.5; percentage derives at query time). Command: `set_brightness(level)` with a percent 0–100 param. Confirmation: TOLERANCE (±2 LEVEL units). Optional for LIGHT. |
| `ColorTemperature` | record implements `Capability` | Color temperature control (Kelvin) | Attribute: `color_temp_kelvin` (int). Command: `set_color_temperature(kelvin)`. Confirmation: TOLERANCE (±50K). Optional for LIGHT. |
| `TemperatureMeasurement` | record implements `Capability` | Ambient temperature sensing | Attribute: `temperature_c` (float). Read-only. Confirmation: DISABLED. For SENSOR. |
| `HumidityMeasurement` | record implements `Capability` | Relative humidity sensing (0–100%) | Attribute: `humidity_pct` (float, 0–100). Read-only. Confirmation: DISABLED. For SENSOR. |
| `IlluminanceMeasurement` | record implements `Capability` | Ambient light sensing (lux) | Attribute: `illuminance_lux` (float). Read-only. Confirmation: DISABLED. For SENSOR. |
| `PowerMeasurement` | record implements `Capability` | Instantaneous power sensing (watts) | Attribute: `power_w` (float). Read-only. Confirmation: DISABLED. For SENSOR. |
| `BinaryState` | record implements `Capability` | Generic binary state sensor | Attribute: `active` (boolean). Read-only. Confirmation: DISABLED. For BINARY_SENSOR. |
| `Contact` | record implements `Capability` | Door/window contact sensor | Attribute: `open` (boolean). Read-only. Confirmation: DISABLED. For BINARY_SENSOR. |
| `Motion` | record implements `Capability` | Motion detection sensor | Attribute: `detected` (boolean). Read-only. Confirmation: DISABLED. For BINARY_SENSOR. |
| `Occupancy` | record implements `Capability` | Room occupancy sensor | Attribute: `occupied` (boolean). Read-only. Confirmation: DISABLED. For BINARY_SENSOR. |
| `Battery` | record implements `Capability` | Battery status reporting | Attributes: `battery_pct` (int, 0–100), `battery_low` (boolean). Read-only. Confirmation: DISABLED. Cross-cutting — any battery-powered device. |
| `DeviceHealth` | record implements `Capability` | Wireless link quality metrics | Attributes: `rssi_dbm` (int), `lqi` (int, 0–255). Read-only. Confirmation: DISABLED. Cross-cutting — any wireless device. |
| `EnergyMeter` | record implements `Capability` | Cumulative energy measurement | Attributes: `energy_wh` (float), `direction` (EnergyDirection), `cumulative` (boolean). Command: `reset_meter`. Confirmation: EXACT_MATCH. Required for ENERGY_METER. |
| `PowerMeter` | record implements `Capability` | Power with voltage/current measurement | Attributes: `power_w` (float), `voltage_v` (float, **nullable**), `current_a` (float, **nullable**). Read-only. Confirmation: DISABLED. Optional for ENERGY_METER. |
| `CustomCapability` | **final class** (NOT record) | Runtime-registered capabilities from JSON schemas | Implements all `Capability` methods. Constructor validates namespace is not "core". Uses `equals()`/`hashCode()`/`toString()` overrides. Final class (not record) because fields are constructed from runtime JSON, not compile-time components. |

### Sealed AttributeValue Hierarchy — RELOCATED to `com.homesynapse.value` (M4.0b-4a)

The `AttributeValue` sealed interface + its 8 variant records (`BooleanValue`, `IntValue`, `FloatValue`, `StringValue`, `EnumValue`, `QuantityValue`, `ArrayValue`, `DegradedAttributeValue`) **left this module** for the new `com.homesynapse.value` leaf at M4.0b-4a (AMD-52 §11 erratum; behavior-preserving, contracts unchanged). **See `core/value-model/MODULE_CONTEXT.md` for the full inventory + the AMD-47 contracts.** device-model re-exports them via `requires transitive com.homesynapse.value`, so consumers that read `com.homesynapse.device` resolve the types exactly as before. The `AttributeType` enum (the 10th relocated type) likewise moved — it is no longer in this module's Enums table below.

### Sealed Expectation Hierarchy (1 sealed interface + 4 records)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Expectation` | sealed interface (permits 4 types) | Evaluation contract for command confirmation against reported values | Method: `evaluate(AttributeValue reportedValue)` → `ConfirmationResult`. |
| `ExactMatch` | record(`AttributeValue expectedValue`) implements `Expectation` | Confirmed when reported value equals expected exactly | For boolean/enum attributes. |
| `WithinTolerance` | record(`double target`, `double tolerance`) implements `Expectation` | Confirmed when numeric value within ±tolerance of target | **`evaluate()` IMPLEMENTED at M9.3 (A10)**: accepts `IntValue`/`FloatValue`/`QuantityValue`; band inclusive at both edges (`|reported − target| ≤ tolerance` ⇒ CONFIRMED); non-numeric reported values ⇒ NOT_YET, never a failure (the EnumTransition precedent). Record components UNCHANGED — the AMD-87 serialized shape is untouched (ExpectationSerdeTest green). Measured motivation: the Wave-1 bulb re-derives commanded CT ±1 mired, so EXACT_MATCH false-fails; the ±50K band confirms the drift (AMD-97). Caveat for callers: `QuantityValue` normalizes to canonical units in its compact constructor ("K" is temperature-Kelvin → °C conversion) — color-temp Kelvin is an `IntValue`. |
| `EnumTransition` | record(`String expectedValue`) implements `Expectation` | Confirmed when enum value matches expected transition target | For enum-valued attributes after command. |
| `AnyChange` | record(`AttributeValue previousValue`) implements `Expectation` | Confirmed when reported value differs from pre-command value | For toggle commands where the target state is unknown. |

### Core Domain Records

| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `Device` | record | Container for one or more Entity instances; carries hardware metadata | `deviceId` (DeviceId), `deviceSlug`, `displayName`, `manufacturer`, `model`, `serialNumber` (**nullable**), `firmwareVersion` (**nullable**), `hardwareVersion` (**nullable**), `integrationId` (IntegrationId), `areaId` (AreaId, **nullable**), `viaDeviceId` (DeviceId, **nullable**), `labels` (List\<String\>), `hardwareIdentifiers` (**Set**\<HardwareIdentifier\> — M4.B-S1/AMD-44 §2.6), `createdAt` (Instant). **Compact ctor (M4.B-S1)** defensively copies both collection fields (`List.copyOf(labels)`, `Set.copyOf(hardwareIdentifiers)`) — NPE on null collection/element. |
| `Entity` | record | Atomic functional unit of a device — primary target for automation, queries, commands | **12 components** (M4.B-S2/AMD-44 §2.5.3): `entityId` (EntityId), `entitySlug`, `entityType` (EntityType), `displayName`, `deviceId` (DeviceId, **nullable** — for helper entities), `endpointIndex` (int), `areaId` (AreaId, **nullable** — inherits from device), `enabled` (boolean), `labels` (List\<String\>), `capabilities` (List\<CapabilityInstance\>), **`entityRole` (EntityRole — between `capabilities` and `createdAt`; never null after construction)**, `createdAt` (Instant). **Compact ctor (M4.B-S2):** null `entityRole` → `EntityRole.PRIMARY` (coercion), then matrix guard `entityType.allows(entityRole)` (IAE if illegal), then `List.copyOf(labels)`/`List.copyOf(capabilities)` (null collection now NPEs). **11-arg convenience ctor** = pre-AMD-44 signature, defaults role PRIMARY. `entityType` is NOT null-guarded (DP-4 boundary) — a null type NPEs via `allows(...)`. |
| `CapabilityInstance` | record | Specific instantiation of a capability on a device entity with feature map | `capabilityId`, `version` (int), `namespace`, `featureMap` (int — bitmask), `attributes` (Map\<String, AttributeSchema\>), `commands` (Map\<String, CommandDefinition\>), `confirmation` (ConfirmationPolicy). |
| `HardwareIdentifier` | record | Protocol-level device identifier for discovery deduplication | `namespace` (e.g., "zigbee_ieee"), `value`. Both non-null. |
| `ProposedDevice` | record | Device detected by discovery pipeline, proposed for adoption | `hardwareIdentifiers` (**Set**\<HardwareIdentifier\> — M4.B-S1/AMD-44 §2.6), `proposedManufacturer`, `proposedModel`, `proposedEntities` (List\<ProposedEntity\>). **Compact ctor (M4.B-S1)** defensively copies both collections (`Set.copyOf(hardwareIdentifiers)`, `List.copyOf(proposedEntities)`). |
| `ProposedEntity` | record | Proposed entity mapping from detected device endpoint | **4 components** (M4.B-S2/AMD-44 §2.5.3): `endpointIndex` (int), `proposedEntityType` (EntityType), `proposedCapabilities` (List\<String\>), **`entityRole` (EntityRole — never null after construction)**. **Compact ctor (M4.B-S2):** null `entityRole` → `EntityRole.PRIMARY`, then matrix guard `proposedEntityType.allows(entityRole)` (IAE), then `List.copyOf(proposedCapabilities)`. **3-arg convenience ctor** for adapters that do not classify (defaults role PRIMARY). |
| `Floor` | record (**M4.B-S1 / AMD-44 §2.1.2**) | Floor aggregate — a vertical level grouping of areas; enables level-scoped selectors in multi-story homes | `id` (FloorId), `name` (non-blank, ≤100 chars), `level` (int, **signed**: -1 basement / 0 ground / 1 first / …; **no uniqueness** — split-level, Decision 8), `icon` (String, **nullable** — MDI name), `aliases` (List\<String\> — voice synonyms, `List.copyOf` in compact ctor), `createdAt` (Instant). Compact ctor null-guards id/name/createdAt and length-checks name. |
| `Area` | record (**M4.B-S1 / AMD-44 §2.2**) | Minimal area aggregate so `Area.floorId` is structurally expressible; full lifecycle deferred to AMD-45 | `id` (AreaId), `name` (non-blank, ≤100 chars), `floorId` (FloorId, **nullable** — null = unassigned, no synthetic "Unassigned" floor per Decision 5), `createdAt` (Instant). Compact ctor null-guards id/name/createdAt and length-checks name. |
| `CapabilityCompatibilityReport` | record | Result of capability compatibility check during device replacement | `compatible` (boolean), `capabilityAdditions` (List\<String\>), `capabilityLosses` (List\<String\>), `requiresUserConfirmation` (boolean). |

### Schema and Definition Records

| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `AttributeSchema` | record (**stays in device-model**; M4.0b-4a: now `import`s `AttributeType`/`DegradedAttributeValue` from `com.homesynapse.value`) | Defines schema for a single attribute within a capability | `attributeKey`, `type` (AttributeType), `minimum` (Number, **nullable**), `maximum` (Number, **nullable**), `step` (Number, **nullable**), `validValues` (Set\<String\>, **nullable**), `unitSymbol` (String, **nullable**), `canonicalUnitSymbol` (String, **nullable**), `permissions` (Set\<Permission\>), `nullable` (boolean), `persistent` (boolean). **Compact constructor (AMD-47-INV-04, M4.B3):** rejects `type == AttributeType.DEGRADED` (IAE) — the only validation; no other field checks (those belong to the future `SchemaAttributeValidator`). |
| `ParameterSchema` | record | Describes a single parameter accepted by a device command | `parameterName`, `type` (AttributeType), `minimum` (Number, **nullable**), `maximum` (Number, **nullable**), `required` (boolean), `requiredFeatures` (int bitmask), `validValues` (Set\<String\>, **nullable**). |
| `CommandDefinition` | record | Defines a command that can be issued to a device through a capability | `commandType`, `parameters` (List\<ParameterSchema\>), `requiredFeatures` (int), `expectedOutcomes` (List\<ExpectedOutcome\>), `defaultTimeout` (Duration), `idempotencyClass` (IdempotencyClass). |
| `ConfirmationPolicy` | record | Governs how Pending Command Ledger confirms command execution | `mode` (ConfirmationMode), `authoritativeAttributes` (List\<String\>), `defaultTolerance` (Number, **nullable**), `defaultTimeoutMs` (long). |
| `ExpectedOutcome` | record | Maps an attribute to confirmation logic for command evaluation | `attributeKey`, `expectation` (Expectation), `timeoutMs` (long). |
| `ValidationError` | record | Single validation failure | `field`, `reason`, `rejectedValue`. |
| `ValidationResult` | record | Structured result from attribute/command validation | `valid` (boolean), `errors` (List\<ValidationError\>). |

### Enums

| Type | Kind | Purpose | Values |
|---|---|---|---|
| `EntityType` | enum | Functional classification of a device entity | LIGHT (requires OnOff; optional Brightness, ColorTemperature), SWITCH (requires OnOff), PLUG (requires OnOff; optional PowerMeasurement, EnergyMeter), SENSOR (requires 1+ measurement), BINARY_SENSOR (requires 1+ of BinaryState/Contact/Motion/Occupancy), ENERGY_METER (requires EnergyMeter; optional PowerMeter, Battery, DeviceHealth). **Only 6 MVP values declared** (source order LIGHT, SWITCH, PLUG, SENSOR, BINARY_SENSOR, ENERGY_METER — pinned by `EntityTypeTest`). **M4.B-S2/AMD-44 §2.5.1:** each constant declares `Set<EntityRole> legalRoles` via the `EntityType(Set<EntityRole>)` ctor (`Set.copyOf` — immutable). Surface: `allows(EntityRole)` → boolean (NPE on null role, msg "role must not be null"), `legalRoles()` → immutable `Set<EntityRole>`. Matrix: LIGHT{PRIMARY,DIAGNOSTIC}, SWITCH{PRIMARY,DIAGNOSTIC,CONFIG}, PLUG{PRIMARY}, SENSOR{PRIMARY,DIAGNOSTIC}, BINARY_SENSOR{PRIMARY,DIAGNOSTIC}, ENERGY_METER{PRIMARY,DIAGNOSTIC}. Every type permits PRIMARY. |
| `EntityRole` | enum (**M4.B-S2 / AMD-44 §2.5**) | UX-role classification axis (orthogonal to `EntityType`) — what the entity means to the user | PRIMARY (user-facing value/control), DIAGNOSTIC (device health/infrastructure), CONFIG (configuration parameter). **Exactly 3 values — no SYSTEM** (HA scrubbed it pre-2021.11; visibility lives on `Entity.enabled` / future `visibleByDefault`). Declaration order PRIMARY, DIAGNOSTIC, CONFIG. Coordinator-state entities default DIAGNOSTIC (Decision 9). Mutable post-adoption via `entity_profile_changed`, EntityId preserved (INV-CS-02). |
| `Permission` | enum | Access modes for an attribute in capability schema | READ, WRITE, NOTIFY. |
| `EnergyDirection` | enum | Direction of energy flow for energy metering | IMPORT, EXPORT, BIDIRECTIONAL. |
| `IdempotencyClass` | enum | Idempotency semantics of a device command | IDEMPOTENT, NOT_IDEMPOTENT, CONDITIONAL. |
| `ConfirmationMode` | enum | Comparison strategy for command confirmation | EXACT_MATCH, TOLERANCE, ENUM_MATCH, ANY_CHANGE, DISABLED. |
| `ConfirmationResult` | enum | Outcome of evaluating reported value against expectation | CONFIRMED, NOT_YET, FAILED, TIMEOUT. |

### Service Interfaces

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `DeviceRegistry` | interface | Manages device lifecycle with CRUD and hardware identifier lookups | `getDevice(DeviceId)`, `findDevice(DeviceId)` → `Optional<Device>`, `listAllDevices()`, `createDevice(Device)`, `updateDevice(Device)`, `removeDevice(DeviceId)`, `findByHardwareIdentifier(String namespace, String value)`. |
| `EntityRegistry` | interface | Manages entity lifecycle with CRUD and administrative control | `getEntity(EntityId)`, `findEntity(EntityId)` → `Optional<Entity>`, `listAllEntities()`, `listEntitiesByDevice(DeviceId)`, `createEntity(Entity)`, `updateEntity(Entity)`, `removeEntity(EntityId)`, `enableEntity(EntityId)`, `disableEntity(EntityId)`. |
| `CapabilityRegistry` | interface | Registry for capability definition lookups and custom capability registration | `getCapability(String)`, `getAllStandardCapabilities()`, `registerCustomCapability(CustomCapability)`, `getCustomCapability(String)` → `Optional<CustomCapability>`, `getAttributeSchema(String, String)`, `getCommandDefinition(String, String)`. Thread-safe for reads, serialized writes. |
| `AttributeValidator` | interface | Validates attribute values against capability-defined schemas | `validate(String capabilityId, String attributeKey, AttributeValue)` → `ValidationResult`, `validateAll(String capabilityId, Map<String, AttributeValue>)`. |
| `CommandValidator` | interface | Validates command parameters against capability schemas | `validate(String capabilityId, String commandType, Map<String, Object> params, int featureMap)` → `ValidationResult`, `isCommandSupported(String capabilityId, String commandType, int featureMap)`. |
| `ExpectationFactory` | interface | Factory for creating Expectation instances for command confirmation | `createExpectation(String capabilityId, String commandType, Map<String, Object> params, AttributeValue previousValue)` → `Expectation`. |
| `DeviceReplacementService` | interface | Checks capability compatibility and transfers entities during device replacement | `checkCompatibility(DeviceId old, DeviceId new)` → `CapabilityCompatibilityReport`, `transferEntities(DeviceId old, DeviceId new, boolean userConfirmedLosses)`. |
| `DiscoveryPipeline` | interface | Orchestrates device discovery, proposal, and adoption lifecycle | `propose(`**`Set<HardwareIdentifier>`**`, String manufacturer, String model, List<ProposedEntity>)` → `ProposedDevice`, `adopt(ProposedDevice, String displayName, AreaId)` → `Device`, `findExistingDevice(`**`Set<HardwareIdentifier>`**`)` → `Optional<Device>`. (M4.B-S1: the two `HardwareIdentifier` params became `Set`; `List<ProposedEntity>` unchanged.) |
| `FloorRegistry` | interface (**M4.B-S1 / AMD-44 §2.1.3** — interface only, no impl this WU) | Floor lifecycle surface | `create(String name, int level, String icon, List<String> aliases)` → `Floor`, `get(FloorId)` → `Optional<Floor>`, `getAll()` → `Collection<Floor>` (**sorted level ASC, name ASC, createdAt ASC** — Decision 8), `getByLevel(int)` → `Collection<Floor>`, `update(FloorId, String, int, String, List<String>)` → `Floor`, `delete(FloorId)` (Javadoc contract: rejects when areas remain assigned; cascade/`?force=true` is REST/impl, Decision 11). Implementers: `ReentrantLock`, never `synchronized` (LTD-11). |
| `AreaRegistry` | interface (**M4.B-S1 / AMD-44 §2.2** — read-only in Stage 1; write CRUD deferred to AMD-45) | Read-only area lookup | `get(AreaId)` → `Optional<Area>`, `getAll()` → `Collection<Area>`, `getByFloor(FloorId)` → `Collection<Area>`, `getUnassigned()` → `Collection<Area>` (areas with `floorId == null`). |
| `AttributeValueUpcaster` | interface — **AMD-47** (**stays in device-model**; M4.0b-4a: now `import`s `AttributeValue`/`DegradedAttributeValue` from `com.homesynapse.value`) | Migration seam for evolving stored `AttributeValue`s across type changes (value-layer analogue of the event upcaster) | `canUpcast(String storedTypeName, int fromSchemaVersion)` → `boolean`; `upcast(String storedTypeName, String rawForm, int fromSchemaVersion)` → `AttributeValue` (**strict** — throws on failure, never produces a `DegradedAttributeValue`); `default upcastLenient(...)` → `AttributeValue` (**lenient** — returns a `DegradedAttributeValue` on failure, never throws). **No `ServiceLoader`** (DECIDE-04 — constructor injection downstream). No implementation in M4.B3; projection-path wiring (AMD-47-INV-02, both paths) is **M4.0b-3**. |

### Standard Capability Catalogue (M4.0b-3 / DP-K, AMD-51)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `StandardCapabilities` | **public** final factory class | Production catalogue of the 15 standard (core-namespace) capabilities + their aggregated attribute schemas (DP-K) | `all()` → `List<Capability>` (the 15 standard records; **excludes** `CustomCapability`); `attributeSchemas()` → immutable `Map<String, AttributeSchema>` keyed by `attributeKey`, **fails fast** (`IllegalStateException`) if two standard capabilities declare the same key with different `AttributeType` (the AMD-51 resolver's global-consistency assumption; `power_w` is FLOAT in both `PowerMeasurement` and `PowerMeter`, so no conflict). Plus the 15 typed factory methods (`onOff()`…`powerMeter()`). **Construction logic lifted verbatim from `TestCapabilityFactory`**, which now delegates here (single source of truth, no duplication). Pure, no clock/I/O/locale — an immutable compile-time-shaped catalogue (the `QuantityValue.CATALOGUE` posture), NOT a runtime registry. **Seed for the future `CapabilityRegistry` implementation.** No standard attribute is `QUANTITY`/`ARRAY` at M4.0b-3 — all are `BOOLEAN`/`INT`/`FLOAT`/`ENUM`. |

**Total: 58 public types + 1 package-info.java + 1 module-info.java = 60 Java files.** (M9.4b added 1 type: `Identify`. M4.B-S2 / AMD-44 Stage 2 added 1 type: `EntityRole`. M4.B-S1 / AMD-44 Stage 1 added 4 types: `Floor`, `FloorRegistry`, `Area`, `AreaRegistry`. Was 52/54 after M4.0b-4a relocated the 10 value types — `AttributeValue` + 8 variants + `AttributeType` — to `com.homesynapse.value`.)

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **value-model** (`com.homesynapse.value`) | `requires transitive` (M4.0b-4a) — the `AttributeValue` hierarchy + `AttributeType` device-model used to own, re-exported on its public API | `AttributeValue`, `AttributeType` (`AttributeSchema`, `ParameterSchema`, `CommandDefinition`, capability records, `Expectation` hierarchy, `AttributeValidator`, `ExpectationFactory`, `StandardCapabilities`), `BooleanValue`/`QuantityValue` (`StandardCapabilities`), `EnumValue` (`EnumTransition`), `DegradedAttributeValue` (`AttributeSchema`, `AttributeValueUpcaster`). |
| **event-model** (`com.homesynapse.event`) | `requires` (non-transitive) — Event types referenced only in Javadoc `@see` tags, not in public API signatures (vestigial — see Gotchas) | `EventId` (in CommandDefinition/ExpectedOutcome cross-references), `CommandIdempotency` (mapped to device-model's `IdempotencyClass`). |
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types for device/entity/area/floor identification | `DeviceId`, `EntityId`, `IntegrationId`, `AreaId` (fields on Device, Entity, and discovery types), `FloorId` (M4.B-S1 — fields on `Floor`/`Area`; params on `FloorRegistry`/`AreaRegistry`), `Ulid` (underlying identity). |

## Consumers

### Current consumers (modules with completed Phase 2 specs):
None directly — device-model defines contracts consumed by downstream modules.

### Planned consumers (from design doc dependency graph):
- **state-store** — Will use `EntityId`, `AttributeValue`, `CapabilityInstance` for materialized state storage. State Projection maps `StateReportedEvent` values through `AttributeValidator`.
- **persistence** — Will implement `DeviceRegistry`, `EntityRegistry`, `CapabilityRegistry` backed by SQLite.
- **integration-runtime** — Will use `DiscoveryPipeline` for device adoption, `DeviceRegistry`/`EntityRegistry` (read-only) for entity lookup, `AttributeValidator`/`CommandValidator` for ingestion/dispatch validation.
- **automation** — Will use `EntityRegistry` for entity lookups, `CapabilityInstance` for trigger evaluation against entity capabilities.
- **rest-api** — Will serialize Device, Entity, Capability types for API responses.
- **zigbee-adapter** — First integration: will map ZCL clusters to standard capabilities, use `DiscoveryPipeline` for device adoption, `AttributeValidator` for ZCL attribute validation.

## Cross-Module Contracts

- **Entity is the atomic unit of behavior, not Device.** Automation targets, state events, and commands all reference `EntityId`, not `DeviceId`. A device is a container that owns one or more entities. This distinction is critical everywhere — do not treat Device and Entity as interchangeable.
- **Sealed Capability hierarchy enables exhaustive switch.** Code that processes capabilities must handle all 16 permitted types (15 standard records + CustomCapability). Java's pattern matching switch enforces exhaustiveness at compile time. If you add a standard capability, the sealed permits clause must be updated and all switches will fail to compile until updated.
- **Standard capabilities are data-carrying records, not behavioral methods.** Capabilities define schema (attribute schemas, command definitions, confirmation policy) as data. They do NOT contain behavioral methods like `handleCommand()` or `processState()`. Behavior lives in the integration adapter (protocol-specific) and the state projection (generic). This is a deliberate separation of data from behavior.
- **`CustomCapability` is a final class, not a record.** This is because custom capabilities are constructed from runtime JSON schemas, not from compile-time record components. The class provides constructor validation ensuring namespace is not "core" (reserved for standard capabilities). It implements equals/hashCode/toString manually.
- **`EntityRegistry` must be populated before `StateProjection` processes device-subject events.** The State Projection needs to look up entity capabilities to validate incoming state reports. If entities aren't registered yet, state reports will be rejected. The startup-lifecycle module coordinates this ordering.
- **`DeviceReplacementService.transferEntities()` preserves EntityId across hardware swaps.** When a device is replaced, its entities (and their full event history) transfer to the new device. The EntityId is stable — only the backing DeviceId changes. This is the core of INV-CS-02 for device replacement.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-04** | ULID for DeviceId and EntityId. Typed wrappers from platform-api. |
| **LTD-17** | In-process compiled integrations with enforced API boundary. Device model types are the API surface integrations compile against. |
| **INV-CS-02** | Entity identifiers are stable. EntityId survives device replacement, area reassignment, and capability changes. |
| **INV-CS-04** | Integration API stability. Device model types versioned via semver independently from core. |
| **INV-CE-04** | Protocol agnosticism. Device model must not be locked to any protocol. HardwareIdentifiers are protocol-specific but device/entity identity is protocol-independent. |
| **AMD-47 value-type contracts** | **As of M4.0b-4a, the `AttributeValue` hierarchy + `AttributeType` live in `com.homesynapse.value`, so the value-type halves of AMD-47-INV-01/03/05 travel with them — see `core/value-model/MODULE_CONTEXT.md`.** The rows below are retained here because device-model still consumes the types and INV-04's structural guard is owned here. |
| **AMD-47-INV-01** | `AttributeValue` sealing stays total — `permits` is exactly the 8 variants `{BooleanValue, IntValue, FloatValue, StringValue, EnumValue, QuantityValue, ArrayValue, DegradedAttributeValue}`; every exhaustive `switch` handles all eight, no `default`. (Also registered in `Architecture_Invariants_v1.md` §20.) Types now in `com.homesynapse.value`. |
| **AMD-47-INV-02** | Upcaster-before-derivation: when the `AttributeValueUpcaster` is wired (M4.0b-3), it runs strictly before `DerivationRule.evaluate()` on **both** `onEvent` and `processBatch`. The SPI + contract exist at M4.B3; the production both-paths wiring + path test are carried to M4.0b-3. |
| **AMD-47-INV-03** | `QuantityValue` normalizes to its canonical unit at construction via a pure, hand-rolled, deterministic, table-driven conversion — no units library, no I/O, no locale/clock dependence; same-dimension values are magnitude-comparable on canonical `value`; null/blank/non-finite/unrecognised unit fails closed (NPE/IAE). |
| **AMD-47-INV-04** | `AttributeType.DEGRADED` is never schema-declarable — enforced **structurally at `AttributeSchema` construction** (compact-ctor guard, M4.B3; stronger than the literal "validator rejects it"). **This guard stays in device-model** (`AttributeSchema` did not move), even though `AttributeType`/`DegradedAttributeValue` now live in `com.homesynapse.value`. The never-written-to-canonical-state-under-strict-mode clause rides with the upcaster wiring → M4.0b-3. `DegradedAttributeValue` preserves its fields without mutation. |
| **AMD-47-INV-05** | `ArrayValue` is full-replacement — no delta/patch semantics (bounded-window-advancer compatible); `elements` is an unmodifiable, null-free, possibly-empty `List<AttributeValue>`. |

## Sealed Hierarchies

### Capability Hierarchy
```
sealed interface Capability
    permits OnOff, Brightness, ColorTemperature,
            TemperatureMeasurement, HumidityMeasurement,
            IlluminanceMeasurement, PowerMeasurement,
            BinaryState, Contact, Motion, Occupancy,
            Battery, DeviceHealth, EnergyMeter, PowerMeter,
            Identify,
            CustomCapability
```
**Exhaustive switch pattern:**
```java
switch (capability) {
    case OnOff o -> ...
    case Brightness b -> ...
    case ColorTemperature ct -> ...
    case TemperatureMeasurement tm -> ...
    case HumidityMeasurement hm -> ...
    case IlluminanceMeasurement im -> ...
    case PowerMeasurement pm -> ...
    case BinaryState bs -> ...
    case Contact c -> ...
    case Motion m -> ...
    case Occupancy o -> ...
    case Battery bat -> ...
    case DeviceHealth dh -> ...
    case EnergyMeter em -> ...
    case PowerMeter pwm -> ...
    case CustomCapability cc -> ...
}
```

### AttributeValue Hierarchy (8 variants — AMD-47) — RELOCATED to `com.homesynapse.value` (M4.0b-4a)
*The hierarchy now lives in the `com.homesynapse.value` leaf module — see `core/value-model/MODULE_CONTEXT.md`. Retained here because device-model consumes it (e.g. the `Expectation.evaluate(AttributeValue)` contract) and re-exports it via `requires transitive com.homesynapse.value`.*
```
sealed interface AttributeValue   // now: package com.homesynapse.value
    permits BooleanValue, IntValue, FloatValue, StringValue, EnumValue,
            QuantityValue, ArrayValue, DegradedAttributeValue
```
**Exhaustive switch pattern (all 8 cases; no `default` that would swallow a new variant — AMD-47-INV-01):**
```java
switch (value) {
    case BooleanValue bv -> ...
    case IntValue iv -> ...
    case FloatValue fv -> ...
    case StringValue sv -> ...
    case EnumValue ev -> ...
    case QuantityValue qv -> ...
    case ArrayValue av -> ...
    case DegradedAttributeValue dav -> ...
}
```
**Note:** there are **no** production exhaustive `switch`es over `AttributeValue` today — `CheckpointSerializer:238` and `EnumTransition:23` are `instanceof` patterns with fallbacks, so the new variants flow through their generic branches and required no change (AMD-47 §7.4, verified M4.B3).

### Expectation Hierarchy
```
sealed interface Expectation
    permits ExactMatch, WithinTolerance, EnumTransition, AnyChange
```
**Exhaustive switch pattern:**
```java
switch (expectation) {
    case ExactMatch em -> ...
    case WithinTolerance wt -> ...
    case EnumTransition et -> ...
    case AnyChange ac -> ...
}
```

## Key Design Decisions

1. **Entity is the atomic unit, not Device.** A Zigbee smart power strip with 4 outlets is one Device with 4 Entities. Each entity has its own capabilities, state, and event stream. Automations target entities. This was chosen over the "flat device" model (one Device = one control point) because compound devices are common in Zigbee/Matter. Reference: Doc 02 §3.

2. **15 standard capabilities + 1 custom capability type.** The standard set covers MVP device types exhaustively. `CustomCapability` (final class, not record) allows runtime registration of integration-specific capabilities from JSON schemas. The alternative (all capabilities from JSON) was rejected because it loses compile-time exhaustiveness for the standard set. Reference: Doc 02 §3.

3. **`unitSymbol` is `String` — permanently (no JSR 385).** Unit fields (`AttributeSchema.unitSymbol`/`canonicalUnitSymbol`) and `QuantityValue.unit` are `String` canonical-unit symbols (e.g. "°C", "W", "Wh", "lux"). The earlier "Phase 3 adds JSR 385 (`javax.measure`)" plan is **retired** — superseded by **REC-93 / AMD-47-INV-03** (RATIFIED 2026-05-30): unit normalization is **hand-rolled, deterministic, table-driven, with no external units library** (the version catalog has no `javax.measure`/`indriya`/`uom` entry, and AMD-47-INV-03 forbids adding one). `QuantityValue` (AMD-47) carries the (value, unit) moat decision at the value layer and normalizes to canonical units at construction. Reference: Doc 02 §3.7, AMD-47.

4. **Feature maps use int bitmask, not Set\<Feature\>.** Zigbee ZCL defines feature maps as bitmasks. Using `int` directly avoids translation overhead and matches the protocol representation. The `requiredFeatures` field on `ParameterSchema` and `CommandDefinition` uses the same bitmask semantics. Reference: Doc 02 §3.

5. **Discovery pipeline produces `ProposedDevice`, not `Device` directly.** Integrations propose devices; the core validates and adopts them. This prevents integrations from creating invalid device/entity/capability combinations. The pipeline also deduplicates via `HardwareIdentifier` matching. Reference: Doc 02 §4.

## Gotchas

**GOTCHA: EntityType has only 6 MVP values.** LIGHT, SWITCH, PLUG, SENSOR, BINARY_SENSOR, ENERGY_METER are the only declared enum constants. Post-MVP values (THERMOSTAT, LOCK, COVER, MEDIA_PLAYER, CAMERA, CLIMATE, FAN, VALVE, SIREN, REMOTE) are documented in Javadoc but NOT declared as enum constants. Do not add them prematurely.

**GOTCHA: unit fields are `String` canonical-unit symbols — do NOT add a units library.** `unitSymbol`/`canonicalUnitSymbol`/`QuantityValue.unit` are plain `String`s, and this is **permanent** (REC-93 / AMD-47-INV-03, RATIFIED 2026-05-30) — the old "Phase 3 adds JSR 385" note is retired. Unit normalization lives in `QuantityValue`'s hand-rolled, table-driven, deterministic catalogue (canonical-at-construction, fail-closed on unknown units). Do not pull in `javax.measure`/`indriya`/any unit-of-measure library, and match units by exact string equality against the catalogue (no locale-folding).

**GOTCHA: `module-info.java` has two `requires transitive` edges — `com.homesynapse.value` and `com.homesynapse.platform`.** `requires transitive com.homesynapse.value` (M4.0b-4a) re-exports the `AttributeValue` hierarchy + `AttributeType` (on `AttributeSchema`, the `Expectation` contract, capabilities, `StandardCapabilities`); `requires transitive com.homesynapse.platform` re-exports `DeviceId`/`EntityId` etc. Event-model is non-transitive (`requires com.homesynapse.event`) because no event-model types appear in device-model's public API signatures — only Javadoc `@see` cross-references. Removing `transitive` from value-model or platform-api will break downstream compilation; event-model is correctly non-transitive.

**GOTCHA: the `requires com.homesynapse.event` edge is vestigial (M4.0b-4a finding — do NOT invert it).** Device-model main source has **zero** `import com.homesynapse.event` — only three Javadoc `@see com.homesynapse.event.*` references (`Expectation`, `ExpectationFactory`, `IdempotencyClass`). The edge is therefore dead for compilation. It was deliberately **left in place** at M4.0b-4a (the relocation WU scope excludes touching it). Dropping it is optional independent hygiene; **never** "fix" anything by inverting to `event → device` (wrong layering — events are produced about devices; the dependency flows device → event, never the reverse — design-note §7).

**GOTCHA: Nullable fields on `Device`.** `serialNumber`, `firmwareVersion`, `hardwareVersion` are nullable (not all hardware reports these). `areaId` is nullable (device not yet assigned to an area). `viaDeviceId` is nullable (only set for devices connected through a router/coordinator). These were audit findings against Doc 02 during Block G — do not regress them to non-null.

**GOTCHA: `Entity.deviceId` is nullable.** Helper entities (entities not backed by physical hardware, such as virtual sensors or computed entities) have `deviceId = null`. This is a deliberate design decision, not a bug. Always null-check `Entity.deviceId()` before using it.

**GOTCHA: `Entity.areaId` is nullable and inherits from Device.** If `Entity.areaId` is null, the entity inherits its area from its parent device (`Device.areaId`). If both are null, the entity has no area assignment. Do not assume a non-null area is always available.

**GOTCHA: Capability count is 17, not 16 (M9.4b).** The sealed interface permits 17 types: 16 standard records (OnOff, Brightness, ColorTemperature, TemperatureMeasurement, HumidityMeasurement, IlluminanceMeasurement, PowerMeasurement, BinaryState, Contact, Motion, Occupancy, Battery, DeviceHealth, EnergyMeter, PowerMeter, Identify — M9.4b/SD-3) + 1 final class (CustomCapability). Exhaustive switches must have 17 branches.

**GOTCHA: `EnergyMeter` attributes include `direction` (EnergyDirection enum) and `cumulative` (boolean).** These were Block G audit additions. The `direction` field distinguishes import (consumption) from export (solar/battery). The `cumulative` field indicates whether `energy_wh` resets on meter reset or accumulates forever. Do not omit these when implementing EnergyMeter-related logic.

**GOTCHA: `PowerMeter.voltage_v` and `PowerMeter.current_a` are nullable.** Not all power meters report voltage and current — some only report watts. Always null-check these fields. This was a Block G audit finding.

<!-- Added 2026-03-21: Architecture benchmark assessment finding M-4 -->

**GOTCHA: Integration-initiated Display Name changes do NOT regenerate slugs.** When an integration adapter updates an entity's display name (e.g., from firmware metadata), the slug remains unchanged. Slug regeneration is a user-initiated action only (Identity Model §4.3). This prevents automations referencing slugs from silently breaking when an integration pushes a name update.

- **S4-06 / S4-02:** `requires com.homesynapse.event` is intentionally non-transitive — event-model types do not appear in device-model's public API (only Javadoc `@see` references). `requires transitive com.homesynapse.platform` is explicit in module-info despite being implicitly available through event-model's transitivity — this is intentional for JPMS clarity.

**GOTCHA: `hardwareIdentifiers` is `Set<HardwareIdentifier>`, not `List` (M4.B-S1 / AMD-44 §2.6).** `Device`, `ProposedDevice`, and the `DiscoveryPipeline.propose`/`findExistingDevice` params all take a `Set` — duplicate `(namespace, value)` tuples are semantically meaningless and collapse (`HardwareIdentifier` is a value record with record-derived `equals`/`hashCode`). `Device`/`ProposedDevice` gained compact constructors that did not exist before; they `Set.copyOf(hardwareIdentifiers)` (and `List.copyOf` the other collection field), so passing a **null collection now throws NPE** where the bare records previously accepted it. Build a `Set` from raw discovered identifiers (e.g. `Set.copyOf(list)` — collapses dups; not `Set.of(a, a)` which throws). `labels` stays `List<String>`.

**GOTCHA: `Floor`/`FloorRegistry`/`Area`/`AreaRegistry` are interface/record-level only (M4.B-S1 / AMD-44 Stage 1).** Like `DeviceRegistry`/`EntityRegistry`/`CapabilityRegistry`, the two new registries are **interfaces with no production implementation** in this module. No registry impl, no floor/area event emission, and no first-boot synthetic-`Area` creation ship in Stage 1 — those land with a later registry-implementation WU / AMD-45. (Historical note: AMD-44 Stage 2 — `EntityRole` + the `EntityType` legality matrix + `entityRole` on `Entity`/`ProposedEntity` — has since landed at **M4.B-S2**; see the EntityRole gotchas below.)

**GOTCHA: illegal `(entityType, entityRole)` pairs are UNREPRESENTABLE (M4.B-S2 / AMD-44 §2.5.1, DP-1).** The `Entity` and `ProposedEntity` compact constructors call `entityType.allows(entityRole)` and throw `IllegalArgumentException` ("entityRole %s is not legal for entityType %s") on a false. There is no soft-fail or override. The future `DiscoveryPipeline`/`EntityRegistry` implementations inherit this guard for free at adoption and reclassification time — **they must not duplicate or weaken it** (precedent: the AMD-47-INV-04 `AttributeSchema` compact-ctor guard). E.g. `new ProposedEntity(0, PLUG, caps, CONFIG)` throws; only `{PRIMARY}` is legal for PLUG.

**GOTCHA: a null `entityRole` COERCES to PRIMARY — it does NOT NPE (M4.B-S2, DP-2).** Both compact constructors replace `null` with `EntityRole.PRIMARY` **before** the matrix guard runs (coercion → guard → copies). So a null role on PLUG (which permits only PRIMARY) coerces and passes — it does not blow up inside `allows(...)`. Every pre-AMD-44 construction site flows through the back-compat 11-arg (`Entity`) / 3-arg (`ProposedEntity`) convenience constructor, which passes PRIMARY explicitly; since every `EntityType` permits PRIMARY, no existing construction breaks. **Do not "add" a null-role NPE.**

**GOTCHA: `EntityType` constant order is pinned by `EntityTypeTest` — matrix CONTENT is from AMD-44, ORDER is from source (M4.B-S2, DP-5).** `EntityTypeTest.allExpectedValuesPresent` asserts `containsExactly(LIGHT, SWITCH, PLUG, SENSOR, BINARY_SENSOR, ENERGY_METER)` — the source declaration order. The AMD §2.5.1 code block lists the constants in a *different* order (LIGHT, SWITCH, BINARY_SENSOR, SENSOR, PLUG, ENERGY_METER); only the **role sets** were taken from the AMD, never its ordering. Reordering the constants is a contract change and breaks the test. Also: `EnumSet.of(...)` is mutable — the ctor stores `Set.copyOf(...)`, so `legalRoles()` is immutable (`add` → `UnsupportedOperationException`); never retain the raw `EnumSet`.

## Phase 3 Notes

- **Registry implementations needed:** `SqliteDeviceRegistry`, `SqliteEntityRegistry`, `InMemoryCapabilityRegistry` (standard capabilities are static; custom capabilities persisted to SQLite). All must be thread-safe.
- **Validator implementations needed:** `SchemaAttributeValidator` (validates against `AttributeSchema` constraints), `SchemaCommandValidator` (validates against `ParameterSchema` constraints). Both are stateless — they look up schemas from `CapabilityRegistry`.
  - **AMD-47-INV-04 Phase-3 note (added M4.B3):** The non-declarable clause of INV-04 is already **structurally enforced at `AttributeSchema` construction** — the compact-constructor guard rejects `type == AttributeType.DEGRADED`, so a `DEGRADED`-typed schema can never be constructed and never reaches a validator. The future `SchemaAttributeValidator` **inherits this for free and must not duplicate or weaken it** (do not add a redundant DEGRADED check, and never relax the construction guard). The validator still owns the full validation framework AMD-47 leaves out of M4.B3: **ARRAY element constraints**, **QUANTITY unit/min/max** checks, and the general min/max/step/validValues/unit/nullable rules across all 8 types. The never-written-to-canonical-state-under-strict-mode clause of INV-04 rides with the upcaster projection-path wiring → **M4.0b-3**.
- **ExpectationFactory implementation needed:** Creates appropriate `Expectation` subtype based on `ConfirmationPolicy.mode` and command parameters. Used by Pending Command Ledger.
- **DiscoveryPipeline implementation needed:** Orchestrates propose → validate → adopt → publish_event flow. Must handle deduplication via `HardwareIdentifier` matching and existing device lookup.
  - **EntityRole validation inherits the construction guard (M4.B-S2 / AMD-44 §2.5.1, DP-1).** Because every adoption constructs an `Entity` and every adapter proposal constructs a `ProposedEntity`, and both compact constructors enforce `entityType.allows(entityRole)`, the illegal-pair rejection at adoption time is already structural. The same holds for reclassification: any future `EntityRegistry.reclassify(...)` (or equivalent) that rebuilds the `Entity` record inherits the guard. **Registry/pipeline implementations must not re-implement, duplicate, or weaken this check** — and an illegal pair can never reach canonical state because it cannot be constructed. The `entity_profile_changed` `oldRole`/`newRole` payload remains taxonomy/JSON-schema-level only (AMD-44 §4.3); no typed Java event record exists yet.
- **DeviceReplacementService implementation needed:** Compares capability sets between old and new device, produces `CapabilityCompatibilityReport`, and transfers entities with event publication.
- **~~JSR 385 integration (Phase 3)~~ — RETIRED (REC-93 / AMD-47-INV-03, 2026-05-30).** There is **no** JSR 385 / `javax.measure` integration: unit handling is hand-rolled `String` canonical-unit symbols, permanently, normalized at construction by `QuantityValue` (no units library — AMD-47-INV-03 forbids adding one). Do not resurrect this plan.
- **Testing strategy:** Unit tests for all record validation, sealed hierarchy exhaustiveness (ArchUnit), capability schema correctness. Integration tests for registry CRUD through SQLite. Property-based tests for `AttributeValidator` boundary conditions.
- **Performance targets (from Doc 02 §8):** EntityRegistry.getEntity() must complete within 1ms. CapabilityRegistry lookups must be sub-millisecond (in-memory). DiscoveryPipeline.adopt() may take up to 50ms including event publication.


---


## Phase 3 Cross-Module Context

*Updated 2026-05-17 (Post-M3.1 refresh). Phase 3 active — M3.1 `InProcessEventBus` landed 2026-05-17. Next milestone: M3.5a (StateProjection vertical slice). M3 governance: AMD-41/42/43 APPLIED. See `homesynapse-core-docs/design/HomeSynapse_Core_M3_Implementation_Plan_PLAN-M3-CONSOLIDATED-02.md` for the full M3 implementation plan.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: consumers that need to dispatch on device-related event types use registry lookup keyed by `@EventType` string
- **D-05** — *`@EventType` on every event record*: device state events (`state_changed`, `state_reported`, `command_issued`, etc.) are annotated in event-model and mapped to `[DEVICE_STATE]` category

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for Phase 3 pattern discoveries (including M3.1 entries on default interface methods, contract test capability hooks, and JPMS-enforced JDBC-free constraints).

# device-model — `com.homesynapse.device` — 57 types — Entity/Device/Capability model, sealed hierarchies, registries, discovery pipeline

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
    requires com.homesynapse.event;
    requires transitive com.homesynapse.platform;

    exports com.homesynapse.device;
}
```

The `requires transitive com.homesynapse.platform` declaration means any module that reads `com.homesynapse.device` automatically gets access to all identity types (`DeviceId`, `EntityId`, etc.) without needing to declare the dependency. Event-model is non-transitive because no event-model types (`EventEnvelope`, `EventPublisher`, etc.) appear in device-model's public API signatures — only Javadoc `@see` cross-references. Only platform-api is `requires transitive`.

## Package Structure

- **`com.homesynapse.device`** — All types in a single flat package. Contains: core domain records (Device, Entity), sealed capability hierarchy (15 standard records + CustomCapability), sealed AttributeValue hierarchy (5 records), sealed Expectation hierarchy (4 records), schema/definition records, validation interfaces, registry interfaces, discovery pipeline types, and supporting enums.

## Complete Type Inventory

### Sealed Capability Hierarchy (1 sealed interface + 15 standard records + 1 final class)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Capability` | sealed interface (permits 16 types) | Contract that capabilities implement — defines attributes, commands, and confirmation policy | Methods: `capabilityId()`, `version()`, `namespace()`, `attributeSchemas()` → `Map<String, AttributeSchema>`, `commandDefinitions()` → `Map<String, CommandDefinition>`, `confirmationPolicy()` → `ConfirmationPolicy`. |
| `OnOff` | record implements `Capability` | Binary on/off control | Attribute: `on` (boolean). Commands: `turn_on`, `turn_off`, `toggle`. Confirmation: EXACT_MATCH. Required for LIGHT, SWITCH, PLUG. |
| `Brightness` | record implements `Capability` | Brightness level control (0–100) | Attribute: `brightness` (int, 0–100). Command: `set_brightness(level)`. Confirmation: TOLERANCE (±2). Optional for LIGHT. |
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

### Sealed AttributeValue Hierarchy (1 sealed interface + 5 records)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `AttributeValue` | sealed interface (permits 5 types) | Typed representation of attribute values in the device model | Methods: `rawValue()` → `Object`, `attributeType()` → `AttributeType`. |
| `BooleanValue` | record(`boolean value`) implements `AttributeValue` | Boolean attribute value | Returns `AttributeType.BOOLEAN`. |
| `IntValue` | record(`long value`) implements `AttributeValue` | Integer attribute value (uses `long` for full range) | Returns `AttributeType.INT`. |
| `FloatValue` | record(`double value`) implements `AttributeValue` | Floating-point attribute value | Returns `AttributeType.FLOAT`. |
| `StringValue` | record(`String value`) implements `AttributeValue` | Free-form string attribute value | Non-null validation. Returns `AttributeType.STRING`. |
| `EnumValue` | record(`String value`) implements `AttributeValue` | Constrained enum string attribute value | Non-null validation. Returns `AttributeType.ENUM`. |

### Sealed Expectation Hierarchy (1 sealed interface + 4 records)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Expectation` | sealed interface (permits 4 types) | Evaluation contract for command confirmation against reported values | Method: `evaluate(AttributeValue reportedValue)` → `ConfirmationResult`. |
| `ExactMatch` | record(`AttributeValue expectedValue`) implements `Expectation` | Confirmed when reported value equals expected exactly | For boolean/enum attributes. |
| `WithinTolerance` | record(`double target`, `double tolerance`) implements `Expectation` | Confirmed when numeric value within ±tolerance of target | `evaluate()` defers to Phase 3 implementation. |
| `EnumTransition` | record(`String expectedValue`) implements `Expectation` | Confirmed when enum value matches expected transition target | For enum-valued attributes after command. |
| `AnyChange` | record(`AttributeValue previousValue`) implements `Expectation` | Confirmed when reported value differs from pre-command value | For toggle commands where the target state is unknown. |

### Core Domain Records

| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `Device` | record | Container for one or more Entity instances; carries hardware metadata | `deviceId` (DeviceId), `deviceSlug`, `displayName`, `manufacturer`, `model`, `serialNumber` (**nullable**), `firmwareVersion` (**nullable**), `hardwareVersion` (**nullable**), `integrationId` (IntegrationId), `areaId` (AreaId, **nullable**), `viaDeviceId` (DeviceId, **nullable**), `labels` (List\<String\>), `hardwareIdentifiers` (List\<HardwareIdentifier\>), `createdAt` (Instant). |
| `Entity` | record | Atomic functional unit of a device — primary target for automation, queries, commands | `entityId` (EntityId), `entitySlug`, `entityType` (EntityType), `displayName`, `deviceId` (DeviceId, **nullable** — for helper entities), `endpointIndex` (int), `areaId` (AreaId, **nullable** — inherits from device), `enabled` (boolean), `labels` (List\<String\>), `capabilities` (List\<CapabilityInstance\>), `createdAt` (Instant). |
| `CapabilityInstance` | record | Specific instantiation of a capability on a device entity with feature map | `capabilityId`, `version` (int), `namespace`, `featureMap` (int — bitmask), `attributes` (Map\<String, AttributeSchema\>), `commands` (Map\<String, CommandDefinition\>), `confirmation` (ConfirmationPolicy). |
| `HardwareIdentifier` | record | Protocol-level device identifier for discovery deduplication | `namespace` (e.g., "zigbee_ieee"), `value`. Both non-null. |
| `ProposedDevice` | record | Device detected by discovery pipeline, proposed for adoption | `hardwareIdentifiers`, `proposedManufacturer`, `proposedModel`, `proposedEntities` (List\<ProposedEntity\>). |
| `ProposedEntity` | record | Proposed entity mapping from detected device endpoint | `endpointIndex` (int), `proposedEntityType` (EntityType), `proposedCapabilities` (List\<String\>). |
| `CapabilityCompatibilityReport` | record | Result of capability compatibility check during device replacement | `compatible` (boolean), `capabilityAdditions` (List\<String\>), `capabilityLosses` (List\<String\>), `requiresUserConfirmation` (boolean). |

### Schema and Definition Records

| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `AttributeSchema` | record | Defines schema for a single attribute within a capability | `attributeKey`, `type` (AttributeType), `minimum` (Number, **nullable**), `maximum` (Number, **nullable**), `step` (Number, **nullable**), `validValues` (Set\<String\>, **nullable**), `unitSymbol` (String, **nullable**), `canonicalUnitSymbol` (String, **nullable**), `permissions` (Set\<Permission\>), `nullable` (boolean), `persistent` (boolean). |
| `ParameterSchema` | record | Describes a single parameter accepted by a device command | `parameterName`, `type` (AttributeType), `minimum` (Number, **nullable**), `maximum` (Number, **nullable**), `required` (boolean), `requiredFeatures` (int bitmask), `validValues` (Set\<String\>, **nullable**). |
| `CommandDefinition` | record | Defines a command that can be issued to a device through a capability | `commandType`, `parameters` (List\<ParameterSchema\>), `requiredFeatures` (int), `expectedOutcomes` (List\<ExpectedOutcome\>), `defaultTimeout` (Duration), `idempotencyClass` (IdempotencyClass). |
| `ConfirmationPolicy` | record | Governs how Pending Command Ledger confirms command execution | `mode` (ConfirmationMode), `authoritativeAttributes` (List\<String\>), `defaultTolerance` (Number, **nullable**), `defaultTimeoutMs` (long). |
| `ExpectedOutcome` | record | Maps an attribute to confirmation logic for command evaluation | `attributeKey`, `expectation` (Expectation), `timeoutMs` (long). |
| `ValidationError` | record | Single validation failure | `field`, `reason`, `rejectedValue`. |
| `ValidationResult` | record | Structured result from attribute/command validation | `valid` (boolean), `errors` (List\<ValidationError\>). |

### Enums

| Type | Kind | Purpose | Values |
|---|---|---|---|
| `EntityType` | enum | Functional classification of a device entity | LIGHT (requires OnOff; optional Brightness, ColorTemperature), SWITCH (requires OnOff), PLUG (requires OnOff; optional PowerMeasurement, EnergyMeter), SENSOR (requires 1+ measurement), BINARY_SENSOR (requires 1+ of BinaryState/Contact/Motion/Occupancy), ENERGY_METER (requires EnergyMeter; optional PowerMeter, Battery, DeviceHealth). **Only 6 MVP values declared.** |
| `AttributeType` | enum | Primitive data type classifier for attribute values | BOOLEAN, INT, FLOAT, STRING, ENUM. |
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
| `DiscoveryPipeline` | interface | Orchestrates device discovery, proposal, and adoption lifecycle | `propose(List<HardwareIdentifier>, String manufacturer, String model, List<ProposedEntity>)` → `ProposedDevice`, `adopt(ProposedDevice, String displayName, AreaId)` → `Device`, `findExistingDevice(List<HardwareIdentifier>)` → `Optional<Device>`. |

**Total: 57 public types + 1 package-info.java + 1 module-info.java = 59 Java files.**

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **event-model** (`com.homesynapse.event`) | `requires` (non-transitive) — Event types referenced only in Javadoc `@see` tags, not in public API signatures | `EventId` (in CommandDefinition/ExpectedOutcome cross-references), `CommandIdempotency` (mapped to device-model's `IdempotencyClass`). |
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types for device/entity/area identification | `DeviceId`, `EntityId`, `IntegrationId`, `AreaId` (fields on Device, Entity, and discovery types), `Ulid` (underlying identity). |

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

## Sealed Hierarchies

### Capability Hierarchy
```
sealed interface Capability
    permits OnOff, Brightness, ColorTemperature,
            TemperatureMeasurement, HumidityMeasurement,
            IlluminanceMeasurement, PowerMeasurement,
            BinaryState, Contact, Motion, Occupancy,
            Battery, DeviceHealth, EnergyMeter, PowerMeter,
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

### AttributeValue Hierarchy
```
sealed interface AttributeValue
    permits BooleanValue, IntValue, FloatValue, StringValue, EnumValue
```
**Exhaustive switch pattern:**
```java
switch (value) {
    case BooleanValue bv -> ...
    case IntValue iv -> ...
    case FloatValue fv -> ...
    case StringValue sv -> ...
    case EnumValue ev -> ...
}
```

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

3. **`unitSymbol` is `String`, not JSR 385 `Unit<?>`.** Phase 2 uses plain string for unit fields (e.g., "°C", "W", "lux"). Phase 3 will add JSR 385 (`javax.measure`) as a dependency for proper unit conversion and validation. The string representation was chosen for Phase 2 to avoid pulling in the JSR 385 dependency before it's needed. Reference: Doc 02 §3, Block G audit.

4. **Feature maps use int bitmask, not Set\<Feature\>.** Zigbee ZCL defines feature maps as bitmasks. Using `int` directly avoids translation overhead and matches the protocol representation. The `requiredFeatures` field on `ParameterSchema` and `CommandDefinition` uses the same bitmask semantics. Reference: Doc 02 §3.

5. **Discovery pipeline produces `ProposedDevice`, not `Device` directly.** Integrations propose devices; the core validates and adopts them. This prevents integrations from creating invalid device/entity/capability combinations. The pipeline also deduplicates via `HardwareIdentifier` matching. Reference: Doc 02 §4.

## Gotchas

**GOTCHA: EntityType has only 6 MVP values.** LIGHT, SWITCH, PLUG, SENSOR, BINARY_SENSOR, ENERGY_METER are the only declared enum constants. Post-MVP values (THERMOSTAT, LOCK, COVER, MEDIA_PLAYER, CAMERA, CLIMATE, FAN, VALVE, SIREN, REMOTE) are documented in Javadoc but NOT declared as enum constants. Do not add them prematurely.

**GOTCHA: `String` used for unit fields (`unitSymbol`) instead of JSR 385 `Unit<?>`.** This is a known Phase 2 simplification. Phase 3 adds the JSR 385 dependency. Do not introduce unit conversion logic using string comparison — wait for proper JSR 385 types.

**GOTCHA: `module-info.java` uses `requires transitive` for platform-api only.** Event-model is non-transitive (`requires com.homesynapse.event`) because no event-model types appear in device-model's public API signatures — only Javadoc `@see` cross-references. `Device` uses `DeviceId` (platform-api), which IS transitive. Removing `transitive` from platform-api will break downstream compilation, but event-model is correctly non-transitive.

**GOTCHA: Nullable fields on `Device`.** `serialNumber`, `firmwareVersion`, `hardwareVersion` are nullable (not all hardware reports these). `areaId` is nullable (device not yet assigned to an area). `viaDeviceId` is nullable (only set for devices connected through a router/coordinator). These were audit findings against Doc 02 during Block G — do not regress them to non-null.

**GOTCHA: `Entity.deviceId` is nullable.** Helper entities (entities not backed by physical hardware, such as virtual sensors or computed entities) have `deviceId = null`. This is a deliberate design decision, not a bug. Always null-check `Entity.deviceId()` before using it.

**GOTCHA: `Entity.areaId` is nullable and inherits from Device.** If `Entity.areaId` is null, the entity inherits its area from its parent device (`Device.areaId`). If both are null, the entity has no area assignment. Do not assume a non-null area is always available.

**GOTCHA: Capability count is 16, not 15.** The sealed interface permits 16 types: 15 standard records (OnOff, Brightness, ColorTemperature, TemperatureMeasurement, HumidityMeasurement, IlluminanceMeasurement, PowerMeasurement, BinaryState, Contact, Motion, Occupancy, Battery, DeviceHealth, EnergyMeter, PowerMeter) + 1 final class (CustomCapability). Exhaustive switches must have 16 branches.

**GOTCHA: `EnergyMeter` attributes include `direction` (EnergyDirection enum) and `cumulative` (boolean).** These were Block G audit additions. The `direction` field distinguishes import (consumption) from export (solar/battery). The `cumulative` field indicates whether `energy_wh` resets on meter reset or accumulates forever. Do not omit these when implementing EnergyMeter-related logic.

**GOTCHA: `PowerMeter.voltage_v` and `PowerMeter.current_a` are nullable.** Not all power meters report voltage and current — some only report watts. Always null-check these fields. This was a Block G audit finding.

<!-- Added 2026-03-21: Architecture benchmark assessment finding M-4 -->

**GOTCHA: Integration-initiated Display Name changes do NOT regenerate slugs.** When an integration adapter updates an entity's display name (e.g., from firmware metadata), the slug remains unchanged. Slug regeneration is a user-initiated action only (Identity Model §4.3). This prevents automations referencing slugs from silently breaking when an integration pushes a name update.

## Phase 3 Notes

- **Registry implementations needed:** `SqliteDeviceRegistry`, `SqliteEntityRegistry`, `InMemoryCapabilityRegistry` (standard capabilities are static; custom capabilities persisted to SQLite). All must be thread-safe.
- **Validator implementations needed:** `SchemaAttributeValidator` (validates against `AttributeSchema` constraints), `SchemaCommandValidator` (validates against `ParameterSchema` constraints). Both are stateless — they look up schemas from `CapabilityRegistry`.
- **ExpectationFactory implementation needed:** Creates appropriate `Expectation` subtype based on `ConfirmationPolicy.mode` and command parameters. Used by Pending Command Ledger.
- **DiscoveryPipeline implementation needed:** Orchestrates propose → validate → adopt → publish_event flow. Must handle deduplication via `HardwareIdentifier` matching and existing device lookup.
- **DeviceReplacementService implementation needed:** Compares capability sets between old and new device, produces `CapabilityCompatibilityReport`, and transfers entities with event publication.
- **JSR 385 integration (Phase 3):** Add `javax.measure` dependency for proper unit conversion. Replace `String unitSymbol` with `Unit<?>` where appropriate. Backward-compatible — existing String values remain valid.
- **Testing strategy:** Unit tests for all record validation, sealed hierarchy exhaustiveness (ArchUnit), capability schema correctness. Integration tests for registry CRUD through SQLite. Property-based tests for `AttributeValidator` boundary conditions.
- **Performance targets (from Doc 02 §8):** EntityRegistry.getEntity() must complete within 1ms. CapabilityRegistry lookups must be sub-millisecond (in-memory). DiscoveryPipeline.adopt() may take up to 50ms including event publication.

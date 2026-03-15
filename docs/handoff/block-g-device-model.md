# Block G — Device Model & Capability System

**Module:** `core/device-model`
**Package:** `com.homesynapse.device`
**Design Doc:** Doc 02 — Device Model & Capability System (§3, §4, §8)
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :core:device-model:compileJava`

---

## Strategic Context

The Device Model is the type system for every physical and logical device in HomeSynapse. Every subsystem downstream — State Store, Automation Engine, Integration Runtime, REST API, WebSocket API — queries these types. The `Capability` sealed hierarchy defines what devices *can do*; `AttributeValue` defines what they *report*; `Expectation` defines how command confirmation works. Getting these interfaces airtight now means 10+ downstream modules implement against stable contracts.

## Scope

**IN:** All public interfaces, records, enums, and sealed interfaces defined in Doc 02 §4, §8. Full Javadoc with contracts, nullability, thread-safety, and @see cross-references. module-info.java with correct exports and requires.

**OUT:** Implementation code. Tests. Discovery pipeline implementation details (§3.12 implementation is Phase 3). JSR 385 `Unit<?>` integration — use `String` for unit representation in Phase 2 (same pragmatic decision as Sprint 1 payload types). No QuantityValue record — defer to Phase 3 when JSR 385 dependency is added.

---

## Locked Decisions

1. **Typed IDs already exist.** `DeviceId`, `EntityId`, `IntegrationId`, `AreaId` are in `com.homesynapse.platform.identity` from Sprint 1. Do NOT recreate them. Import them via `requires com.homesynapse.platform;`.

2. **Capability is a sealed interface, not an enum.** Permitted subtypes are the 14 standard capabilities + `CustomCapability`. Each standard capability is a `record` implementing `Capability`. The sealed hierarchy enables exhaustive `switch` expressions (Doc 02 §3.9).

3. **Standard capabilities are records, not interfaces.** Each standard capability (OnOff, Brightness, etc.) is a `record` that implements `Capability`. They carry their attribute schemas and command definitions as fields. This keeps them immutable and data-oriented.

4. **AttributeValue is a sealed interface with record subtypes.** BooleanValue, IntValue, FloatValue, StringValue, EnumValue — all records. No QuantityValue in Phase 2.

5. **Expectation is a sealed interface with record subtypes.** ExactMatch, WithinTolerance, EnumTransition, AnyChange. The `evaluate` method signature is defined but returns `ConfirmationResult` enum — implementation in Phase 3.

6. **String for unit fields.** Wherever Doc 02 specifies `Unit<?>` (JSR 385), use `String unitSymbol` instead. Phase 3 adds the JSR 385 dependency and proper unit types.

7. **All collections in records must be unmodifiable.** Use `List.copyOf()`, `Map.copyOf()`, `Set.copyOf()` semantics in Javadoc contracts. The records themselves use standard Java record semantics; unmodifiability is a documented contract, not enforced in Phase 2.

8. **Module requires:** `com.homesynapse.event` (for DomainEvent, EventPublisher references in Javadoc) and `com.homesynapse.platform` (for typed identity wrappers).

9. **EntityType MVP values only.** The 6 MVP values: LIGHT, SWITCH, PLUG, SENSOR, BINARY_SENSOR, ENERGY_METER. Post-MVP values (LOCK, CLIMATE, COVER, etc.) are documented in Javadoc as reserved but NOT declared as enum constants.

10. **Service interfaces define contracts only.** DeviceRegistry, EntityRegistry, etc. have method signatures with full Javadoc but no default methods. Exception types are declared but minimal (extend RuntimeException with message constructor).

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Foundation Enums (no dependencies within module)

| File | Type | Notes |
|------|------|-------|
| `AttributeType.java` | enum | BOOLEAN, INT, FLOAT, STRING, ENUM. §3.7 |
| `Permission.java` | enum | READ, WRITE, NOTIFY. §3.7 |
| `EntityType.java` | enum | 6 MVP values. Javadoc documents required/optional capabilities per type. §3.10 |
| `IdempotencyClass.java` | enum | IDEMPOTENT, NOT_IDEMPOTENT, TOGGLE. §3.8 |
| `ConfirmationMode.java` | enum | EXACT_MATCH, TOLERANCE, ENUM_MATCH, ANY_CHANGE, DISABLED. §3.8 |
| `ConfirmationResult.java` | enum | CONFIRMED, NOT_YET, FAILED, TIMEOUT. §3.8 |
| `EnergyDirection.java` | enum | IMPORT, EXPORT, BIDIRECTIONAL. §3.6 |

### Group 2: Sealed Value Types (depends on enums)

| File | Type | Notes |
|------|------|-------|
| `AttributeValue.java` | sealed interface | Marker: `sealed interface AttributeValue permits BooleanValue, IntValue, FloatValue, StringValue, EnumValue`. Methods: `Object rawValue()`, `AttributeType attributeType()`. §8.2 |
| `BooleanValue.java` | record implements AttributeValue | `(boolean value)` |
| `IntValue.java` | record implements AttributeValue | `(long value)` |
| `FloatValue.java` | record implements AttributeValue | `(double value)` |
| `StringValue.java` | record implements AttributeValue | `(String value)` |
| `EnumValue.java` | record implements AttributeValue | `(String value)` |

### Group 3: Schema & Definition Records (depends on enums, AttributeValue)

| File | Type | Notes |
|------|------|-------|
| `AttributeSchema.java` | record | Fields: attributeKey, type (AttributeType), minimum (Number, nullable), maximum (Number, nullable), step (Number, nullable), validValues (Set\<String\>, nullable), unitSymbol (String, nullable), canonicalUnitSymbol (String, nullable), permissions (Set\<Permission\>), nullable (boolean), persistent (boolean). §3.7 |
| `ParameterSchema.java` | record | Fields: parameterName, type (AttributeType), minimum (Number, nullable), maximum (Number, nullable), required (boolean), requiredFeatures (int), validValues (Set\<String\>, nullable). Implied by CommandDefinition |
| `CommandDefinition.java` | record | Fields: commandType, parameters (List\<ParameterSchema\>), requiredFeatures (int), expectedOutcomes (List\<ExpectedOutcome\>), defaultTimeout (Duration), idempotencyClass (IdempotencyClass). §3.8 |
| `ConfirmationPolicy.java` | record | Fields: mode (ConfirmationMode), authoritativeAttributes (List\<String\>), defaultTolerance (Number, nullable), defaultTimeoutMs (long). §4.3 |
| `HardwareIdentifier.java` | record | Fields: namespace (String), value (String). §8.2 |

### Group 4: Expectation Sealed Hierarchy (depends on AttributeValue, ConfirmationResult)

| File | Type | Notes |
|------|------|-------|
| `Expectation.java` | sealed interface | Methods: `ConfirmationResult evaluate(AttributeValue reportedValue)`. Permits: ExactMatch, WithinTolerance, EnumTransition, AnyChange. §3.8 |
| `ExactMatch.java` | record implements Expectation | `(AttributeValue expectedValue)` |
| `WithinTolerance.java` | record implements Expectation | `(double target, double tolerance)` |
| `EnumTransition.java` | record implements Expectation | `(String expectedValue)` |
| `AnyChange.java` | record implements Expectation | `(AttributeValue previousValue)` |

### Group 5: ExpectedOutcome (depends on Expectation)

| File | Type | Notes |
|------|------|-------|
| `ExpectedOutcome.java` | record | Fields: attributeKey (String), expectation (Expectation), timeoutMs (long). §3.8 |

### Group 6: Capability Sealed Hierarchy (depends on AttributeSchema, CommandDefinition, ConfirmationPolicy)

| File | Type | Notes |
|------|------|-------|
| `Capability.java` | sealed interface | Methods: `String capabilityId()`, `int version()`, `String namespace()`, `Map<String, AttributeSchema> attributeSchemas()`, `Map<String, CommandDefinition> commandDefinitions()`, `ConfirmationPolicy confirmationPolicy()`. Permits all 14 standard + CustomCapability. §3.5, §3.9 |
| `OnOff.java` | record implements Capability | Standard capability. Javadoc: capability_id="on_off", attributes: on (BOOLEAN), commands: turn_on, turn_off, toggle |
| `Brightness.java` | record implements Capability | capability_id="brightness", attributes: brightness (INT 0-100), commands: set_brightness |
| `ColorTemperature.java` | record implements Capability | capability_id="color_temperature", attributes: color_temp_kelvin (INT), commands: set_color_temperature |
| `TemperatureMeasurement.java` | record implements Capability | Read-only. capability_id="temperature_measurement", attributes: temperature_c (FLOAT) |
| `HumidityMeasurement.java` | record implements Capability | Read-only. capability_id="humidity_measurement", attributes: humidity_pct (FLOAT) |
| `IlluminanceMeasurement.java` | record implements Capability | Read-only. capability_id="illuminance_measurement", attributes: illuminance_lux (FLOAT) |
| `PowerMeasurement.java` | record implements Capability | Read-only. capability_id="power_measurement", attributes: power_w (FLOAT) |
| `BinaryState.java` | record implements Capability | Read-only. capability_id="binary_state", attributes: active (BOOLEAN) |
| `Contact.java` | record implements Capability | Read-only. capability_id="contact", attributes: open (BOOLEAN) |
| `Motion.java` | record implements Capability | Read-only. capability_id="motion", attributes: detected (BOOLEAN) |
| `Occupancy.java` | record implements Capability | Read-only. capability_id="occupancy", attributes: occupied (BOOLEAN) |
| `Battery.java` | record implements Capability | Read-only. capability_id="battery", attributes: battery_pct (INT), battery_low (BOOLEAN) |
| `DeviceHealth.java` | record implements Capability | Read-only. capability_id="device_health", attributes: rssi_dbm (INT), lqi (INT) |
| `EnergyMeter.java` | record implements Capability | capability_id="energy_meter", attributes: energy_wh (FLOAT), direction (EnergyDirection enum) |
| `PowerMeter.java` | record implements Capability | Read-only. capability_id="power_meter", attributes: power_w (FLOAT), voltage_v (FLOAT nullable), current_a (FLOAT nullable) |
| `CustomCapability.java` | final class implements Capability | Runtime-registered. Fields: capabilityId, version, namespace, attributeSchemas (Map), commandDefinitions (Map), confirmationPolicy. §3.9 |

### Group 7: Core Domain Records (depends on Capability, identity types)

| File | Type | Notes |
|------|------|-------|
| `CapabilityInstance.java` | record | Fields: capabilityId (String), version (int), namespace (String), featureMap (int), attributes (Map\<String, AttributeSchema\>), commands (Map\<String, CommandDefinition\>), confirmation (ConfirmationPolicy). §4.3 |
| `Device.java` | record | Fields: deviceId (DeviceId), deviceSlug (String), displayName (String), manufacturer (String), model (String), serialNumber (String), firmwareVersion (String), hardwareVersion (String), integrationId (IntegrationId), areaId (AreaId, nullable), viaDeviceId (DeviceId, nullable), labels (List\<String\>), hardwareIdentifiers (List\<HardwareIdentifier\>), createdAt (Instant). §4.1 |
| `Entity.java` | record | Fields: entityId (EntityId), entitySlug (String), entityType (EntityType), displayName (String), deviceId (DeviceId), endpointIndex (int), areaId (AreaId, nullable), enabled (boolean), labels (List\<String\>), capabilities (List\<CapabilityInstance\>), createdAt (Instant). §4.2 |
| `ProposedEntity.java` | record | Fields: endpointIndex (int), proposedEntityType (EntityType), proposedCapabilities (List\<String\>). Discovery |
| `ProposedDevice.java` | record | Fields: hardwareIdentifiers (List\<HardwareIdentifier\>), proposedManufacturer (String), proposedModel (String), proposedEntities (List\<ProposedEntity\>). §3.12 |
| `CapabilityCompatibilityReport.java` | record | Fields: compatible (boolean), capabilityAdditions (List\<String\>), capabilityLosses (List\<String\>), requiresUserConfirmation (boolean). §3.14 |
| `ValidationResult.java` | record | Fields: valid (boolean), errors (List\<ValidationError\>). Shared by AttributeValidator and CommandValidator |
| `ValidationError.java` | record | Fields: field (String), reason (String), rejectedValue (String). Structured validation error |

### Group 8: Service Interfaces (depends on all above)

| File | Type | Notes |
|------|------|-------|
| `DeviceRegistry.java` | interface | CRUD + hardware identifier mapping + discovery dedup. §8.1. Full Javadoc on every method. |
| `EntityRegistry.java` | interface | CRUD + type validation + capability composition + enable/disable + selector resolution. §8.1 |
| `CapabilityRegistry.java` | interface | Standard lookup + custom registration + schema retrieval. §8.1 |
| `ExpectationFactory.java` | interface | Produces Expectation instances from capability + command + parameters. §8.1 |
| `AttributeValidator.java` | interface | Validates attribute values against schemas. Returns ValidationResult. §8.1 |
| `CommandValidator.java` | interface | Validates command parameters against schemas. Returns ValidationResult. §8.1 |
| `DeviceReplacementService.java` | interface | Compatibility check + entity transfer. §8.1 |
| `DiscoveryPipeline.java` | interface | Detection → proposal → adoption lifecycle. §3.12, §8.1 |

### Group 9: Module Info

| File | Notes |
|------|-------|
| `module-info.java` | Uncomment `exports com.homesynapse.device;`. Add `requires com.homesynapse.platform;` (for identity types). Keep `requires com.homesynapse.event;`. |

---

## File Placement

All files go in: `core/device-model/src/main/java/com/homesynapse/device/`
Module info: `core/device-model/src/main/java/module-info.java`

---

## Javadoc Standards

Per Sprint 1 lessons:
1. Every `@param` documents nullability
2. Every type has `@see` cross-references to related types
3. Thread-safety explicitly stated on interfaces (all registries are documented as thread-safe for concurrent reads)
4. Class-level Javadoc explains the "why" — what role this type plays in HomeSynapse
5. Reference Doc 02 sections in class-level Javadoc: `@see` or inline `{@code Doc 02 §X.Y}`
6. Collections documented as unmodifiable in their contracts

---

## Compile Gate

```bash
./gradlew :core:device-model:compileJava
```

Must pass with `-Xlint:all -Werror`. Run full project gate after:

```bash
./gradlew check
```

All 19 modules must still compile (no regressions from module-info changes).

---

## Estimated Size

~50 files, ~2000–3000 lines. This is the largest single block in Phase 2. Expect 3–4 hours. If needed, split execution into two sessions (Groups 1–5 first, then Groups 6–9).

---

## Notes

- The standard capabilities (OnOff, Brightness, etc.) are data-carrying records, not behavioral interfaces. They describe what a capability *is* — the schemas and commands it supports. They do NOT contain implementation logic.
- `CustomCapability` is a `final class` (not a record) because it needs to be constructable at runtime from JSON schema. It implements the same `Capability` interface.
- The `Expectation.evaluate()` method is declared but its body is Phase 3. In Phase 2, the method signature exists for compile-time type checking.
- `DiscoveryPipeline` and `DeviceReplacementService` are smaller interfaces that orchestrate the other registries. Their method signatures reference types from Groups 1–7.

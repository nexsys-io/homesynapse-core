# integration-zigbee — `com.homesynapse.integration.zigbee` — Scaffold — Zigbee 3.0 coordinator, MVP protocol adapter, IEEEAddress (raw long, NOT ULID)

## Purpose

The integration-zigbee module is HomeSynapse's first protocol integration adapter (LTD-12). It defines the Zigbee-specific data model that translates between the Zigbee Cluster Library (ZCL) protocol world of endpoints, clusters, and attribute reports and the HomeSynapse world of entities, capabilities, and domain events. This module exercises the entire vertical stack — event publishing, device discovery, entity registration, state reporting, command dispatch, telemetry routing, and health reporting — validating the integration architecture defined by integration-api (Block I) and integration-runtime (Block O).

The Phase 2 specification contains 39 Java files: 7 enums, 1 @FunctionalInterface, 15 records, 1 sealed interface (ZigbeeFrame with 2 permits), 1 sealed interface (ManufacturerCodec with 2 permits), 6 service interfaces, 2 adapter interfaces (extending integration-api), and package-info.java.

## Design Doc Reference

**Doc 08 — Zigbee Adapter** is the governing design document:
- §3.3: ZNP/EZSP transport framing — CommandType, ZnpFrame, EzspFrame
- §3.4: Interview pipeline — InterviewResult, InterviewStatus, NodeDescriptor, EndpointDescriptor
- §3.5: Cluster handler table — ClusterHandler interface, value normalization
- §3.6: Device profiles — DeviceProfile, DeviceCategory, ManufacturerModelPair, ClusterOverride
- §3.7: Reporting configuration — ReportingOverride
- §3.8: Tuya DP protocol — TuyaDpType, TuyaDatapointMapping, TuyaDpCodec, ValueConverter
- §3.9: Xiaomi TLV protocol — XiaomiTagMapping, XiaomiTlvCodec
- §3.11: Topology scan — NeighborTableEntry
- §3.12: IAS Zone — ZoneType
- §3.13: Network parameters — NetworkParameters
- §3.14: Device metadata cache — ZigbeeDeviceRecord
- §3.15: Route health monitoring — RouteHealth, RouteStatus (AMD-07)
- §4.1: Integration descriptor — ZigbeeAdapterFactory
- §4.2: Frame types — ZigbeeFrame sealed hierarchy, ZclFrame
- §4.4: Availability tracking — AvailabilityReason, AvailabilityTracker, AttributeReport
- §5: Contracts and invariants — coordinator type invisibility (INV-CE-04)
- §8.1: Service interfaces — all interfaces in this module

## JPMS Module

```
module com.homesynapse.integration.zigbee {
    requires transitive com.homesynapse.integration;

    exports com.homesynapse.integration.zigbee;
}
```

Single `requires transitive` for integration-api. Through integration-api's own transitive chain, this provides access to event-model, device-model, state-store, persistence, configuration, platform-api, and java.net.http. No additional `requires` needed in Phase 2.

## Package Structure

**`com.homesynapse.integration.zigbee`** — Single flat package. 39 Java files total.

## Complete Type Inventory

### Enums (7)

| Type | Values | Purpose |
|---|---|---|
| `DeviceCategory` (4) | STANDARD_ZCL, MINOR_QUIRKS, MIXED_CUSTOM, FULLY_CUSTOM | Classifies devices by adapter-specific handling required. ~60% STANDARD_ZCL. |
| `CommandType` (3) | SREQ(0x20), SRSP(0x60), AREQ(0x40) | ZNP frame command types (CMD0 bits 7-5). Each carries `protocolId` field. |
| `InterviewStatus` (3) | COMPLETE, PARTIAL, PENDING | Interview pipeline completion state per device. |
| `RouteStatus` (3) | HEALTHY, DEGRADED, UNREACHABLE | Per-device route health based on consecutive command failures. |
| `TuyaDpType` (6) | RAW(0x00), BOOL(0x01), VALUE(0x02), STRING(0x03), ENUM(0x04), BITMAP(0x05) | Tuya datapoint type identifiers. Each carries `protocolId` field. |
| `AvailabilityReason` (6) | FIRST_CONTACT, PING_SUCCESS, FRAME_RECEIVED, PING_TIMEOUT, SILENCE_TIMEOUT, LEAVE | Device availability state transition reasons. |
| `ZoneType` (5) | MOTION(0x000D), CONTACT(0x0015), WATER_LEAK(0x002A), SMOKE(0x0028), VIBRATION(0x002D) | IAS Zone type → capability mapping. Each carries `zclId` and `capabilityId` fields. |

### Functional Interface (1)

| Type | Purpose |
|---|---|
| `ValueConverter` | `@FunctionalInterface`. `Object convert(Object rawValue)`. Protocol value → HomeSynapse canonical value. |

### Value Record (1)

| Type | Fields | Purpose |
|---|---|---|
| `IEEEAddress` (1 field) | `value` (long — 64-bit IEEE EUI-64) | Permanent hardware identifier. NOT a ULID. Methods: `toHexString()`, `fromHexString(String)`, `toString()` delegates to `toHexString()`. |

### Small Data Records (8)

| Type | Fields | Purpose |
|---|---|---|
| `ManufacturerModelPair` (2) | `manufacturerName` (String), `modelIdentifier` (String) | Device profile matching key. Both non-null. |
| `EndpointDescriptor` (5) | `endpointId` (1-240), `profileId`, `deviceTypeId`, `inputClusters` (List, copied), `outputClusters` (List, copied) | ZCL Simple Descriptor per endpoint. |
| `NodeDescriptor` (4) | `deviceType` (0-2), `manufacturerCode`, `maxBufferSize` (>0), `macCapabilityFlags` | ZDO Node Descriptor. deviceType determines power-source. |
| `ClusterOverride` (3) | `clusterId`, `attributeOverrides` (Map, copied), `disableDefaultHandler` | Per-cluster behavioral adjustments in device profiles. |
| `ReportingOverride` (4) | `clusterId`, `minInterval`, `maxInterval` (>minInterval), `reportableChange` | Per-cluster reporting config overrides. |
| `InitializationWrite` (6) | `endpoint` (1-240), `clusterId`, `attributeId`, `dataType`, `value` (Object, non-null), `manufacturerCode` | Post-adoption ZCL attribute writes. |
| `TuyaDatapointMapping` (4) | `dpId` (1-255), `attributeKey`, `expectedType` (TuyaDpType), `converter` (ValueConverter) | Tuya DP-to-capability mapping. |
| `XiaomiTagMapping` (4) | `tag` (0-255), `attributeKey`, `zclDataType`, `converter` (ValueConverter) | Xiaomi TLV tag-to-attribute mapping. |

### Composite Data Records (6)

| Type | Fields | Purpose |
|---|---|---|
| `NeighborTableEntry` (6) | `ieeeAddress`, `networkAddress` (0-0xFFFF), `deviceType` (0-2), `lqi` (0-255), `depth` (≥0), `parentIeee` (**nullable**) | Topology scan entry. parentIeee null for coordinator. |
| `NetworkParameters` (4) | `channel` (11-26), `panId` (0-0xFFFF), `extendedPanId` (long), `networkKeyRef` (String — opaque ref, NOT the key) | Zigbee network identity. INV-SE-03: key stored encrypted. |
| `AttributeReport` (4) | `entityRef`, `attributeKey`, `value` (Object), `eventTime` (Instant) | Normalized attribute observation. All non-null. Canonical values (°C, %). |
| `InterviewResult` (8) | `ieeeAddress`, `networkAddress`, `nodeDescriptor`, `endpoints` (List, copied, non-empty), `manufacturerName`, `modelIdentifier`, `powerSource`, `interviewStatus` | Interview pipeline result. All non-null. |
| `RouteHealth` (7) | `target`, `consecutiveFailures`, `totalFailures`, `totalSuccesses`, `lastSuccess` (**nullable**), `lastFailure` (**nullable**), `status` | Per-device route health tracking. AMD-07. |
| `DeviceProfile` (9) | `profileId`, `matches` (Set, copied, non-empty), `category`, `clusterOverrides` (Map, **nullable**), `reportingOverrides` (Map, **nullable**), `manufacturerCodec` (String, **nullable**), `interviewSkips` (Set, **nullable**), `tuyaDatapoints` (List, **nullable**), `initializationWrites` (List, **nullable**) | Per-model device behavior overrides. 5 nullable collection fields use conditional defensive copy. |
| `ZigbeeDeviceRecord` (10) | `ieeeAddress`, `networkAddress` (0-0xFFFF), `nodeDescriptor` (**nullable**), `endpoints` (List, **nullable**, copied), `manufacturerName` (**nullable**), `modelIdentifier` (**nullable**), `powerSource`, `lastSeen`, `interviewStatus`, `matchedProfileId` (**nullable**) | Local device metadata cache. Nullable fields populated as interview progresses. |

### Sealed Interface Hierarchies (2)

**ZigbeeFrame** — Transport-level frame representation:

| Type | Purpose |
|---|---|
| `ZigbeeFrame` | Sealed root. No methods — variants have incompatible structures. |
| `ZnpFrame` record(4) implements ZigbeeFrame | `subsystem`, `commandId`, `type` (CommandType), `data` (byte[], defensive copy + accessor override) |
| `EzspFrame` record(3) implements ZigbeeFrame | `frameId`, `isCallback`, `parameters` (byte[], defensive copy + accessor override) |

**ManufacturerCodec** — Manufacturer-specific codec subsystems:

| Type | Purpose |
|---|---|
| `ManufacturerCodec` | Sealed root. 2 methods: `decode(ZclFrame)` → `List<AttributeReport>`, `encode(String, Map)` → `ZclFrame`. |
| `TuyaDpCodec` non-sealed interface | Marker subtype for Tuya cluster 0xEF00 devices. |
| `XiaomiTlvCodec` non-sealed interface | Marker subtype for Xiaomi 0xFF01/0xFCC0 devices. |

### Protocol-Level Record (1)

| Type | Fields | Purpose |
|---|---|---|
| `ZclFrame` (7) | `sourceEndpoint` (0-240), `destinationEndpoint` (0-240), `clusterId` (0-0xFFFF), `commandId` (0-0xFF), `isClusterSpecific`, `manufacturerCode` (≥0), `payload` (byte[], defensive copy + accessor override) | Protocol-level ZCL frame. Above transport layer. |

### Service Interfaces (6)

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `ClusterHandler` | interface | Per-cluster ZCL ↔ HomeSynapse translator | `handleAttributeReport(int, int, Map)` → `List<AttributeReport>`, `buildCommand(String, Map)` → `ZclFrame` |
| `DeviceProfileRegistry` | interface | Profile loading, lookup, user override merging | `findProfile(String, String)` → `Optional<DeviceProfile>`, `registerProfile(DeviceProfile)`, `allProfiles()` → `Collection<DeviceProfile>` |
| `AvailabilityTracker` | interface | Per-device availability state machine | `recordFrame(IEEEAddress, Instant)`, `recordCommandResult(IEEEAddress, boolean, Instant)`, `isAvailable(IEEEAddress)`, `lastReason(IEEEAddress)` |
| `CoordinatorTransport` | interface | Serial protocol framing abstraction | `open(Object)`, `close()`, `sendFrame(byte[])`, `receiveFrame()` → `ZigbeeFrame`. NOT thread-safe — single transport thread. |
| `CoordinatorProtocol` | interface | Zigbee protocol operations above transport | `formNetwork(NetworkParameters)`, `resumeNetwork()`, `permitJoin(int)`, `sendZclFrame(ZclFrame, IEEEAddress)`, `interview(IEEEAddress)` → `InterviewResult`, `topologyScan()` → `List<NeighborTableEntry>`, `ping()` → `boolean`. Thread-safe. |
| `ZigbeeAdapterFactory` | interface extends IntegrationFactory | Zigbee adapter factory | No additional methods. Inherits `descriptor()`, `create(IntegrationContext)`. |
| `ZigbeeAdapter` | interface extends IntegrationAdapter | Zigbee adapter lifecycle + queries | `device(IEEEAddress)` → `Optional<ZigbeeDeviceRecord>`, `allDevices()` → `Collection<ZigbeeDeviceRecord>`, `deviceProfile(IEEEAddress)` → `Optional<DeviceProfile>`, `networkParameters()`, `isPermitJoinActive()` |

## Dependencies

### Phase 2: 1 module (`api` scope)

| Module | Why | Key Types Used |
|---|---|---|
| integration-api (`com.homesynapse.integration`) | ZigbeeAdapterFactory extends IntegrationFactory, ZigbeeAdapter extends IntegrationAdapter. Integration-api types pervade the exported API. | `IntegrationFactory`, `IntegrationAdapter`, `IntegrationContext`, `CommandHandler`, `CommandEnvelope`, `IntegrationDescriptor`, `HealthParameters`, `IoType`, `RequiredService`, `DataPath`, `PermanentIntegrationException` |

All upstream core types (event-model, device-model, state-store, persistence, configuration, platform-api, java.net.http) are transitively available through integration-api's own `requires transitive` chain. No types from these modules appear directly in integration-zigbee's Phase 2 type signatures.

### Gradle (build.gradle.kts)

```kotlin
api(project(":integration:integration-api"))
```

Changed from `implementation` to `api` because integration-api types appear in this module's public API signatures.

## Consumers

### Current consumers:
None in Phase 2.

### Planned consumers:
- **homesynapse-app** (Phase 3) — constructs `ZigbeeAdapterFactoryImpl` and passes it to `IntegrationSupervisor.start()`.
- **test-support** (Phase 3) — test fixtures for simulating Zigbee frame sequences.

## Cross-Module Contracts

- **ZigbeeAdapterFactory extends IntegrationFactory.** The factory's `descriptor()` returns a pre-populated IntegrationDescriptor with IoType.SERIAL, RequiredService.SCHEDULER + TELEMETRY_WRITER, DataPath.DOMAIN + TELEMETRY.
- **ZigbeeAdapter extends IntegrationAdapter.** Lifecycle phases: `initialize()` (no serial I/O, INV-RF-03), `run()` (main loop, serial + virtual threads), `close()` (persist cache, close serial).
- **IEEEAddress is NOT a ULID.** It is a 64-bit IEEE EUI-64 hardware identifier stored as `long`. Domain-level identity (DeviceId, EntityId) is assigned during device adoption. IEEE address is the bridge between protocol and domain.
- **NetworkParameters.networkKeyRef is a reference, NOT key material.** The actual network key is stored encrypted in the secrets store per INV-SE-03.
- **CoordinatorTransport is NOT thread-safe.** It runs on a single dedicated platform thread (IoType.SERIAL). CoordinatorProtocol IS thread-safe and runs on virtual threads.
- **AttributeReport carries canonical values.** Value normalization (ZCL → HomeSynapse) happens before AttributeReport construction, not after.
- **DeviceProfile.manufacturerCodec is a string identifier, not a type reference.** Values: "tuya_ef00", "xiaomi_ff01", "xiaomi_fcc0". Phase 3 resolves string → codec instance.
- **DECIDE-04 applies.** ZigbeeAdapterFactory is instantiated directly, not via ServiceLoader.

## Constraints

| Constraint | Description |
|---|---|
| LTD-01 | IoType.SERIAL → platform thread for transport. Virtual threads for protocol layer. No `synchronized`. |
| LTD-04 | IEEEAddress is NOT a ULID. Domain IDs (DeviceId, EntityId) flow through IntegrationContext. |
| LTD-12 | This IS the first protocol adapter. Validates the entire integration architecture. |
| LTD-17 | Depends ONLY on integration-api. No core-internal imports. JPMS + Gradle enforced. |
| INV-CE-04 | Coordinator type (ZNP vs EZSP) invisible outside adapter. No public API exposes firmware type. |
| INV-RF-01 | Adapter exceptions caught by supervisor. PermanentIntegrationException → FAILED. |
| INV-RF-03 | initialize() must not block on serial port or coordinator connectivity. |
| INV-SE-03 | Network key stored encrypted. NetworkParameters.networkKeyRef is a reference only. |
| INV-ES-06 | AttributeReport carries entityRef, attributeKey, value, eventTime for full traceability. |

## Gotchas

**GOTCHA: `IEEEAddress` has no range validation.** The handoff specified "non-negative, max 0xFFFFFFFFFFFFFFFFL" which is the entire unsigned 64-bit range. All `long` values are valid IEEE addresses. No constraint to enforce.

**GOTCHA: `DeviceProfile` has 5 nullable collection fields.** clusterOverrides, reportingOverrides, interviewSkips, tuyaDatapoints, initializationWrites are all nullable. Use conditional defensive copy: `field != null ? List.copyOf(field) : null`. `List.copyOf(null)` throws NPE.

**GOTCHA: `ZigbeeDeviceRecord.endpoints` is a nullable List.** Unlike InterviewResult.endpoints (non-null, non-empty), ZigbeeDeviceRecord.endpoints is null when interview is not yet complete. Use conditional defensive copy.

**GOTCHA: Byte array fields require TWO defensive copies.** ZnpFrame.data, EzspFrame.parameters, ZclFrame.payload must clone in the compact constructor AND override the generated accessor to return a clone. The record-generated accessor returns the internal array reference.

**GOTCHA: `TuyaDpCodec` and `XiaomiTlvCodec` are `non-sealed`.** Java 21 requires subtypes of a sealed interface to be `sealed`, `non-sealed`, or `final`. Since these are interfaces (not records/classes) and need Phase 3 implementations, `non-sealed` is the correct modifier.

**GOTCHA: `CoordinatorTransport.open(Object serialPort)` uses Object in Phase 2.** Phase 3 will use jSerialComm's `SerialPort` type. The Object parameter avoids adding jSerialComm to the Phase 2 dependency list.

**GOTCHA: All ZCL numeric identifiers use `int`, not `short` or `byte`.** Java's `short` and `byte` are signed, creating constant casting noise. The JVM uses int internally for all sub-int operations anyway.

**GOTCHA: `SimpleDescriptor` was collapsed into `EndpointDescriptor`.** The handoff item #13 explicitly says "Do NOT create this file." Doc 08 uses both terms for the same data structure.

## Phase 3 Notes

- **CoordinatorTransport implementations:** `ZnpTransport` (UNPI framing, XOR checksum) and `EzspAshTransport` (ASH framing, CRC-CCITT, byte stuffing, data derandomization). Both run on a dedicated platform thread (IoType.SERIAL).
- **CoordinatorProtocol implementations:** `ZnpProtocol` and `EzspProtocol`. Use CompletableFuture for SREQ/SRSP correlation with timeout. Run on virtual threads.
- **ClusterHandler implementations:** OnOff, LevelControl, ColorControl CT, TemperatureMeasurement, RelativeHumidity, IlluminanceMeasurement, OccupancySensing, IASZone, ElectricalMeasurement, Metering, PowerConfiguration.
- **ValueConverter standard factories:** divideBy10, divideBy100, raw, booleanInvert, batteryVoltageToPercent.
- **DeviceProfile JSON loading:** Bundled zigbee-profiles.json + optional user override file at integrations.zigbee.profiles_path.
- **ZigbeeDeviceRecord cache:** Serialized to zigbee-devices.json on adapter shutdown. Jackson-friendly record structure.
- **AvailabilityTracker restart initialization (Doc 08, Doc 05 §3.14):** On adapter restart, the AvailabilityTracker must initialize from pre-restart state persisted in the device registry, not only from in-memory state. During a planned restart (§3.14 flag set), entity availability is not published as changed — the tracker must carry forward the last-known availability from the device registry and only emit `availability_changed` after the restart completes and fresh state is confirmed from the coordinator. This prevents false unavailable→available transitions that would trigger automations during planned restarts.
- **jSerialComm dependency:** Will need to be added to libs.versions.toml. CoordinatorTransport.open() parameter changes from Object to SerialPort.
- **Testing strategy:** Transport layer tested with byte-level frame fixtures. Protocol layer tested with mock transport. Cluster handlers tested with ZCL attribute maps → AttributeReport assertions. Device profile registry tested with JSON loading and wildcard matching.


---

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2). Phase 3 implementation is active — M2.5 `SqliteEventStore` landed 2026-04-11 (commit `5279e7a`), next milestone M2.6 + M2.7 (combined) pending from Nick.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: dispatch on `@EventType` string, not sealed-switch
- **D-04** — *Clock must be injected*: frame-timing, retry schedules, and join-window expiry all use injected `Clock`
- **D-05** — *`@EventType` on every event record*: integration-namespaced events like `zigbee.device_announce` carry `@EventType` and resolve to `[SYSTEM]` category by INV-PD-07 fallback unless added to `EventCategoryMapping`

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for recent Phase 3 pattern discoveries (especially the 2026-04-10 entries on `NO_DIRECT_TIME_ACCESS` and JUnit 5 `@BeforeEach` ordering).

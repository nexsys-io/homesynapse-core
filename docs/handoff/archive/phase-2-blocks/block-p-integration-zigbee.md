# Block P — Integration Zigbee (ZCL Data Model)

**Module:** `integration/integration-zigbee`
**Package:** `com.homesynapse.integration.zigbee`
**Design Doc:** Doc 08 — Zigbee Adapter (§4, §5, §6) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :integration:integration-zigbee:compileJava`

---

## Strategic Context

The Zigbee Adapter is HomeSynapse's first protocol integration — the concrete implementation that validates every abstract interface defined by the Integration Runtime, Device Model, and Event Model against real hardware behavior. It translates between the Zigbee Cluster Library (ZCL) protocol world of endpoints, clusters, and attribute reports and the HomeSynapse world of entities, capabilities, and domain events.

Block I (integration-api) defined the adapter-facing contracts: `IntegrationFactory`, `IntegrationAdapter`, `IntegrationContext`, `CommandHandler`, `HealthReporter`, `HealthParameters`, and supporting types. Block O (integration-runtime) defined the supervisory layer: `IntegrationSupervisor`, `IntegrationHealthRecord`, `ExceptionClassification`. Block P defines the Zigbee-specific data model that the adapter uses to translate between ZCL protocol frames and HomeSynapse's typed domain model.

This block produces the integration-zigbee module's Phase 2 interface specification: the ZCL frame model, Zigbee transport frame sealed hierarchy, device profile types, cluster-to-capability mapping interfaces, manufacturer codec interfaces, network telemetry types, route health tracking types, and supporting enums and records. These types capture the data structures from Doc 08 §4 (Data Model), the behavioral contracts from §5 (Contracts and Invariants), and the failure mode vocabulary from §6 (Failure Modes and Recovery). Phase 3 implements the transport layer, protocol layer, interview pipeline, cluster handlers, and codec logic.

**Strategic importance:** The Zigbee adapter is the single integration that exercises the entire vertical stack — event publishing, device discovery, entity registration, state reporting, command dispatch, telemetry routing, and health reporting. Every type defined in this block must align precisely with the upstream integration-api contracts (Block I) and the device-model types (Block G) because the adapter is the bridge between protocol-specific wire formats and HomeSynapse's protocol-agnostic domain model. LTD-12 designates Zigbee as the first protocol; this adapter's success validates the entire integration architecture.

## Scope

**IN:** All types from Doc 08 §4 (Data Model): `ZigbeeFrame` sealed hierarchy, `ZclFrame` record, `ZigbeeDeviceRecord`, `DeviceProfile`, `TuyaDatapointMapping`, `XiaomiTagMapping`, `NetworkParameters`, `InterviewResult`, `AttributeReport`, `NeighborTableEntry`, `ManufacturerModelPair`, `InitializationWrite`, `RouteHealth`. All enums: `DeviceCategory`, `CommandType` (ZNP), `InterviewStatus`, `RouteStatus`, `TuyaDpType`, `AvailabilityReason`, `ZoneType`. All interfaces from Doc 08 §8: `ClusterHandler`, `DeviceProfileRegistry`, `ManufacturerCodec`, `AvailabilityTracker`, `CoordinatorTransport`, `CoordinatorProtocol`, `ZigbeeAdapterFactory` (extending `IntegrationFactory`), `ZigbeeAdapter` (extending `IntegrationAdapter`). Value types: `IEEEAddress`, `EndpointDescriptor`, `NodeDescriptor`, `ClusterOverride`, `ReportingOverride`, `SimpleDescriptor`, `ValueConverter`. Module-info with correct JPMS directives.

**OUT:** Implementation code. Tests. Transport framing logic (ZNP UNPI, EZSP ASH byte-level parsing). Serial port interaction (jSerialComm). Protocol layer correlation (`CompletableFuture`-based SREQ/SRSP). Interview pipeline sequencing. Cluster handler implementations (OnOff, LevelControl, etc.). Tuya DP frame parsing logic. Xiaomi TLV buffer parsing logic. Device profile JSON loading. Network formation. Permit-join management. Coordinator auto-detection probing. Availability timeout scheduling. Command dispatch routing. Telemetry routing decisions. JFR metric emission.

---

## Locked Decisions

1. **The module depends ONLY on `integration-api`.** Per LTD-17, the Zigbee adapter communicates with the core exclusively through `IntegrationContext`. The `build.gradle.kts` already has `implementation(project(":integration:integration-api"))`. This must be changed to `api(project(":integration:integration-api"))` because integration-api types (`IntegrationFactory`, `IntegrationAdapter`, `IntegrationContext`, `CommandHandler`, `CommandEnvelope`, `IntegrationDescriptor`, `HealthParameters`, `PermanentIntegrationException`, `IoType`, `RequiredService`, `DataPath`) appear in the module's public API signatures (method parameters, return types, implemented interfaces, superclass). The `implementation` → `api` change makes integration-api a transitive dependency, matching the JPMS `requires transitive` directive.

2. **`ZigbeeAdapterFactory` extends `IntegrationFactory`. `ZigbeeAdapter` extends `IntegrationAdapter`.** These are NOT new interface definitions — they are interfaces that extend the integration-api contracts with Zigbee-specific accessor methods. The factory returns an `IntegrationDescriptor` pre-populated with Zigbee-specific values (IoType.SERIAL, RequiredService.SCHEDULER + TELEMETRY_WRITER, DataPath.DOMAIN + TELEMETRY). The adapter adds Zigbee-specific lifecycle query methods beyond the base IntegrationAdapter contract.

3. **`IEEEAddress` is a value record wrapping a `long` (64-bit IEEE EUI-64).** Doc 08 uses IEEE addresses throughout as the permanent device identifier (namespace `zigbee` per Identity and Addressing Model §6). This is NOT a ULID — it is a protocol-specific hardware identifier. The record provides `toHexString()` formatting (e.g., `"0x00158D00012345AB"`) and `fromHexString(String)` factory method. Storage as `long` (not byte array) because Java 21 supports unsigned long operations and 64-bit comparison is a single instruction.

4. **`ValueConverter` is a `@FunctionalInterface`.** Doc 08 §3.8 specifies conversion functions (divideBy10, divideBy100, raw, booleanInvert, batteryVoltageToPercent). The interface defines `Object convert(Object rawValue)`. Phase 3 provides static factory methods for the standard converters. Phase 2 defines only the interface.

5. **All cluster-related integer constants use `int`, not `short` or `byte`.** ZCL cluster IDs are uint16, attribute IDs are uint16, command IDs are uint8. Java's `short` and `byte` are signed, creating constant casting noise. All ZCL numeric identifiers use `int` in Java (the JVM uses int internally for all sub-int operations anyway). Values are validated in compact constructors against their protocol ranges.

6. **Device profiles are represented as records, not loaded from JSON in Phase 2.** The `DeviceProfile` record from Doc 08 §3.6 and §4.3 captures the data model. JSON loading is Phase 3 implementation. The `DeviceProfileRegistry` interface defines lookup methods.

7. **`ManufacturerCodec` is a sealed interface with two permits: `TuyaDpCodec` and `XiaomiTlvCodec`.** Doc 08 §8.1 specifies this hierarchy. In Phase 2, these are marker interfaces extending `ManufacturerCodec` — the decode/encode methods are on the sealed parent. Phase 3 implements them.

8. **JPMS Default Rule:** All inter-module `requires` directives default to `requires transitive`. Per the three-block lesson (I, K, N), the module-info uses `requires transitive com.homesynapse.integration` because integration-api types pervade the exported API (IntegrationFactory, IntegrationAdapter, CommandHandler, CommandEnvelope, IntegrationDescriptor, HealthParameters, PermanentIntegrationException, etc.). Through integration-api's own transitive chain, this gives the Zigbee module access to event-model, device-model, state-store, persistence, configuration, and platform-api types — all of which are needed by an adapter.

9. **No `@Nullable` annotation library.** Per integration-api MODULE_CONTEXT.md GOTCHA: HomeSynapse uses Javadoc `{@code null} if...` patterns for nullability documentation. The `jspecify` library mentioned in the task brief is NOT in `libs.versions.toml` and has not been used in any prior block. Follow the established project pattern: document nullability in Javadoc, validate non-null fields with `Objects.requireNonNull` in compact constructors.

10. **`ZigbeeFrame` sealed interface has exactly two permits: `ZnpFrame` and `EzspFrame`.** This matches Doc 08 §4.2 exactly. The sealed hierarchy enables exhaustive pattern matching in the protocol layer (Phase 3). Each frame variant captures transport-level data — the `ZclFrame` record represents the protocol-level (above-transport) ZCL frame and is a separate type, not part of the sealed hierarchy.

11. **`RouteHealth` and `RouteStatus` from Doc 08 §3.15 (AMD-07) are in scope.** These types model per-device route health tracking for the route health monitoring feature. `RouteHealth` is a record; `RouteStatus` is an enum with three values (HEALTHY, DEGRADED, UNREACHABLE).

12. **No cross-module updates.** Block P does not modify any files in other modules. All types in this block are internal to the integration-zigbee module. The existing upstream modules (integration-api, device-model, event-model) are consumed but not changed.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Value Types and Enums (no inter-group dependencies)

| # | File | Type | Notes |
|---|------|------|-------|
| 1 | `IEEEAddress.java` | record (1 field) | `value` (long — 64-bit IEEE EUI-64). Compact constructor: value range validation (non-negative, max 0xFFFFFFFFFFFFFFFFL). Methods: `toHexString()` → `String` (format: `"0x"` + 16 uppercase hex digits, zero-padded), `fromHexString(String)` → `IEEEAddress` (static factory, parses `"0x..."` or plain hex). `toString()` delegates to `toHexString()`. Javadoc: permanent hardware identifier for Zigbee devices. Namespace `zigbee` per Identity and Addressing Model §6. Network addresses (16-bit) are transient; IEEE addresses are permanent. Doc 08 §5 contract: "Hardware identifiers are stable across adapter restarts." Thread-safe (immutable record). `@see` DeviceId, EntityId (from device-model — conceptually related but different identity layers). |
| 2 | `DeviceCategory.java` | enum (4 values) | `STANDARD_ZCL` (generic cluster handlers, no profile needed — Sonoff SNZB, Philips Hue), `MINOR_QUIRKS` (profile overrides for reporting/ranges — IKEA TRÅDFRI), `MIXED_CUSTOM` (standard + manufacturer codec — Xiaomi/Aqara), `FULLY_CUSTOM` (proprietary protocol — Tuya 0xEF00/TS0601). Doc 08 §3.6 category distribution table. Javadoc: classifies devices by how much adapter-specific handling they require. ~60% are STANDARD_ZCL (generic), ~40% need profiles. Thread-safe (enum). |
| 3 | `CommandType.java` | enum (3 values) | `SREQ` (synchronous request — CMD0 bits 7-5 = 0x20), `SRSP` (synchronous response — 0x60), `AREQ` (asynchronous notification — 0x40). Doc 08 §3.3 ZNP transport. Javadoc: ZNP frame command types identified by CMD0 bits 7-5. Used in `ZnpFrame` record. EZSP uses a different framing model (frame ID + callback flag). Thread-safe (enum). |
| 4 | `InterviewStatus.java` | enum (3 values) | `COMPLETE` (all interview steps succeeded), `PARTIAL` (some steps failed — device may have limited capabilities), `PENDING` (interview not yet attempted or in progress). Doc 08 §3.4 interview pipeline. Javadoc: tracks the interview completion state per device. Partial interviews produce `device_discovered` events with whatever metadata was gathered; the Device Model decides adoptability. Thread-safe (enum). |
| 5 | `RouteStatus.java` | enum (3 values) | `HEALTHY` (normal operation), `DEGRADED` (1-2 consecutive command failures, monitoring closely), `UNREACHABLE` (3+ consecutive failures, route recovery triggered). Doc 08 §3.15 AMD-07. Javadoc: per-device route health state for passive route monitoring. Success reset: any received frame or successful command resets to HEALTHY. Thread-safe (enum). |
| 6 | `TuyaDpType.java` | enum (6 values) | `RAW` (0x00), `BOOL` (0x01, 1 byte), `VALUE` (0x02, 4 bytes uint32 big-endian), `STRING` (0x03), `ENUM` (0x04, 1 byte), `BITMAP` (0x05). Each value carries `protocolId` (int). Doc 08 §3.8 Tuya DP frame parsing. Javadoc: Tuya datapoint type identifiers as defined in the Tuya MCU protocol specification. Used in `TuyaDatapointMapping` for DP-to-capability translation. Thread-safe (enum). |
| 7 | `AvailabilityReason.java` | enum (6 values) | `FIRST_CONTACT` (initial frame from a newly joined device), `PING_SUCCESS` (active availability ping succeeded), `FRAME_RECEIVED` (any frame received — passive liveness), `PING_TIMEOUT` (active ping unanswered within timeout), `SILENCE_TIMEOUT` (no frame received within power-source-aware timeout — 10 min for mains, 25 hours for battery), `LEAVE` (device sent ZDO Leave notification). Doc 08 §4.4 `availability_changed` event. Javadoc: reason for device availability state transitions. Thread-safe (enum). |
| 8 | `ZoneType.java` | enum (5 values) | `MOTION` (0x000D), `CONTACT` (0x0015), `WATER_LEAK` (0x002A), `SMOKE` (0x0028), `VIBRATION` (0x002D). Each value carries `zclId` (int) and `capabilityId` (String — maps to HomeSynapse capability name). Doc 08 §3.12 IAS Zone enrollment. Javadoc: IAS Zone type identifiers that determine capability mapping. Zone type 0x0001 from the IAS Zone cluster's ZoneType attribute. Thread-safe (enum). |
| 9 | `ValueConverter.java` | @FunctionalInterface | Single method: `Object convert(Object rawValue)`. Doc 08 §3.8 TuyaDatapointMapping converter field. Javadoc: converts protocol-specific raw values to HomeSynapse canonical values. Standard converters (divideBy10, divideBy100, raw, booleanInvert, batteryVoltageToPercent) are provided as static factory methods in Phase 3. The functional interface enables lambda implementations for per-profile custom converters. `@see TuyaDatapointMapping`, `@see XiaomiTagMapping`. |

### Group 2: Small Data Records (depend only on Group 1 enums and JDK)

| # | File | Type | Notes |
|---|------|------|-------|
| 10 | `ManufacturerModelPair.java` | record (2 fields) | `manufacturerName` (String, non-null), `modelIdentifier` (String, non-null). Doc 08 §3.6 — key for device profile matching. Compact constructor: `Objects.requireNonNull` both. Javadoc: exact string match key for the device profile registry, with optional wildcard prefix matching for manufacturer families (e.g., `"TRADFRI*"`). Wildcard matching is Phase 3 implementation; this record is the data carrier. Thread-safe (immutable record). |
| 11 | `EndpointDescriptor.java` | record (5 fields) | `endpointId` (int, 1–240), `profileId` (int — ZCL profile, typically 0x0104 for HA), `deviceTypeId` (int — ZCL device type), `inputClusters` (List\<Integer\>, defensively copied), `outputClusters` (List\<Integer\>, defensively copied). Doc 08 §3.4 step 4 — Simple Descriptor per endpoint. Compact constructor: validate endpointId 1–240, profileId ≥ 0, deviceTypeId ≥ 0, `List.copyOf` both cluster lists. Javadoc: ZCL Simple Descriptor for a single application endpoint. Input clusters are server-side (the device implements them); output clusters are client-side (the device sends commands to them). Thread-safe (immutable record). |
| 12 | `NodeDescriptor.java` | record (4 fields) | `deviceType` (int — 0=coordinator, 1=router, 2=end device), `manufacturerCode` (int), `maxBufferSize` (int), `macCapabilityFlags` (int). Doc 08 §3.4 step 2 — Node Descriptor. Compact constructor: validate deviceType 0–2, maxBufferSize > 0. Javadoc: ZDO Node Descriptor from the interview pipeline. The deviceType determines power-source-aware availability tracking (routers are mains-powered, end devices are battery-powered). Thread-safe (immutable record). |
| 13 | `SimpleDescriptor.java` | record (5 fields) | Identical field structure to `EndpointDescriptor` — this is an alias used specifically during the interview pipeline. **PM DECISION: Collapse into EndpointDescriptor.** Doc 08 uses "Simple Descriptor" as the ZDO term and "endpoint descriptor" as the adapter-internal term. They carry identical data. Use `EndpointDescriptor` for both. **Do NOT create this file.** |
| 14 | `ClusterOverride.java` | record (3 fields) | `clusterId` (int), `attributeOverrides` (Map\<Integer, String\>, defensively copied — attribute ID → override behavior description), `disableDefaultHandler` (boolean). Doc 08 §3.6 DeviceProfile.clusterOverrides. Compact constructor: validate clusterId ≥ 0, `Map.copyOf(attributeOverrides)`. Javadoc: per-cluster behavioral adjustments within a device profile. Overrides take precedence over standard cluster handler behavior. Thread-safe (immutable record). |
| 15 | `ReportingOverride.java` | record (4 fields) | `clusterId` (int), `minInterval` (int, seconds, ≥ 0), `maxInterval` (int, seconds, > minInterval), `reportableChange` (int, ≥ 0). Doc 08 §3.7 reporting configuration. Compact constructor: validate clusterId ≥ 0, minInterval ≥ 0, maxInterval > 0, maxInterval > minInterval, reportableChange ≥ 0. Javadoc: per-cluster reporting configuration overrides within a device profile, taking precedence over the adapter's default reporting intervals (Doc 08 §3.7 table). Thread-safe (immutable record). |
| 16 | `InitializationWrite.java` | record (6 fields) | `endpoint` (int, 1–240), `clusterId` (int), `attributeId` (int), `dataType` (int — ZCL data type ID), `value` (Object, non-null — the value to write), `manufacturerCode` (int — 0 if not manufacturer-specific). Doc 08 §3.6 — post-adoption attribute writes. Compact constructor: validate endpoint 1–240, clusterId ≥ 0, attributeId ≥ 0, `Objects.requireNonNull(value)`, manufacturerCode ≥ 0. Javadoc: ZCL attribute write executed after device adoption. Each write specifies a single attribute on a single endpoint. Failures are logged at WARN but do not block adoption. Example: Aqara wall switch decoupled mode (cluster 0xFCC0, attribute 0x0200, value 0x01, manufacturer 0x115F). Thread-safe (immutable record). |
| 17 | `TuyaDatapointMapping.java` | record (4 fields) | `dpId` (int, 1–255), `attributeKey` (String, non-null — HomeSynapse attribute key), `expectedType` (TuyaDpType, non-null), `converter` (ValueConverter, non-null). Doc 08 §3.8 — DP-to-capability mapping per Tuya device profile. Compact constructor: validate dpId 1–255, `Objects.requireNonNull` for attributeKey, expectedType, converter. Javadoc: maps a Tuya datapoint ID and type to a HomeSynapse attribute with an optional value conversion. The converter transforms the raw DP value (big-endian uint32 for VALUE, single byte for BOOL/ENUM) to the HomeSynapse canonical form. Thread-safe (immutable record). `@see TuyaDpType`, `@see ValueConverter`. |
| 18 | `XiaomiTagMapping.java` | record (4 fields) | `tag` (int, 0x00–0xFF), `attributeKey` (String, non-null), `zclDataType` (int — ZCL type ID for value decoding), `converter` (ValueConverter, non-null). Doc 08 §3.9 — Xiaomi TLV tag-to-attribute mapping. Compact constructor: validate tag 0–255, `Objects.requireNonNull` for attributeKey, converter. Javadoc: maps a Xiaomi 0xFF01/0xFCC0 TLV tag to a HomeSynapse attribute. Tags use standard ZCL type IDs for value encoding, so the parser reuses the adapter's ZCL type codec. Tag 0x64 is model-dependent — the device profile carries a `tag64Type` field. Thread-safe (immutable record). `@see ValueConverter`. |
| 19 | `NeighborTableEntry.java` | record (6 fields) | `ieeeAddress` (IEEEAddress, non-null), `networkAddress` (int, 0x0000–0xFFFF), `deviceType` (int — 0=coordinator, 1=router, 2=end device), `lqi` (int, 0–255), `depth` (int, ≥ 0), `parentIeee` (IEEEAddress — **nullable**, null for the coordinator). Doc 08 §3.11 topology scan. Compact constructor: `Objects.requireNonNull(ieeeAddress)`, validate networkAddress 0–0xFFFF, deviceType 0–2, lqi 0–255, depth ≥ 0. Javadoc parentIeee: `{@code null} for the coordinator node which has no parent`. Thread-safe (immutable record). |

### Group 3: Composite Data Records (depend on Groups 1–2)

| # | File | Type | Notes |
|---|------|------|-------|
| 20 | `NetworkParameters.java` | record (4 fields) | `channel` (int, 11–26), `panId` (int, 0x0000–0xFFFF), `extendedPanId` (long — 64-bit), `networkKeyRef` (String, non-null — opaque reference to the encrypted network key in the secrets infrastructure, NOT the key itself). Doc 08 §3.13, §4.1. Compact constructor: validate channel 11–26, panId 0–0xFFFF, `Objects.requireNonNull(networkKeyRef)`. Javadoc: Zigbee network identity and security parameters. The network key is NEVER stored in this record — `networkKeyRef` is a reference to the encrypted material in the secrets store (INV-SE-03). Channel selection follows the two-tier model: primary (15, 20, 11), fallback (21–26). Thread-safe (immutable record). |
| 21 | `AttributeReport.java` | record (4 fields) | `entityRef` (String, non-null — HomeSynapse entity reference for event production), `attributeKey` (String, non-null — HomeSynapse attribute key per device-model capability), `value` (Object, non-null — typed per device-model AttributeValue), `eventTime` (Instant, non-null). Doc 08 §4.4 `state_reported` payload, §3.5 value normalization. Compact constructor: `Objects.requireNonNull` all four. Javadoc: normalized attribute observation produced by cluster handlers and manufacturer codecs. This is the adapter's internal DTO — it is converted to a `state_reported` event via `EventPublisher` in Phase 3. The `value` field carries a HomeSynapse canonical value (°C, %, lx, etc.), not the raw ZCL protocol value. Thread-safe (immutable record). `@see ClusterHandler`, `@see ManufacturerCodec`. |
| 22 | `InterviewResult.java` | record (8 fields) | `ieeeAddress` (IEEEAddress, non-null), `networkAddress` (int), `nodeDescriptor` (NodeDescriptor, non-null), `endpoints` (List\<EndpointDescriptor\>, defensively copied, non-null, non-empty), `manufacturerName` (String, non-null), `modelIdentifier` (String, non-null), `powerSource` (int — ZCL PowerSource enum value), `interviewStatus` (InterviewStatus, non-null). Doc 08 §3.4 interview pipeline result. Compact constructor: `Objects.requireNonNull` all object fields, `List.copyOf(endpoints)`, validate endpoints not empty. Javadoc: collected device metadata from the interview pipeline (steps 2–6 of Doc 08 §3.4). Produced after a successful or partial interview; consumed by the `device_discovered` event builder. Thread-safe (immutable record). |
| 23 | `RouteHealth.java` | record (7 fields) | `target` (IEEEAddress, non-null), `consecutiveFailures` (int, ≥ 0), `totalFailures` (int, ≥ 0), `totalSuccesses` (int, ≥ 0), `lastSuccess` (Instant — **nullable**, null if device has never had a successful command), `lastFailure` (Instant — **nullable**, null if device has never had a command failure), `status` (RouteStatus, non-null). Doc 08 §3.15 AMD-07. Compact constructor: `Objects.requireNonNull(target)`, `Objects.requireNonNull(status)`, validate all counts ≥ 0. Javadoc lastSuccess: `{@code null} if the device has never received a successful command response`. Javadoc lastFailure: `{@code null} if the device has never experienced a command failure`. Thread-safe (immutable record). |
| 24 | `DeviceProfile.java` | record (9 fields) | `profileId` (String, non-null — e.g., `"ikea_tradfri_bulb"`), `matches` (Set\<ManufacturerModelPair\>, defensively copied, non-null, non-empty), `category` (DeviceCategory, non-null), `clusterOverrides` (Map\<Integer, ClusterOverride\>, defensively copied — **nullable**, null means no overrides), `reportingOverrides` (Map\<Integer, ReportingOverride\>, defensively copied — **nullable**), `manufacturerCodec` (String — **nullable**, null for STANDARD_ZCL, values: `"tuya_ef00"`, `"xiaomi_ff01"`, `"xiaomi_fcc0"`), `interviewSkips` (Set\<String\>, defensively copied — **nullable**, e.g., `{"configure_reporting"}` for Xiaomi), `tuyaDatapoints` (List\<TuyaDatapointMapping\>, defensively copied — **nullable**, present only for Tuya 0xEF00 devices), `initializationWrites` (List\<InitializationWrite\>, defensively copied — **nullable**, post-adoption attribute writes). Doc 08 §3.6, §4.3. Compact constructor: `Objects.requireNonNull` for profileId, matches, category; `Set.copyOf(matches)` and validate non-empty; nullable collections use conditional defensive copy (`field != null ? List.copyOf(field) : null` / `Map.copyOf` / `Set.copyOf`). Javadoc: per-model device behavior overrides. Profiles are loaded from bundled `zigbee-profiles.json` and optional user override files. User profiles take precedence over bundled profiles. Thread-safe (immutable record). |
| 25 | `ZigbeeDeviceRecord.java` | record (10 fields) | `ieeeAddress` (IEEEAddress, non-null), `networkAddress` (int, 0x0000–0xFFFF), `nodeDescriptor` (NodeDescriptor — **nullable**, null if interview not yet complete), `endpoints` (List\<EndpointDescriptor\> — **nullable**, null if interview not yet complete; defensively copied if non-null), `manufacturerName` (String — **nullable**), `modelIdentifier` (String — **nullable**), `powerSource` (int — ZCL PowerSource value, 0 if unknown), `lastSeen` (Instant, non-null), `interviewStatus` (InterviewStatus, non-null), `matchedProfileId` (String — **nullable**, device profile match result). Doc 08 §3.14 local device metadata cache. Compact constructor: `Objects.requireNonNull` for ieeeAddress, lastSeen, interviewStatus; nullable list defensive copy for endpoints. Javadoc: per-device Zigbee protocol metadata maintained in the adapter's local cache. Separate from Device Model's EntityRegistry — holds protocol-level metadata needed for transport and protocol operations. Serialized to `zigbee-devices.json` on adapter shutdown. Thread-safe (immutable record). |

### Group 4: Transport and Protocol Frame Types (depend on Group 1)

| # | File | Type | Notes |
|---|------|------|-------|
| 26 | `ZigbeeFrame.java` | sealed interface (permits 2) | `ZnpFrame`, `EzspFrame`. Doc 08 §4.2. No methods on the sealed interface itself — the two variants have incompatible structures. Javadoc: transport-level frame representation shared between the transport layer (platform thread) and protocol layer (virtual thread) via a `BlockingQueue<ZigbeeFrame>`. The sealed hierarchy enables exhaustive pattern matching in the protocol layer's frame dispatch logic. Thread-safe (immutable variants). `@see CoordinatorTransport`, `@see ZclFrame`. |
| 27 | `ZnpFrame.java` | record implements ZigbeeFrame (4 fields) | `subsystem` (int — ZNP subsystem ID), `commandId` (int — command within subsystem), `type` (CommandType, non-null), `data` (byte[], defensively copied). Doc 08 §3.3, §4.2. Compact constructor: `Objects.requireNonNull(type)`, `data = data.clone()` (defensive copy of mutable byte array). Javadoc: ZNP (Z-Stack Network Processor) frame. UNPI framing format: `SOF(0xFE) | Length | CMD0 | CMD1 | Data | FCS`. Subsystem and commandId are extracted from CMD0/CMD1. Thread-safe (immutable record with defensively copied byte array). |
| 28 | `EzspFrame.java` | record implements ZigbeeFrame (3 fields) | `frameId` (int — EZSP command/callback ID), `isCallback` (boolean — true for unsolicited callbacks from the NCP), `parameters` (byte[], defensively copied). Doc 08 §3.3, §4.2. Compact constructor: `parameters = parameters.clone()`. Javadoc: EZSP (EmberZNet Serial Protocol) frame. ASH framing handles byte stuffing, data derandomization, CRC-CCITT, and acknowledgment at the transport layer; this record represents the decoded application-layer frame. EZSP version negotiation (command 0x0000) uses legacy single-byte frame ID format. Thread-safe (immutable record with defensively copied byte array). |
| 29 | `ZclFrame.java` | record (7 fields) | `sourceEndpoint` (int, 0–240), `destinationEndpoint` (int, 0–240), `clusterId` (int, 0x0000–0xFFFF), `commandId` (int, 0x00–0xFF), `isClusterSpecific` (boolean — false for global ZCL commands like Read Attributes, Configure Reporting), `manufacturerCode` (int — 0 if not manufacturer-specific, e.g., 0x115F for Xiaomi), `payload` (byte[], defensively copied). Doc 08 §4.2 (ZclFrame entry in Key Types table), §3.2 protocol layer. Compact constructor: validate endpoint ranges, clusterId 0–0xFFFF, commandId 0–0xFF, manufacturerCode ≥ 0, `payload = payload.clone()`. Javadoc: protocol-level ZCL frame representing a single cluster operation. This is above the transport layer — it is constructed by the protocol layer from transport frames (`ZigbeeFrame`) and consumed by cluster handlers and manufacturer codecs. Outbound ZCL frames are constructed by command dispatch and sent via `CoordinatorProtocol.sendZclFrame()`. Thread-safe (immutable record). `@see ZigbeeFrame`, `@see ClusterHandler`, `@see CoordinatorProtocol`. |

### Group 5: Service Interfaces (depend on Groups 1–4)

| # | File | Type | Notes |
|---|------|------|-------|
| 30 | `ClusterHandler.java` | interface | Doc 08 §8.1. Two methods: `List<AttributeReport> handleAttributeReport(int endpoint, int clusterId, Map<Integer, Object> attributes)` — translates ZCL attribute reports into normalized HomeSynapse attribute observations. `ZclFrame buildCommand(String commandType, Map<String, Object> parameters)` — constructs a ZCL frame from a HomeSynapse command definition. Javadoc: per-cluster translator between ZCL protocol operations and HomeSynapse capabilities. Phase 3 provides implementations for each MVP cluster (OnOff, LevelControl, ColorControl CT, TemperatureMeasurement, RelativeHumidity, IlluminanceMeasurement, OccupancySensing, IASZone, ElectricalMeasurement, Metering, PowerConfiguration — Doc 08 §3.5 table). The handler performs value normalization: ZCL protocol units → HomeSynapse canonical units (e.g., 0.01°C → °C, 0.5% battery → percentage). Thread-safe: implementations must be stateless or thread-safe. `@see AttributeReport`, `@see ZclFrame`. |
| 31 | `DeviceProfileRegistry.java` | interface | Doc 08 §8.1. Methods: `Optional<DeviceProfile> findProfile(String manufacturerName, String modelIdentifier)` — looks up a profile by manufacturer/model with optional wildcard prefix matching. `void registerProfile(DeviceProfile profile)` — adds or replaces a profile. `Collection<DeviceProfile> allProfiles()` — returns all registered profiles (unmodifiable). Javadoc: manages device profile loading, lookup, and user override merging. Profiles are loaded from bundled `zigbee-profiles.json` and an optional user override file at `integrations.zigbee.profiles_path`. User profiles take precedence. Thread-safe. `@see DeviceProfile`, `@see ManufacturerModelPair`. |
| 32 | `ManufacturerCodec.java` | sealed interface (permits 2) | Permits: `TuyaDpCodec`, `XiaomiTlvCodec`. Doc 08 §8.1. Two methods: `List<AttributeReport> decode(ZclFrame frame)` — decodes a manufacturer-specific ZCL frame into normalized attribute reports. `ZclFrame encode(String commandType, Map<String, Object> parameters)` — constructs a manufacturer-specific ZCL frame from a HomeSynapse command. Javadoc: sealed interface for manufacturer-specific codec subsystems. Tuya devices tunnel a proprietary datapoint protocol through cluster 0xEF00. Xiaomi/Aqara devices embed sensor data in a custom TLV structure on the Basic cluster (0xFF01) or cluster 0xFCC0. Treating these as isolated subsystems prevents quirk accumulation (Doc 08 §1 design principle 4). Thread-safe: implementations must be stateless or thread-safe. `@see TuyaDpCodec`, `@see XiaomiTlvCodec`, `@see AttributeReport`, `@see ZclFrame`. |
| 33 | `TuyaDpCodec.java` | interface extends ManufacturerCodec | Marker subtype. Doc 08 §3.8. No additional methods beyond ManufacturerCodec. Javadoc: Tuya datapoint codec for devices using cluster 0xEF00. All TS0601-model devices use this codec. DP frame format: `[Header 2B] [DP1] [DP2] ... [DPN]` where each DP is `[DPID 1B] [Type 1B] [Length 2B BE] [Value NB BE]`. A single frame can contain multiple concatenated DPs. Phase 3 implements parsing and DP-to-capability mapping via `TuyaDatapointMapping`. `@see TuyaDatapointMapping`, `@see TuyaDpType`. |
| 34 | `XiaomiTlvCodec.java` | interface extends ManufacturerCodec | Marker subtype. Doc 08 §3.9. No additional methods beyond ManufacturerCodec. Javadoc: Xiaomi/Aqara TLV codec for devices reporting via attribute 0xFF01 (Basic cluster, manufacturer code 0x115F) or attribute 0x00F7 (cluster 0xFCC0). TLV format: `[Tag 1B] [ZCL Type 1B] [Value NB]` — tags use standard ZCL type IDs for value encoding, reusing the adapter's ZCL type codec. Documented tags: 0x01 (battery mV), 0x03 (device temp), 0x64 (model-dependent primary measurement), 0x65 (humidity), 0x66 (pressure). Tag 0x64 interpretation requires the device model identifier from the interview. Phase 3 implements parsing and tag-to-capability mapping via `XiaomiTagMapping`. `@see XiaomiTagMapping`. |
| 35 | `AvailabilityTracker.java` | interface | Doc 08 §8.1. Methods: `void recordFrame(IEEEAddress device, Instant timestamp)` — updates last-seen time on any frame receipt. `void recordCommandResult(IEEEAddress device, boolean success, Instant timestamp)` — updates availability state after a command result. `boolean isAvailable(IEEEAddress device)` — returns current availability for the device. `AvailabilityReason lastReason(IEEEAddress device)` — returns the reason for the last availability transition. Javadoc: per-device availability state machine with power-source-aware timeout logic. Mains-powered devices: active ping after 10 minutes of silence. Battery-powered devices: passive timeout after 25 hours. Availability transitions produce `availability_changed` events with CRITICAL priority for offline transitions and NORMAL for online. Thread-safe. `@see AvailabilityReason`, `@see IEEEAddress`. |
| 36 | `CoordinatorTransport.java` | interface | Doc 08 §8.1. Methods: `void open(Object serialPort)` — opens the serial connection (parameter is `Object` in Phase 2; Phase 3 uses jSerialComm's `SerialPort`). `void close()` — closes the serial connection (idempotent). `void sendFrame(byte[] data)` — serializes and transmits a frame. `ZigbeeFrame receiveFrame()` — blocking read of next complete frame from the serial port. Javadoc: abstraction over serial protocol framing. Two implementations: `ZnpTransport` (UNPI framing, XOR checksum) and `EzspAshTransport` (ASH framing, CRC-CCITT, byte stuffing, data derandomization, ACK/NAK). Runs on a dedicated platform thread (IoType.SERIAL per LTD-01, Doc 05 §3.2) to isolate JNI-induced carrier thread pinning from the virtual thread pool. Not thread-safe — single-threaded access by the transport thread. `@see ZigbeeFrame`, `@see CoordinatorProtocol`. |
| 37 | `CoordinatorProtocol.java` | interface | Doc 08 §8.1. Methods: `void formNetwork(NetworkParameters params)` — forms a new Zigbee network with the given parameters. `void resumeNetwork()` — resumes an existing network from stored parameters. `void permitJoin(int durationSeconds)` — enables device pairing for the specified duration (max 254 per Zigbee spec). `void sendZclFrame(ZclFrame frame, IEEEAddress target)` — sends a ZCL frame to the target device. `InterviewResult interview(IEEEAddress device)` — executes the full interview pipeline (Node Descriptor → Active Endpoints → Simple Descriptors → Basic cluster read). `List<NeighborTableEntry> topologyScan()` — performs BFS mesh topology discovery via ZDO Mgmt_Lqi_req. `boolean ping()` — coordinator liveness check (SYS_PING for ZNP, nop() for EZSP). Javadoc: abstraction over Zigbee protocol operations above the transport layer. The protocol layer runs on virtual threads, using `CompletableFuture<T>` for synchronous request-response correlation with timeout. The coordinator abstraction makes the choice between ZNP and EZSP invisible to the rest of the adapter (Doc 08 §5 contract: "Coordinator type is an internal detail"). Thread-safe: methods may be called from the adapter's virtual threads and the command dispatch thread. `@see CoordinatorTransport`, `@see ZclFrame`, `@see NetworkParameters`, `@see InterviewResult`, `@see NeighborTableEntry`. |

### Group 6: Adapter Factory and Adapter Interfaces (depend on Groups 1–5 and integration-api)

| # | File | Type | Notes |
|---|------|------|-------|
| 38 | `ZigbeeAdapterFactory.java` | interface extends IntegrationFactory | Doc 08 §8.1. Extends `com.homesynapse.integration.IntegrationFactory`. No additional methods — the base `descriptor()` and `create(IntegrationContext)` are sufficient. Javadoc: factory for the Zigbee integration adapter. The `descriptor()` method returns a pre-populated `IntegrationDescriptor` with: integrationType `"zigbee"`, displayName `"Zigbee"`, ioType `IoType.SERIAL`, requiredServices `{SCHEDULER, TELEMETRY_WRITER}`, dataPaths `{DOMAIN, TELEMETRY}`, and health parameters per Doc 08 §4.1 (heartbeatTimeout 600s, healthWindowSize 20, etc.). Per DECIDE-04, this factory is instantiated directly by the application module (`new ZigbeeAdapterFactoryImpl()`), not discovered via ServiceLoader. `@see IntegrationFactory`, `@see IntegrationDescriptor`, `@see ZigbeeAdapter`. |
| 39 | `ZigbeeAdapter.java` | interface extends IntegrationAdapter | Doc 08 §8.1. Extends `com.homesynapse.integration.IntegrationAdapter`. Additional query methods: `Optional<ZigbeeDeviceRecord> device(IEEEAddress address)` — returns the local metadata cache entry for the device. `Collection<ZigbeeDeviceRecord> allDevices()` — returns all cached device records (unmodifiable). `Optional<DeviceProfile> deviceProfile(IEEEAddress address)` — returns the matched device profile for the device. `NetworkParameters networkParameters()` — returns the current network parameters (channel, PAN ID, etc.). `boolean isPermitJoinActive()` — returns whether permit-join is currently enabled. Javadoc: Zigbee integration adapter. Lifecycle: `initialize()` configures the serial port and prepares the coordinator protocol layer but does NOT connect to the serial device (INV-RF-03 — startup independence). `run()` opens the serial connection, auto-detects coordinator type, forms or resumes the Zigbee network, and enters the main frame processing loop. `close()` gracefully shuts down the coordinator connection and persists the device metadata cache. The adapter runs on a platform thread (IoType.SERIAL) for the transport layer and spawns virtual threads for the protocol layer. `@see IntegrationAdapter`, `@see ZigbeeAdapterFactory`, `@see ZigbeeDeviceRecord`, `@see NetworkParameters`. |

### Group 7: Module Descriptor, Build Configuration, Package Info

| # | File | Type | Notes |
|---|------|------|-------|
| 40 | `package-info.java` (update) | package Javadoc | Replace the existing one-line Javadoc with a comprehensive package description. Content: the Zigbee integration adapter translates between the Zigbee Cluster Library (ZCL) protocol and HomeSynapse's event-sourced domain model. This package contains the Phase 2 interface specification: frame types, device profile data model, cluster handler and codec interfaces, coordinator abstraction, and the adapter factory. The adapter communicates with ZNP (CC2652) and EZSP (EFR32) coordinators over USB serial, interviews devices to discover their ZCL capabilities, maps clusters to HomeSynapse capabilities, publishes attribute observations as domain events, and dispatches commands as ZCL frames. Reference: Doc 08 — Zigbee Adapter. |
| 41 | `module-info.java` | module descriptor | `module com.homesynapse.integration.zigbee { requires transitive com.homesynapse.integration; exports com.homesynapse.integration.zigbee; }`. Single `requires transitive` for integration-api. Through integration-api's transitive chain, this provides access to event-model, device-model, state-store, persistence, configuration, platform-api, and java.net.http. No additional requires needed in Phase 2 — all upstream types are available transitively. Phase 3 may add `requires com.jSerialComm` for serial port access (this library will need to be added to `libs.versions.toml`). |
| 42 | `build.gradle.kts` (update) | build config | Change `implementation(project(":integration:integration-api"))` to `api(project(":integration:integration-api"))`. This is required because IntegrationFactory, IntegrationAdapter, and integration-api types appear in this module's public API signatures (ZigbeeAdapterFactory extends IntegrationFactory, ZigbeeAdapter extends IntegrationAdapter, method parameters reference CommandEnvelope, IntegrationContext, etc.). |

### Group 8: Compile Gate

Run `./gradlew :integration:integration-zigbee:compileJava` from the repository root. Then `./gradlew compileJava` (full project). All must pass with zero errors and zero warnings (`-Xlint:all -Werror`).

**Common pitfalls:**
- **`[exports]` warning:** If `module-info.java` exports `com.homesynapse.integration.zigbee` but the JPMS compiler detects types leaking from `com.homesynapse.integration` (superinterfaces, supertypes), ensure `requires transitive com.homesynapse.integration` is used.
- **Byte array defensive copy:** `byte[]` is mutable. `ZnpFrame`, `EzspFrame`, and `ZclFrame` must clone `data`/`parameters`/`payload` in the compact constructor AND override any accessor that returns byte[] to return a clone. The record-generated accessor returns the original array. Override with: `@Override public byte[] data() { return data.clone(); }`.
- **`List.copyOf(null)` throws NPE:** Nullable collection fields in `DeviceProfile` must use `field != null ? List.copyOf(field) : null` (per Block N coder lesson).
- **Unused imports:** `-Xlint:all -Werror` flags any unused import as an error. Only import types actually used in each file.

---

## Cross-Module Type Dependencies

**Phase 2 imports from `com.homesynapse.integration` (integration-api):**
- `IntegrationFactory` — superinterface of `ZigbeeAdapterFactory`
- `IntegrationAdapter` — superinterface of `ZigbeeAdapter`
- `IntegrationDescriptor` — referenced in `ZigbeeAdapterFactory` Javadoc (descriptor() return type)
- `IntegrationContext` — referenced in factory Javadoc (create() parameter type)
- `CommandHandler` — referenced in adapter Javadoc (commandHandler() return type)
- `CommandEnvelope` — referenced in command-related Javadoc
- `IoType`, `RequiredService`, `DataPath` — referenced in descriptor construction Javadoc
- `HealthParameters` — referenced in descriptor Javadoc
- `PermanentIntegrationException` — referenced in failure-mode Javadoc

**Phase 2 imports from `java.time`:** `Instant` in `AttributeReport`, `ZigbeeDeviceRecord`, `RouteHealth`.

**Phase 2 imports from `java.util`:** `Optional`, `Collection`, `List`, `Set`, `Map` in interface return types and record fields.

**Phase 2: NO direct imports from event-model, device-model, state-store, persistence, or configuration.** These are accessible transitively through integration-api but no types from these modules appear directly in the Zigbee module's Phase 2 type signatures. Phase 3 will use `EventPublisher`, `EntityRegistry`, `StateQueryService`, `TelemetryWriter`, `ConfigurationAccess` — all obtained via `IntegrationContext`.

**Exported to (downstream consumers):**
- **homesynapse-app** (Phase 3) — constructs `ZigbeeAdapterFactoryImpl` and passes it to `IntegrationSupervisor.start()`.
- **test-support** (Phase 3) — test fixtures for simulating Zigbee frame sequences.

---

## Javadoc Standards

Per Sprint 1–4 lessons (Blocks A–O), plus Zigbee-specific requirements:

1. Every `@param` documents nullability (`never {@code null}` or `{@code null} if...`)
2. Every type has `@see` cross-references to related types (within module and to integration-api types)
3. Thread-safety explicitly stated on all interfaces and records
4. Class-level Javadoc explains the "why" — what role this type plays in the Zigbee adapter architecture
5. Reference Doc 08 sections in class-level Javadoc (e.g., `Doc 08 §3.6` for DeviceProfile)
6. ZCL numeric constants documented in hex where appropriate (e.g., `cluster 0xEF00`, `manufacturer code 0x115F`)
7. Byte array fields documented with the defensive copy contract: "The returned array is a copy; modifications do not affect this record."
8. Nullable fields use `{@code null} if...` pattern in Javadoc, NOT `@Nullable` annotations
9. Protocol-specific terms (SREQ, SRSP, AREQ, ASH, ZNP, EZSP, ZCL, ZDO, NWK) defined on first use in each class's Javadoc
10. `package-info.java` Javadoc: comprehensive description of the module's purpose, relationship to integration-api, and the adapter's role in the HomeSynapse architecture

---

## Execution Order

1. Update `package-info.java` — comprehensive module Javadoc
2. Create all Group 1 files (9 files: IEEEAddress, 7 enums, ValueConverter)
3. Create all Group 2 files (9 files: small data records)
4. Create all Group 3 files (6 files: composite data records)
5. Create all Group 4 files (4 files: ZigbeeFrame sealed hierarchy, ZclFrame)
6. Create all Group 5 files (8 files: service interfaces)
7. Create all Group 6 files (2 files: ZigbeeAdapterFactory, ZigbeeAdapter)
8. Create `module-info.java`
9. Update `build.gradle.kts` (`implementation` → `api`)
10. Run compile gate: `./gradlew :integration:integration-zigbee:compileJava` then `./gradlew compileJava`

---

## File Summary

| # | File | Type | Fields/Methods | Lines (est.) |
|---|------|------|----------------|-------------|
| 1 | `package-info.java` | package Javadoc | — | ~30 |
| 2 | `IEEEAddress.java` | record (1 field) | value + toHexString/fromHexString | ~55 |
| 3 | `DeviceCategory.java` | enum (4 values) | STANDARD_ZCL, MINOR_QUIRKS, MIXED_CUSTOM, FULLY_CUSTOM | ~40 |
| 4 | `CommandType.java` | enum (3 values) | SREQ, SRSP, AREQ + protocolId field | ~35 |
| 5 | `InterviewStatus.java` | enum (3 values) | COMPLETE, PARTIAL, PENDING | ~30 |
| 6 | `RouteStatus.java` | enum (3 values) | HEALTHY, DEGRADED, UNREACHABLE | ~30 |
| 7 | `TuyaDpType.java` | enum (6 values) | RAW through BITMAP + protocolId field | ~45 |
| 8 | `AvailabilityReason.java` | enum (6 values) | 6 availability transition reasons | ~45 |
| 9 | `ZoneType.java` | enum (5 values) | MOTION through VIBRATION + zclId, capabilityId | ~45 |
| 10 | `ValueConverter.java` | @FunctionalInterface | convert(Object) → Object | ~25 |
| 11 | `ManufacturerModelPair.java` | record (2 fields) | manufacturerName, modelIdentifier | ~30 |
| 12 | `EndpointDescriptor.java` | record (5 fields) | endpointId through outputClusters | ~50 |
| 13 | `NodeDescriptor.java` | record (4 fields) | deviceType through macCapabilityFlags | ~40 |
| 14 | `ClusterOverride.java` | record (3 fields) | clusterId, attributeOverrides, disableDefaultHandler | ~35 |
| 15 | `ReportingOverride.java` | record (4 fields) | clusterId through reportableChange | ~40 |
| 16 | `InitializationWrite.java` | record (6 fields) | endpoint through manufacturerCode | ~50 |
| 17 | `TuyaDatapointMapping.java` | record (4 fields) | dpId through converter | ~40 |
| 18 | `XiaomiTagMapping.java` | record (4 fields) | tag through converter | ~40 |
| 19 | `NeighborTableEntry.java` | record (6 fields) | ieeeAddress through parentIeee | ~50 |
| 20 | `NetworkParameters.java` | record (4 fields) | channel through networkKeyRef | ~45 |
| 21 | `AttributeReport.java` | record (4 fields) | entityRef through eventTime | ~40 |
| 22 | `InterviewResult.java` | record (8 fields) | ieeeAddress through interviewStatus | ~60 |
| 23 | `RouteHealth.java` | record (7 fields) | target through status | ~55 |
| 24 | `DeviceProfile.java` | record (9 fields) | profileId through initializationWrites | ~80 |
| 25 | `ZigbeeDeviceRecord.java` | record (10 fields) | ieeeAddress through matchedProfileId | ~75 |
| 26 | `ZigbeeFrame.java` | sealed interface (2 permits) | — | ~25 |
| 27 | `ZnpFrame.java` | record implements ZigbeeFrame (4 fields) | subsystem through data + clone accessor | ~50 |
| 28 | `EzspFrame.java` | record implements ZigbeeFrame (3 fields) | frameId through parameters + clone accessor | ~45 |
| 29 | `ZclFrame.java` | record (7 fields) | sourceEndpoint through payload + clone accessor | ~65 |
| 30 | `ClusterHandler.java` | interface (2 methods) | handleAttributeReport, buildCommand | ~55 |
| 31 | `DeviceProfileRegistry.java` | interface (3 methods) | findProfile, registerProfile, allProfiles | ~45 |
| 32 | `ManufacturerCodec.java` | sealed interface (2 permits, 2 methods) | decode, encode | ~50 |
| 33 | `TuyaDpCodec.java` | interface extends ManufacturerCodec | marker subtype | ~35 |
| 34 | `XiaomiTlvCodec.java` | interface extends ManufacturerCodec | marker subtype | ~35 |
| 35 | `AvailabilityTracker.java` | interface (4 methods) | recordFrame, recordCommandResult, isAvailable, lastReason | ~50 |
| 36 | `CoordinatorTransport.java` | interface (4 methods) | open, close, sendFrame, receiveFrame | ~55 |
| 37 | `CoordinatorProtocol.java` | interface (7 methods) | formNetwork through ping | ~70 |
| 38 | `ZigbeeAdapterFactory.java` | interface extends IntegrationFactory | — (inherits descriptor, create) | ~40 |
| 39 | `ZigbeeAdapter.java` | interface extends IntegrationAdapter (5 methods) | device, allDevices, deviceProfile, networkParameters, isPermitJoinActive | ~60 |
| 40 | `module-info.java` | module descriptor | requires transitive + exports | ~15 |

**Estimated total:** ~1850 lines across 40 files (39 new + 1 updated package-info + module-info + build.gradle update). This is the largest Phase 2 block, reflecting the Zigbee adapter's position as the most protocol-specific module in the system.

---

## Lessons Incorporated

**From coder-lessons.md (2026-03-20 — Block N):**
- **JPMS `requires transitive` default rule:** Applied in LD#8 analysis. `requires transitive com.homesynapse.integration` because integration-api types pervade the exported API.
- **Nullable collection defensive copy:** Applied to DeviceProfile (5 nullable collection fields) and ZigbeeDeviceRecord (1 nullable list field). Pattern: `field != null ? List.copyOf(field) : null`.
- **Byte array defensive copy:** New pattern for this block. Records with `byte[]` fields must clone in compact constructor AND override the generated accessor to return a clone. This prevents callers from mutating the record's internal state.

**From pm-lessons.md (2026-03-20):**
- **JPMS default rule codified:** Handoff defaults to `requires transitive`. Only use non-transitive `requires` when the PM can confirm NO types from the required module appear in any Phase 2 public API signature.
- **Compile gate deferred if blocked by infrastructure:** If the VM runs out of disk space, document the deferral and flag for manual execution.

**From cross-agent-notes (2026-03-20):**
- **DECIDE-04 (direct construction over ServiceLoader):** ZigbeeAdapterFactory is instantiated directly by the application module, not discovered via ServiceLoader.
- **Path changes:** Design docs at `homesynapse-core-docs/design/`, not `nexsys-hivemind/context/`.
- **18 LTDs (not 17).** LTD-18 exists.
- **No `@Nullable` annotations:** Project uses Javadoc nullability documentation, not annotation libraries.

---

## Constraints Active in This Block

| Constraint | Application |
|---|---|
| LTD-01 | Java 21 records, sealed interfaces, pattern matching. IoType.SERIAL: platform thread for transport (Phase 3 thread allocation). No `synchronized` — `ReentrantLock` only (Phase 3). |
| LTD-04 | `IEEEAddress` is NOT a ULID — it's a protocol-specific 64-bit hardware identifier. Entity IDs and Device IDs (ULIDs from platform-api) flow through IntegrationContext in Phase 3. |
| LTD-08 | Jackson serialization for device profile JSON loading and ZigbeeDeviceRecord cache persistence — Phase 3. Phase 2 types are Jackson-friendly records. |
| LTD-09 | YAML configuration (`integrations.zigbee:` namespace per Doc 08 §9) — Phase 3 consumption via ConfigurationAccess. |
| LTD-12 | This IS the first protocol adapter. Every type in this block exercises the integration architecture. |
| LTD-15 | JFR metrics and structured logging — Phase 3. Phase 2 Javadoc documents which operations produce structured log events. |
| LTD-17 | Build-enforced API boundary. integration-zigbee depends ONLY on integration-api. No core-internal imports. JPMS and Gradle enforce this. |
| INV-CE-04 | Protocol agnosticism — the coordinator type (ZNP vs EZSP) is invisible outside the adapter. No public API exposes which firmware is in use (Doc 08 §5). |
| INV-ES-06 | Every state change is explainable — AttributeReport carries entityRef, attributeKey, value, and eventTime for full traceability. |
| INV-RF-01 | Integration isolation — adapter exceptions are caught by the supervisor. PermanentIntegrationException transitions to FAILED. |
| INV-RF-03 | Startup independence — ZigbeeAdapter.initialize() must not block on serial port or coordinator connectivity (Doc 08 §1 principle 5). |
| INV-SE-03 | Network key stored encrypted at rest — NetworkParameters.networkKeyRef is a reference, not the key material. |
| INV-TO-01 | Observable behavior — every frame produces an event or a log entry (Doc 08 §1 principle 3). |
| INV-MN-01 | Protocol-agnostic network telemetry — NeighborTableEntry, RouteHealth expose mesh health as observable state. |

---

## Context Delta (post-completion)

**Files to create:**
- 39 new Java files in `integration/integration-zigbee/src/main/java/com/homesynapse/integration/zigbee/`
- 1 new `module-info.java` at `integration/integration-zigbee/src/main/java/module-info.java`

**Files to update:**
- `integration/integration-zigbee/src/main/java/com/homesynapse/integration/zigbee/package-info.java` — comprehensive Javadoc
- `integration/integration-zigbee/build.gradle.kts` — `implementation` → `api` for integration-api dependency

**What the next block (Q — Observability) needs to know:**
- integration-zigbee is a pure consumer of integration-api. It does NOT export types consumed by observability, lifecycle, or other blocks.
- The Zigbee adapter's health is exposed through IntegrationSupervisor.allHealth() (from integration-runtime, Block O), not through direct access to Zigbee types.
- Phase 2 is nearing completion. After Block P, remaining blocks are Q (observability), R (lifecycle), S (app assembly), T (test support).
- The `IEEEAddress` record is the first protocol-specific value type — it establishes the pattern for Z-Wave's `NodeId` or MQTT's `TopicPath` in future adapters.

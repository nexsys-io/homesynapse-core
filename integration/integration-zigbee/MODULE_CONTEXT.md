# integration-zigbee — `com.homesynapse.integration.zigbee` — M9.3 IMPLEMENTED — interview pipeline + ingestion + device-profile registry live on the M9.2 transport — Zigbee 3.0 coordinator, MVP protocol adapter, IEEEAddress (raw long, NOT ULID)

> **Type count (M9.3, 2026-07-04):** 59 M9.2 type files + 41 new M9.3 types = **100 type files + module-info**. The 41 split: **10 new PUBLIC §1 freeze types** (`ConfirmationCharacterization`, `ReportsAuthoritative`, `ReportingPosture`, `Confirmability`, `DegradeRule`, `MatchCriteria`, `ExactModel`, `ModelWildcard`, `Fingerprint`, `EndpointSignature`) + **31 package-private implementation types** (loader/registry, interview FSM+queue, codecs, handlers, ingestion, adoption, configurator, cache, tracker — inventoried in the M9.3 section below). Public total 38→48; package-private 21→52. Test tree 13 → **34 files** (30 test classes + 4 fakes/utilities) + 2 copied bench fixtures under `src/test/resources/fixtures/`.

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
    requires com.fazecast.jSerialComm; // explicit JPMS module (ships module-info.class); interior-only per D-M92-1
    requires org.slf4j; // plain (implementation-only): Doc 08 §3.3 mandates structured log entries (LTD-15)
    requires com.fasterxml.jackson.databind; // plain (implementation-only): the M9.3 JSON profile loader + device cache; no Jackson type on any exported signature (the D-M92-1 pattern)

    exports com.homesynapse.integration.zigbee;
}
```

M9.3 added the Jackson plain requires in Gradle lockstep (`implementation(libs.jackson.databind)`), TREE-MODEL ONLY (no reflective databind → no `opens` needed). `ConfirmationCharacterization` puts device-model's `ConfirmationMode` on the exported surface — legal with NO module-info change because integration-api `requires transitive com.homesynapse.device` (implied readability flows to every consumer).

`requires transitive` for integration-api (the Phase-2 edge). M9.2 added two PLAIN requires in Gradle lockstep (`implementation(libs.jserialcomm)` + `implementation(libs.slf4j.api)`): jSerialComm stays interior-only (D-M92-1 — no jSerialComm type on any exported signature; note the jar is a full explicit JPMS module, NOT an automatic module), and org.slf4j is the forced LTD-15 edge (no module in the transitive chain provides it transitively — the M9.1 integration-runtime precedent pair).

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
| `ManufacturerModelPair` (2) | `manufacturerName` (String), `modelIdentifier` (String) | Phase-2 data carrier; SUPERSEDED as the profile match key by the sealed `MatchCriteria` at M9.3 (stands alone — `ExactModel` does not wrap it; zero non-scaffold consumers at the retype, A7). Both non-null. |
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
| `DeviceProfile` (**10** — AMD-97 realized at M9.3) | `profileId`, `matches` (**Set&lt;MatchCriteria&gt;** — retyped from `Set<ManufacturerModelPair>` at M9.3, copied, non-empty), `category`, `clusterOverrides` (Map, **nullable**), `reportingOverrides` (Map, **nullable**), `manufacturerCodec` (String, **nullable**), `interviewSkips` (Set, **nullable**), `tuyaDatapoints` (List, **nullable**), `initializationWrites` (List, **nullable**), `confirmation` (**List&lt;ConfirmationCharacterization&gt;, nullable** — component 10, the AMD-97 block; null/empty = read-only device) | Per-model device behavior overrides. **6** nullable collection fields use conditional defensive copy. profileId namespace convention: bare = first-party (reserved), `publisher.profile` = third-party (Doc 18 §3.5(a)/(b)). |
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
| `DeviceProfileRegistry` | interface | Profile loading, lookup, user override merging | `findProfile(String, String)` → `Optional<DeviceProfile>` (ExactModel/ModelWildcard only), **`findProfile(InterviewResult)` (M9.3 additive widening — the fingerprint-capable path)**, `registerProfile(DeviceProfile)`, `allProfiles()` → `Collection<DeviceProfile>`. Precedence: Fingerprint > ExactModel > ModelWildcard, then USER > RUNTIME > BUNDLED, then priority desc, then profileId ascending (Doc 18 §3.5(d)). |
| `AvailabilityTracker` | interface | Per-device availability state machine | `recordFrame(IEEEAddress, Instant)`, `recordCommandResult(IEEEAddress, boolean, Instant)`, `isAvailable(IEEEAddress)`, `lastReason(IEEEAddress)` |
| `CoordinatorTransport` | interface | Serial protocol framing abstraction | `open(Object)`, `close()`, `sendFrame(byte[])`, `receiveFrame()` → `ZigbeeFrame`. NOT thread-safe — single transport thread. |
| `CoordinatorProtocol` | interface | Zigbee protocol operations above transport | `formNetwork(NetworkParameters)`, `resumeNetwork()`, `permitJoin(int)`, **`enablePreconfiguredKeyJoins()` (M9.4-TCJ — the instruction-sanctioned one-method widening; coordinator-neutral vocabulary, INV-CE-04 holds)**, `sendZclFrame(ZclFrame, IEEEAddress)`, `interview(IEEEAddress)` → `InterviewResult`, `topologyScan()` → `List<NeighborTableEntry>`, `ping()` → `boolean`. Thread-safe. |
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
implementation(libs.jserialcomm)   // M9.2, lockstep with plain requires
implementation(libs.slf4j.api)     // M9.2, lockstep with plain requires
implementation(libs.jackson.databind) // M9.3, lockstep with plain requires (interior tree-model)
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

**GOTCHA: `DeviceProfile` has 6 nullable collection fields (was 5; AMD-97 added `confirmation`).** clusterOverrides, reportingOverrides, interviewSkips, tuyaDatapoints, initializationWrites, confirmation are all nullable. Use conditional defensive copy: `field != null ? List.copyOf(field) : null`. `List.copyOf(null)` throws NPE.

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

## M9.2 Implementation — Transport + EZSP/ASH Protocol Layer (2026-07-03, EZSP-first per DP-C)

M9.2 delivered the first REAL protocol substrate beneath the M9.1 integration spine: the serial byte seam, the ASH data-link layer, the EZSP command layer, transport auto-detection (ZNP probe-encode only, D-M92-2), port identity/death recovery, and §3.13 network formation/resume. Everything is proven against embedded byte vectors and fake byte channels — zero real serial I/O in the test tree. Real-silicon contact happens at M9.4 bench acceptance. Nothing in M9.2 registers with the supervisor, publishes an event, or mints an identity.

### M9.2 Type Inventory (all package-private)

| Type | Kind | Purpose |
|---|---|---|
| `SerialByteChannel` | interface (seam) | D-M92-3 byte seam. EXACTLY two impls by design: `JSerialCommByteChannel` + the test fake. Timeout-bounded reads (W4); `isOpen()` documented liar (W5). |
| `JSerialCommByteChannel` | final class | Production channel: 115200 8N1 none, `TIMEOUT_READ_SEMI_BLOCKING` per-call, drain-loop flush. Thin; untested in unit tree (bench-exercised at M9.4). |
| `AshFrame` (+ nested `Data`/`Ack`/`Nak`/`Rst`/`RstAck`/`Error`) | sealed interface + records | Decoded ASH frames. `Data.payload` double-defensive-copied. |
| `AshCodec` (+ nested `ParseResult.Parsed`/`Rejected`) | final utility | Pure framing: stuff/destuff, LFSR randomize (seed 0x42/feedback 0xB8, DATA-payload-only), CRC-CCITT-FALSE big-endian, emit/parse. Corrupt input → typed `Rejected`, never an exception. |
| `AshFrameAccumulator` | final class | Stream-level byte accumulator: Flag delimits, Cancel discards partial, Substitute invalidates till Flag, XON/XOFF dropped. |
| `AshSession` | final class | The ASH state machine (CLOSED/CONNECTED/FAILED): Cancel+RST→RSTACK handshake (3200 ms = AN706 T_RSTACK_MAX, satisfies §3.3 "≥2 s"), stop-and-wait window 1, piggyback acks, NAK/timeout retransmit, adaptive T_RX_ACK (1.6 s start, 0.4–3.2 band, double-on-timeout, 7/8·T+measured/2 on ack), 5-consecutive-timeouts→FAILED exactly once. NOT thread-safe (serial platform thread). Clock-injected. |
| `TransportFailureException` | RuntimeException | The one-shot transport-failure signal (TRANSIENT under the M9.1 classifier). Post-FAILED calls fast-reject with `IllegalStateException`. |
| `EzspCodec` (+ nested `LegacyVersionResponse`/`Decoded`) | final class | Legacy version frames (static — format-invariant, W8) + extended v8+ frames; the D-M92-4 width seam: `decodeStatus` reads 1-byte EmberStatus <v14, 4-byte LE sl_status_t ≥v14. `EmberNetworkStatus` stays 1 byte on ALL versions. |
| `EzspFormatException` / `EzspCommandTimeoutException` / `EzspCommandException` | RuntimeExceptions | Malformed frame (discarded at transport boundary) / per-command timeout (names frameId+elapsed) / non-success NCP status. |
| `EzspAshTransport` | final class, implements `CoordinatorTransport` | D-M92-1 `open(Object)` validation (`instanceof SerialPort` → IAE naming the expected type); open performs the ASH handshake; close idempotent; `pinVersion(int)` pivots decode legacy→extended (W8); package-private `receiveDecoded(long)` → `Inbound(sequence, frame)` (EzspFrame drops the seq; single-in-flight correlation needs it — the M7.4b richer-package-private-method pattern). Test seam: ctor with a channel-opener `Function`. |
| `EzspCoordinatorProtocol` | final class, implements `CoordinatorProtocol` | D-M92-6: negotiation-first (legacy format, opens at 13, renegotiates at NCP version; tiers <8→PIE, 8–12→WARN `zigbee.ezsp_legacy_version`+proceed, 13–14 accept, >14→PIE), single-in-flight `ReentrantLock` pipeline (D-M92-5; timeout floor 1600 ms = bench CONFIG_APS_ACK_TIMEOUT), caller-executes `CompletableFuture` results, `maybeSendKeepalive()` (30 s idle, `tryLock` — never interleaves; misses feed liveness, never throw), `formNetworkAutomatically()` (full §3.13 scan path), `drainPendingCallbacks()` for M9.3. Thread-safe (LTD-11). |
| `NetworkParameterStore` | interface (seam) | D-M92-7 custody seam (load/save params + keyed network-key material). Javadoc carries the INV-SE-03 contract + DP-E non-preclusion note. M9.2 ships seam + test fake ONLY; SecretStore/config binding lands M9.3/M9.4. |
| `NetworkFormation` (+ nested `CoordinatorOps` seam + `CoordinatorNetwork`) | final class | §3.13 orchestration: two-tier channel selection (primary 15/20/11 → 15 when comparable; fallback 21–26 + `zigbee.channel_fallback_tier` WARN), SecureRandom key → store (key-first, params-after-success write ordering), resume with lost-network deterministic re-form and mismatch→PIE `zigbee.network_parameter_mismatch` (documented §3.13 reading — the doc has no explicit mismatch rule). Congestion/comparability constants (-75 dBm / 6 dB) are CHOSEN (§3.13 gives no numerics). |
| `TransportProbe` (+ nested `Kind`) | final utility | The §3.3 five-step auto-detect, exact budgets (4 s/200 ms/4 s/4 s within 10 s), ZNP SYS_PING probe-encode + SRSP FCS check only (D-M92-2), ERROR→one RST retry, `adapter_type` override as a PARAMETER (W10 — config key binds at M9.3/M9.4), failure → `zigbee.auto_detect_failed` + PIE with (a)/(b)/(c) guidance. |
| `PortIdentity` / `PortCandidate` | records | Strings/ints only (no jSerialComm types). Identity = VID:PID + by-id stable path + probe fingerprint; candidate descriptor field is diagnostics-ONLY. |
| `PortLocator` (+ nested `PortEnumerator` seam) | final class | VID:PID (`10c4:ea60`) matching + by-id preference; NEVER descriptor strings (AMD-96/E2); reopen-by-stable-id then VID:PID fallback. |
| `JSerialCommPortEnumerator` | final class | Production enumerator: `getCommPorts()` + best-effort `/dev/serial/by-id` symlink resolution. VID/PID are −1 on path-constructed ports — identity is captured at enumeration time only. |
| `PortWatchdog` (+ nested `ReopenAction`) | final class | Signals-only health (disconnect listener OR read-error OR ASH-liveness — never `isOpen()`, W5); capped clock-injected backoff (1 s doubling → 30 s cap, chosen constants); repeat signals don't reset the schedule. |

### M9.2 Cross-Module / Seam Contracts

- **Byte seam (D-M92-3):** `SerialByteChannel` has exactly two impls (jSerialComm + test fake); consumers: `AshSession` + `TransportProbe`. M9.4 hosts the production channel on the supervisor's dedicated SERIAL platform thread.
- **Storage seam (D-M92-7):** `NetworkParameterStore` — single production consumer `NetworkFormation` (+ the recording fake). M9.3/M9.4 bind SecretStore-backed key custody (INV-SE-03) + config-backed parameters. Key ref constant: `NetworkFormation.NETWORK_KEY_REF = "zigbee.network_key"`.
- **Enumeration seam:** `PortLocator.PortEnumerator` — production `JSerialCommPortEnumerator`, tests inject candidate lists.
- **Formation ops seam:** `NetworkFormation.CoordinatorOps` — implemented privately by `EzspCoordinatorProtocol` over the EZSP pipeline, faked in `NetworkFormationTest`.
- **D-M92-1 `open(Object)` contract:** the frozen `Object` parameter STAYS; the impl validates `instanceof SerialPort` and throws `IllegalArgumentException` naming `com.fazecast.jSerialComm.SerialPort`. No jSerialComm type on any exported signature (plain `requires` ⇔ `implementation(...)` lockstep holds).
- **M9.4 wiring TODOs:** transport open/probe orchestration (probe channel can be REUSED by the transport via the channel-opener seam — do not double-open the port); `maybeSendKeepalive()` driven from the adapter scheduler (a DIFFERENT thread than the command callers — the tryLock guard assumes it); `PortWatchdog.ReopenAction` composed from `PortLocator.reopenTarget` + transport reopen; formation NETWORK_UP (`stackStatusHandler`) await deliberately NOT implemented (unpinned frame ID — bench-verify and add at M9.4).
- **M9.3 inheritance — `pendingCallbacks` must be bounded-and-drained:** the protocol parks callback frames (attribute reports etc.) in an UNBOUNDED `ArrayDeque` for `drainPendingCallbacks()`; harmless while nothing live feeds it (no production wiring until M9.4), but M9.3's ingestion unit MUST establish the drain cadence + a bound-with-drop-policy (WARN + count) before any live NCP runs — an un-drained deque on a chatty network is a slow memory leak. (`resetSession()` DOES clear it — cross-session callbacks are stale truth.)
- **M9.4 inheritance — PortLocator multi-identical-dongle limitation:** with TWO same-VID:PID sticks (e.g. the Wave-2 ZBDongle-P bench next to the MG24) and no by-id path recorded, the VID:PID fallback in `reopenTarget()` can reopen the WRONG stick after a USB renumber. Single-coordinator scope is safe; before any two-stick topology, extend `PortIdentity` with the probe fingerprint (negotiated stack version) as the disambiguator — flagged by the 2026-07-03 hardening review.
- **Hardening review record (2026-07-03, hub-dispatched, two independent adversarial reviewers over all three integration modules — post-gate-green):** implementation verdict SOUND (threading seams, mod-8/mod-256 arithmetic, adaptive-timeout formula, key custody, width-seam offsets all verified against source); test-net verdict SOLID with 3 gaps → **H1–H5 applied same session:** H1 `AshFrameAccumulator.MAX_STUFFED_BODY_BYTES=512` byte-storm bound (+ direct test), H2 ASH frmNum 7→0 wrap test, H3 EZSP 300-command 0xFF→0x00 wrap test, H4 v14 wide-status END-TO-END resume test (the offset-math consumer), H5 both-casings key-leak assertions. Reviewer claims REJECTED on evidence (recorded so they don't resurface): a proposed `startSession()` already-active guard (breaks the pinned idempotence contract), "isHeldByCurrentThread is redundant" (false — reentrant `tryLock` SUCCEEDS for the owner), "stale pendingCallbacks survive resetSession" (false — cleared, line-verified).

### M9.2 Gotchas

- **W4 (blocking reads/interruption):** jSerialComm blocking reads don't respond to `Thread.interrupt()`; every read through the seam is timeout-bounded (`TIMEOUT_READ_SEMI_BLOCKING`), and `closePort()` from another thread unblocks an in-flight read. Never design a read loop that waits unboundedly.
- **W5 (`isOpen()` lies):** after USB unplug `isOpen()` keeps returning true (it only checks the internal handle). Health = disconnect listener OR read-error OR ASH-liveness; `PortWatchdog` structurally never consults `isOpen()`.
- **W7 (randomizer order):** randomize (DATA payload only) → CRC over control+randomized data → stuff EVERYTHING incl. CRC → unstuffed Flag. RST/RSTACK/ERROR data fields are NEVER randomized. A ≥16-byte vector test pins the ordering.
- **W8 (legacy version frame):** the version command/response is ALWAYS legacy format on every protocol version; nothing decodes extended until `pinVersion(...)` after negotiation. Don't parameterize the version-frame codec by version.
- **v13/v14 codec seam (D-M92-4):** v14 widens EmberStatus/EzspStatus fields to 4-byte LE sl_status_t but NOT `EmberNetworkStatus` (stays 1 byte). v14 is SYNTHETIC-frame tested only; physical v14 is the AMD-96 reflash contingency.
- **5-consecutive-timeouts is a Doc 08 LOCKED policy value:** references disagree with each other (bellows 4, Silabs/herdsman 6); 5 is deliberate policy, not a wire constant.
- **ASH duplicate-vs-out-of-sequence:** only the duplicate of the last delivered frame is re-ACKed without redelivery; a genuinely out-of-sequence frame enters the reject condition (one NAK per entry, cleared by valid in-sequence DATA) — per bellows/AN706, refined from the instruction's broader phrasing.
- **networkInit not-joined discriminators (adversarial-derivation-verified 2026-07-03):** `EMBER_STATUS_NOT_JOINED=0x93` (v13, bellows-confirmed) / `SL_STATUS_NOT_JOINED=0x17` (v14, sl_status.h-confirmed — the initially drafted 0x0B was SL_STATUS_IS_WAITING, caught by the derivation fleet). Any OTHER non-zero status throws — no silent re-formation on a mis-pin. The networkInit 2-byte `EmberNetworkInitBitmask` parameter arity is bellows-confirmed for the whole 8–14 band (v6+ redefinition).
- **Formation security bitmask = 0x1B04 (the bellows formation baseline):** HAVE_PRECONFIGURED_KEY | HAVE_NETWORK_KEY | TRUST_CENTER_GLOBAL_LINK_KEY | REQUIRE_ENCRYPTED_KEY | NO_FRAME_COUNTER_RESET, with the plaintext ZigBeeAlliance09 TC link key per §3.13 step 5. The hashed-TCLK mode (0x0084) bellows adds on EZSP > 4 is a deliberate deferral to the M9.4 bench pass ([REVIEW] — security-posture election).
- **Reopen requires session reset:** after a transport close/reopen the transport decodes only legacy frames until renegotiation, and UG100 requires `version` first after an NCP reset — the M9.4 ReopenAction must call `EzspCoordinatorProtocol.resetSession()` then `startSession()` (the M9.1 relaunch discipline; see `PortWatchdog.ReopenAction` Javadoc).
- **NAK-storm bound:** `AshSession.MAX_NAK_RETRANSMITS_PER_SEND = 8` (chosen defensive constant, no §3.3 numeric) — a babbling NCP that NAKs every retransmission would otherwise livelock send() under the pipeline lock (a NAK resets the ACK timer, so the 5-timeout counter never accrues).
- **D-M92-6 stub inventory:** `interview` (→ M9.3), `sendZclFrame` (→ M9.4), `topologyScan` (→ post-M9.4 §3.11 unit) throw `UnsupportedOperationException` naming the milestone. Implemented: `formNetwork`, `resumeNetwork`, `permitJoin`, `ping` — 4 of 7; stubbed 3 of 7 (~43%, the borderline the D-M92-6 tripwire flags; the stubbed set exactly matches the instruction's anticipated interview/ZCL/topology classes — arithmetic recorded in the M9.2 handoff).
- **Checked-PIE seam split (hub gate-round-1 fix, 2026-07-03):** `PermanentIntegrationException` is CHECKED (`extends HomeSynapseException`) and NEVER crosses the no-throws frozen `CoordinatorProtocol` surface. The checked type lives on the package-private ADAPTER seam — `startSession()`, `resumeStored()`, and both `TransportProbe.detect(...)` overloads declare it; the M9.4 adapter propagates it from its lifecycle methods (Doc 05 §3.7 → FAILED-no-retry). The interface's `resumeNetwork()` delegates to `resumeStored()` and rethrows `IllegalStateException` cause-chained (pinned by `resumeNetwork_mismatch_wrappedOnFrozenSurface`). **CLASSIFIER CONSEQUENCE (M9.4-binding, source-verified):** `ExceptionClassifier.classify()` is bare-`instanceof` with NO cause unwrapping — "wrapped causes → TRANSIENT" is its documented deliberate default (the HA anti-pattern guard). A permanent condition that reaches the supervisor WRAPPED (e.g. the interface surface's ISE) classifies TRANSIENT → **infinite restart-with-backoff on an unrecoverable condition** (mismatched network, unsupported firmware). Therefore: adapter lifecycle methods (`initialize()`/`run()`) must let checked PIE propagate BARE — always via the checked seams (`startSession`/`resumeStored`/`detect`), never via the no-throws interface surface. This rule generalizes to EVERY future adapter (Matter included): permanent-failure exceptions must arrive at the supervisor unwrapped. (Candidate hardening for the M9.3/M9.4 window, Nick to rule: teach the classifier to walk cause chains FOR PIE ONLY — recognizes wrapped permanents without reintroducing the unknown→PERMANENT anti-pattern.) Provenance of the seam split: the draft threw undeclared checked PIE from THREE files but javac reported only the first (its error-stop hid the other two) — checked-exception FLOW errors are a real-javac-only catch; the 6-dimension LLM fleet missed all three.

### M9.2 Phase-3 Notes (forward pointers)

- **DP-E reservation (D-M92-8, ZERO code):** `NetworkParameterStore` and the formation path are shaped so an exportable coordinator backup (the zigpy/z2m Open-Coordinator-Backup format class) can be added later without reshaping them: key material stays retrievable by reference (`loadNetworkKey`) rather than collapsed into an unexportable in-memory-only form, and `NetworkParameters` + the key ref jointly carry everything the backup format needs. Build nothing for it until the backup/restore WU.
- **DP-B pointer:** no identity is minted anywhere in M9.2 (`PortIdentity` is a value record, not an entity identity). Device adoption (M9.3) consumes Nick's DP-B ruling on durable integration identity.
- **M9.3 inheritance:** the charter §3.5 namespace convention + third-party-profile channel bind at M9.3 (`DeviceProfileRegistry` impl); `drainPendingCallbacks()` is the M9.3 ingestion feed; the `integrations.zigbee.adapter_type` config KEY binds at M9.3/M9.4 (M9.2 takes the override as a `TransportProbe.detect` parameter).

---

## M9.3 Implementation — Interview, Reporting Config, Ingestion, Device-Profile Registry (2026-07-04)

M9.3 delivered the pipeline layer on the M9.2 transport: the §1 AMD-97/MatchCriteria freeze (the one-way doors), the Doc 08 §3.4 interview machine + sleepy queue, the §3.7 reporting configurator with the first-class ACK-lies downgrade, the measured-contract ingestion (drain → dedup → handlers → `state_reported`), the Doc 02 §3.12 adoption slice (identity-UNGATED per DP-B), the index-first profile loader/registry with the bundled measured corpus, and the §J fixture-replay acceptance gate. Command dispatch, the confirmation-acceptance engine, adapter factory/composition-root wiring, and `registerIntegrationSchema` are M9.4's.

### M9.3 §1 Freeze Types (PUBLIC — the governance unit)

| Type | Kind | Purpose |
|---|---|---|
| `ConfirmationCharacterization` (9) | record | One AMD-97 `confirmation[]` entry: capability, `confirmationMode` (**consumes device-model's `ConfirmationMode`** — the §1.1 STOP-gate found the core type), `authoritativeAttribute` (nullable), `reportsAuthoritative`, `reportingPosture`, `confirmability`, `recommendedTimeoutMs` (≥0), `degradeRule` (Set, copied), `notes` (nullable). Zigbee-scoped javadoc (§K/INV-CE-04) on this and every §1 type. |
| `ReportsAuthoritative` (3) | enum | VERIFIED_REPORTS, READBACK_ONLY, NONE — with `Confirmability` carries the measured E5-#5 taxonomy split (never-reported vs no-attribute). |
| `ReportingPosture` (4) | enum | ON_CHANGE, PERIODIC, SLEEPY, NONE. |
| `Confirmability` (3) | enum | CONFIRMABLE, BEST_EFFORT, UNCONFIRMABLE — the load-bearing honest verdict (AMD-97-INV-01). |
| `DegradeRule` (4) | enum | NO_REPORT_TIMEOUT_TO_UNCONFIRMED, NACK_TO_FAILED, IMMEDIATE_UNCONFIRMED, CONFIRM_FROM_CACHE_OR_READBACK — composable Set; free text lives in `notes`, never the rule. |
| `MatchCriteria` | sealed interface | permits ExactModel, ModelWildcard, Fingerprint — SEALED FOREVER; Wave-2 populates fingerprint matching as behavior on an existing permit, never a hierarchy change (DP-2). `matches(String, String)` on the interface; exhaustive switches carry no `default`. |
| `ExactModel` (2) / `ModelWildcard` (2) | records | Implemented matching (exact / manufacturer-exact + model-prefix). |
| `Fingerprint` (3) | record | mfr + model + `List<EndpointSignature>` (non-empty). `matches()` throws `UnsupportedOperationException` with the Wave-2 pointer — a DEFINED permit with deferred behavior; registry arms treat it as no-match, never a silent match. |
| `EndpointSignature` (4) | record | profileId / deviceType / inClusters / outClusters — pinned verbatim from the corpus IR `identity.fingerprint[]`. |

### M9.3 Implementation Types (all package-private)

| Type | Purpose |
|---|---|
| `ZigbeeProfileLoader` + `ProfileEntry` + `ProfileSource` + `ProfileLoadException` | §F/§H/§C loader: eager index (id + criteria + priority), LAZY memoized bodies (a malformed body errors on materialization, naming the profile); loader-owned `schemaVersion {major, minor}` — unknown major fail-closed, unknown minor tolerated; duplicate ids per load = error; sources USER/RUNTIME/BUNDLED rank in that order. Jackson tree-model only. |
| `StandardDeviceProfileRegistry` | The resolution total order: criteria tier → source rank → priority desc → profileId asc. Fingerprint tier reserved (tier 0), contributes no matches until Wave-2. |
| `StandardValueConverters` | The named converter registry (§D no-eval-in-data): raw, divideBy10, divideBy100, booleanInvert, batteryVoltageToPercent (2.0–3.0 V linear band, chosen constants). Unknown name = load error. |
| `InterviewStateMachine` + `InterviewOps` (seam) + `InterviewAttempt` | §3.4 sequencer: 10 s/step + 60 s/whole (Clock-derived); one call = ONE attempt; EP-selection = first application endpoint in wire order (EP 242/GP profile skipped, never queried); Basic failure ⇒ PARTIAL with empty identity strings. `InterviewOps` returns Optional (empty = step failed) — no checked seams. |
| `PendingInterviewQueue` | Clock-scheduled retry ladder (5/15/30 s), park-after-3-retries, resume-on-ANY-frame (also short-circuits backoff), 24 h expiry (`zigbee.interview_expired` WARN), re-announce resets the ladder. NO sleeping anywhere. |
| `ZdoCodec` / `ZclCodec` / `EzspIncomingMessage` | Pure codecs: ZDP requests/responses (Node_Desc/Active_EP/Simple_Desc/Device_annce), ZCL header + Report/ReadResponse attribute records + ReadAttributes encoding, and the 0x0045 callback layout. Total: malformed input → empty/partial, never an exception. |
| `ZigbeeClusterHandler` (base) + `OnOffHandler`/`LevelControlHandler`/`ColorControlHandler`/`OccupancySensingHandler`/`PowerConfigurationHandler`/`IasZoneHandler` + `ClusterHandlers` (factory) + `NormalizedAttribute` | Doc 08 §3.5 normalization to the IN-TREE capability vocabulary (`on`, `brightness`, `color_temp_kelvin`, `occupied`, `battery_pct`, `detected`/`open`). Handlers bind per device (IEEE + Clock) so the frozen `ClusterHandler` surface can fill entityRef/eventTime; ingestion calls the richer package-private `normalize(...)` (raw retained — the M7.4b pattern). `buildCommand` throws until M9.4. |
| `ReportDeduplicator` | The measured contract: duplicate iff payload-equal AND TSN same-or-successor (mod 256), per (device, endpoint, cluster), cleared on announce. |
| `ZclIngestionUnit` (+ nested `DeviceResolver`/`IngestionListener` seams) | drain → route (announce/ZCL) → dedup → dispatch → `state_reported` publishRoot. Unknown cluster/sender/unadopted endpoint = logged skip. Origin: PHYSICAL (state), DEVICE_AUTONOMOUS (battery). eventTime = injected-Clock frame-receive approximation. |
| `ZigbeeAdoptionSlice` | Doc 02 §3.12 detection→proposal→adoption inside the adapter; dedup via `DeviceRegistry.findByHardwareIdentifier("zigbee", ieeeHex)` (constructor-injected — NOT an IntegrationContext component; M9.4 wiring decides the instance); IEEE match ⇒ re-link + `availability_changed`, NO adoption event; `adopt()` mints ULIDs (identity-ungated), registers Device/Entities/capabilities, publishes `device_adopted`; `entityFor(ieee, endpoint)` is the ingestion link. Blank identity → `"unknown"` sentinel (the frozen `DeviceDiscoveredEvent` rejects blanks). |
| `EndpointClassifier` | §3.5 deviceType table + cluster fallback → (EntityType, StandardCapabilities instances). Occupancy outranks IAS when both present (the measured dual-path rule). |
| `ReportingConfigurator` + `ReportingOps` (seam) + `ReportingPostureFact` | §3.7 bind→configure→VERIFY read-back; posture matrix: match ⇒ VERIFIED_REPORTS (ON_CHANGE / PERIODIC by min-interval); ACK-lies ⇒ posture from the READ-BACK (reporting-off ⇒ READBACK_ONLY/NONE); UNSUPPORTED ⇒ NONE/NONE; UNREPORTABLE ⇒ READBACK_ONLY/NONE; sleepy TIMEOUT ⇒ VERIFIED_REPORTS/SLEEPY; Xiaomi skip ⇒ no commands, VERIFIED_REPORTS/PERIODIC. IAS CIE write ATTEMPTED + recorded, never a gate. Facts feed the M9.4 confirmability consumption. |
| `ZigbeeDeviceCache` | §3.14 cache + NWK→IEEE index + the `lastKnownAvailability` FILE sidecar (the frozen record carries no availability component); 30 s debounced writes + shutdown flush; corrupt file ⇒ empty cache + WARN. |
| `StandardAvailabilityTracker` | Implements the frozen `AvailabilityTracker` + `evaluateTimeouts()`: battery 25 h passive offline; mains 10 min ⇒ PING CANDIDATES only (active ping is M9.4); M-1 restart-init from persisted state with ZERO transitions at init. |

### M9.3 Gotchas

- **Dedup payload scope:** dedup compares the ZCL COMMAND PAYLOAD (attribute records after the header), never the whole frame — the measured twins differ in their header TSN byte, so whole-frame equality never fires.
- **EP-11 selection:** Basic reads target the FIRST APPLICATION endpoint in Active-EP wire order (the Hue light lives on EP 11); EP 242 is skipped by ENDPOINT ID before its descriptor is ever requested, and GP-profile endpoints are dropped after fetch as defense.
- **ACK-lies downgrade:** posture derives from what the device DOES (the read-back), never what it ACKed; read-back `maxInterval 0xFFFF` = reporting off ⇒ READBACK_ONLY/NONE.
- **Fixture-clock discipline:** replay tests `setFixed(...)` the TestClock to each fixture frame's timestamp BEFORE feeding the frame — `eventTime` then equals the capture wall-clock deterministically; fixture time never leaks into production paths as `now()`.
- **`QuantityValue` normalizes in its compact constructor** (unit catalogue → canonical): `new QuantityValue(126, "K")` becomes −147.15 °C — color-temp Kelvin is an `IntValue`, never a `QuantityValue("K")` (bit the WithinTolerance test).
- **`InterviewStatus` has NO FAILED constant** (COMPLETE/PARTIAL/PENDING): hard failure surfaces as PARTIAL-with-gathered-data after retries, or an endpointless attempt that has NO constructible `InterviewResult` — the frozen surface's `interview()` throws `IllegalStateException` for that case (the `resumeNetwork()` ISE precedent); the internal pipeline uses `InterviewAttempt` and never hits it.
- **The frozen `DeviceDiscoveredEvent` is 4 fields** (integrationId, protocolAddress, manufacturer, model — non-blank): the Doc 08 §4.4 richer sketch (endpoints, interview_status, matched_profile_id) never landed in event-model; PARTIAL identity publishes the `"unknown"` sentinel.
- **TWO handler seams, by design (hub P2 note, 2026-07-04):** the frozen PUBLIC Phase-2 `ClusterHandler` (handleAttributeReport + `buildCommand`) is the **M9.4 command-path seam** — implementor-less today, NOT dead; the package-private `ZigbeeClusterHandler` base is the **ingestion-side richer internal** (device+clock-bound, typed `state_reported`-ready output — the M7.4b richer-internal pattern, because the frozen DTO drops slots ingestion needs). M9.4 implements `buildCommand` against `ClusterHandler`; do NOT duplicate ingestion normalization onto those impls, and never retire the public interface silently (PD-1 precedent — retirement is a Doc 08 AMD).

### M9.3 Phase-3 Notes (what M9.4 inherits)

- **The adoption entry point is callable:** `ZigbeeAdoptionSlice.adopt(IEEEAddress)` — M9.4's API/composition-root wires the user-acceptance path; `DeviceRegistry` arrives constructor-injected (the AB-3 in-memory substrate at the composition root).
- **The config schema resource awaits registration:** `src/main/resources/schema/zigbee-config-schema.json` ships now; the `SchemaRegistry.registerIntegrationSchema("zigbee", <json>)` call site + the `integrations.zigbee.profiles_path` key binding land with the adapter factory (W10).
- **Posture facts feed confirmation acceptance:** `ReportingPostureFact` rows are the measured per-device inputs to the AMD-97 `confirmability` consumption in the M9.4 engine; re-recorded on every rejoin (and OTA re-interview re-characterizes — the Q10 STYRBAR class).
- **`ReportingOps` needs its EZSP binding:** bind (ZDO 0x0021), ConfigureReporting/ReadReportingConfiguration (ZCL 0x06/0x08), and the IAS CIE write ride the M9.4 ZCL write path (`sendZclFrame`); M9.3 proved the configurator logic against fakes.
- **Interview wire pins bench-verified at M9.4:** `sendUnicast 0x0034` / `incomingMessageHandler 0x0045` / `lookupNodeIdByEui64 0x0060` re-derived from bellows (v4 lineage inherited through v13); the v13 `lookupNodeIdByEui64` reply is a bare nodeId (no status) — the v14 dialect of that reply is a bench-verify item.
- **The cycle contract (§G):** the M9.4 adapter run-loop calls `ZclIngestionUnit.processCycle()` FIRST each pass (drain before live NCP), then queue-driven interviews (`PendingInterviewQueue.due()`/`expireStale()`), then `ZigbeeDeviceCache.maybeFlush()`; the protocol's callback queue is bounded at 1024 with drop-oldest + WARN + `droppedCallbacks()`.
- **`interview()` is single-attempt:** Doc 08's 3-retry/backoff ladder lives in the QUEUE as clock-scheduled eligibility, not inside the frozen surface — no thread ever sleeps through a backoff.

---

## M9.4a Implementation — Moat Wiring + the Hardware-Free Hero Loop (2026-07-04)

M9.4a closes the loop: adoption-installed per-device confirmation (DP-a), the ZCL command write path, the EZSP band narrowing, and the composition wiring — proven end-to-end hardware-free (`HeroLoopHardwareFreeIT` / `ZigbeeReplaySafetyIT`, lifecycle test tree) over the scripted NCP.

### M9.4a Type Deltas

| Type | Kind | Purpose |
|---|---|---|
| `ZigbeeIntegrationFactory` | **PUBLIC** final class, implements `ZigbeeAdapterFactory` (**public count 48→49 — the ONE new public type**) | Direct-construction factory (DECIDE-04). Public ctor `(Supplier<DeviceRegistry>, Path dataDirectory, Clock)` — the registry is a SUPPLIER because the composition root's instance is assembled during `start()` and resolved once at `create()` (Phase 6); a package-private ctor adds the transport seam `Function<Object, SerialByteChannel>` (the hardware-free rig injects the scripted channel). `descriptor()`: `IoType.SERIAL`, `RequiredService` EMPTY (the M9.1 supervisor composes no scheduler/telemetry — declaring them would be a lie), `DataPath.DOMAIN`. `configSchemaJson()` reads the bundled schema resource (the W10 registration input). `lastCreated()` (package-private) is the gate drive seam. |
| `ZigbeeIntegrationAdapter` | package-private, implements `ZigbeeAdapter` | The minimal M9.4a composition: `initialize()` (INV-RF-03 — no serial I/O) loads the bundled profile corpus, opens the device cache, and composes slice/ingestion/command-handler; an UNBOUND transport (the public-ctor path — real serial orchestration is M9.4b) throws PIE `zigbee.transport_unbound` (honest FAILED-no-retry, never-false-ALIVE). `run()` opens the injected channel + `startSession()` (checked PIE propagates BARE) + parks on the stop latch; **the §G cycle is DRIVEN** (`runCycleOnce()`: processCycle → interviews due/expire → cache flush check) — the free-running cadence + network resume/formation orchestration bind with the real transport (M9.4b). `commandHandler()` = the §3.3 handler. In-memory `NetworkParameterStore` (SecretStore custody = M9.4b). `networkParameters()` throws UOE until M9.4b; `isPermitJoinActive()` false (the REST path is post-M9.4a). |
| `ConfirmationOverrideInstaller` | package-private static utility | THE DP-a pin-1 home: maps `confirmation[]` characterizations onto freshly classified `CapabilityInstance`s. CONFIRMABLE/BEST_EFFORT → policy `defaultTimeoutMs` = measured `recommendedTimeoutMs` AND every `CommandDefinition.defaultTimeout` rebuilt to it (the P17 executor precedence carries it into `command_issued.confirmationTimeoutMs` — zero executor change); a non-positive timeout leaves the instance untouched. UNCONFIRMABLE → `ConfirmationPolicy(DISABLED, [], null, <capability default>)` (never-tracked ⇒ never-CONFIRMED, AMD-97-INV-01); commands NOT rebuilt. No characterization → untouched; a characterization naming no classified capability → ONE WARN `zigbee.characterization_unmatched`, never a failure (the bundled Hue `identify`/`effect` land here — no core capability exists for them). |
| `ZigbeeCommandHandler` | package-private, implements integration-api `CommandHandler` (+ nested seams `ZclDispatch`, `AddressLookup`) | §3.3: identity join (entity → the slice's `EntityBinding` → the cache) with the F-6 hint check (unknown-sentinel OR live NWK→IEEE index disagreement → re-resolve via `lookupNetworkAddress` + `cache.recordAnnounce` heal); data absence → `command_result("unroutable")`, never a throw. `buildFrame` routes commandType → the actuator handlers; `identify` builds the Identify-cluster frame HERE (ZCL8 §3.5.2.2.1 — no classified capability owns cluster 0x0003); unknown commands → UOE (→ PERMANENT, the M9.4 classifier arm). Unicast rejection → `command_result("rejected")`. UNCONFIRMABLE (resolved via `matchedProfileId` + the registry + the adapter-side `CAPABILITY_BY_COMMAND` vocabulary — INV-CE-04) → STILL DISPATCHES, then the immediate honest `command_result("unconfirmed")` with the profile's recorded reason (notes, else the degrade-rule set). CONFIRMABLE success publishes NOTHING (the confirmation window owns the outcome). N-6 BINDING: every publication chains `(correlationId, commandEventId)` — never a root publish. |

### M9.4a Behavior Deltas (existing types)

- **`ZigbeeClusterHandler.buildCommand`** is NO LONGER `final` (it was ONE `public final` throw on the base — the M9.3 shape; the "six impls all throw" shorthand was structurally inaccurate): the base default throws `"<Handler> does not support command '<type>'"` (→ PERMANENT by design); `OnOffHandler` (`turn_on` 0x01 / `turn_off` 0x00, no payload), `LevelControlHandler` (`set_brightness` → Move-to-Level-with-On/Off 0x04, level = round(percent×254/100) clamped [0,254], `transition_ms` → deciseconds), and `ColorControlHandler` (`set_color_temperature` → 0x0A, **mireds = clamp(round(1e6/kelvin), 1, 0xFEFF)** — the AMD-96 Kelvin canonicalization in reverse, at the WIRE ONLY) override it. OccupancySensing/PowerConfiguration/IasZone still throw (M9.4b+/Wave-2). Byte-exact pins: `BuildCommandTest`.
- **`EzspCoordinatorProtocol`:** `MAX_SUPPORTED_PROTOCOL_VERSION` 14→13 (the M9.4 consolidated amendment correcting AMD-96 — the v14 0x0034/0x0045 dialect is uncharacterized on owned silicon; the `decodeStatus` width seam UNTOUCHED); the >MAX PIE message gains the EmberZNet 7.4.x reflash contingency. `sendZclFrame(ZclFrame, IEEEAddress)` FILLED over the bench-proven v13 unicast (ZCL header [fc][mfr LE]?[tsn][cmd] + payload; TSN/APS-seq per the interview convention); NEW package-private `boolean sendZclFrame(ZclFrame, int networkAddress)` — the failure seam the command handler converts to an honest result (the frozen surface gains no throws); `lookupNetworkAddress` widened private→package-private (the F-6 re-resolution seam). D-M92-6 stub inventory: only `topologyScan` remains. Test deltas (format #12): the sendZclFrame stub-assert DROPPED (topologyScan's retained verbatim); `negotiation_v14_renegotiatesAndPinsWideSeam` FLIPPED to the v14→PIE pin; `resumeNetwork_restored_v14WideStatus` DELETED (it reached the wide seam only through v14 acceptance; the H4 offset coverage survives in `EzspCodecTest`).
- **`ZigbeeAdoptionSlice`:** ctor +`DeviceProfileRegistry` (6-arg). §2.2: `adopt()` installs the overrides between classification and `createEntity` — the ONLY write; every downstream read path consumes the tuned `CapabilityInstance` unchanged. §2.3 (DP-a pin 2): `relink(ieee, device, matchedProfileId)` re-installs from the matched profile id (the rediscovery re-match — the same value `recordInterview` re-writes to the cache) via `entityRegistry.updateEntity` when the tuned set differs (idempotent; availability-only publish preserved; the registry-empty-post-restart rebuild stays FENCED). NEW: `EntityBinding(ieee, endpoint)` + `bindingFor(EntityId)` (the §3.3 identity join) + `matchedProfileIdFor(IEEEAddress)`.
- **`ZigbeeDeviceCache` (F-6):** `NETWORK_ADDRESS_UNKNOWN = 0xFFFF` (the protocol's not-in-table sentinel); a `reindex` collision (an address reassigned to a DIFFERENT IEEE) invalidates the VICTIM record's address to the sentinel + WARN `zigbee.network_address_collision`. The observable contract: a victim's cached address is never silently the reassigned one; the §3.3 hint check re-resolves before dispatch (the misdirected-actuation class closed).
- **F-9 invalid markers:** `ZclCodec` bool (0x10) markers other than 0x00/0x01 → the RECORD drops (a null-value `Decoded`; the callers skip it and continue — the rest of the frame survives) + DEBUG; `PowerConfigurationHandler` raw 0xFF (unknown) and raw > 200 (out-of-band) → no observation (raw 200 = 100 % still reports).
- **testFixtures (NEW source set — TEST-SCOPE build delta):** `FakeNcp` + `FakeSerialByteChannel` MOVED from `src/test` (same package — zigbee's own tests compile unchanged); NEW public `ZigbeeHardwareFreeRig` — the REAL adapter over the scripted NCP (two measured Wave-1 identities, full ZDO/Basic interview scripting, a queued-callback pump via the keepalive nop, cluster-specific unicast capture) — the lifecycle composition-root gates consume it via `testFixtures(project(":integration:integration-zigbee"))`.

### M9.4a Gotchas

- **`identify` issuability — REALIZED at M9.4b §3** (the M9.4a gap closed): the core `Identify` capability (device-model, DISABLED-at-root) + the classifier's 0x0003 attachment make identify issuable through the REAL Tier-1 validator; the SD-3 fence in `ZigbeeCommandHandler` (the `INHERENTLY_UNCONFIRMABLE` set) renders the generic honest `unconfirmed` verdict when the characterization is ABSENT — never silence. `color_loop`/effects stay NON-issuable (no core effects vocabulary — deferred, recorded).
- **Brightness capability-domain mismatch — REALIZED at M9.4b §2** (SD-2, derivation-side): `StandardCapabilities.brightness()` attribute schema is the CANONICAL 0–254 (Doc 08 §3.5); the ledger's `deriveOutcome` rescales param-domain → attribute-domain generically when bounds differ (schema-driven, zero ZCL knowledge in core); `brightness_percent` derives at QUERY time in state-store. The hero loop's brightness leg confirms end-to-end.
- **The rig's callback pump is the keepalive nop:** unsolicited frames reach the protocol only while it reads (single-in-flight pipeline) — the rig queues callbacks and `deliverAndCycle()` pumps them through a `ping()` whose scripted response carries them (the `EzspInterviewTest` §G pattern). The PRODUCTION inbound pump is `pumpInbound(ms)` (M9.4b §5.1): the adapter's cycle parks ON the bounded serial read itself — the read IS the park.

---

## M9.4b Implementation — Real Transport, Registry Unification, and the Honest-Verdict Fences (2026-07-04)

M9.4b makes the adapter REAL-WORLD-OPERABLE: production port location/probe/session/resume-or-form/NETWORK_UP/watchdog orchestration, SecretStore-backed network-key custody, the SD-3 identify fence, and the ruled hardening folds. Type delta: **+1 package-private production type** (`PersistentNetworkParameterStore`); the M9.4a nested `InMemoryParameterStore` DELETED; public count unchanged (49).

### M9.4b Type/Behavior Deltas

| Surface | Delta |
|---|---|
| `ZigbeeIntegrationAdapter` | TWO run modes by construction (canonical 7-arg ctor): DRIVEN (injected channel — the rig, unchanged) vs PRODUCTION (§5.1: `resolvePort()` — the `serial_port` key else the VID:PID locator, never descriptor strings — → `bindTransport()` (probe-channel REUSED by the transport, never a double-open) → `startSession()` → `resumeOrForm()` → `awaitNetworkUp()` → the watchdog-armed cycle loop). The cycle parks ON `pumpInbound(50ms)` (the read IS the park — no sleep); keepalive misses ≥ 3 feed `onAshLivenessLost`. `attemptReopen()` composes the P24 ReopenAction: close → reopenTarget → reopen → `resetSession()` → `startSession()` → `resumeStored()` — **RESUME, never re-form**; PIE inside reopen is the ONE deliberate catch (the watchdog's backoff domain). **Recorded limitation:** a PERMANENT mismatch discovered at reopen-resume keeps failing on the backoff and surfaces via WARNs + operator action, never the classifier. `networkParameters()` now reads the store. |
| `PersistentNetworkParameterStore` | §5.5 custody split: non-secret params JSON at `<dataDir>/zigbee-network.json` (temp-then-atomic-move); key material hex-inside an INDEPENDENT data-dir-rooted `SecretStore` (`SecretStore.create(dataDir, ScopeKeyManager.create(dataDir, clock), clock)` — its own `.root-key`/`scope_keys.json`/`secrets.enc`; zero sharing with the config-dir store) under `zigbee.network_key.<keyRef>`. Params-present-key-missing = CORRUPT custody → the resume path's `loadNetworkKey().orElseThrow` PIE (PERMANENT, never silent re-form); a CORRUPT params file → ISE naming the file (TRANSIENT backoff — recorded limitation); only params ABSENT reads first-run. INV-SE-03 test-asserted (no key hex in any plaintext file, both casings). |
| `EzspCoordinatorProtocol` | `INITIAL_SECURITY_BITMASK` **0x1B84** (SD-5 hashed-TCLK election REALIZED; fallback = one-constant revert to 0x1B04, carried in the bench protocol — never a runtime branch; preconfigured key stays ZigBeeAlliance09 — see the M9.4b [REVIEW] on bellows' generated-seed divergence). NEW: `FRAME_STACK_STATUS_HANDLER=0x0019` + `EMBER_NETWORK_UP=0x90` (**BENCH-VERIFY** — bellows-derived, synthetic-tested) + `awaitNetworkUp()` (10 s window; checks BUFFERED callbacks first — a stackStatus arriving during resume exchanges is enqueued by that command's response loop; timeout → ISE = TRANSIENT) + `pumpInbound(ms)` (tryLock, callback-only, stray responses WARN-dropped). N-4: non-success `scanCompleteHandler` status WARNs (`zigbee.energy_scan_incomplete`), scan proceeds over partial data. |
| `ZclIngestionUnit` | F-4 scope: dedup ONLY unsolicited 0x0A; 0x01 Read-Attributes-Response (the readback/VERIFY channel) BYPASSES dedup. F-7a: 0x0500 cmd 0x01 ZoneEnrollRequest → ZoneEnrollResponse (0x00, [0x00, 0x00] — ZCL8 §8.2.2.3) via the NEW required last-ctor-param seam `ZclFrameSender` (adapter wires `protocol::sendZclFrame`); IAS attr 0x0001 learns into a TRANSIENT `Map<Long, ZoneType>` consulted before the resolver default (MOTION stays the fallback; `ZigbeeDeviceRecord` untouched — persisted zone-type out of scope). F-8: `invalidateHandlers(IEEEAddress)` — called on announce, on adoption completion (the slice's `onAdopted` hook, adapter-wired), and on zone-type change. |
| `ReportDeduplicator` | F-4: sole ctor `(Clock)`; `LastFrame` +`seenAt`; duplicate additionally requires `now − seenAt <= DEDUP_WINDOW_MS = 10_000` (corpus twins are sub-second; periodic reports are minutes apart — the false-drop class closed). The SNZB 18-edge pin is UNCHANGED. |
| `ZigbeeAdoptionSlice` | F-11: `adopt()` claims atomically (remove INSIDE the first lock; a downstream failure never re-inserts — the device re-announces naturally). N-8: `Proposal` +`Instant offeredAt`; age > `PROPOSAL_MAX_AGE` (24 h — the sleepy-interview horizon) → ISE naming device/age/max; re-discovery replaces the entry. NEW seam `onAdopted(Consumer<IEEEAddress>)` (fires post-registration, outside the lock). |
| `ZigbeeDeviceCache` | F-14: state snapshots UNDER the lock, file I/O OUTSIDE it; IOException → WARN + 60 s write-suppression backoff (re-dirties — no data loss); `flush()` bypasses the backoff (shutdown last chance); reads never block on write I/O. |
| `ZigbeeProfileLoader` / `StandardDeviceProfileRegistry` | F-15: unknown `degradeRule` → `ProfileLoadException` naming field + profileId + value + the ratified vocabulary (fail-closed at the lazy parse point; siblings unaffected). F-12: `findProfile` catches `ProfileLoadException` per candidate at materialization → no-match + ONE WARN (`zigbee.profile_body_unloadable`) per attempt; `allProfiles()` deliberately still propagates (full-truth). |
| `StandardAvailabilityTracker` | N-5: battery-conservative UNLESS powerSource ∈ {0x01, 0x02} (ZCL mains classes); UNKNOWN 0x00 and exotic values (0x04 DC…) take the 25 h passive window — never false-offlined by the 10-min ping regime. (0x81/0x82 battery-backup variants fall battery-side — flagged for a PM ruling.) |
| `ReportingConfigurator` | F-7b: the IAS enroll row DERIVES the fact — both CIE arms record `READBACK_ONLY`/`NONE` (an ACKed write with no verifying read-back must not claim `VERIFIED_REPORTS`); notes stay truthful per arm. |
| `EndpointClassifier` | §3.2: cluster 0x0003 present ⇒ append `StandardCapabilities.identify()` on EVERY classification arm (post-processed); 0x0003 alone never invents an entity. |
| `ZigbeeCommandHandler` | §3.3 SD-3 fence: `INHERENTLY_UNCONFIRMABLE = {identify, color_loop}` (adapter-side protocol knowledge, INV-CE-04) — characterization ABSENT + inherently-unconfirmable ⇒ the generic honest `unconfirmed` verdict ("no confirmation surface exists for '<command>'; the command was issued and is not tracked") — never silence. |
| testFixtures | `ZigbeeHardwareFreeRig`: +`reportBrightnessLevel(int)`; the channel opener now supplies a FRESH channel per open (restartIntegration re-opens the transport — the ONE scripted NCP's RST handler resets its ASH numbering). |

### M9.4b Gotchas

- **`awaitNetworkUp` must check the buffered callback queue FIRST** — a stackStatusHandler that arrived during the resume exchanges was enqueued by that command's own response loop; re-reading the transport past an answered radio is a false timeout.
- **Dedup scope consequence (flagged):** cluster-specific IAS ZoneStatusChangeNotifications no longer pass through dedup (the verbatim "unsolicited 0x0A only" ruling) — if Wave-1 bench data shows ×2 notification twins, that is a PM follow-up, not a code assumption.
- **`serial_port` is consumed via the SCOPED ConfigurationAccess** (`getString("serial_port")` — the supervisor scopes by integration type); `adapter_type` remains an unbound key (probe-only detection; the override is still a `TransportProbe.detect` parameter).
- **A configured-but-unenumerated port synthesizes a candidate** (operator intent wins) with a zeroed VID:PID identity — reopen then matches by stable path only.

---

## M9.4-PJ Implementation — Permit-Join Config Wiring (2026-07-06)

M9.4-PJ wires the already-schema'd `integrations.zigbee.permit_join_duration` key to `CoordinatorProtocol.permitJoin(int)` — the headless/bench operator path for the M9.4 "join two devices" step. Before this WU there was NO production caller of `permitJoin` (reachable only from unit tests). Type delta: ZERO (no new types). Change: 1 main file + 1 new test file; zero module-info/build.gradle/schema/dependency/event diffs. The REST permit-join surface remains the future UI mechanism — this does not preempt it.

### M9.4-PJ Behavior Deltas (existing types)

- **`ZigbeeIntegrationAdapter` — permit-join binding (M9.4-PJ):** NEW constant `PERMIT_JOIN_DURATION_KEY = "permit_join_duration"` (beside `SERIAL_PORT_KEY`) + clamp bounds `PERMIT_JOIN_MIN_SECONDS=1`/`PERMIT_JOIN_MAX_SECONDS=254`. NEW package-private `openPermitJoinWindow()` is called from PRODUCTION `run()` ONLY — after `awaitNetworkUp()` + the `production_session_started` log, before `productionLoop()`. Conservative default is LAW: an ABSENT key opens NOTHING (the schema's `default: 120` is documentation-side; the adapter never auto-opens). A present value is clamped to [1,254] (out-of-range logs ONE WARN `zigbee.permit_join_clamped: configured={} clamped={}` and proceeds — the adapter clamps FIRST so a configured value never reaches the protocol's own `permitJoin` range throw), `protocol.permitJoin(n)` fires ONCE, and `permitJoinDeadline` (a NEW `volatile Instant`) is recorded AFTER acceptance (a rejected open leaves the window honestly closed); INFO `zigbee.permit_join_opened: duration={}s`. NOT called from `initialize()` (INV-RF-03) nor from the M9.4a driven cadence (`runCycleOnce()`); a watchdog `attemptReopen()` does NOT renew the window (reopen ≠ boot — the ONE deliberate PIE catch there is untouched). A restart re-opens the window while the key is present — the designed bench semantic (remove the key to stop re-opening on boot).
- **`isPermitJoinActive()` — REALIZED (was the M9.4a `return false` stub):** now `permitJoinDeadline != null && clock.instant().isBefore(deadline)` (volatile read into a local; written on the production run thread, read from query threads — never-false-ALIVE: never claims open past close). The public `ZigbeeAdapter` signature is unchanged.

### M9.4-PJ Gotchas

- **The ONE-frame rule is the designed bench semantic:** `openPermitJoinWindow()` sends at most one `permitJoin` per boot; a restart naturally re-opens while the key is present. Stopping the re-open is an operator action (remove the key), not a code branch.
- **Production `run()` is not driven end-to-end by any unit test** — `run()` blocks on `productionLoop()`/`stopSignal.await()`. `ZigbeePermitJoinTest` drives the production ladder via the package-private seams (the `ZigbeeProductionTransportTest` idiom: `resolvePort`→`bindTransport`→`startSession`→`resumeOrForm`→`awaitNetworkUp`) then calls `openPermitJoinWindow()` directly; the call-site placement in `run()` rests on inspection, not a live test.
- **Log-capture:** the clamp WARN is asserted via the `ListAppender` idiom (the `StandardDeviceProfileRegistryTest`/F-12 pattern; `logback.classic` is test-scoped only).

---

## M9.4-TCJ Implementation — Trust Center Join Enablement + Channel Pin (2026-07-06)

M9.4-TCJ closes the bench blocker the first silicon run surfaced: the coordinator opened the MAC window (M9.4-PJ) but never set the TC join policy / installed the transient well-known key / parsed the join handler — so a Z3.0 device could not complete the APS key exchange (no Device_annce → no adopt). §A = the join-side realization of the RULED SD-5 hashed-TCLK posture (v18 beat-2 — not a re-opened election). §B = the `integrations.zigbee.channel` pin (11–26, first formation only). Change: 5 main files + 4 test files; zero module-info/build.gradle/`libs.versions.toml`/schema/event-mint diffs (both keys were already schema'd); public surface widened by EXACTLY the one `CoordinatorProtocol` method (instruction-pinned budget).

### M9.4-TCJ Behavior Deltas (existing types)

- **`CoordinatorProtocol` +1 method (the frozen-surface exception, instruction-sanctioned):** `enablePreconfiguredKeyJoins()` — sets the TC join policy and installs the well-known link key as a wildcard TRANSIENT credential. The name is coordinator-neutral (no EZSP vocabulary — INV-CE-04 holds); no new checked throws (rejections surface as the unchecked `EzspCommandException`, the `permitJoin` precedent). The prior every-capability-goes-package-private pattern was deliberately overridden by the instruction's pinned change shape ("`CoordinatorProtocol` (+1 method)"; "ZERO public-surface beyond the one `CoordinatorProtocol` method").
- **`EzspCoordinatorProtocol` — the M9.4-TCJ BENCH-VERIFY block + enablement seam:** new constants `FRAME_SET_POLICY=0x0055`, `POLICY_TRUST_CENTER=0x00`, `POLICY_TC_KEY_REQUEST=0x09`, `DECISION_ALLOW_PRECONFIGURED_KEY_JOINS=0x0003` (u16 LE bitmask), `DECISION_ALLOW_TC_KEY_REQUESTS=0x51`, `FRAME_IMPORT_TRANSIENT_KEY=0x0111` (7.x security-manager; legacy fallback `addTransientLinkKey 0x00AF` noted in javadoc), `TRANSIENT_KEY_FLAGS_NONE=0x00`, `FRAME_TRUST_CENTER_JOIN_HANDLER=0x0024`, `FRAME_CHILD_JOIN_HANDLER=0x0023`, plus the `DEVICE_UPDATE_*`/`JOIN_DECISION_*` vocabularies — ALL bellows-derived, synthetic-tested until silicon (the 0x0019/0x90 precedent; each isolated so a bench correction is a one-constant/one-layout edit). `enablePreconfiguredKeyJoins()` runs THREE `execute(...)`+`requireSuccess` exchanges in order (TC policy → TC key-request policy → transient key: wildcard all-0xFF partner EUI + the REUSED `TC_LINK_KEY` + flags byte). New nested package-private records **`TrustCenterJoin`** (0x0024 parse: nodeId u16 LE, eui64 u64 LE, status u8, decision u8, parent u16 LE; `deviceLeft()`/`denied()`/`joinStarted()`/name helpers) and **`ChildJoin`** (0x0023 parse: index, joining, childId, eui64, type). New `formNetworkAutomatically(int)` overload (§B — lock + `formation.form(channel)`).
- **`ZigbeeIntegrationAdapter` — enablement rides the window (§A) + channel pin (§B):** `openPermitJoinWindow()` calls `protocol.enablePreconfiguredKeyJoins()` BEFORE `protocol.permitJoin(duration)` — order policy → transient key → permitJoin. Enablement inherits every M9.4-PJ window rule: absent key ⇒ NOTHING (no policy/key frames either); a rejected enablement propagates BEFORE the 0x0022 and the deadline stays unset (never-false-ALIVE — no half-open door); `attemptReopen()` never re-runs it (reopen ≠ boot — atomic-per-window). §B: new `CHANNEL_KEY="channel"`/`CHANNEL_MIN=11`/`CHANNEL_MAX=26`; the private `formNetwork()` on the form path reads the key — present+in-range ⇒ `formNetworkAutomatically(channel)`; present+out-of-range ⇒ ONE WARN `zigbee.channel_pin_ignored: configured={} range={}-{}; the energy scan selects the channel` + scan fallback (the PJ defensive-floor pattern — never the `NetworkParameters` range throw); absent ⇒ the unchanged energy scan. The RESUME path never reads the key.
- **`NetworkFormation.form(int pinnedChannel)` (§B):** validates 11–26 (defensive IAE floor), logs INFO `zigbee.channel_pinned: channel={}`, skips the energy scan, and rides the shared `form(NetworkParameters)` path (PAN identity, key-first custody ordering, persistence all unchanged — via the extracted private `formWithFreshIdentity`).
- **`ZclIngestionUnit` — the drain widened (§A.2):** `processCycle()` routes `0x0024`→`handleTrustCenterJoin` and `0x0023`→`handleChildJoin` — LOG-ONLY. INFO `zigbee.device_join: device= nwk= status= decision=` (secured/started), INFO `zigbee.device_left`, WARN `zigbee.device_join_failed: device= status= decision=` (DENY_JOIN or unknown status), INFO `zigbee.child_join`/`zigbee.child_left`. **THE PIN:** the join handlers NEVER create a device, NEVER schedule an interview, NEVER publish an event, NEVER feed availability — adoption stays Device_annce-gated (`handleAnnounce` is still the only chain trigger; test-pinned).

### M9.4-TCJ Gotchas

- **Transient-key lifetime (§A.3 disposition):** the WU relies on the EmberZNet stack's own transient-key self-expiry (expected to cover the 254 s window max) — **BENCH-VERIFY on silicon**; if silicon shows NO auto-expiry, the correction is a clear-on-window-close derived from `permitJoinDeadline`. Never leave an unbounded wildcard well-known transient key installed. The key material is never logged (INV-SE-03); the well-known literal exists ONCE in src/main (`TC_LINK_KEY`), and tests assert against independent literals.
- **Callbacks riding a command response are NOT in the drain until the protocol next reads:** `executeLocked` returns on its matched response; frames the FakeNcp appends after it sit unread in the channel. Adapter-level tests must pump before cycling — the rig's `ping()`-then-`runCycleOnce()` idiom (`deliverAndCycle`). Forgetting the pump makes join/announce tests assert against an empty drain.
- **A watchdog reopen resets NCP-side policy/transient-key/MAC-window state while `permitJoinDeadline` may still be in the future** — `isPermitJoinActive()` can read true for a window the NCP no longer holds. SAME pre-existing accepted M9.4-PJ semantic (reopen ≠ boot renews nothing); TCJ adds no new dishonesty class. Recorded limitation, not a defect.
- **`ZigbeeProductionTransportTest`'s `defaultResponses` does NOT answer 0x0055/0x0111** (harmless — no test there opens the window). Any future window-open test in that class must widen its script the way `ZigbeePermitJoinTest.defaultResponses` did.
- **The `importTransientKey` response is sl_Status u32 on v13** — `requireSuccess` reads the LE low byte via the 1-byte v13 `decodeStatus`, which detects success/failure correctly (SL_STATUS_OK low byte = 0x00); the full-width read is a v14-dialect concern (BENCH-VERIFY).

---

## M9.4-NCFG Implementation — NCP Session Configuration (2026-07-06)

M9.4-NCFG closes the join-completion blocker bench iteration 1 surfaced (core `2a5fba2`): the TCJ enablement was silicon-ACCEPTED (`tc_joins_enabled` + `permit_join_opened` logged) yet two 254 s windows produced ZERO join activity — the NCP resets to firmware defaults every launch (`resetCode=0xb`) and `startSession()` was version-negotiation-ONLY, so the stack was never configured (STACK_PROFILE default 0 ⇒ Z3.0 devices ignore the non-PRO beacon — the leading candidate; APPLICATION_ZDO_FLAGS 0 ⇒ host ZDO delivery ambiguity; SECURITY_LEVEL pinned as hygiene). Change: 1 main file + FakeNcp (testFixtures) + 1 adapted test + 1 new test class; ZERO module-info/build.gradle/`libs.versions.toml`/schema/event-mint/public-surface diffs; no new types.

### M9.4-NCFG Behavior Deltas (existing types)

- **`EzspCoordinatorProtocol.startSession()` — the config prelude:** after the version tiers pin the codec (inside the guarded post-negotiation block, under the pipeline lock), private `configureNcp()` runs the §2 batch BEFORE returning — so config always precedes `networkInit`/`formNetwork` on BOTH run() paths and the watchdog-reopen ladder (`resetSession()` clears `negotiatedVersion` ⇒ the reopen re-negotiates AND re-configures; a no-op `startSession()` on a live session never re-writes — G-NCFG6, test-pinned). REQUIRED-first: `STACK_PROFILE`(0x0C)=2 → `SECURITY_LEVEL`(0x0D)=5 → `APPLICATION_ZDO_FLAGS`(0x2A)=0x0003 — a NAK logs WARN `zigbee.ncp_config_rejected: id= status=` and throws `EzspCommandException` (unchecked → TRANSIENT at the supervisor, the TCJ §A-6 precedent; the window never opens). SUPPORTING best-effort tail (`KEY_TABLE_SIZE` 0x1E=12 · `ADDRESS_TABLE_SIZE` 0x02=16 · `TRUST_CENTER_ADDRESS_CACHE_SIZE` 0x38=2 · `MAX_END_DEVICE_CHILDREN` 0x11=32 · `INDIRECT_TRANSMISSION_TIMEOUT` 0x12=7680 · `PACKET_BUFFER_COUNT` 0x01=64 · `MULTICAST_TABLE_SIZE` 0x06=16) — a NAK logs ONE WARN `zigbee.ncp_config_skipped` and continues (7.4.x self-manages some and legitimately rejects). §1.2 read-back (the decisive instrument): the three REQUIRED ids re-read via `getConfigurationValue`; ONE INFO `zigbee.ncp_configured: zdo_flags=0x{} stack_profile={} security_level={}` logs the values THE NCP REPORTS; a mismatch logs WARN `zigbee.ncp_config_readback_mismatch: id= wrote= read=` and throws `IllegalStateException` (accepted-but-not-applied is a lie). All ids/values in the BENCH-VERIFY constants block (`FRAME_SET_CONFIGURATION_VALUE 0x0053` / `FRAME_GET_CONFIGURATION_VALUE 0x0052`; encoding configId u8 + value u16 LE; response status via the `decodeStatus` width seam, value at `statusWidthBytes()` offset).
- **`FakeNcp` (testFixtures) — the built-in config model:** when the scripted handler returns an EMPTY list for a frame (unscripted), the fake NCP itself answers 0x0053 (status 0x00, remembering configId→value) and 0x0052 (status 0x00 + the last-written value u16 LE) — read-backs echo writes the way a live NCP that applied them would. An explicit scripted response (e.g. a NAK) always wins; a `null` handler return still simulates silence; RST clears the store (reset-to-defaults). This keeps every per-class responder (PermitJoin/TCJ/ChannelPin/ProductionTransport/Interview/rig) UNTOUCHED while their `startSession()` ladders now carry the batch.
- **`EzspProtocolTest` count adaptations (intent-preserving):** `hasSize(1)`-style total-command counts replaced — no-renegotiation now asserted as exactly-one LEGACY version frame; exactly-once as second-start-adds-nothing (which now ALSO pins config-not-rewritten); the single-in-flight test counts its 8 nops instead of totals.

### M9.4-NCFG Gotchas

- **Session-start frame arithmetic changed:** every successful `startSession()` now emits 13 extra extended commands (10 writes + 3 read-backs) between the version exchange and the first caller command. Any future test asserting `receivedEzspCommands()` TOTALS (or "first extended command is X") must account for the batch — count per-frame-id or filter legacy frames instead.
- **A config failure leaves `negotiatedVersion` SET** (config runs after the negotiation state pins, per the instruction's "after version negotiation, before it returns" placement): a hypothetical re-call of `startSession()` on that same instance would no-op and skip config. No REAL path does this — a production run() failure surfaces to the supervisor (fresh adapter on restart) and the reopen ladder always `resetSession()`s first — but a new driver added later must preserve that discipline (reset-before-restart).
- **`zigbee.ncp_configured` logs the READ-BACK values** — on the bench, record the line VERBATIM; `zdo_flags=0x3 stack_profile=2 security_level=5` is the expected healthy shape. A mismatching line never appears (mismatch throws); a differing-but-consistent read-back on silicon means the id/value table needs its one-constant correction.

---

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2). Phase 3 implementation is active — M2.5 `SqliteEventStore` landed 2026-04-11 (commit `5279e7a`), next milestone M2.6 + M2.7 (combined) pending from Nick.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: dispatch on `@EventType` string, not sealed-switch
- **D-04** — *Clock must be injected*: frame-timing, retry schedules, and join-window expiry all use injected `Clock`
- **D-05** — *`@EventType` on every event record*: integration-namespaced events like `zigbee.device_announce` carry `@EventType` and resolve to `[SYSTEM]` category by INV-PD-07 fallback unless added to `EventCategoryMapping`

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for recent Phase 3 pattern discoveries (especially the 2026-04-10 entries on `NO_DIRECT_TIME_ACCESS` and JUnit 5 `@BeforeEach` ordering).

# integration-zigbee — `com.homesynapse.integration.zigbee` — M9.2 IMPLEMENTED — transport/EZSP layer live (EZSP-first per DP-C) — Zigbee 3.0 coordinator, MVP protocol adapter, IEEEAddress (raw long, NOT ULID)

> **Type count (M9.2, 2026-07-03):** 38 pre-existing public Phase-2 types + 21 new M9.2 implementation types (ALL package-private — zero new public types) = 59 type files + module-info. Correction to the Phase-2 note below: `package-info.java` does NOT exist in the tree (the "39 files including package-info" claim counted module-info; package-info was never created). First test tree created in M9.2: 13 files (10 test classes + 3 fakes).

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

    exports com.homesynapse.integration.zigbee;
}
```

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

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2). Phase 3 implementation is active — M2.5 `SqliteEventStore` landed 2026-04-11 (commit `5279e7a`), next milestone M2.6 + M2.7 (combined) pending from Nick.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: dispatch on `@EventType` string, not sealed-switch
- **D-04** — *Clock must be injected*: frame-timing, retry schedules, and join-window expiry all use injected `Clock`
- **D-05** — *`@EventType` on every event record*: integration-namespaced events like `zigbee.device_announce` carry `@EventType` and resolve to `[SYSTEM]` category by INV-PD-07 fallback unless added to `EventCategoryMapping`

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for recent Phase 3 pattern discoveries (especially the 2026-04-10 entries on `NO_DIRECT_TIME_ACCESS` and JUnit 5 `@BeforeEach` ordering).

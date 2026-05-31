# value-model — `com.homesynapse.value` — 10 types — Self-contained `AttributeValue` sealed hierarchy + `AttributeType` (leaf value model)

## Purpose

The value-model module is a **leaf** module holding the self-contained `AttributeValue` value hierarchy and its `AttributeType` classifier. These are the typed representation of attribute values that flow through events, device state, the state store, and the persistence codec. The module requires nothing but `java.base` — both `com.homesynapse.event` and `com.homesynapse.device` depend on it as **peers over a shared value contract**, which is what lets a `com.homesynapse.event` record carry an `AttributeValue` without forcing an `event → device` edge.

**Relocated from `com.homesynapse.device` as of M4.0b-4a (AMD-52 §11 erratum; AttributeValue Module Relocation Design Note, 2026-05-31).** This is a pure, behavior-preserving move — the 10 types' public contracts (records, fields, `sealed`/`permits`, `QuantityValue` canonicalize-at-construction, `AttributeType` constants and order, Javadoc) are byte-for-byte identical to their pre-move form in device-model; **only the `package` and owning module changed.** The relocation breaks the event↔device JPMS cycle that AMD-52's typed `StateChangedEvent` payload would otherwise create, and unblocks M4.0b-4b. The AMD-47 contracts that established these types travel with them unchanged (see Constraints).

## Design Doc Reference

- **AMD-47** — established the `AttributeValue`/variant/`AttributeType` hierarchy (8-variant sealing, canonicalize-at-construction, Degraded non-declarable, ArrayValue full-replacement). The *contract* is governed here; AMD-47's original *placement* in device-model is superseded by this relocation (a one-line forward-note is on AMD-47).
- **AMD-52 §11 erratum** — authorizes this relocation; confirms no ratified AMD-52 fork is reopened.
- **`homesynapse-core-docs/design/2026-05-31_AttributeValue_Module_Relocation_Design_Note.md`** — §2 (the `value` leaf decision, not platform-api), §3 (the minimal 10-type move; `AttributeSchema`/`AttributeValueUpcaster` stay in device-model), §4 (the `requires` graph), §8 (coherence).

## JPMS Module

```
module com.homesynapse.value {
    exports com.homesynapse.value;
}
```

A true leaf: **no `requires` beyond the implicit `java.base`.** The value types reference only `java.*` types and each other (+ `AttributeType`). `com.homesynapse.event` and `com.homesynapse.device` both declare `requires transitive com.homesynapse.value` (they re-export the types on their public APIs); `com.homesynapse.state` likewise; `com.homesynapse.persistence` and `com.homesynapse.automation` declare plain `requires com.homesynapse.value` (internal/Javadoc use only).

## Package Structure

- **`com.homesynapse.value`** — All 10 types in a single flat package: the `AttributeValue` sealed interface, its 8 permitted variant records, and the `AttributeType` enum.

## Complete Type Inventory

### Sealed AttributeValue Hierarchy (1 sealed interface + 8 records — AMD-47)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `AttributeValue` | sealed interface (permits 8 types) | Typed representation of attribute values | Methods: `rawValue()` → `Object`, `attributeType()` → `AttributeType`. Expanded 5→8 by AMD-47. |
| `BooleanValue` | record(`boolean value`) implements `AttributeValue` | Boolean attribute value | Returns `AttributeType.BOOLEAN`. |
| `IntValue` | record(`long value`) implements `AttributeValue` | Integer attribute value (uses `long` for full range) | Returns `AttributeType.INT`. |
| `FloatValue` | record(`double value`) implements `AttributeValue` | Floating-point attribute value | Returns `AttributeType.FLOAT`. |
| `StringValue` | record(`String value`) implements `AttributeValue` | Free-form string attribute value | Non-null validation. Returns `AttributeType.STRING`. |
| `EnumValue` | record(`String value`) implements `AttributeValue` | Constrained enum string attribute value | Non-null validation. Returns `AttributeType.ENUM`. |
| `QuantityValue` | record(`double value, String unit`) implements `AttributeValue` — **AMD-47** | Physical quantity carrying a (value, unit) pair, **canonical-normalized at construction** | Compact ctor: hand-rolled, table-driven, deterministic conversion to the dimension's canonical unit (temperature `°C`, power `W`, energy `Wh`, illuminance `lux`, percent `%`); no units library (AMD-47-INV-03). Fail-closed: null unit → NPE; blank unit / non-finite magnitude / unrecognised unit → IAE. `rawValue()` → canonical magnitude boxed as `Double` (never null). Returns `AttributeType.QUANTITY`. |
| `ArrayValue` | record(`List<AttributeValue> elements`) implements `AttributeValue` — **AMD-47** | Ordered, unmodifiable, **full-replacement** list (no delta/patch — bounded-window-advancer compatible, AMD-47-INV-05) | Compact ctor: `List.copyOf(elements)` (rejects null list + null elements; empty permitted; unmodifiable). `rawValue()` → the unmodifiable `List<AttributeValue>` (never null). Returns `AttributeType.ARRAY`. Element homogeneity is a schema-level concern (future validator), not enforced here; nesting permitted by type, discouraged by schema. |
| `DegradedAttributeValue` | record(`String originalTypeName, String rawForm, String failureReason`) implements `AttributeValue` — **AMD-47** | Subtype-level upcast-failure fallback (the `AttributeValue` analogue of `DegradedEvent`); lenient-mode/forensic artifact only | Compact ctor mirrors `DegradedEvent`: all three non-null; `originalTypeName`/`failureReason` non-blank; **blank `rawForm` permitted**. `rawValue()` → `rawForm`. Returns sentinel `AttributeType.DEGRADED`. Never written to canonical state under strict mode (AMD-47-INV-04). |

### Enum

| Type | Kind | Purpose | Values |
|---|---|---|---|
| `AttributeType` | enum | Primitive/value data type classifier for attribute values | BOOLEAN, INT, FLOAT, STRING, ENUM, **QUANTITY, ARRAY, DEGRADED** (last three added by AMD-47; declared in that order). `QUANTITY`/`ARRAY` are schema-declarable; `DEGRADED` is a sentinel — never declarable in an `AttributeSchema` (AMD-47-INV-04, enforced at `AttributeSchema` construction in device-model). |

**Total: 10 public types + 1 module-info.java = 11 Java files.** (No `package-info.java`; no test source set — the value types' unit tests remain in `core/device-model/src/test` per the M4.0b-4a spike scope.)

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| *(none)* | `com.homesynapse.value` is a leaf — `requires` nothing but the implicit `java.base`. | The value types reference only `java.lang`, `java.util.List`, `java.util.Objects`, `java.util.Map`/`HashMap`, `java.util.function.DoubleUnaryOperator`, and each other (+ `AttributeType`). |

## Consumers

| Module | Edge | Uses |
|---|---|---|
| **event-model** (`com.homesynapse.event`) | `requires transitive` (re-exports on its public API at M4.0b-4b's typed `StateChangedEvent` payload; forward-prep at 4a) | `AttributeValue` (the typed payload field, M4.0b-4b). |
| **device-model** (`com.homesynapse.device`) | `requires transitive` (re-exports throughout its API) | `AttributeValue`, `AttributeType`, `DegradedAttributeValue` (in `AttributeSchema`, `AttributeValueUpcaster`, capabilities, `Expectation` hierarchy, `StandardCapabilities`). |
| **state-store** (`com.homesynapse.state`) | `requires transitive` (`EntityState` exposes `Map<String, AttributeValue>`) | `AttributeValue`, `StringValue`, and the full hierarchy in the comparator/reconstructor (`AttributeValueComparator`, `StructuralAttributeValueComparator`, `AttributeValueReconstructor`, `ProductionDerivationRule`). |
| **persistence** (`com.homesynapse.persistence`) | `requires` (plain — internal only) | `AttributeValue`, `StringValue` (package-private `CheckpointSerializer`; the AMD-52 codec is M4.0b-4b). |
| **automation** (`com.homesynapse.automation`) | `requires` (plain — Javadoc `{@link}` only) | `AttributeValue` (referenced in `PendingCommand`'s Javadoc via `Expectation#evaluate`). |
| **rest-api** (`api.rest`) | test-only — `testImplementation(":core:value-model")`, no `module-info` edge | `AttributeValue`, `EnumValue`, `StringValue` in four endpoint tests (classpath-compiled). |

## Constraints — the AMD-47 contracts that travel with the types

| Constraint | Description |
|---|---|
| **AMD-47-INV-01** | `AttributeValue` sealing stays total — `permits` is exactly the 8 variants `{BooleanValue, IntValue, FloatValue, StringValue, EnumValue, QuantityValue, ArrayValue, DegradedAttributeValue}`; every exhaustive `switch` handles all eight, no `default`. (Also registered in `Architecture_Invariants_v1.md` §20.) |
| **AMD-47-INV-03** | `QuantityValue` normalizes to its canonical unit at construction via a pure, hand-rolled, deterministic, table-driven conversion — no units library, no I/O, no locale/clock dependence; same-dimension values are magnitude-comparable on canonical `value`; null/blank/non-finite/unrecognised unit fails closed (NPE/IAE). |
| **AMD-47-INV-04** | `AttributeType.DEGRADED` is never schema-declarable. `DegradedAttributeValue` preserves its fields without mutation and is never written to canonical state under strict mode. The structural enforcement (the compact-ctor guard rejecting a DEGRADED-typed schema) lives in `AttributeSchema`, which **stays in device-model** — so that half of INV-04 is owned there; the value-type field-preservation half rides here. |
| **AMD-47-INV-05** | `ArrayValue` is full-replacement — no delta/patch semantics (bounded-window-advancer compatible); `elements` is an unmodifiable, null-free, possibly-empty `List<AttributeValue>`. |
| **Jackson-isolation HARD RULE** | The value types carry **no Jackson annotation** (before or after the move). Jackson stays confined to `core/persistence`; ArchUnit `no com.fasterxml.jackson.* outside persistence` is unaffected. |

## Sealed Hierarchy

```
sealed interface AttributeValue
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
**Note:** there are **no** production exhaustive `switch`es over `AttributeValue` today — `CheckpointSerializer` (persistence) and `EnumTransition` (device-model) are `instanceof` patterns with fallbacks (AMD-47 §7.4). The typed materialization/codec switch arrives at M4.0b-4b.

## Gotchas

**GOTCHA: this is a leaf — keep it one.** `com.homesynapse.value` must `requires` nothing but `java.base`. Do NOT add a `requires com.homesynapse.device`/`.event`/`.platform` here — the whole point of the relocation is that both event and device sit *above* value. If a value type ever needs a device/event/platform type, the move was wrong; STOP and escalate (it would re-introduce a cycle).

**GOTCHA: `AttributeSchema` and `AttributeValueUpcaster` did NOT move.** They stay in `com.homesynapse.device` (they are device/capability metadata + the stored-value-migration SPI, not pure value types). They now `import com.homesynapse.value.{AttributeType, DegradedAttributeValue, AttributeValue}`. Do not "follow" the value types here.

**GOTCHA: unit fields are `String` canonical-unit symbols — do NOT add a units library.** `QuantityValue.unit` is a plain `String`, permanently (REC-93 / AMD-47-INV-03). Normalization is the hand-rolled, table-driven, deterministic catalogue in `QuantityValue` (canonical-at-construction, fail-closed on unknown units). No `javax.measure`/`indriya`/uom dependency; match units by exact string equality.

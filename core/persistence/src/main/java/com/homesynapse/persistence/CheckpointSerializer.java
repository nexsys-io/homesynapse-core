/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityState;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Jackson-based serializer for State Projection checkpoint data (Doc 03 §3.6,
 * AMD-41 §3.2.4).
 *
 * <p>Replaces the M3.5a {@code byte[0]} stub: {@link SqliteStateStore} writes
 * a full snapshot of the materialized state map on checkpoint and rebuilds
 * the in-memory map on startup. The serialized form embeds the
 * {@code projectionVersion} alongside the state map and reconciliation
 * metadata (AMD-41 §3.2.4), making it the authoritative source for the
 * version-mismatch reconciliation check.</p>
 *
 * <h2>JSON shape</h2>
 *
 * <pre>{@code
 * {
 *   "projectionVersion": 2,
 *   "reconciledAt": "2026-05-18T00:00:00Z" | null,
 *   "reconciledFromVersion": 1 | null,
 *   "reconciledToVersion": 2 | null,
 *   "stateMap": {
 *     "<entityId Crockford>": {
 *       "entityId": "<Crockford>",
 *       "attributes": { "<key>": {"t":"<AttributeType>","v":...} | null, ... },
 *       "availability": "AVAILABLE" | "UNAVAILABLE" | "UNKNOWN",
 *       "stateVersion": 42,
 *       "lastChanged": "2026-01-01T00:00:00Z",
 *       "lastUpdated": "2026-01-01T00:00:00Z",
 *       "lastReported": "2026-01-01T00:00:00Z",
 *       "staleAfter": "2026-01-01T00:10:00Z" | null,
 *       "stale": false
 *     }
 *   }
 * }
 * }</pre>
 *
 * <h2>Attribute representation (typed envelope, AMD-52 / AMD-52-INV-06)</h2>
 *
 * <p>Attributes serialize as a {@code Map<String, AttributeValue>}, each value written by the
 * shared {@link AttributeValueSerializer} as the AMD-52 tagged-union envelope
 * ({@code {"t":"<AttributeType>","v":...}}). This is the typed-envelope extension this class's
 * original Javadoc anticipated — it fulfils the S2 materialization surface so a typed value the
 * projection writes ({@code FloatValue}, {@code QuantityValue}, …) round-trips as its real
 * variant rather than being flattened to a string. The supplied {@link ObjectMapper} MUST
 * register the {@code AttributeValue} codec (i.e. include {@code PersistenceJacksonModule}) so
 * the value type resolves. The deserializer rebuilds {@code Map<String, AttributeValue>}
 * preserving {@code null} entries.</p>
 *
 * <h2>Null handling</h2>
 *
 * <p>{@link EntityState#staleAfter()} is nullable, and an attribute value may be {@code null}
 * (a schema-declared attribute that has never received a report). The supplied
 * {@link ObjectMapper} MUST be configured to preserve null values in serialized output (use
 * {@code JsonInclude.Include.ALWAYS} or do not configure a {@code NON_NULL} default) so that
 * null {@code staleAfter} and null attribute values survive the round trip. Both
 * {@code toSerializable} and {@code fromSerializable} build the attribute map via a
 * {@code HashMap}/{@code LinkedHashMap} copy (never {@code Map.copyOf}) so null values inside
 * the map do not trigger {@code NullPointerException}.</p>
 *
 * <h2>Edge cases</h2>
 *
 * <ul>
 *   <li>{@code byte[]} of length 0 → empty map, {@code projectionVersion=0},
 *       nulls for reconciliation metadata. Equivalent to "no prior
 *       checkpoint" / replay-from-zero.</li>
 *   <li>Deserialization failure (malformed JSON, schema drift) →
 *       {@link IllegalStateException}. The projection's lazy-init catches
 *       this and falls back to clear-and-replay.</li>
 * </ul>
 *
 * <p>Package-private — the seam to outside-module callers is
 * {@link SqliteStateStore}'s {@link SqliteStateStore#serialize()} method,
 * which produces the {@code byte[]} written to the view checkpoint store.</p>
 *
 * @see SqliteStateStore
 * @see com.homesynapse.state.ViewCheckpointStore
 */
final class CheckpointSerializer {

    private final ObjectMapper objectMapper;

    /**
     * Constructs a serializer over the given Jackson {@link ObjectMapper}.
     *
     * <p>The mapper must:</p>
     * <ul>
     *   <li>Handle {@link Instant} round-trip via the
     *       {@code jackson-datatype-jsr310} module
     *       ({@code JavaTimeModule}).</li>
     *   <li>Disable {@code SerializationFeature.WRITE_DATES_AS_TIMESTAMPS}
     *       so timestamps serialize as ISO-8601 strings.</li>
     *   <li>Use a {@code JsonInclude} setting that preserves nulls in the
     *       serialized output ({@code Include.ALWAYS} or none). The
     *       {@code Include.NON_NULL} default of
     *       {@code PersistenceObjectMapper} drops {@code null} attribute
     *       values and nullable {@code staleAfter} — both required by the
     *       checkpoint round-trip contract.</li>
     * </ul>
     *
     * @param objectMapper the configured Jackson mapper; never {@code null}
     * @throws NullPointerException if {@code objectMapper} is {@code null}
     */
    CheckpointSerializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper,
                "objectMapper must not be null");
    }

    /**
     * Serializes the given state map and projection metadata into a
     * checkpoint {@code byte[]} payload.
     *
     * <p>Iteration order of the resulting JSON map mirrors the iteration
     * order of {@code stateMap} — for deterministic test output, pass a
     * {@link LinkedHashMap} or a sorted snapshot.</p>
     *
     * @param stateMap              the materialized entity state map; never
     *                              {@code null} (may be empty)
     * @param projectionVersion     the running projection's code version
     * @param reconciledAt          when reconciliation last ran; may be
     *                              {@code null}
     * @param reconciledFromVersion the prior checkpoint's projection version;
     *                              may be {@code null}
     * @param reconciledToVersion   the new projection version after
     *                              reconciliation; may be {@code null}
     * @return the serialized checkpoint bytes
     * @throws NullPointerException if {@code stateMap} is {@code null}
     * @throws RuntimeException     if Jackson fails to serialize
     */
    byte[] serialize(
            Map<EntityId, EntityState> stateMap,
            int projectionVersion,
            Instant reconciledAt,
            Integer reconciledFromVersion,
            Integer reconciledToVersion) {
        Objects.requireNonNull(stateMap, "stateMap must not be null");

        Map<String, SerializableEntityState> serializedMap =
                new LinkedHashMap<>(stateMap.size());
        for (Map.Entry<EntityId, EntityState> entry : stateMap.entrySet()) {
            serializedMap.put(
                    entry.getKey().toString(),
                    toSerializable(entry.getValue()));
        }

        SerializableCheckpointData payload = new SerializableCheckpointData(
                projectionVersion,
                reconciledAt,
                reconciledFromVersion,
                reconciledToVersion,
                serializedMap);

        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Checkpoint serialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Deserializes the given checkpoint bytes into a {@link CheckpointData}
     * wrapper.
     *
     * <p>An empty {@code byte[]} (or {@code null}) yields a
     * {@code CheckpointData} with an empty map, {@code projectionVersion = 0},
     * and null reconciliation metadata. This is the "no prior checkpoint"
     * sentinel matching the M3.5a stub's {@code byte[0]} default.</p>
     *
     * @param data the checkpoint bytes; may be {@code null} or empty
     * @return the deserialized checkpoint
     * @throws IllegalStateException if the bytes are non-empty but cannot be
     *                               parsed (caller falls back to clear-and-replay)
     */
    CheckpointData deserialize(byte[] data) {
        if (data == null || data.length == 0) {
            return CheckpointData.empty();
        }

        SerializableCheckpointData parsed;
        try {
            parsed = objectMapper.readValue(data,
                    new TypeReference<SerializableCheckpointData>() { });
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Checkpoint deserialization failed: " + e.getMessage(), e);
        }

        Map<EntityId, EntityState> rebuilt = new LinkedHashMap<>();
        if (parsed.stateMap() != null) {
            for (Map.Entry<String, SerializableEntityState> entry
                    : parsed.stateMap().entrySet()) {
                EntityId id = EntityId.parse(entry.getKey());
                rebuilt.put(id, fromSerializable(entry.getValue(), id));
            }
        }

        return new CheckpointData(
                rebuilt,
                parsed.projectionVersion(),
                parsed.reconciledAt(),
                parsed.reconciledFromVersion(),
                parsed.reconciledToVersion());
    }

    // ──────────────────────────────────────────────────────────────────
    // Conversion helpers
    // ──────────────────────────────────────────────────────────────────

    private static SerializableEntityState toSerializable(EntityState state) {
        // Null-preserving copy of the typed attributes (AMD-52 S2). LinkedHashMap, NOT
        // Map.copyOf — the latter rejects null values (a schema-declared attribute that has
        // never reported). Each value serializes through the AttributeValue typed envelope.
        Map<String, AttributeValue> attrs = new LinkedHashMap<>(state.attributes());

        return new SerializableEntityState(
                state.entityId().toString(),
                attrs,
                state.availability().name(),
                state.stateVersion(),
                state.lastChanged(),
                state.lastUpdated(),
                state.lastReported(),
                state.staleAfter(),
                state.stale());
    }

    private static EntityState fromSerializable(
            SerializableEntityState s, EntityId entityId) {
        // Rebuild attributes — DO NOT use Map.copyOf (throws on null values). The codec
        // deserialized each non-null value to its real AttributeValue variant.
        Map<String, AttributeValue> attrs = new HashMap<>();
        if (s.attributes() != null) {
            attrs.putAll(s.attributes());
        }

        return new EntityState(
                entityId,
                attrs,
                Availability.valueOf(s.availability()),
                s.stateVersion(),
                s.lastChanged(),
                s.lastUpdated(),
                s.lastReported(),
                s.staleAfter(),
                s.stale());
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal serialization records
    // ──────────────────────────────────────────────────────────────────

    /**
     * Internal Jackson-serializable shape of one entity's state. Attribute values are the
     * typed {@link AttributeValue} hierarchy, (de)serialized through the AMD-52 tagged-union
     * codec ({@link AttributeValueSerializer}/{@link AttributeValueDeserializer}); null values
     * are preserved (the {@code ALWAYS}-inclusion mapper requirement).
     */
    record SerializableEntityState(
            String entityId,
            Map<String, AttributeValue> attributes,
            String availability,
            long stateVersion,
            Instant lastChanged,
            Instant lastUpdated,
            Instant lastReported,
            Instant staleAfter,
            boolean stale
    ) { }

    /**
     * Internal Jackson-serializable shape of a checkpoint payload. The
     * top-level record carries both projection metadata (version,
     * reconciliation history) and the state map.
     */
    record SerializableCheckpointData(
            int projectionVersion,
            Instant reconciledAt,
            Integer reconciledFromVersion,
            Integer reconciledToVersion,
            Map<String, SerializableEntityState> stateMap
    ) { }
}

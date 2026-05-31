/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.QuantityValue;
import com.homesynapse.value.StringValue;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CheckpointSerializer} — Jackson round-trip semantics
 * for the State Projection checkpoint payload.
 */
@DisplayName("CheckpointSerializer")
final class CheckpointSerializerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T00:01:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T00:02:00Z");
    private static final Instant T_STALE = Instant.parse("2026-01-01T00:10:00Z");
    private static final Instant T_RECONCILED = Instant.parse("2026-05-18T00:00:00Z");

    /** Two arbitrary entity IDs used across the suite. */
    private static final EntityId ENT_A = new EntityId(
            new Ulid(0x01__00_00_00_00_00_00_00L, 0x00_00_00_00_00_00_00_01L));
    private static final EntityId ENT_B = new EntityId(
            new Ulid(0x02__00_00_00_00_00_00_00L, 0x00_00_00_00_00_00_00_02L));

    private CheckpointSerializer serializer;

    /** Required no-arg constructor for {@code -Xlint:all -Werror} builds. */
    CheckpointSerializerTest() {
        // No-op.
    }

    @BeforeEach
    void setUp() {
        // ALWAYS inclusion is required so null staleAfter and null attribute
        // values survive the round trip. NON_NULL inclusion (the persistence
        // module's default for event payloads) would silently drop them.
        // PersistenceJacksonModule registers the AMD-52 AttributeValue typed-envelope
        // codec, which the typed attribute representation depends on (mirrors the
        // composition root's checkpoint mapper: events mapper + Include.ALWAYS).
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(new PersistenceJacksonModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.ALWAYS)
                .build();
        serializer = new CheckpointSerializer(mapper);
    }

    // ──────────────────────────────────────────────────────────────────
    // Round-trip
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("serialize / deserialize round-trips a multi-entity state map")
    void serializeDeserializeRoundTrip() {
        Map<EntityId, EntityState> input = new LinkedHashMap<>();
        input.put(ENT_A, entityWithAttrs(ENT_A,
                Map.of("on", new StringValue("true"), "brightness", new StringValue("80")),
                Availability.AVAILABLE, 5L, T0, T1, T2, T_STALE, false));
        input.put(ENT_B, entityWithAttrs(ENT_B,
                Map.of("temperature_c", new StringValue("21.5")),
                Availability.UNAVAILABLE, 3L, T0, T1, T2, null, false));

        byte[] bytes = serializer.serialize(input, 1, null, null, null);
        CheckpointData parsed = serializer.deserialize(bytes);

        assertThat(parsed.stateMap()).hasSize(2);
        assertThat(parsed.stateMap()).containsKeys(ENT_A, ENT_B);

        EntityState a = parsed.stateMap().get(ENT_A);
        assertThat(a.attributes()).containsKeys("on", "brightness");
        assertThat(((StringValue) a.attributes().get("on")).value()).isEqualTo("true");
        assertThat(((StringValue) a.attributes().get("brightness")).value()).isEqualTo("80");
        assertThat(a.availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(a.stateVersion()).isEqualTo(5L);
        assertThat(a.lastChanged()).isEqualTo(T0);
        assertThat(a.staleAfter()).isEqualTo(T_STALE);

        EntityState b = parsed.stateMap().get(ENT_B);
        assertThat(b.staleAfter()).isNull();
    }

    @Test
    @DisplayName("null staleAfter is preserved across the round trip")
    void nullStaleAfterPreserved() {
        Map<EntityId, EntityState> input = Map.of(ENT_A,
                entityWithAttrs(ENT_A, Map.of(), Availability.UNKNOWN,
                        1L, T0, T0, T0, null, false));

        byte[] bytes = serializer.serialize(input, 1, null, null, null);
        CheckpointData parsed = serializer.deserialize(bytes);

        assertThat(parsed.stateMap().get(ENT_A).staleAfter()).isNull();
    }

    @Test
    @DisplayName("AMD-52 §5#7: mixed typed variants + a null attribute value + null staleAfter round-trip equal")
    void typedEnvelopeMixedVariantsRoundTrip() {
        Map<String, AttributeValue> attrs = new HashMap<>();
        attrs.put("on", new BooleanValue(true));
        attrs.put("brightness", new IntValue(80));
        attrs.put("temperature_c", new FloatValue(21.5));
        attrs.put("power", new QuantityValue(1500.0, "W"));
        attrs.put("firmware", new StringValue("1.2.3"));
        attrs.put("never_reported", null); // schema-declared but never reported

        Map<EntityId, EntityState> input = Map.of(ENT_A,
                entityWithAttrs(ENT_A, attrs, Availability.AVAILABLE,
                        9L, T0, T1, T2, null, false));

        byte[] bytes = serializer.serialize(input, 4, null, null, null);
        CheckpointData parsed = serializer.deserialize(bytes);

        Map<String, AttributeValue> rt = parsed.stateMap().get(ENT_A).attributes();
        assertThat(rt.get("on"))
                .as("typed variant survives round-trip as its real variant, not a StringValue")
                .isEqualTo(new BooleanValue(true));
        assertThat(rt.get("brightness")).isEqualTo(new IntValue(80));
        assertThat(rt.get("temperature_c")).isEqualTo(new FloatValue(21.5));
        assertThat(rt.get("power")).isEqualTo(new QuantityValue(1500.0, "W"));
        assertThat(rt.get("firmware")).isEqualTo(new StringValue("1.2.3"));
        assertThat(rt).containsKey("never_reported");
        assertThat(rt.get("never_reported"))
                .as("null attribute value preserved (ALWAYS inclusion)")
                .isNull();
        assertThat(parsed.stateMap().get(ENT_A).staleAfter())
                .as("null staleAfter preserved")
                .isNull();
    }

    @Test
    @DisplayName("null attribute values are preserved (not dropped) across the round trip")
    void nullAttributeValuesPreserved() {
        Map<String, AttributeValue> attrs = new HashMap<>();
        attrs.put("on", new StringValue("true"));
        attrs.put("brightness", null);

        Map<EntityId, EntityState> input = Map.of(ENT_A,
                entityWithAttrs(ENT_A, attrs, Availability.AVAILABLE,
                        1L, T0, T0, T0, null, false));

        byte[] bytes = serializer.serialize(input, 1, null, null, null);
        CheckpointData parsed = serializer.deserialize(bytes);

        Map<String, AttributeValue> roundTripped = parsed.stateMap().get(ENT_A).attributes();
        assertThat(roundTripped).containsKeys("on", "brightness");
        assertThat(roundTripped.get("on")).isNotNull();
        assertThat(roundTripped.get("brightness")).isNull();
    }

    @Test
    @DisplayName("empty state map serializes and round-trips to an empty map")
    void emptyStateMap() {
        byte[] bytes = serializer.serialize(Map.of(), 7, null, null, null);
        CheckpointData parsed = serializer.deserialize(bytes);

        assertThat(parsed.stateMap()).isEmpty();
        assertThat(parsed.projectionVersion()).isEqualTo(7);
    }

    @Test
    @DisplayName("byte[0] returns empty CheckpointData with projectionVersion=0")
    void emptyByteArrayReturnsEmptyCheckpointData() {
        CheckpointData parsed = serializer.deserialize(new byte[0]);

        assertThat(parsed.stateMap()).isEmpty();
        assertThat(parsed.projectionVersion()).isEqualTo(0);
        assertThat(parsed.reconciledAt()).isNull();
        assertThat(parsed.reconciledFromVersion()).isNull();
        assertThat(parsed.reconciledToVersion()).isNull();
    }

    @Test
    @DisplayName("null byte[] returns empty CheckpointData")
    void nullByteArrayReturnsEmptyCheckpointData() {
        CheckpointData parsed = serializer.deserialize(null);

        assertThat(parsed.stateMap()).isEmpty();
        assertThat(parsed.projectionVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("projectionVersion is preserved across the round trip")
    void projectionVersionPreserved() {
        byte[] bytes = serializer.serialize(Map.of(), 7, null, null, null);
        CheckpointData parsed = serializer.deserialize(bytes);

        assertThat(parsed.projectionVersion()).isEqualTo(7);
    }

    @Test
    @DisplayName("reconciliation metadata round-trips when populated")
    void reconciliationMetadataPreserved() {
        byte[] bytes = serializer.serialize(Map.of(), 2,
                T_RECONCILED, 1, 2);
        CheckpointData parsed = serializer.deserialize(bytes);

        assertThat(parsed.reconciledAt()).isEqualTo(T_RECONCILED);
        assertThat(parsed.reconciledFromVersion()).isEqualTo(1);
        assertThat(parsed.reconciledToVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("reconciliation metadata is null when absent")
    void reconciliationMetadataNullWhenAbsent() {
        byte[] bytes = serializer.serialize(Map.of(), 1, null, null, null);
        CheckpointData parsed = serializer.deserialize(bytes);

        assertThat(parsed.reconciledAt()).isNull();
        assertThat(parsed.reconciledFromVersion()).isNull();
        assertThat(parsed.reconciledToVersion()).isNull();
    }

    @Test
    @DisplayName("1000-entity state map round-trips correctly")
    void largeStateMap() {
        Map<EntityId, EntityState> input = new LinkedHashMap<>();
        for (int i = 0; i < 1000; i++) {
            EntityId id = new EntityId(new Ulid(0x05L, i));
            input.put(id, entityWithAttrs(id,
                    Map.of("v", new StringValue(Integer.toString(i))),
                    Availability.AVAILABLE, i, T0, T0, T0, null, false));
        }

        byte[] bytes = serializer.serialize(input, 1, null, null, null);
        CheckpointData parsed = serializer.deserialize(bytes);

        assertThat(parsed.stateMap()).hasSize(1000);
        // Spot-check a representative entry.
        EntityId entry42Id = new EntityId(new Ulid(0x05L, 42L));
        EntityState entry42 = parsed.stateMap().get(entry42Id);
        assertThat(entry42).isNotNull();
        assertThat(((StringValue) entry42.attributes().get("v")).value()).isEqualTo("42");
    }

    @Test
    @DisplayName("corrupt byte[] input throws IllegalStateException")
    void deserializationFailureThrowsIllegalState() {
        byte[] garbage = new byte[]{'{', 'n', 'o', 't', 'j', 's', 'o', 'n'};

        assertThatThrownBy(() -> serializer.deserialize(garbage))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("constructor rejects null ObjectMapper")
    void constructorRejectsNullMapper() {
        assertThatThrownBy(() -> new CheckpointSerializer(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static EntityState entityWithAttrs(
            EntityId id,
            Map<String, AttributeValue> attrs,
            Availability availability,
            long stateVersion,
            Instant lastChanged,
            Instant lastUpdated,
            Instant lastReported,
            Instant staleAfter,
            boolean stale) {
        return new EntityState(
                id, attrs, availability, stateVersion,
                lastChanged, lastUpdated, lastReported,
                staleAfter, stale);
    }
}

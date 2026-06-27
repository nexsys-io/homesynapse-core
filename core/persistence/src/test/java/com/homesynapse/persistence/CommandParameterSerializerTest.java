/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.StringValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the M7.4b {@link CommandParameterSerializer} — the persistence-backed
 * {@code command_issued.parameters} serializer exposed via
 * {@link PersistenceFactory#commandParameterSerializer()}.
 *
 * <p>Exercises the serializer directly against a configuration-identical persistence
 * {@link ObjectMapper} (no SQLite boot needed — the same lightweight pattern as
 * {@code AttributeValueSerdeTest}), proving production serialization handles value-model
 * {@code AttributeValue}s (the M7.4b forward-risk closer), not a stub.</p>
 */
@DisplayName("CommandParameterSerializer (M7.4b)")
final class CommandParameterSerializerTest {

    private final Function<Map<String, Object>, String> serializer = new CommandParameterSerializer();

    /** Independent persistence mapper used to read the serializer's output back. */
    private final ObjectMapper mapper = PersistenceObjectMapper.create();

    CommandParameterSerializerTest() {
        // Explicit constructor for -Xlint:all -Werror.
    }

    @Test
    @DisplayName("a map mixing literals and an AttributeValue round-trips faithfully through the "
            + "persistence mapper")
    void commandParameterSerializer_roundTripsLiteralsAndAttributeValue() throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("state", new StringValue("on"));   // value-model AttributeValue (AMD-52 codec)
        parameters.put("level", new IntValue(80));         // value-model AttributeValue
        parameters.put("mode", "comfort");                 // String literal
        parameters.put("count", 42);                       // Integer literal

        String json = serializer.apply(parameters);

        // The AMD-52 codec fired for the map's AttributeValue values (the tagged-union envelope
        // discriminator), proving production serialization, not a POJO/toString stub.
        assertThat(json).contains("\"t\":\"STRING\"").contains("\"t\":\"INT\"");

        JsonNode root = mapper.readTree(json);
        // The AttributeValues serialize through the AMD-52 codec and deserialize back to the exact
        // value (not a stub / toString) — the forward-risk closer.
        assertThat(mapper.treeToValue(root.get("state"), AttributeValue.class))
                .isEqualTo(new StringValue("on"));
        assertThat(mapper.treeToValue(root.get("level"), AttributeValue.class))
                .isEqualTo(new IntValue(80));
        // Literals survive verbatim.
        assertThat(root.get("mode").asText()).isEqualTo("comfort");
        assertThat(root.get("count").asInt()).isEqualTo(42);
    }

    @Test
    @DisplayName("an empty parameter map serializes to the empty JSON object")
    void commandParameterSerializer_emptyMap_isEmptyObject() {
        assertThat(serializer.apply(Map.of())).isEqualTo("{}");
    }

    @Test
    @DisplayName("a null parameter map is rejected (total only over non-null maps)")
    void commandParameterSerializer_nullMap_rejected() {
        assertThatThrownBy(() -> serializer.apply(null))
                .isInstanceOf(NullPointerException.class);
    }
}

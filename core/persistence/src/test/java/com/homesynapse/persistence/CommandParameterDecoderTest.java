/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.value.IntValue;
import com.homesynapse.value.StringValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the M9.1 {@link CommandParameterDecoder} — the persistence-backed
 * {@code command_issued.parameters} decoder exposed via
 * {@link PersistenceFactory#commandParameterDecoder()} — the M7.4b
 * {@link CommandParameterSerializer} in the opposite direction.
 *
 * <p>Same lightweight pattern as {@code CommandParameterSerializerTest} (no
 * SQLite boot). The load-bearing contract is DP-3's never-throw rule: the
 * decoder feeds the integration spine's routing subscriber on the bus thread,
 * so null/blank/{@code "{}"}/malformed inputs must all decode to
 * {@code Map.of()}, never an exception.</p>
 */
@DisplayName("CommandParameterDecoder (M9.1)")
final class CommandParameterDecoderTest {

    private final Function<Map<String, Object>, String> serializer =
            new CommandParameterSerializer();
    private final Function<String, Map<String, Object>> decoder = new CommandParameterDecoder();

    CommandParameterDecoderTest() {
        // Explicit constructor for -Xlint:all -Werror.
    }

    @Test
    @DisplayName("a serializer-encoded map of literals round-trips through the decoder")
    void decoder_roundTripsSerializedLiterals() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("mode", "comfort");
        parameters.put("count", 42);
        parameters.put("enabled", true);

        Map<String, Object> decoded = decoder.apply(serializer.apply(parameters));

        assertThat(decoded)
                .containsEntry("mode", "comfort")
                .containsEntry("count", 42)
                .containsEntry("enabled", true);
    }

    @Test
    @DisplayName("a serializer-encoded AttributeValue survives decoding as its AMD-52 envelope "
            + "(the adapter receives the tagged-union map, not a decode failure)")
    void decoder_decodesAttributeValueEnvelopes() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("state", new StringValue("on"));
        parameters.put("level", new IntValue(80));

        Map<String, Object> decoded = decoder.apply(serializer.apply(parameters));

        // Decoding targets Map<String,Object>, so the AMD-52 envelope arrives as its
        // tagged-union map shape — present and structurally intact, never a throw.
        assertThat(decoded).containsKeys("state", "level");
        assertThat(decoded.get("state")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("null, blank, and \"{}\" inputs decode to the empty map (DP-3)")
    void decoder_nullBlankAndEmptyObject_decodeToEmptyMap() {
        assertThat(decoder.apply(null)).isEmpty();
        assertThat(decoder.apply("   ")).isEmpty();
        assertThat(decoder.apply("{}")).isEmpty();
    }

    @Test
    @DisplayName("malformed input decodes to the empty map — never an exception on the "
            + "caller's thread (DP-3)")
    void decoder_malformedInput_decodesToEmptyMap() {
        assertThat(decoder.apply("{not json")).isEmpty();
        assertThat(decoder.apply("[1,2,3]")).isEmpty();     // a JSON array is not an object
    }
}

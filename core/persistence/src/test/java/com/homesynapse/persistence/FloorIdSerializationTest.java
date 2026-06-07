/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.platform.identity.FloorId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link FloorId} serde registered by {@link PersistenceJacksonModule}
 * (AMD-44 carry-item). {@code FloorId} mirrors the other typed ULID wrappers — it must
 * round-trip as a bare Crockford Base32 string, never a nested {@code {msb,lsb}} object.
 */
@DisplayName("FloorId serialization")
class FloorIdSerializationTest {

    private static final Ulid ULID = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = PersistenceObjectMapper.create();
    }

    @Test
    @DisplayName("FloorId serializes as a bare 26-character Crockford Base32 string")
    void floorId_serializesAsCrockfordBase32() throws Exception {
        FloorId original = FloorId.of(ULID);

        String json = mapper.writeValueAsString(original);

        assertThat(json).isEqualTo("\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"");
    }

    @Test
    @DisplayName("FloorId round-trip preserves value")
    void floorId_roundTrip_preservesValue() throws Exception {
        FloorId original = FloorId.of(ULID);

        FloorId roundTripped = mapper.readValue(
                mapper.writeValueAsString(original), FloorId.class);

        assertThat(roundTripped).isEqualTo(original);
    }
}

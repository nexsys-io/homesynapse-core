/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.EventId;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.PersonId;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for custom ULID serde registered by {@link PersistenceJacksonModule}.
 */
@DisplayName("ULID serialization")
class UlidSerializationTest {

    private static final Ulid ULID = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = PersistenceObjectMapper.create();
    }

    @Test
    @DisplayName("Ulid round-trip preserves value")
    void ulid_roundTrip_preservesValue() throws Exception {
        String json = mapper.writeValueAsString(ULID);
        Ulid roundTripped = mapper.readValue(json, Ulid.class);

        assertThat(roundTripped).isEqualTo(ULID);
    }

    @Test
    @DisplayName("Ulid serializes as a bare 26-character Crockford Base32 string")
    void ulid_serializesAsCrockfordBase32() throws Exception {
        String json = mapper.writeValueAsString(ULID);

        assertThat(json).isEqualTo("\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"");
    }

    @Test
    @DisplayName("EntityId round-trip preserves value")
    void entityId_roundTrip_preservesValue() throws Exception {
        EntityId original = EntityId.of(ULID);

        String json = mapper.writeValueAsString(original);
        EntityId roundTripped = mapper.readValue(json, EntityId.class);

        assertThat(json).isEqualTo("\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"");
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("EventId round-trip preserves value")
    void eventId_roundTrip_preservesValue() throws Exception {
        EventId original = EventId.of(ULID);

        String json = mapper.writeValueAsString(original);
        EventId roundTripped = mapper.readValue(json, EventId.class);

        assertThat(json).isEqualTo("\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"");
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("IntegrationId round-trip preserves value")
    void integrationId_roundTrip_preservesValue() throws Exception {
        IntegrationId original = IntegrationId.of(ULID);

        IntegrationId roundTripped = mapper.readValue(
                mapper.writeValueAsString(original), IntegrationId.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("DeviceId round-trip preserves value")
    void deviceId_roundTrip_preservesValue() throws Exception {
        DeviceId original = DeviceId.of(ULID);

        DeviceId roundTripped = mapper.readValue(
                mapper.writeValueAsString(original), DeviceId.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("all 10 typed wrappers serialize as bare Crockford Base32 strings")
    void allTypedWrappers_serializeAsBareStrings() throws Exception {
        String expected = "\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"";

        assertThat(mapper.writeValueAsString(ULID)).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(EntityId.of(ULID))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(DeviceId.of(ULID))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(AreaId.of(ULID))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(AutomationId.of(ULID))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(PersonId.of(ULID))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(HomeId.of(ULID))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(IntegrationId.of(ULID))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(SystemId.of(ULID))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(EventId.of(ULID))).isEqualTo(expected);
    }

    @Test
    @DisplayName("Ulid inside a record serializes as a bare string, not {msb,lsb}")
    void ulid_inRecord_roundTrip() throws Exception {
        CommandDispatchedEvent event = new CommandDispatchedEvent(
                ULID, ULID, "zigbee://test");

        String json = mapper.writeValueAsString(event);

        // Bare string — no nested {"msb":...,"lsb":...} object.
        assertThat(json).doesNotContain("msb").doesNotContain("lsb");
        assertThat(json).contains("\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"");

        CommandDispatchedEvent roundTripped =
                mapper.readValue(json, CommandDispatchedEvent.class);
        assertThat(roundTripped).isEqualTo(event);
    }
}

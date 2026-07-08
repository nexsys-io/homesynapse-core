/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.AttributeSchemaRef;
import com.homesynapse.event.CapabilityInstanceRef;
import com.homesynapse.event.CommandDefinitionRef;
import com.homesynapse.event.ConfirmationPolicyRef;
import com.homesynapse.event.DeviceRegisteredEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.ExpectationRef;
import com.homesynapse.event.ExpectedOutcomeRef;
import com.homesynapse.event.HardwareIdentifierRef;
import com.homesynapse.event.ParameterSchemaRef;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.value.AttributeType;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.IntValue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round-trip tests for the two AMD-99 registration payloads through the full
 * production event-payload path ({@link EventTypeRegistry} &rarr;
 * {@link JacksonWarmup} &rarr; {@link EventPayloadCodec}) over a DEEP fixture:
 * two capabilities, populated attribute/command maps, all four
 * {@link ExpectationRef} permits, and {@code Number} fields exercising the
 * canonical Integer/Double pair (the AMD-52/AMD-87 float discipline — the
 * decoded payload must be structurally EQUAL to the emitted one, which is what
 * makes the projection's DP-8 equal-state short-circuit work across replay).
 */
@DisplayName("Registration event codec (AMD-99)")
final class RegistrationEventSerdeTest {

    private static final Ulid DEVICE_ID = Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final Ulid ENTITY_ID = Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final Ulid AREA_ID = Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAF1");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private EventPayloadCodec codec;

    RegistrationEventSerdeTest() {
        // Explicit constructor for -Xlint:all -Werror.
    }

    @BeforeEach
    void setUp() {
        EventTypeRegistry registry = new EventTypeRegistry(AllEventClasses.ALL_EVENTS);
        JacksonWarmup warmup =
                JacksonWarmup.warmup(PersistenceObjectMapper.create(), registry);
        codec = new EventPayloadCodec(registry, warmup);
    }

    private DomainEvent roundTrip(String eventType, DomainEvent event) throws Exception {
        byte[] encoded = codec.encode(event);
        return codec.decode(eventType, 1, encoded);
    }

    private static DeviceRegisteredEvent deviceFixture() {
        return new DeviceRegisteredEvent(
                DEVICE_ID,
                "zigbee-0011223344556677",
                "Signify Hue Bulb",
                "Signify",
                "LWA021",
                "SN-123",
                "1.108.7",
                null,
                "01JAAAAAAAAAAAAAAAAAAAAAD2",
                AREA_ID,
                null,
                List.of("hero", "living-room"),
                List.of(
                        new HardwareIdentifierRef("zigbee", "0011223344556677"),
                        new HardwareIdentifierRef("ble", "AA:BB:CC")),
                CREATED_AT);
    }

    private static EntityRegisteredEvent entityFixture() {
        CapabilityInstanceRef onOff = new CapabilityInstanceRef(
                "on_off", 1, "core", 0,
                Map.of("on", new AttributeSchemaRef(
                        "on", AttributeType.BOOLEAN, null, null, null, null,
                        null, null, List.of("READ", "NOTIFY"), false, true)),
                Map.of("turn_on", new CommandDefinitionRef(
                        "turn_on",
                        List.of(),
                        0,
                        List.of(
                                new ExpectedOutcomeRef("on",
                                        new ExpectationRef.ExactMatchRef(
                                                new BooleanValue(true)), 5000L),
                                new ExpectedOutcomeRef("on",
                                        new ExpectationRef.AnyChangeRef(
                                                new BooleanValue(false)), 5000L)),
                        Duration.ofSeconds(5),
                        "IDEMPOTENT")),
                new ConfirmationPolicyRef("EXACT_MATCH", List.of("on"), null, 5000L));

        CapabilityInstanceRef brightness = new CapabilityInstanceRef(
                "brightness", 1, "core", 3,
                Map.of(
                        "brightness", new AttributeSchemaRef(
                                "brightness", AttributeType.INT,
                                0, 254, 1,
                                null, null, null,
                                List.of("READ", "WRITE"), false, true),
                        "color_mode", new AttributeSchemaRef(
                                "color_mode", AttributeType.ENUM,
                                null, null, null,
                                List.of("temperature", "dimming"),
                                null, null, List.of("READ"), true, false),
                        "level_pct", new AttributeSchemaRef(
                                "level_pct", AttributeType.FLOAT,
                                0.0d, 100.0d, 0.5d,
                                null, "%", "%",
                                List.of("READ"), false, false)),
                Map.of("set_brightness", new CommandDefinitionRef(
                        "set_brightness",
                        List.of(
                                new ParameterSchemaRef("level", AttributeType.INT,
                                        0, 100, true, 0, null),
                                new ParameterSchemaRef("mode", AttributeType.ENUM,
                                        null, null, false, 1,
                                        List.of("smooth", "instant"))),
                        1,
                        List.of(
                                new ExpectedOutcomeRef("brightness",
                                        new ExpectationRef.WithinToleranceRef(
                                                127.25d, 2.0d), 30000L),
                                new ExpectedOutcomeRef("color_mode",
                                        new ExpectationRef.EnumTransitionRef(
                                                "dimming"), 30000L),
                                new ExpectedOutcomeRef("brightness",
                                        new ExpectationRef.AnyChangeRef(
                                                new IntValue(3L)), 30000L)),
                        Duration.ofSeconds(30),
                        "IDEMPOTENT")),
                new ConfirmationPolicyRef("TOLERANCE", List.of("brightness"), 2, 30000L));

        return new EntityRegisteredEvent(
                ENTITY_ID,
                "zigbee-0011223344556677-ep1",
                "LIGHT",
                "Signify Hue Bulb",
                DEVICE_ID,
                1,
                null,
                true,
                List.of("hero"),
                "PRIMARY",
                CREATED_AT,
                List.of(onOff, brightness));
    }

    @Test
    @DisplayName("device_registered round-trips equal through the production codec")
    void deviceRegistered_roundTrips() throws Exception {
        DeviceRegisteredEvent original = deviceFixture();

        DomainEvent decoded = roundTrip(EventTypes.DEVICE_REGISTERED, original);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("entity_registered round-trips equal over the deep fixture (tuning included)")
    void entityRegistered_roundTrips() throws Exception {
        EntityRegisteredEvent original = entityFixture();

        DomainEvent decoded = roundTrip(EventTypes.ENTITY_REGISTERED, original);

        assertThat(decoded).isEqualTo(original);
        EntityRegisteredEvent rebuilt = (EntityRegisteredEvent) decoded;
        assertThat(rebuilt.capabilities().get(1).confirmation())
                .isEqualTo(new ConfirmationPolicyRef(
                        "TOLERANCE", List.of("brightness"), 2, 30000L));
        // Number canonical-pair discipline: an Integer bound decodes as Integer,
        // a decimal bound decodes as Double (structural equality, not just value).
        AttributeSchemaRef level = rebuilt.capabilities().get(1)
                .attributes().get("brightness");
        assertThat(level.minimum()).isExactlyInstanceOf(Integer.class);
        AttributeSchemaRef pct = rebuilt.capabilities().get(1)
                .attributes().get("level_pct");
        assertThat(pct.step()).isExactlyInstanceOf(Double.class);
        assertThat(pct.step()).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("encoding is deterministic: re-encoding the decoded payload is byte-identical")
    void encodingDeterministic() throws Exception {
        EntityRegisteredEvent original = entityFixture();

        byte[] first = codec.encode(original);
        DomainEvent decoded = codec.decode(EventTypes.ENTITY_REGISTERED, 1, first);
        byte[] second = codec.encode(decoded);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("nullable components (hardwareVersion, areaId, validValues) survive absence")
    void nullableComponentsSurvive() throws Exception {
        DeviceRegisteredEvent decoded = (DeviceRegisteredEvent)
                roundTrip(EventTypes.DEVICE_REGISTERED, deviceFixture());

        assertThat(decoded.hardwareVersion()).isNull();
        assertThat(decoded.viaDeviceId()).isNull();
        assertThat(decoded.serialNumber()).isEqualTo("SN-123");
        assertThat(decoded.areaId()).isEqualTo(AREA_ID);
    }
}

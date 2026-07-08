/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.homesynapse.device.Expectation;
import com.homesynapse.event.EventId;
import com.homesynapse.event.ExpectationRef;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.FloorId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.PersonId;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Jackson {@link SimpleModule} that registers custom serializers and deserializers
 * for HomeSynapse typed ULID wrappers (LTD-04, DECIDE-M2-04).
 *
 * <p>All typed identity types in HomeSynapse are records that wrap a {@link Ulid}
 * instance. Without custom serde, Jackson would serialize each as a nested object
 * exposing the {@code msb}/{@code lsb} long pair, which is both verbose and violates
 * INV-TO-01 (payloads must be human-observable). This module installs a pair of
 * serde for each of the ten ULID-based types so they round-trip as bare 26-character
 * Crockford Base32 JSON strings.</p>
 *
 * <p><strong>Registered types (11):</strong></p>
 * <ul>
 *   <li>{@link Ulid} — raw ULID value type from platform-api.</li>
 *   <li>{@link EntityId}, {@link DeviceId}, {@link AreaId}, {@link FloorId},
 *       {@link AutomationId}, {@link PersonId}, {@link HomeId}, {@link IntegrationId},
 *       {@link SystemId} — typed wrappers from platform-api.</li>
 *   <li>{@link EventId} — typed wrapper from event-model (NOT in platform-api).</li>
 * </ul>
 *
 * <p><strong>{@code AttributeValue} serde (AMD-52 / M4.0b-4b):</strong> the
 * {@link AttributeValueSerializer}/{@link AttributeValueDeserializer} pair — the
 * DECIDE-M2-03 pre-declared expansion point — is now registered here, keyed on the
 * {@code AttributeValue} interface so all eight variants resolve through it
 * (Jackson's {@code SimpleSerializers} walks superclasses and interfaces). This is the
 * ONLY place device value types are Jackson-serialized (AMD-52-INV-02 Jackson isolation);
 * the codec is a hand-rolled tagged-union envelope, never {@code @JsonTypeInfo}.</p>
 *
 * <p><strong>{@code Expectation} serde (AMD-87 / M5-A Part 2):</strong> the
 * {@link ExpectationSerializer}/{@link ExpectationDeserializer} pair is registered here,
 * keyed on the {@code Expectation} interface so all four permits ({@code ExactMatch},
 * {@code AnyChange}, {@code EnumTransition}, {@code WithinTolerance}) resolve through it. It
 * closes the command-bearing {@code CapabilityAdded} round-trip gap (the embedded
 * {@code CommandDefinition → ExpectedOutcome → Expectation} subtree previously degraded on
 * decode); {@code ExactMatch}/{@code AnyChange} delegate their wrapped {@code AttributeValue}
 * to the codec above. Same hand-rolled tagged-union discipline — no {@code @JsonTypeInfo}.</p>
 *
 * <p>Package-private — installed only by {@link PersistenceObjectMapper}. External
 * modules receive pre-configured {@code ObjectMapper} instances and never touch
 * this module directly.</p>
 *
 * @see PersistenceObjectMapper
 * @see UlidSerializer
 * @see UlidDeserializer
 * @see TypedUlidSerializer
 * @see TypedUlidDeserializer
 * @see AttributeValueSerializer
 * @see AttributeValueDeserializer
 * @see ExpectationSerializer
 * @see ExpectationDeserializer
 */
final class PersistenceJacksonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the module and registers all ULID wrapper serde.
     *
     * <p>Registration order is irrelevant — {@link SimpleModule} stores entries in
     * a {@code HashMap} keyed by concrete class. Each wrapper type receives its own
     * serializer/deserializer pair keyed by the wrapper {@code Class} literal.</p>
     */
    PersistenceJacksonModule() {
        super("PersistenceJacksonModule");

        // Raw Ulid — used directly by several event records (CommandDispatchedEvent,
        // CommandIssuedEvent, DeviceAdoptedEvent, DeviceDiscoveredEvent, CommandResultEvent)
        addSerializer(Ulid.class, new UlidSerializer());
        addDeserializer(Ulid.class, new UlidDeserializer());

        // Typed wrappers from platform-api (9)
        registerTypedWrapper(EntityId.class, EntityId::toString, EntityId::parse);
        registerTypedWrapper(DeviceId.class, DeviceId::toString, DeviceId::parse);
        registerTypedWrapper(AreaId.class, AreaId::toString, AreaId::parse);
        registerTypedWrapper(FloorId.class, FloorId::toString, FloorId::parse);
        registerTypedWrapper(AutomationId.class, AutomationId::toString, AutomationId::parse);
        registerTypedWrapper(PersonId.class, PersonId::toString, PersonId::parse);
        registerTypedWrapper(HomeId.class, HomeId::toString, HomeId::parse);
        registerTypedWrapper(IntegrationId.class, IntegrationId::toString, IntegrationId::parse);
        registerTypedWrapper(SystemId.class, SystemId::toString, SystemId::parse);

        // EventId lives in event-model, not platform-api
        registerTypedWrapper(EventId.class, EventId::toString, EventId::parse);

        // AttributeValue serde (AMD-52 / M4.0b-4b — the DECIDE-M2-03 expansion point).
        // Keyed on the AttributeValue interface so all eight sealed variants dispatch
        // through the single hand-rolled tagged-union codec (no @JsonTypeInfo).
        addSerializer(AttributeValue.class, new AttributeValueSerializer());
        addDeserializer(AttributeValue.class, new AttributeValueDeserializer());

        // Expectation serde (AMD-87 / M5-A Part 2). Keyed on the Expectation interface so
        // all four sealed permits (ExactMatch/AnyChange/EnumTransition/WithinTolerance)
        // dispatch through the single hand-rolled tagged-union codec — same mechanism as the
        // AttributeValue pair above (no @JsonTypeInfo). Closes the command-bearing
        // CapabilityAdded round-trip gap (AMD-59-INV-02): ExactMatch/AnyChange delegate their
        // wrapped AttributeValue to the codec registered just above.
        addSerializer(Expectation.class, new ExpectationSerializer());
        addDeserializer(Expectation.class, new ExpectationDeserializer());

        // ExpectationRef serde (AMD-99 / M9.5-DUR). The event-local sealed mirror
        // riding inside entity_registered payloads (CapabilityInstanceRef ->
        // CommandDefinitionRef -> ExpectedOutcomeRef) needs the same interface-keyed
        // tagged-union treatment as the domain Expectation above — identical wire
        // form, same delegation to the AttributeValue codec (no @JsonTypeInfo).
        addSerializer(ExpectationRef.class, new ExpectationRefSerializer());
        addDeserializer(ExpectationRef.class, new ExpectationRefDeserializer());
    }

    /**
     * Registers a serializer/deserializer pair for one typed ULID wrapper.
     *
     * @param <T>        the wrapper type
     * @param type       the wrapper {@link Class} literal
     * @param toStringFn the function that returns the Crockford Base32 string
     * @param parseFn    the factory that parses a Crockford Base32 string into a wrapper
     */
    private <T> void registerTypedWrapper(
            Class<T> type,
            java.util.function.Function<T, String> toStringFn,
            java.util.function.Function<String, T> parseFn) {
        addSerializer(type, new TypedUlidSerializer<>(toStringFn));
        addDeserializer(type, new TypedUlidDeserializer<>(parseFn));
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.module.SimpleModule;
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
 * <p><strong>Registered types (10):</strong></p>
 * <ul>
 *   <li>{@link Ulid} — raw ULID value type from platform-api.</li>
 *   <li>{@link EntityId}, {@link DeviceId}, {@link AreaId}, {@link AutomationId},
 *       {@link PersonId}, {@link HomeId}, {@link IntegrationId}, {@link SystemId}
 *       — typed wrappers from platform-api.</li>
 *   <li>{@link EventId} — typed wrapper from event-model (NOT in platform-api).</li>
 * </ul>
 *
 * <p><strong>Future expansion point (DECIDE-M2-03):</strong> {@code AttributeValue}
 * serde is deliberately NOT registered here. No current event record uses
 * {@code AttributeValue} as a field type; the state-store milestone will add a
 * dedicated handler (or a new module extending this one) when the type becomes
 * part of serialized payloads.</p>
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

        // Typed wrappers from platform-api (8)
        registerTypedWrapper(EntityId.class, EntityId::toString, EntityId::parse);
        registerTypedWrapper(DeviceId.class, DeviceId::toString, DeviceId::parse);
        registerTypedWrapper(AreaId.class, AreaId::toString, AreaId::parse);
        registerTypedWrapper(AutomationId.class, AutomationId::toString, AutomationId::parse);
        registerTypedWrapper(PersonId.class, PersonId::toString, PersonId::parse);
        registerTypedWrapper(HomeId.class, HomeId::toString, HomeId::parse);
        registerTypedWrapper(IntegrationId.class, IntegrationId::toString, IntegrationId::parse);
        registerTypedWrapper(SystemId.class, SystemId::toString, SystemId::parse);

        // EventId lives in event-model, not platform-api
        registerTypedWrapper(EventId.class, EventId::toString, EventId::parse);
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

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Generic Jackson deserializer for typed ULID wrappers such as
 * {@code EntityId}, {@code DeviceId}, {@code EventId}, and the other eight
 * typed identity types (LTD-04, DECIDE-M2-04).
 *
 * <p>Reads a bare Crockford Base32 JSON string and invokes an injected
 * {@link Function} factory (typically a record's {@code parse} static method)
 * to construct the wrapper instance. Parse failures propagate as
 * {@link IllegalArgumentException} from the underlying
 * {@code Ulid.parse} / wrapper {@code parse} methods; Jackson wraps them in a
 * {@code JsonMappingException} at the calling site.</p>
 *
 * <p>Instances are registered against a specific wrapper class in
 * {@link PersistenceJacksonModule} via
 * {@link com.fasterxml.jackson.databind.module.SimpleModule#addDeserializer(Class,
 * JsonDeserializer)}, which passes the concrete class to Jackson explicitly —
 * no generic-type resolution at runtime is required.</p>
 *
 * <p>Package-private — internal persistence infrastructure, not public API.</p>
 *
 * @param <T> the typed ULID wrapper type
 * @see TypedUlidSerializer
 * @see PersistenceJacksonModule
 */
final class TypedUlidDeserializer<T> extends JsonDeserializer<T> {

    private final Function<String, T> factory;

    /**
     * Constructs a new deserializer with the given wrapper-construction factory.
     *
     * @param factory the factory function that constructs a wrapper instance
     *                from a Crockford Base32 string; must not be {@code null}
     * @throws NullPointerException if {@code factory} is {@code null}
     */
    TypedUlidDeserializer(Function<String, T> factory) {
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
    }

    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return factory.apply(p.getText());
    }
}

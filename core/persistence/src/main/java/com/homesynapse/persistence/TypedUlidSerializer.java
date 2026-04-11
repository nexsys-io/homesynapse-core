/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Generic Jackson serializer for typed ULID wrappers such as
 * {@code EntityId}, {@code DeviceId}, {@code EventId}, and the other eight
 * typed identity types (LTD-04, DECIDE-M2-04).
 *
 * <p>Every typed ULID wrapper in HomeSynapse is a {@code record XxxId(Ulid value)}
 * whose {@code toString()} method delegates to the underlying {@link
 * com.homesynapse.platform.identity.Ulid#toString()} — producing the canonical
 * 26-character Crockford Base32 representation. This serializer extracts that
 * string via an injected {@link Function} and writes it as a bare JSON string.
 * Without this serializer, Jackson would serialize each wrapper as a nested
 * object {@code {"value":{"msb":...,"lsb":...}}}, which is both verbose and
 * destroys the human-readable form required by INV-TO-01.</p>
 *
 * <p>Instances are registered against a specific wrapper class in
 * {@link PersistenceJacksonModule} via
 * {@link com.fasterxml.jackson.databind.module.SimpleModule#addSerializer(Class,
 * JsonSerializer)}, which passes the concrete class to Jackson explicitly — no
 * generic-type resolution at runtime is required.</p>
 *
 * <p>Package-private — internal persistence infrastructure, not public API.</p>
 *
 * @param <T> the typed ULID wrapper type
 * @see TypedUlidDeserializer
 * @see PersistenceJacksonModule
 */
final class TypedUlidSerializer<T> extends JsonSerializer<T> {

    private final Function<T, String> toStringFn;

    /**
     * Constructs a new serializer with the given string-extraction function.
     *
     * @param toStringFn the function that produces the Crockford Base32 string
     *                   for a wrapper instance; must not be {@code null}
     * @throws NullPointerException if {@code toStringFn} is {@code null}
     */
    TypedUlidSerializer(Function<T, String> toStringFn) {
        this.toStringFn = Objects.requireNonNull(toStringFn, "toStringFn must not be null");
    }

    @Override
    public void serialize(T value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeString(toStringFn.apply(value));
    }
}

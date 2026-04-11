/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.homesynapse.platform.identity.Ulid;

import java.io.IOException;

/**
 * Jackson deserializer that parses a 26-character Crockford Base32 string into
 * a {@link Ulid} (LTD-04, DECIDE-M2-04).
 *
 * <p>Symmetrical to {@link UlidSerializer}. Delegates to {@link Ulid#parse(String)}
 * which is case-insensitive and follows the Crockford specification (see
 * {@code Ulid.parse} Javadoc). Parse failures propagate as
 * {@link IllegalArgumentException}, which Jackson wraps in a
 * {@code JsonMappingException} at the calling site.</p>
 *
 * <p>Registered via {@link PersistenceJacksonModule}. Package-private — internal
 * persistence infrastructure, not public API.</p>
 *
 * @see UlidSerializer
 * @see PersistenceJacksonModule
 */
final class UlidDeserializer extends JsonDeserializer<Ulid> {

    @Override
    public Ulid deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return Ulid.parse(p.getText());
    }

    @Override
    public Class<?> handledType() {
        return Ulid.class;
    }
}

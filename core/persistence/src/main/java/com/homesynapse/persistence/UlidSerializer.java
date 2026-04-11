/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.homesynapse.platform.identity.Ulid;

import java.io.IOException;

/**
 * Jackson serializer that writes a {@link Ulid} as its 26-character Crockford
 * Base32 string representation (LTD-04, DECIDE-M2-04).
 *
 * <p>Without this custom serializer, Jackson would serialize the {@code Ulid}
 * record as a nested JSON object {@code {"msb":...,"lsb":...}}, which is verbose
 * and obscures the human-readable form that operators see in diagnostic tooling
 * (INV-TO-01). With this serializer, ULID fields appear as a bare 26-character
 * string literal in the payload BLOB.</p>
 *
 * <p>Registered via {@link PersistenceJacksonModule}. Package-private — internal
 * persistence infrastructure, not public API.</p>
 *
 * @see UlidDeserializer
 * @see PersistenceJacksonModule
 */
final class UlidSerializer extends JsonSerializer<Ulid> {

    @Override
    public void serialize(Ulid value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeString(value.toString());
    }

    @Override
    public Class<Ulid> handledType() {
        return Ulid.class;
    }
}

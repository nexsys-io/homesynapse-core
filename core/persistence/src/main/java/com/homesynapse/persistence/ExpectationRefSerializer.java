/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.homesynapse.event.ExpectationRef;

import java.io.IOException;

/**
 * Jackson serializer for the four-permit sealed {@link ExpectationRef} mirror
 * (AMD-99 §3) — the {@link ExpectationSerializer} pattern applied to the
 * event-local mirror that rides inside {@code entity_registered} payloads
 * ({@code CapabilityInstanceRef → CommandDefinitionRef → ExpectedOutcomeRef}).
 *
 * <p>Wire form is IDENTICAL to the AMD-87 domain {@code Expectation} envelope —
 * the same {@code "t"} discriminator tags and field layout — so the encoding
 * discipline (AMD-52 bit-anchored floats, delegated {@code AttributeValue}
 * envelopes) carries over verbatim and the stored form stays stable even if
 * the mirror's Java names ever change:</p>
 *
 * <pre>{@code
 * {"t":"ExactMatch","v":<AttributeValue>}        // expectedValue
 * {"t":"AnyChange","v":<AttributeValue>}          // previousValue
 * {"t":"EnumTransition","v":"<string>"}           // expectedValue
 * {"t":"WithinTolerance","target":<double>,"tolerance":<double>}
 * }</pre>
 *
 * <p>Dispatch is an exhaustive {@code switch} over the sealed mirror with
 * <strong>no {@code default} arm</strong> — a future fifth permit MUST break
 * compilation rather than silently lossy-encode. No Jackson annotation appears
 * on the mirror (Jackson-isolation HARD RULE, ArchUnit Rule 10). Stateless,
 * thread-safe, package-private.</p>
 *
 * @see ExpectationRefDeserializer
 * @see ExpectationSerializer
 * @see PersistenceJacksonModule
 */
final class ExpectationRefSerializer extends JsonSerializer<ExpectationRef> {

    ExpectationRefSerializer() {
        // Stateless; no initialization required.
    }

    @Override
    public void serialize(ExpectationRef value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();

        // Exhaustive over the four ExpectationRef permits — NO default. A future
        // fifth permit must break compilation here, never silently encode lossily.
        switch (value) {
            case ExpectationRef.ExactMatchRef em -> {
                gen.writeStringField(ExpectationSerializer.FIELD_TYPE,
                        ExpectationSerializer.TAG_EXACT_MATCH);
                gen.writeFieldName(ExpectationSerializer.FIELD_VALUE);
                // Delegate to the registered AttributeValue codec — do not re-encode.
                provider.defaultSerializeValue(em.expectedValue(), gen);
            }
            case ExpectationRef.AnyChangeRef ac -> {
                gen.writeStringField(ExpectationSerializer.FIELD_TYPE,
                        ExpectationSerializer.TAG_ANY_CHANGE);
                gen.writeFieldName(ExpectationSerializer.FIELD_VALUE);
                provider.defaultSerializeValue(ac.previousValue(), gen);
            }
            case ExpectationRef.EnumTransitionRef et -> {
                gen.writeStringField(ExpectationSerializer.FIELD_TYPE,
                        ExpectationSerializer.TAG_ENUM_TRANSITION);
                gen.writeStringField(ExpectationSerializer.FIELD_VALUE, et.expectedValue());
            }
            case ExpectationRef.WithinToleranceRef wt -> {
                gen.writeStringField(ExpectationSerializer.FIELD_TYPE,
                        ExpectationSerializer.TAG_WITHIN_TOLERANCE);
                // AMD-52 bit-anchored-float treatment via the shared helper.
                gen.writeFieldName(ExpectationSerializer.FIELD_TARGET);
                AttributeValueSerializer.writeDouble(gen, wt.target());
                gen.writeFieldName(ExpectationSerializer.FIELD_TOLERANCE);
                AttributeValueSerializer.writeDouble(gen, wt.tolerance());
            }
        }

        gen.writeEndObject();
    }

    @Override
    public Class<ExpectationRef> handledType() {
        return ExpectationRef.class;
    }
}

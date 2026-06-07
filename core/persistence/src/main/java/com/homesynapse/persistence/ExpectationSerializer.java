/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.homesynapse.device.AnyChange;
import com.homesynapse.device.EnumTransition;
import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.Expectation;
import com.homesynapse.device.WithinTolerance;

import java.io.IOException;

/**
 * Jackson serializer for the four-variant sealed {@link Expectation} hierarchy
 * (AMD-87 §2 / AMD-87-INV-01), the {@link AttributeValueSerializer} pattern applied to
 * the device-model command-confirmation type.
 *
 * <p>A command-bearing {@code CapabilityAdded} carries the full {@code CapabilityInstance}
 * (AMD-59-INV-02), which embeds {@code CommandDefinition → ExpectedOutcome → Expectation}.
 * Without this codec, that subtree decodes to {@code DegradedEvent}; with it, the event
 * round-trips. The variant is keyed by an explicit permit-simple-name discriminator written
 * as the {@code "t"} field — NOT a Jackson polymorphic-type mechanism. No Jackson annotation
 * appears on {@code Expectation} (which lives in {@code com.homesynapse.device}) — the codec
 * is the ONLY place the type is Jackson-serialized, satisfying the Jackson-isolation HARD
 * RULE and {@code NO_JACKSON_IN_DOMAIN_MODEL} (ArchUnit Rule 10).</p>
 *
 * <h2>Wire form (compact tagged-union envelope, mirroring REC-100)</h2>
 *
 * <pre>{@code
 * {"t":"ExactMatch","v":<AttributeValue>}        // expectedValue
 * {"t":"AnyChange","v":<AttributeValue>}          // previousValue
 * {"t":"EnumTransition","v":"<string>"}           // expectedValue
 * {"t":"WithinTolerance","target":<double>,"tolerance":<double>}
 * }</pre>
 *
 * <ul>
 *   <li>{@link ExactMatch}/{@link AnyChange} → {@code "v"} delegates to the <strong>existing</strong>
 *       {@link AttributeValueSerializer} via {@link SerializerProvider#defaultSerializeValue}; the
 *       wrapped value is never re-encoded here.</li>
 *   <li>{@link EnumTransition} → {@code "v"} = JSON string.</li>
 *   <li>{@link WithinTolerance} → {@code "target"}/{@code "tolerance"} use the shared
 *       {@link AttributeValueSerializer#writeDouble} helper, so the AMD-52 bit-anchored-float
 *       treatment (round-trippable number, {@code "NaN"}/{@code "+Inf"}/{@code "-Inf"} sentinels,
 *       {@code -0.0}→{@code +0.0}) applies — the two doubles survive encode→decode bit-identically.</li>
 * </ul>
 *
 * <p>Dispatch is an exhaustive {@code switch} over the sealed type with <strong>no
 * {@code default} arm</strong>: a future fifth permit MUST break compilation rather than
 * silently lossy-encode (the established AMD-52-INV-01 discipline). Fields are written in a
 * fixed order; no {@code Map} appears in any variant.</p>
 *
 * <p>Stateless, pure, thread-safe. Registered via {@link PersistenceJacksonModule}, keyed on
 * the {@code Expectation} interface so all four permits resolve through it (Jackson's
 * {@code SimpleSerializers} walks superclasses/interfaces). Package-private — internal
 * persistence infrastructure, not public API.</p>
 *
 * @see ExpectationDeserializer
 * @see AttributeValueSerializer
 * @see PersistenceJacksonModule
 */
final class ExpectationSerializer extends JsonSerializer<Expectation> {

    /** Field name for the permit-simple-name discriminator. */
    static final String FIELD_TYPE = "t";
    /** Field name for the {@link ExactMatch}/{@link AnyChange}/{@link EnumTransition} payload. */
    static final String FIELD_VALUE = "v";
    /** Field name for the {@link WithinTolerance} target magnitude. */
    static final String FIELD_TARGET = "target";
    /** Field name for the {@link WithinTolerance} tolerance magnitude. */
    static final String FIELD_TOLERANCE = "tolerance";

    /** Discriminator tags — the permit simple names. */
    static final String TAG_EXACT_MATCH = "ExactMatch";
    static final String TAG_ANY_CHANGE = "AnyChange";
    static final String TAG_ENUM_TRANSITION = "EnumTransition";
    static final String TAG_WITHIN_TOLERANCE = "WithinTolerance";

    ExpectationSerializer() {
        // Stateless; no initialization required.
    }

    @Override
    public void serialize(Expectation value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();

        // Exhaustive over the four Expectation permits — NO default (AMD-87-INV-01).
        // A future fifth permit must break compilation here, never silently encode lossily.
        switch (value) {
            case ExactMatch em -> {
                gen.writeStringField(FIELD_TYPE, TAG_EXACT_MATCH);
                gen.writeFieldName(FIELD_VALUE);
                // Delegate to the registered AttributeValue codec — do not re-encode.
                provider.defaultSerializeValue(em.expectedValue(), gen);
            }
            case AnyChange ac -> {
                gen.writeStringField(FIELD_TYPE, TAG_ANY_CHANGE);
                gen.writeFieldName(FIELD_VALUE);
                provider.defaultSerializeValue(ac.previousValue(), gen);
            }
            case EnumTransition et -> {
                gen.writeStringField(FIELD_TYPE, TAG_ENUM_TRANSITION);
                gen.writeStringField(FIELD_VALUE, et.expectedValue());
            }
            case WithinTolerance wt -> {
                gen.writeStringField(FIELD_TYPE, TAG_WITHIN_TOLERANCE);
                // AMD-52 bit-anchored-float treatment via the shared helper — never re-invent.
                gen.writeFieldName(FIELD_TARGET);
                AttributeValueSerializer.writeDouble(gen, wt.target());
                gen.writeFieldName(FIELD_TOLERANCE);
                AttributeValueSerializer.writeDouble(gen, wt.tolerance());
            }
        }

        gen.writeEndObject();
    }

    @Override
    public Class<Expectation> handledType() {
        return Expectation.class;
    }
}

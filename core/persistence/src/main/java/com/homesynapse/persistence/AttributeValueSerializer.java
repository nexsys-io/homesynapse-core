/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.homesynapse.value.ArrayValue;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.DegradedAttributeValue;
import com.homesynapse.value.EnumValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.QuantityValue;
import com.homesynapse.value.StringValue;

import java.io.IOException;

/**
 * Jackson serializer for the eight-variant sealed {@link AttributeValue} hierarchy
 * (AMD-52 §2.2 / AMD-52-INV-02).
 *
 * <p>This is the hand-rolled, non-reflective alternative to {@code @JsonTypeInfo}: the
 * variant is keyed by an explicit {@link com.homesynapse.value.AttributeType} string
 * discriminator written as the {@code "t"} field, NOT by a Jackson polymorphic-type
 * mechanism. No Jackson annotation appears on {@code AttributeValue} (which lives in
 * {@code com.homesynapse.value}) — the codec is the ONLY place device value types are
 * Jackson-serialized, satisfying the Jackson-isolation HARD RULE.</p>
 *
 * <h2>Wire form (compact tagged-union envelope, REC-100)</h2>
 *
 * <pre>{@code
 * {"t":"<AttributeType.name()>","v":<value> [,"u":"<unit>"]}
 * }</pre>
 *
 * <ul>
 *   <li>{@link BooleanValue} → {@code "v"} = JSON boolean.</li>
 *   <li>{@link IntValue} → {@code "v"} = JSON number ({@code long}).</li>
 *   <li>{@link FloatValue} → {@code "v"} = a round-trippable JSON number, or a non-finite
 *       sentinel string ({@code "NaN"}/{@code "+Inf"}/{@code "-Inf"}); {@code -0.0} is
 *       written as {@code +0.0} (DP-3, coherent with AMD-51 §2.3).</li>
 *   <li>{@link StringValue}/{@link EnumValue} → {@code "v"} = JSON string.</li>
 *   <li>{@link QuantityValue} → {@code "v"} = canonical magnitude (finite — construction
 *       rejects non-finite), {@code "u"} = canonical unit symbol.</li>
 *   <li>{@link ArrayValue} → {@code "v"} = JSON array, recursing this same envelope per
 *       element in list order (AMD-47-INV-05 ordered semantics).</li>
 *   <li>{@link DegradedAttributeValue} → {@code original_type_name}/{@code raw_form}/
 *       {@code failure_reason} string fields (no {@code "v"}).</li>
 * </ul>
 *
 * <p>Dispatch is an exhaustive {@code switch} over the sealed type with <strong>no
 * {@code default} arm</strong>: a future ninth permit MUST break compilation rather than
 * silently lossy-encode (the serialization twin of AMD-51-INV-01). Fields are written in a
 * fixed order; no {@code Map} appears in any variant.</p>
 *
 * <p>Stateless, pure, thread-safe. Registered via {@link PersistenceJacksonModule}.
 * Package-private — internal persistence infrastructure, not public API.</p>
 *
 * @see AttributeValueDeserializer
 * @see PersistenceJacksonModule
 */
final class AttributeValueSerializer extends JsonSerializer<AttributeValue> {

    /** Field name for the {@link com.homesynapse.value.AttributeType} discriminator. */
    static final String FIELD_TYPE = "t";
    /** Field name for the scalar / quantity-magnitude / array payload. */
    static final String FIELD_VALUE = "v";
    /** Field name for the {@link QuantityValue} canonical unit symbol. */
    static final String FIELD_UNIT = "u";
    /** {@link DegradedAttributeValue} field names. */
    static final String FIELD_DEGRADED_ORIGINAL_TYPE = "original_type_name";
    static final String FIELD_DEGRADED_RAW_FORM = "raw_form";
    static final String FIELD_DEGRADED_FAILURE_REASON = "failure_reason";

    /** JSON-valid sentinel strings for IEEE-754 non-finite doubles (AMD-52-INV-04). */
    static final String SENTINEL_NAN = "NaN";
    static final String SENTINEL_POS_INF = "+Inf";
    static final String SENTINEL_NEG_INF = "-Inf";

    AttributeValueSerializer() {
        // Stateless; no initialization required.
    }

    @Override
    public void serialize(AttributeValue value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField(FIELD_TYPE, value.attributeType().name());

        // Exhaustive over the eight AttributeValue variants — NO default (AMD-52-INV-02).
        // A future ninth permit must break compilation here, never silently encode lossily.
        switch (value) {
            case BooleanValue b -> gen.writeBooleanField(FIELD_VALUE, b.value());
            case IntValue i -> gen.writeNumberField(FIELD_VALUE, i.value());
            case FloatValue f -> {
                gen.writeFieldName(FIELD_VALUE);
                writeDouble(gen, f.value());
            }
            case StringValue s -> gen.writeStringField(FIELD_VALUE, s.value());
            case EnumValue e -> gen.writeStringField(FIELD_VALUE, e.value());
            case QuantityValue q -> {
                gen.writeFieldName(FIELD_VALUE);
                // QuantityValue rejects non-finite at construction, so writeDouble never
                // emits a sentinel here — it writes the canonical magnitude as a number.
                writeDouble(gen, q.value());
                gen.writeStringField(FIELD_UNIT, q.unit());
            }
            case ArrayValue a -> {
                gen.writeArrayFieldStart(FIELD_VALUE);
                for (AttributeValue element : a.elements()) {
                    serialize(element, gen, provider);
                }
                gen.writeEndArray();
            }
            case DegradedAttributeValue d -> {
                gen.writeStringField(FIELD_DEGRADED_ORIGINAL_TYPE, d.originalTypeName());
                gen.writeStringField(FIELD_DEGRADED_RAW_FORM, d.rawForm());
                gen.writeStringField(FIELD_DEGRADED_FAILURE_REASON, d.failureReason());
            }
        }

        gen.writeEndObject();
    }

    @Override
    public Class<AttributeValue> handledType() {
        return AttributeValue.class;
    }

    /**
     * Writes a {@code double} into the {@code "v"} position using the AMD-52-INV-03/-04
     * encoding: non-finite values become JSON-valid sentinel strings, {@code -0.0} is
     * canonicalized to {@code +0.0}, and every finite value is written as the round-trippable
     * number Jackson emits (Schubfach shortest form — {@code parseDouble(render(x))} recovers
     * the same bits, so no canonical text renderer is owned).
     *
     * <p>Identity is anchored on the {@code double} bits after canonicalization, never on the
     * stored text. {@code ALLOW_NON_NUMERIC_NUMBERS} stays disabled — a bare
     * {@code NaN}/{@code Infinity} token is never emitted.</p>
     *
     * @param gen   the JSON generator, positioned after a {@code writeFieldName}
     * @param value the magnitude to write
     * @throws IOException if the generator fails
     */
    static void writeDouble(JsonGenerator gen, double value) throws IOException {
        if (Double.isNaN(value)) {
            gen.writeString(SENTINEL_NAN);
        } else if (value == Double.POSITIVE_INFINITY) {
            gen.writeString(SENTINEL_POS_INF);
        } else if (value == Double.NEGATIVE_INFINITY) {
            gen.writeString(SENTINEL_NEG_INF);
        } else if (value == 0.0) {
            // Canonicalize signed zero: -0.0 == 0.0 is true, so this maps both to +0.0
            // (coherent with the AMD-51 §2.3 comparator, which treats them equal).
            gen.writeNumber(0.0);
        } else {
            gen.writeNumber(value);
        }
    }
}

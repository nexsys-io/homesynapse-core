/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.homesynapse.event.ExpectationRef;
import com.homesynapse.value.AttributeValue;

import java.io.IOException;

/**
 * Jackson deserializer for the four-permit sealed {@link ExpectationRef}
 * mirror, the inverse of {@link ExpectationRefSerializer} (AMD-99 §3, the
 * AMD-87 {@link ExpectationDeserializer} pattern).
 *
 * <p>Reads the {@code "t"} discriminator and reconstructs the mirror permit.
 * The wrapped {@code "v"} envelopes decode by delegating to the registered
 * {@link AttributeValueDeserializer} (self-describing envelope);
 * {@code WithinTolerance} magnitudes reverse the AMD-52 encoding through the
 * shared {@link AttributeValueDeserializer#readDouble} helper. Strict decode:
 * a missing/unknown discriminator or malformed payload throws
 * {@link JsonMappingException} (&rarr; {@link EventPayloadCodec} stage-2
 * {@code DegradedEvent} on the event surface). Stateless, thread-safe,
 * package-private.</p>
 *
 * @see ExpectationRefSerializer
 * @see PersistenceJacksonModule
 */
final class ExpectationRefDeserializer extends JsonDeserializer<ExpectationRef> {

    ExpectationRefDeserializer() {
        // Stateless; no initialization required.
    }

    @Override
    public ExpectationRef deserialize(JsonParser parser, DeserializationContext ctxt)
            throws IOException {
        JsonNode node = ctxt.readTree(parser);
        if (node == null || !node.isObject()) {
            throw JsonMappingException.from(parser,
                    "ExpectationRef envelope must be a JSON object");
        }

        JsonNode typeNode = node.get(ExpectationSerializer.FIELD_TYPE);
        if (typeNode == null || !typeNode.isTextual()) {
            throw JsonMappingException.from(parser,
                    "ExpectationRef envelope missing string '"
                            + ExpectationSerializer.FIELD_TYPE + "' discriminator");
        }

        String tag = typeNode.textValue();
        return switch (tag) {
            case ExpectationSerializer.TAG_EXACT_MATCH ->
                    new ExpectationRef.ExactMatchRef(readWrappedValue(node, ctxt, parser));
            case ExpectationSerializer.TAG_ANY_CHANGE ->
                    new ExpectationRef.AnyChangeRef(readWrappedValue(node, ctxt, parser));
            case ExpectationSerializer.TAG_ENUM_TRANSITION ->
                    new ExpectationRef.EnumTransitionRef(readEnumValue(node, parser));
            case ExpectationSerializer.TAG_WITHIN_TOLERANCE ->
                    readWithinTolerance(node, parser);
            default -> throw JsonMappingException.from(parser,
                    "Unknown ExpectationRef discriminator: '" + tag + "'");
        };
    }

    @Override
    public Class<?> handledType() {
        return ExpectationRef.class;
    }

    private static AttributeValue readWrappedValue(
            JsonNode node, DeserializationContext ctxt, JsonParser parser) throws IOException {
        JsonNode valueNode = node.get(ExpectationSerializer.FIELD_VALUE);
        if (valueNode == null) {
            throw JsonMappingException.from(parser,
                    "ExpectationRef envelope missing '"
                            + ExpectationSerializer.FIELD_VALUE + "' payload");
        }
        return ctxt.readTreeAsValue(valueNode, AttributeValue.class);
    }

    private static String readEnumValue(JsonNode node, JsonParser parser)
            throws JsonMappingException {
        JsonNode valueNode = node.get(ExpectationSerializer.FIELD_VALUE);
        if (valueNode == null || !valueNode.isTextual()) {
            throw JsonMappingException.from(parser,
                    "EnumTransition '" + ExpectationSerializer.FIELD_VALUE
                            + "' must be a JSON string");
        }
        return valueNode.textValue();
    }

    private static ExpectationRef.WithinToleranceRef readWithinTolerance(
            JsonNode node, JsonParser parser) throws JsonMappingException {
        double target = AttributeValueDeserializer.readDouble(
                requiredField(node, ExpectationSerializer.FIELD_TARGET, parser), parser);
        double tolerance = AttributeValueDeserializer.readDouble(
                requiredField(node, ExpectationSerializer.FIELD_TOLERANCE, parser), parser);
        return new ExpectationRef.WithinToleranceRef(target, tolerance);
    }

    private static JsonNode requiredField(JsonNode node, String field, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = node.get(field);
        if (value == null) {
            throw JsonMappingException.from(parser,
                    "WithinTolerance envelope missing '" + field + "' field");
        }
        return value;
    }
}

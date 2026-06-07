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
import com.homesynapse.device.AnyChange;
import com.homesynapse.device.EnumTransition;
import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.Expectation;
import com.homesynapse.device.WithinTolerance;
import com.homesynapse.value.AttributeValue;

import java.io.IOException;

/**
 * Jackson deserializer for the four-variant sealed {@link Expectation} hierarchy,
 * the inverse of {@link ExpectationSerializer} (AMD-87 §2 / AMD-87-INV-01).
 *
 * <p>Reads the explicit {@code "t"} permit-simple-name discriminator and reconstructs the
 * permit from the {@code "v"} (or {@code "target"}/{@code "tolerance"}) payload. The
 * {@link ExactMatch}/{@link AnyChange} {@code "v"} is decoded by delegating back to the
 * <strong>existing</strong> {@link AttributeValueDeserializer} via
 * {@link DeserializationContext#readTreeAsValue(JsonNode, Class)} — the wrapped envelope is
 * self-describing (it carries its own {@code "t"} {@code AttributeType} tag), so no element
 * schema is needed. {@link WithinTolerance}'s two doubles reverse the AMD-52 encoding through
 * the shared {@link AttributeValueDeserializer#readDouble} helper (numbers and the
 * {@code "NaN"}/{@code "+Inf"}/{@code "-Inf"} sentinels), so the magnitudes return
 * bit-identically.</p>
 *
 * <p><strong>Strict decode.</strong> A missing/unknown {@code "t"} tag, a missing payload, a
 * non-textual {@code EnumTransition} value, a malformed double, or a non-finite sentinel
 * outside {@code "NaN"}/{@code "+Inf"}/{@code "-Inf"} throws a {@link JsonMappingException}.
 * On the event-payload surface that exception is caught one layer up by {@link EventPayloadCodec}
 * (stage-2 → {@code DegradedEvent}). Dispatch is a {@code switch} on the {@code String} tag with
 * a rejecting {@code default} (the discriminator is an open string, unlike the serializer's
 * total switch over the sealed type).</p>
 *
 * <p>Stateless, thread-safe. Registered via {@link PersistenceJacksonModule}.
 * Package-private — internal persistence infrastructure, not public API.</p>
 *
 * @see ExpectationSerializer
 * @see AttributeValueDeserializer
 * @see PersistenceJacksonModule
 */
final class ExpectationDeserializer extends JsonDeserializer<Expectation> {

    ExpectationDeserializer() {
        // Stateless; no initialization required.
    }

    @Override
    public Expectation deserialize(JsonParser parser, DeserializationContext ctxt)
            throws IOException {
        JsonNode node = ctxt.readTree(parser);
        if (node == null || !node.isObject()) {
            throw JsonMappingException.from(parser, "Expectation envelope must be a JSON object");
        }

        JsonNode typeNode = node.get(ExpectationSerializer.FIELD_TYPE);
        if (typeNode == null || !typeNode.isTextual()) {
            throw JsonMappingException.from(parser,
                    "Expectation envelope missing string '"
                            + ExpectationSerializer.FIELD_TYPE + "' discriminator");
        }

        String tag = typeNode.textValue();
        return switch (tag) {
            case ExpectationSerializer.TAG_EXACT_MATCH ->
                    new ExactMatch(readWrappedValue(node, ctxt, parser));
            case ExpectationSerializer.TAG_ANY_CHANGE ->
                    new AnyChange(readWrappedValue(node, ctxt, parser));
            case ExpectationSerializer.TAG_ENUM_TRANSITION ->
                    new EnumTransition(readEnumValue(node, parser));
            case ExpectationSerializer.TAG_WITHIN_TOLERANCE ->
                    readWithinTolerance(node, parser);
            default -> throw JsonMappingException.from(parser,
                    "Unknown Expectation discriminator: '" + tag + "'");
        };
    }

    @Override
    public Class<?> handledType() {
        return Expectation.class;
    }

    /**
     * Decodes the {@code "v"} envelope of an {@link ExactMatch}/{@link AnyChange} by delegating
     * to the registered {@link AttributeValueDeserializer}.
     *
     * @param node   the Expectation envelope node
     * @param ctxt   the active context (carries the registered AttributeValue deserializer)
     * @param parser the active parser (for error location)
     * @return the reconstructed wrapped value, never {@code null}
     * @throws IOException on a missing {@code "v"} payload or an AttributeValue decode failure
     */
    private static AttributeValue readWrappedValue(
            JsonNode node, DeserializationContext ctxt, JsonParser parser) throws IOException {
        JsonNode valueNode = node.get(ExpectationSerializer.FIELD_VALUE);
        if (valueNode == null) {
            throw JsonMappingException.from(parser,
                    "Expectation envelope missing '"
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

    private static WithinTolerance readWithinTolerance(JsonNode node, JsonParser parser)
            throws JsonMappingException {
        double target = AttributeValueDeserializer.readDouble(
                requiredField(node, ExpectationSerializer.FIELD_TARGET, parser), parser);
        double tolerance = AttributeValueDeserializer.readDouble(
                requiredField(node, ExpectationSerializer.FIELD_TOLERANCE, parser), parser);
        return new WithinTolerance(target, tolerance);
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

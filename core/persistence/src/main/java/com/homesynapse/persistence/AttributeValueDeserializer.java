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
import com.homesynapse.value.ArrayValue;
import com.homesynapse.value.AttributeType;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.DegradedAttributeValue;
import com.homesynapse.value.EnumValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.QuantityValue;
import com.homesynapse.value.StringValue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson deserializer for the eight-variant sealed {@link AttributeValue} hierarchy,
 * the inverse of {@link AttributeValueSerializer} (AMD-52 §2.2 / AMD-52-INV-02).
 *
 * <p>Reads the explicit {@code "t"} {@link AttributeType} discriminator, maps it to the
 * variant, and constructs the value from the {@code "v"} (and {@code "u"} for
 * {@link QuantityValue}) payload. {@link ArrayValue} recurses this same envelope per element
 * — each element carries its own {@code "t"}, so the deserializer needs no element-schema
 * metadata (the envelope is self-describing).</p>
 *
 * <p><strong>Strict decode.</strong> An unknown/missing {@code "t"}, a malformed {@code "v"},
 * a non-finite sentinel token outside {@code "NaN"}/{@code "+Inf"}/{@code "-Inf"}, or a
 * variant-construction failure throws a {@link JsonMappingException}. That exception is
 * caught one layer up by {@link EventPayloadCodec} (stage-2 → {@code DegradedEvent}) on the
 * event-payload surface, or by {@link CheckpointSerializer#deserialize(byte[])}
 * ({@code IllegalStateException} → AMD-50 clear-and-replay reconciliation) on the checkpoint
 * surface. <strong>No {@code AttributeValueUpcaster} or schema resolver is consulted here</strong>
 * (DP-5 / AMD-52-INV-05): persistence holds no device/state schema knowledge.</p>
 *
 * <p>Stateless, thread-safe. Registered via {@link PersistenceJacksonModule}.
 * Package-private — internal persistence infrastructure, not public API.</p>
 *
 * @see AttributeValueSerializer
 * @see PersistenceJacksonModule
 */
final class AttributeValueDeserializer extends JsonDeserializer<AttributeValue> {

    AttributeValueDeserializer() {
        // Stateless; no initialization required.
    }

    @Override
    public AttributeValue deserialize(JsonParser parser, DeserializationContext ctxt)
            throws IOException {
        JsonNode node = ctxt.readTree(parser);
        return fromNode(node, parser);
    }

    @Override
    public Class<?> handledType() {
        return AttributeValue.class;
    }

    /**
     * Reconstructs one {@link AttributeValue} envelope node, recursing for
     * {@link ArrayValue} elements.
     *
     * @param node   the envelope object node
     * @param parser the active parser (carries location for error reporting)
     * @return the reconstructed value, never {@code null}
     * @throws JsonMappingException on a missing/unknown discriminator, malformed payload, or
     *                              variant-construction failure
     */
    private static AttributeValue fromNode(JsonNode node, JsonParser parser)
            throws JsonMappingException {
        if (node == null || !node.isObject()) {
            throw JsonMappingException.from(parser,
                    "AttributeValue envelope must be a JSON object");
        }

        JsonNode typeNode = node.get(AttributeValueSerializer.FIELD_TYPE);
        if (typeNode == null || !typeNode.isTextual()) {
            throw JsonMappingException.from(parser,
                    "AttributeValue envelope missing string '"
                            + AttributeValueSerializer.FIELD_TYPE + "' discriminator");
        }

        AttributeType type;
        try {
            type = AttributeType.valueOf(typeNode.textValue());
        } catch (IllegalArgumentException e) {
            throw JsonMappingException.from(parser,
                    "Unknown AttributeType discriminator: '" + typeNode.textValue() + "'");
        }

        return switch (type) {
            case BOOLEAN -> new BooleanValue(readBoolean(node, parser));
            case INT -> new IntValue(readLong(node, parser));
            case FLOAT -> new FloatValue(readDouble(requiredValue(node, parser), parser));
            case STRING -> new StringValue(readString(node, AttributeValueSerializer.FIELD_VALUE, parser));
            case ENUM -> new EnumValue(readString(node, AttributeValueSerializer.FIELD_VALUE, parser));
            case QUANTITY -> readQuantity(node, parser);
            case ARRAY -> readArray(node, parser);
            case DEGRADED -> readDegraded(node, parser);
        };
    }

    private static JsonNode requiredValue(JsonNode node, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = node.get(AttributeValueSerializer.FIELD_VALUE);
        if (value == null) {
            throw JsonMappingException.from(parser,
                    "AttributeValue envelope missing '"
                            + AttributeValueSerializer.FIELD_VALUE + "' payload");
        }
        return value;
    }

    private static boolean readBoolean(JsonNode node, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = requiredValue(node, parser);
        if (!value.isBoolean()) {
            throw JsonMappingException.from(parser, "BOOLEAN '"
                    + AttributeValueSerializer.FIELD_VALUE + "' must be a JSON boolean");
        }
        return value.booleanValue();
    }

    private static long readLong(JsonNode node, JsonParser parser) throws JsonMappingException {
        JsonNode value = requiredValue(node, parser);
        if (!value.isIntegralNumber()) {
            throw JsonMappingException.from(parser, "INT '"
                    + AttributeValueSerializer.FIELD_VALUE + "' must be an integral JSON number");
        }
        return value.longValue();
    }

    private static String readString(JsonNode node, String field, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw JsonMappingException.from(parser,
                    "Expected a JSON string for field '" + field + "'");
        }
        return value.textValue();
    }

    private static QuantityValue readQuantity(JsonNode node, JsonParser parser)
            throws JsonMappingException {
        double magnitude = readDouble(requiredValue(node, parser), parser);
        String unit = readString(node, AttributeValueSerializer.FIELD_UNIT, parser);
        try {
            return new QuantityValue(magnitude, unit);
        } catch (RuntimeException e) {
            // Construction enforces finite magnitude + recognised unit (AMD-47). A stored
            // envelope that violates either is corrupt — surface it as a mapping failure.
            throw JsonMappingException.from(parser,
                    "Invalid QuantityValue envelope: " + e.getMessage());
        }
    }

    private static ArrayValue readArray(JsonNode node, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = requiredValue(node, parser);
        if (!value.isArray()) {
            throw JsonMappingException.from(parser, "ARRAY '"
                    + AttributeValueSerializer.FIELD_VALUE + "' must be a JSON array");
        }
        List<AttributeValue> elements = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            elements.add(fromNode(element, parser));
        }
        return new ArrayValue(elements);
    }

    private static DegradedAttributeValue readDegraded(JsonNode node, JsonParser parser)
            throws JsonMappingException {
        String originalTypeName =
                readString(node, AttributeValueSerializer.FIELD_DEGRADED_ORIGINAL_TYPE, parser);
        String rawForm =
                readString(node, AttributeValueSerializer.FIELD_DEGRADED_RAW_FORM, parser);
        String failureReason =
                readString(node, AttributeValueSerializer.FIELD_DEGRADED_FAILURE_REASON, parser);
        try {
            return new DegradedAttributeValue(originalTypeName, rawForm, failureReason);
        } catch (RuntimeException e) {
            throw JsonMappingException.from(parser,
                    "Invalid DegradedAttributeValue envelope: " + e.getMessage());
        }
    }

    /**
     * Reverses {@link AttributeValueSerializer#writeDouble}: a number node decodes to its
     * {@code double}; a textual node must be exactly one of the non-finite sentinels
     * (AMD-52-INV-04). Any other token is a strict decode failure.
     *
     * @param value  the {@code "v"} node
     * @param parser the active parser (for error location)
     * @return the decoded {@code double}
     * @throws JsonMappingException if {@code value} is neither a number nor a known sentinel
     */
    static double readDouble(JsonNode value, JsonParser parser) throws JsonMappingException {
        if (value.isNumber()) {
            return value.doubleValue();
        }
        if (value.isTextual()) {
            String token = value.textValue();
            return switch (token) {
                case AttributeValueSerializer.SENTINEL_NAN -> Double.NaN;
                case AttributeValueSerializer.SENTINEL_POS_INF -> Double.POSITIVE_INFINITY;
                case AttributeValueSerializer.SENTINEL_NEG_INF -> Double.NEGATIVE_INFINITY;
                default -> throw JsonMappingException.from(parser,
                        "Unknown non-finite sentinel for floating-point '"
                                + AttributeValueSerializer.FIELD_VALUE + "': '" + token + "'");
            };
        }
        throw JsonMappingException.from(parser, "Floating-point '"
                + AttributeValueSerializer.FIELD_VALUE
                + "' must be a JSON number or a non-finite sentinel string");
    }
}

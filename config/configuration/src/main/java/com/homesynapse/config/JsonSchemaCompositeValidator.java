/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ConfigValidator} implementation over networknt
 * json-schema-validator (LTD-09) in allErrors mode — every issue in the
 * document is collected in a single pass (Doc 06 §3.1 stage 5, P4).
 *
 * <h2>Severity classification (Doc 06 §3.6)</h2>
 *
 * <p>Each schema violation maps to the three-tier model by its JSON Schema
 * keyword:</p>
 *
 * <ul>
 *   <li>{@code required} → {@link Severity#FATAL} — a missing required key
 *       survived the default merge, so no default exists to compensate;
 *       the document is structurally incomplete.</li>
 *   <li>{@code additionalProperties} → {@link Severity#WARNING} — an
 *       unknown key is a possible typo; the value is accepted as-is.</li>
 *   <li>everything else (type, enum, range, length, pattern, format) →
 *       {@link Severity#ERROR} — a value-level violation. The issue
 *       carries the schema default the startup pipeline applies in its
 *       place (DP-2); {@code appliedDefault} is {@code null} when the
 *       schema declares no default for the path.</li>
 * </ul>
 *
 * <p>Schema-validation issues carry no YAML line number: validation runs
 * against the merged map (Doc 06 §3.1 stage 4 output, AMD-71 §2.4
 * compose-after-merge), which no longer corresponds line-for-line to any
 * single file. Parse-stage issues are where line numbers live.</p>
 *
 * <p>The networknt {@code JsonSchema} type never crosses this class's
 * boundary — the frozen {@code ConfigValidator} contract takes JSON text
 * (JPMS hygiene; MODULE_CONTEXT key decision #3).</p>
 *
 * <p>Stateless and thread-safe; concurrent {@link #validate} calls are
 * independent.</p>
 */
final class JsonSchemaCompositeValidator implements ConfigValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final SchemaValidatorsConfig VALIDATORS_CONFIG =
            SchemaValidatorsConfig.builder()
                    .pathType(PathType.JSON_POINTER)
                    .build();

    private static final String KEYWORD_REQUIRED = "required";
    private static final String KEYWORD_ADDITIONAL_PROPERTIES = "additionalProperties";

    /** Creates a new validator. */
    JsonSchemaCompositeValidator() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    public List<ConfigIssue> validate(Map<String, Object> parsedConfig,
                                      String composedSchemaJson) {
        Objects.requireNonNull(parsedConfig, "parsedConfig must not be null");
        Objects.requireNonNull(composedSchemaJson, "composedSchemaJson must not be null");

        JsonNode schemaNode;
        try {
            schemaNode = MAPPER.readTree(composedSchemaJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "composed schema is not valid JSON: " + e.getOriginalMessage(), e);
        }
        JsonSchema schema = FACTORY.getSchema(schemaNode, VALIDATORS_CONFIG);
        JsonNode document = MAPPER.valueToTree(parsedConfig);

        Set<ValidationMessage> messages = schema.validate(document);
        List<ConfigIssue> issues = new ArrayList<>(messages.size());
        for (ValidationMessage message : messages) {
            issues.add(toIssue(message, parsedConfig, schemaNode));
        }
        // The validator returns a Set with no contractual order; sort for
        // deterministic reporting and stable tests.
        issues.sort(Comparator.comparing(ConfigIssue::path)
                .thenComparing(ConfigIssue::message));
        return List.copyOf(issues);
    }

    // ──────────────────────────────────────────────────────────────────
    // ValidationMessage → ConfigIssue
    // ──────────────────────────────────────────────────────────────────

    private static ConfigIssue toIssue(ValidationMessage message,
                                       Map<String, Object> parsedConfig,
                                       JsonNode schemaNode) {
        String keyword = message.getType();
        Severity severity = classify(keyword);
        List<String> segments = pathSegments(message, keyword);
        String path = String.join(".", segments);
        Object invalidValue = KEYWORD_REQUIRED.equals(keyword)
                ? null
                : valueAt(parsedConfig, segments);
        Object appliedDefault = severity == Severity.ERROR
                ? schemaDefaultAt(schemaNode, segments)
                : null;
        return new ConfigIssue(severity, path, message.getMessage(),
                invalidValue, appliedDefault, null);
    }

    private static Severity classify(String keyword) {
        return switch (keyword) {
            case KEYWORD_ADDITIONAL_PROPERTIES -> Severity.WARNING;
            case KEYWORD_REQUIRED -> Severity.FATAL;
            default -> Severity.ERROR;
        };
    }

    /**
     * Builds the dotted path to the offending key. The instance location is
     * a JSON pointer; for {@code required} and {@code additionalProperties}
     * it points at the containing object, so the violating property name is
     * appended from the message metadata.
     */
    private static List<String> pathSegments(ValidationMessage message, String keyword) {
        List<String> segments = new ArrayList<>();
        String pointer = message.getInstanceLocation().toString();
        if (pointer != null && !pointer.isEmpty() && !"/".equals(pointer)) {
            for (String rawSegment : pointer.substring(1).split("/", -1)) {
                segments.add(rawSegment.replace("~1", "/").replace("~0", "~"));
            }
        }
        if (KEYWORD_REQUIRED.equals(keyword)
                || KEYWORD_ADDITIONAL_PROPERTIES.equals(keyword)) {
            String property = message.getProperty();
            if ((property == null || property.isBlank())
                    && message.getArguments() != null
                    && message.getArguments().length > 0) {
                property = String.valueOf(message.getArguments()[0]);
            }
            if (property != null && !property.isBlank()) {
                segments.add(property);
            }
        }
        return segments;
    }

    private static Object valueAt(Map<String, Object> config, List<String> segments) {
        Object current = config;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    /**
     * Walks the composed schema's {@code properties} tree to the default
     * declared for the path, or {@code null} when none is declared.
     */
    private static Object schemaDefaultAt(JsonNode schemaNode, List<String> segments) {
        JsonNode current = schemaNode;
        for (String segment : segments) {
            JsonNode properties = current.get("properties");
            if (properties == null) {
                return null;
            }
            current = properties.get(segment);
            if (current == null) {
                return null;
            }
        }
        JsonNode defaultNode = current.get("default");
        return defaultNode == null
                ? null
                : MAPPER.convertValue(defaultNode, Object.class);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only index over the composed JSON Schema's custom annotations:
 * the per-property {@code x-reload} reload classification (Doc 06 §3.3, P5
 * — classification lives in the schema, not in code) and the
 * {@code x-sensitive} redaction marker (Doc 06 §12.4 — sensitive values
 * never appear in event payloads or logs).
 *
 * <p>Lookups walk the composed schema's {@code properties} tree by dotted
 * path, the same navigation {@link JsonSchemaCompositeValidator} uses for
 * schema defaults. A path that does not resolve to a property node yields
 * the absent answer ({@link Optional#empty()} / {@code false}) — the
 * callers' safe defaults ({@code PROCESS_RESTART} fallback per AMD-66
 * §2.3; non-sensitive) then apply.</p>
 *
 * <p>{@code x-reload} values are accepted case-insensitively with either
 * hyphen or underscore separators ({@code "hot"},
 * {@code "integration-restart"}, {@code "process_restart"}, …). An
 * unparseable value logs a WARNING and reads as unannotated, so the
 * disruptive-but-correct {@code PROCESS_RESTART} default governs.</p>
 *
 * <p>Stateless after construction; safe for concurrent reads.</p>
 */
final class SchemaAnnotationIndex {

    private static final Logger log =
            LoggerFactory.getLogger(SchemaAnnotationIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String X_RELOAD = "x-reload";
    private static final String X_SENSITIVE = "x-sensitive";

    private final JsonNode schemaRoot;

    /**
     * Creates an index over the composed schema.
     *
     * @param composedSchemaJson the composed root schema as JSON text (the
     *                           {@code SchemaRegistry} contract); never
     *                           {@code null}
     * @throws IllegalArgumentException if the schema is not valid JSON
     */
    SchemaAnnotationIndex(String composedSchemaJson) {
        Objects.requireNonNull(composedSchemaJson,
                "composedSchemaJson must not be null");
        try {
            this.schemaRoot = MAPPER.readTree(composedSchemaJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "composed schema is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Returns the {@code x-reload} classification declared for a section
     * key, or empty when the property is unannotated, unknown to the
     * schema, or carries an unparseable annotation value.
     *
     * @param sectionPath the dotted section path
     *                    (e.g., {@code "persistence.retention"})
     * @param key         the property key within the section
     * @return the declared classification, or empty for the AMD-66 §2.3
     *         fallback
     */
    Optional<ReloadClassification> reloadClassificationAt(String sectionPath, String key) {
        JsonNode property = propertyAt(sectionPath + "." + key);
        if (property == null) {
            return Optional.empty();
        }
        JsonNode annotation = property.get(X_RELOAD);
        if (annotation == null || !annotation.isTextual()) {
            return Optional.empty();
        }
        String normalized = annotation.asText()
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        try {
            return Optional.of(ReloadClassification.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown x-reload value '{}' at '{}.{}'; treating the"
                            + " property as unannotated (PROCESS_RESTART default)",
                    annotation.asText(), sectionPath, key);
            return Optional.empty();
        }
    }

    /**
     * Returns whether the schema marks the property at the dotted path
     * {@code x-sensitive: true} (Doc 06 §12.4). Dormant until sensitive
     * properties exist in the schema fragments — correctness over coverage.
     *
     * @param dottedPath the full dotted property path
     *                   (e.g., {@code "vault.api_secret"})
     * @return {@code true} only for an explicit boolean {@code true} marker
     */
    boolean sensitiveAt(String dottedPath) {
        JsonNode property = propertyAt(dottedPath);
        if (property == null) {
            return false;
        }
        JsonNode annotation = property.get(X_SENSITIVE);
        return annotation != null && annotation.isBoolean() && annotation.asBoolean();
    }

    /** Walks {@code properties.<segment>} per dotted segment; null when absent. */
    private JsonNode propertyAt(String dottedPath) {
        JsonNode current = schemaRoot;
        for (String segment : dottedPath.split("\\.", -1)) {
            JsonNode properties = current.get("properties");
            if (properties == null) {
                return null;
            }
            current = properties.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}

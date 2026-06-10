/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link SchemaRegistry} implementation composing core and integration
 * JSON Schema fragments into the single root schema the loading pipeline
 * validates against (Doc 06 §3.2, AMD-71 §2.4).
 *
 * <h2>Composition shape</h2>
 *
 * <p>The composed root is a draft 2020-12 object schema with one property
 * per registered core section, an {@code integrations} object whose
 * properties are keyed by integration type, and the AMD-67
 * {@code schema_version} property — an object {@code {major, minor}} whose
 * {@code major} is pinned by {@code const} to the runtime's declared major
 * (Doc 06 §3.2 / INV-CS-03) and which defaults to the declared pair so a
 * zero-configuration file validates. Fragments are embedded inline rather
 * than {@code $ref}-linked: fragments arrive as strings, not files, and
 * inline embedding keeps the composed document self-contained for the
 * {@code schemas/} cache and the VS Code integration.</p>
 *
 * <p>{@code additionalProperties: false} is set at the root and on the
 * {@code integrations} node so an unknown top-level key or unknown
 * integration type surfaces as a WARNING (possible typo) under the §3.6
 * classification — known sections own their interior strictness.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>A single {@link ReentrantLock} (LTD-11) guards the fragment maps and
 * the composition cache. The cache is invalidated on any registration and
 * recomposed on demand — the {@code schemas/} directory on disk is a
 * regenerable artifact, never the authoritative source (AMD-71 §2.4).</p>
 *
 * <p>The declared {@code (major, minor)} pair must match the pair the
 * composition root wires into {@code StandardConfigurationService} — the
 * service stamps models and triggers migration with its pair, while this
 * registry pins the {@code schema_version.major} constant the validator
 * enforces. Wiring different values is a composition-root defect.</p>
 */
final class StandardSchemaRegistry implements SchemaRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA_DIALECT =
            "https://json-schema.org/draft/2020-12/schema";

    private final int schemaMajor;
    private final int schemaMinor;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, String> coreSchemas = new LinkedHashMap<>();
    private final Map<String, String> integrationSchemas = new LinkedHashMap<>();
    private String composedCache;

    /**
     * Creates a registry declaring the runtime's config-document schema
     * version (AMD-67).
     *
     * @param schemaMajor the declared config-document schema major; the
     *                    composed {@code schema_version.major} constant;
     *                    must be {@code >= 1}
     * @param schemaMinor the declared config-document schema minor, used
     *                    for the {@code schema_version} default; must be
     *                    {@code >= 0}
     */
    StandardSchemaRegistry(int schemaMajor, int schemaMinor) {
        if (schemaMajor < 1) {
            throw new IllegalArgumentException(
                    "schemaMajor must be >= 1: " + schemaMajor);
        }
        if (schemaMinor < 0) {
            throw new IllegalArgumentException(
                    "schemaMinor must be >= 0: " + schemaMinor);
        }
        this.schemaMajor = schemaMajor;
        this.schemaMinor = schemaMinor;
    }

    @Override
    public void registerCoreSchema(String sectionName, String schemaJson) {
        register(coreSchemas, sectionName, "sectionName", schemaJson);
    }

    @Override
    public void registerIntegrationSchema(String integrationType, String schemaJson) {
        register(integrationSchemas, integrationType, "integrationType", schemaJson);
    }

    @Override
    public String getComposedSchema() {
        lock.lock();
        try {
            if (composedCache == null) {
                composedCache = compose();
            }
            return composedCache;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeComposedSchema(Path outputPath) throws IOException {
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        String composed = getComposedSchema();
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputPath, composed, StandardCharsets.UTF_8);
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    private void register(Map<String, String> target, String key, String keyName,
                          String schemaJson) {
        Objects.requireNonNull(key, keyName + " must not be null");
        Objects.requireNonNull(schemaJson, "schemaJson must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException(keyName + " must not be blank");
        }
        parseFragment(key, schemaJson);
        lock.lock();
        try {
            target.put(key, schemaJson);
            composedCache = null;
        } finally {
            lock.unlock();
        }
    }

    /** Must be called with the lock held. */
    private String compose() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", SCHEMA_DIALECT);
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        properties.set("schema_version", schemaVersionProperty());
        for (Map.Entry<String, String> entry : coreSchemas.entrySet()) {
            properties.set(entry.getKey(),
                    parseFragment(entry.getKey(), entry.getValue()));
        }
        ObjectNode integrations = properties.putObject("integrations");
        integrations.put("type", "object");
        ObjectNode integrationProperties = integrations.putObject("properties");
        for (Map.Entry<String, String> entry : integrationSchemas.entrySet()) {
            integrationProperties.set(entry.getKey(),
                    parseFragment(entry.getKey(), entry.getValue()));
        }
        integrations.put("additionalProperties", false);
        root.put("additionalProperties", false);
        return root.toPrettyString();
    }

    /**
     * The AMD-67 schema-version property: object form
     * {@code schema_version: { major: N, minor: M }} (no string-parse
     * ambiguity), {@code major} pinned to the declared constant.
     */
    private ObjectNode schemaVersionProperty() {
        ObjectNode schemaVersion = MAPPER.createObjectNode();
        schemaVersion.put("type", "object");
        ObjectNode versionProperties = schemaVersion.putObject("properties");
        ObjectNode major = versionProperties.putObject("major");
        major.put("type", "integer");
        major.put("const", schemaMajor);
        ObjectNode minor = versionProperties.putObject("minor");
        minor.put("type", "integer");
        minor.put("minimum", 0);
        schemaVersion.putArray("required").add("major").add("minor");
        schemaVersion.put("additionalProperties", false);
        ObjectNode defaultPair = schemaVersion.putObject("default");
        defaultPair.put("major", schemaMajor);
        defaultPair.put("minor", schemaMinor);
        return schemaVersion;
    }

    private static JsonNode parseFragment(String key, String schemaJson) {
        try {
            JsonNode fragment = MAPPER.readTree(schemaJson);
            if (!fragment.isObject()) {
                throw new IllegalArgumentException(
                        "schema fragment for '" + key + "' must be a JSON object");
            }
            return fragment;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "schema fragment for '" + key + "' is not valid JSON: "
                            + e.getOriginalMessage(), e);
        }
    }
}

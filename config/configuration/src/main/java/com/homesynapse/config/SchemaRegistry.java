/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Manages JSON Schema composition for configuration validation
 * (Doc 06 §8.6).
 *
 * <p>The {@code SchemaRegistry} collects schema fragments from core
 * subsystems and integration adapters and composes them into a single,
 * unified JSON Schema used by {@link ConfigValidator}. Core schemas are
 * registered during startup; integration schemas are contributed when
 * adapters are registered with the Integration Supervisor.</p>
 *
 * <h2>String-Based API</h2>
 *
 * <p>All schema parameters and return types use {@link String} (JSON text),
 * not external library types (e.g., {@code JsonSchema} from
 * {@code networknt:json-schema-validator}). This avoids leaking
 * implementation dependencies through the public JPMS module API. Phase 3
 * implementation will parse JSON strings into library types internally.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. Schema registration and composition may
 * occur concurrently with validation reads of
 * {@link #getComposedSchema()}.</p>
 *
 * @see ConfigValidator
 * @see ConfigurationService
 */
public interface SchemaRegistry {

    /**
     * Registers a static core subsystem schema fragment.
     *
     * <p>Core schemas define the structure for built-in configuration
     * sections (e.g., {@code "event_bus"}, {@code "persistence"},
     * {@code "state_store"}). They are registered once during startup
     * and remain fixed for the lifetime of the process.</p>
     *
     * @param sectionName the configuration section name this schema
     *                    governs (e.g., {@code "persistence"});
     *                    never {@code null}
     * @param schemaJson  the JSON Schema fragment as a JSON string
     *                    (not a file path); never {@code null}
     */
    void registerCoreSchema(String sectionName, String schemaJson);

    /**
     * Registers an integration adapter's schema fragment.
     *
     * <p>Integration schemas define the structure for
     * {@code integrations.{type}:} configuration sections. They are
     * contributed when adapters are registered with the Integration
     * Supervisor and may be added or removed dynamically.</p>
     *
     * @param integrationType the integration type identifier
     *                        (e.g., {@code "zigbee"}, {@code "mqtt"});
     *                        never {@code null}
     * @param schemaJson      the JSON Schema fragment as a JSON string
     *                        (not a file path); never {@code null}
     */
    void registerIntegrationSchema(String integrationType, String schemaJson);

    /**
     * Returns the fully composed JSON Schema as a JSON string.
     *
     * <p>The composed schema merges all registered core and integration
     * schema fragments into a single schema document suitable for passing
     * to {@link ConfigValidator#validate(java.util.Map, String)}.</p>
     *
     * @return the composed JSON Schema as a JSON string;
     *         never {@code null}
     */
    String getComposedSchema();

    /**
     * Serializes the composed JSON Schema to disk.
     *
     * <p>This method writes the output of {@link #getComposedSchema()} to
     * the specified file path, creating parent directories as needed. Used
     * by the {@code validate-config} CLI for offline schema inspection.</p>
     *
     * @param outputPath the file path to write the schema to;
     *                   never {@code null}
     * @throws IOException if writing to the file fails
     */
    void writeComposedSchema(Path outputPath) throws IOException;
}

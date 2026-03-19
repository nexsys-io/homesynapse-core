/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.List;
import java.util.Map;

/**
 * Validates parsed configuration against a composed JSON Schema
 * (Doc 06 §8.1).
 *
 * <p>{@code ConfigValidator} is a pure validation function with no side
 * effects. It validates the parsed YAML map against the fully composed
 * JSON Schema produced by {@link SchemaRegistry#getComposedSchema()} and
 * returns all validation issues in a single pass (allErrors mode).</p>
 *
 * <p>This interface is consumed by the loading pipeline (Doc 06 §3.1),
 * the reload pipeline (Doc 06 §3.3), and the {@code validate-config} CLI
 * command for offline validation.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations are thread-safe. Concurrent validation calls are
 * safe.</p>
 *
 * @see ConfigIssue
 * @see SchemaRegistry
 * @see ConfigurationService
 */
public interface ConfigValidator {

    /**
     * Validates the parsed configuration map against the composed JSON
     * Schema.
     *
     * <p>Validation runs in allErrors mode — all issues are collected in a
     * single pass rather than stopping at the first failure. The returned
     * list is unmodifiable.</p>
     *
     * @param parsedConfig       the parsed YAML configuration as a map;
     *                           never {@code null}
     * @param composedSchemaJson the fully composed JSON Schema as a JSON
     *                           string (not a file path); never {@code null}
     * @return an unmodifiable list of validation issues, empty if the
     *         configuration is fully valid; never {@code null}
     */
    List<ConfigIssue> validate(Map<String, Object> parsedConfig,
                               String composedSchemaJson);
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, validated in-memory representation of the HomeSynapse
 * configuration (Doc 06 §4.1).
 *
 * <p>{@code ConfigModel} is the single source of truth for runtime
 * configuration. It is produced by the loading pipeline
 * ({@link ConfigurationService#load()}) and replaced atomically on reload
 * ({@link ConfigurationService#reload()}). All subsystems read configuration
 * from the active {@code ConfigModel} via
 * {@link ConfigurationService#getCurrentModel()} or through the
 * integration-scoped {@link ConfigurationAccess} interface.</p>
 *
 * <h2>Schema Versioning (AMD-67)</h2>
 *
 * <p>The configuration document is versioned by the
 * {@code (configSchemaMajor, configSchemaMinor)} pair. The major version
 * identifies breaking config-layout changes and is the sole migration
 * trigger: a persisted major lower than the runtime's expected major runs
 * the {@link ConfigMigrator} chain, while a minor-only mismatch never
 * migrates — the loader tolerates older minors within the same major
 * (AMD-67-INV-02). The minor version identifies additive,
 * backward-compatible changes and resets to 0 on a major bump. On disk the
 * pair is the YAML top-level key
 * {@code schema_version: { major: N, minor: M }} (object form).</p>
 *
 * <p>This pair versions the <em>whole system config document</em>. It is a
 * distinct compatibility surface from the per-adapter config-schema pair on
 * {@code IntegrationDescriptor.configSchemaMajor()}/{@code configSchemaMinor()}
 * (AMD-54), which versions a single integration's configuration section and
 * drives {@code IntegrationAdapter.migrate(int, int)}. The two surfaces share
 * one idiom; no code path derives one from the other (AMD-67-INV-01).</p>
 *
 * <h2>Phase 2 Simplification</h2>
 *
 * <p>This Phase 2 version uses {@code Map<String, ConfigSection>} for
 * structured section access and {@code Map<String, Object>} for the complete
 * raw map. Typed subsystem records ({@code EventBusConfig},
 * {@code DeviceModelConfig}, etc.) are Phase 3 — they depend on each
 * subsystem's JSON Schema content.</p>
 *
 * <h2>Concurrency Token</h2>
 *
 * <p>The {@link #fileModifiedAt()} field captures the YAML file's
 * {@code mtime} at read time. The UI/API write path (Doc 06 §3.5) uses
 * this as an optimistic concurrency token: before writing, it compares
 * the stored {@code fileModifiedAt} against the file's current
 * {@code mtime}. If they differ, an external edit occurred and the write
 * is rejected with {@link java.util.ConcurrentModificationException}.</p>
 *
 * @param configSchemaMajor the breaking config-layout schema version; must be
 *                          {@code >= 1}; a lower persisted major than the
 *                          runtime expects triggers the migration chain
 * @param configSchemaMinor the additive, backward-compatible schema version;
 *                          must be {@code >= 0}; resets to 0 on a major bump;
 *                          a minor-only mismatch never triggers migration
 *                          (AMD-67-INV-02)
 * @param loadedAt          the instant this model was loaded or reloaded;
 *                          never {@code null}
 * @param fileModifiedAt    the YAML file's modification time at read time,
 *                          serving as the optimistic concurrency token for
 *                          the write path; never {@code null}
 * @param sections          structured access by dotted section path, unmodifiable;
 *                          never {@code null}
 * @param rawMap            the complete parsed-and-validated configuration map,
 *                          unmodifiable; never {@code null}
 *
 * @see ConfigSection
 * @see ConfigurationService
 * @see ConfigurationAccess
 * @see ConfigMigrator
 */
public record ConfigModel(
        int configSchemaMajor,
        int configSchemaMinor,
        Instant loadedAt,
        Instant fileModifiedAt,
        Map<String, ConfigSection> sections,
        Map<String, Object> rawMap
) {

    /**
     * Validates the schema-version pair and required fields, and makes both
     * maps unmodifiable.
     */
    public ConfigModel {
        if (configSchemaMajor < 1) {
            throw new IllegalArgumentException(
                    "configSchemaMajor must be >= 1: " + configSchemaMajor);
        }
        if (configSchemaMinor < 0) {
            throw new IllegalArgumentException(
                    "configSchemaMinor must be >= 0: " + configSchemaMinor);
        }
        Objects.requireNonNull(loadedAt, "loadedAt must not be null");
        Objects.requireNonNull(fileModifiedAt, "fileModifiedAt must not be null");
        Objects.requireNonNull(sections, "sections must not be null");
        Objects.requireNonNull(rawMap, "rawMap must not be null");
        sections = Map.copyOf(sections);
        rawMap = Map.copyOf(rawMap);
    }
}

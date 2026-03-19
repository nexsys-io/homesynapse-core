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
 * @param schemaVersion  the configuration schema version number
 * @param loadedAt       the instant this model was loaded or reloaded;
 *                       never {@code null}
 * @param fileModifiedAt the YAML file's modification time at read time,
 *                       serving as the optimistic concurrency token for
 *                       the write path; never {@code null}
 * @param sections       structured access by dotted section path, unmodifiable;
 *                       never {@code null}
 * @param rawMap         the complete parsed-and-validated configuration map,
 *                       unmodifiable; never {@code null}
 *
 * @see ConfigSection
 * @see ConfigurationService
 * @see ConfigurationAccess
 */
public record ConfigModel(
        int schemaVersion,
        Instant loadedAt,
        Instant fileModifiedAt,
        Map<String, ConfigSection> sections,
        Map<String, Object> rawMap
) {

    /**
     * Validates that all required fields are non-null and makes both maps
     * unmodifiable.
     */
    public ConfigModel {
        Objects.requireNonNull(loadedAt, "loadedAt must not be null");
        Objects.requireNonNull(fileModifiedAt, "fileModifiedAt must not be null");
        Objects.requireNonNull(sections, "sections must not be null");
        Objects.requireNonNull(rawMap, "rawMap must not be null");
        sections = Map.copyOf(sections);
        rawMap = Map.copyOf(rawMap);
    }
}

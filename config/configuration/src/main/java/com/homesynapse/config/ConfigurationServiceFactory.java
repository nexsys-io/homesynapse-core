/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.EventPublisher;
import com.homesynapse.platform.identity.SystemId;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Public assembly entry-point for the {@link ConfigurationService} (AB-3).
 *
 * <p>The production {@code ConfigurationService} implementation
 * ({@code StandardConfigurationService}), its {@code SchemaRegistry}
 * ({@code StandardSchemaRegistry}), and the {@code ConfigValidator}
 * ({@code JsonSchemaCompositeValidator}) are all package-private — there was no
 * public way to construct a {@code ConfigurationService} before this factory.
 * Because those three types are package-private, this factory must live in
 * {@code com.homesynapse.config}; the composition root (lifecycle/app) calls
 * {@link #create(Path, Clock, SystemId, EventPublisher)} and then registers core
 * schemas on the returned {@link SchemaRegistry} before invoking
 * {@link ConfigurationService#load()}.</p>
 *
 * <h2>The {@code (major, minor)} invariant</h2>
 *
 * <p>The declared config-document schema version is wired <strong>twice</strong>
 * — into the {@code StandardSchemaRegistry} and into the
 * {@code StandardConfigurationService} — and the two MUST match, or every load
 * fails validation (config MODULE_CONTEXT GOTCHA). This factory sources the pair
 * from the single {@link #CONFIG_SCHEMA_MAJOR}/{@link #CONFIG_SCHEMA_MINOR}
 * constants and passes the identical values to both, so a mismatch is impossible
 * by construction.</p>
 */
public final class ConfigurationServiceFactory {

    /**
     * The declared config-document schema major version. Pinned as the composed
     * {@code schema_version.major} const; current value matches every existing
     * construction site (major = 1).
     */
    public static final int CONFIG_SCHEMA_MAJOR = 1;

    /**
     * The declared config-document schema minor version (current value 0).
     */
    public static final int CONFIG_SCHEMA_MINOR = 0;

    private ConfigurationServiceFactory() {
        // Static factory — no instantiation.
    }

    /**
     * The assembled configuration subsystem: the {@link ConfigurationService}
     * the composition root drives, plus the {@link SchemaRegistry} it must use to
     * register core schemas (e.g. the automation schema) before
     * {@link ConfigurationService#load()}.
     *
     * @param service        the wired configuration service; never {@code null}
     * @param schemaRegistry the schema registry the service validates against —
     *                       the SAME instance wired into the service, so schemas
     *                       registered here are visible to {@code load()};
     *                       never {@code null}
     */
    public record Assembly(ConfigurationService service, SchemaRegistry schemaRegistry) {

        /** Validates non-null components. */
        public Assembly {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        }
    }

    /**
     * Assembles a {@link ConfigurationService} against the given config
     * directory, wiring a version-matched {@link SchemaRegistry}, the resolving
     * {@link SecretStore} (for {@code !secret}/{@code !env} tag resolution), the
     * JSON-Schema validator, {@link System#getenv(String)} as the {@code !env}
     * lookup, and no migrators/listeners (none exist at this baseline).
     *
     * <p>The returned service is NOT yet loaded — the composition root registers
     * core schemas on {@link Assembly#schemaRegistry()} and then calls
     * {@link ConfigurationService#load()} (the first subsystem init step,
     * Doc 12 Phase 1).</p>
     *
     * @param configDir      the resolved configuration directory; never {@code null}
     * @param clock          injected clock (NO_DIRECT_TIME_ACCESS); never {@code null}
     * @param systemId       the system identity (subject of config observability
     *                       events); never {@code null}
     * @param eventPublisher the publisher for AMD-70 config observability events;
     *                       never {@code null}
     * @return the assembled {@link Assembly}; never {@code null}
     */
    public static Assembly create(Path configDir,
                                  Clock clock,
                                  SystemId systemId,
                                  EventPublisher eventPublisher) {
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(eventPublisher, "eventPublisher");

        // One source of truth for (major, minor) -> mismatch impossible.
        SchemaRegistry schemaRegistry =
                new StandardSchemaRegistry(CONFIG_SCHEMA_MAJOR, CONFIG_SCHEMA_MINOR);

        // Config-secret substrate (!secret/!env resolution). This is the
        // config-secret SecretStore, distinct from the at-rest PayloadCipher
        // (AB-4) — assemble it here; the at-rest cipher stays inert.
        SecretStore secretStore = SecretStore.create(
                configDir, ScopeKeyManager.create(configDir, clock), clock);

        ConfigurationService service = new StandardConfigurationService(
                configDir,
                CONFIG_SCHEMA_MAJOR,
                CONFIG_SCHEMA_MINOR,
                clock,
                systemId,
                eventPublisher,
                schemaRegistry,
                new JsonSchemaCompositeValidator(),
                List.of(),          // no production ConfigMigrator (AMD-13 framework)
                List.of(),          // no ConfigurationChangeListeners at boot
                secretStore,
                System::getenv);    // !env resolver

        return new Assembly(service, schemaRegistry);
    }
}

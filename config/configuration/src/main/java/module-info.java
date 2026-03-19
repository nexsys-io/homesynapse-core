/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Configuration System — YAML loading, schema validation, secrets management,
 * hot reload, and integration-scoped configuration access (Doc 06).
 *
 * <p>This module defines the public API contracts for configuration management.
 * All subsystems receive their runtime configuration through
 * {@link com.homesynapse.config.ConfigModel} and
 * {@link com.homesynapse.config.ConfigurationAccess} rather than parsing YAML
 * independently.</p>
 */
module com.homesynapse.config {
    requires transitive com.homesynapse.event;

    exports com.homesynapse.config;
}

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

    // Third-party, non-transitive (Nick ruling 2026-06-10): consumed only by
    // the package-private M6.1a pipeline classes; never exposed on the public
    // API (-Xlint:exports silent). The HomeSynapse-module edge set above is
    // unchanged — the [AMD-71-A] zero-new-edge property holds.
    requires org.snakeyaml.engine.v2;
    requires com.networknt.schema;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;

    exports com.homesynapse.config;
}

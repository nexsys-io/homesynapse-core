/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * The {@code automations.yaml} JSON-Schema fragment and its registration section name
 * (AMD-93 §2.3; Doc 06 §3.2) — config-free constants the composition root uses to
 * register the schema and to feed {@link AutomationDefinitionLoader}.
 *
 * <p><strong>Why these live here and not in a config bridge.</strong> The automation
 * module must not depend on {@code com.homesynapse.config} — the {@code core→config}
 * edge is forbidden by {@code assertAllowedModuleDependencies} at every scope (the
 * "Core depends only on platform + other core" layer rule), independent of the exported-API
 * §authoring check. So the schema-registration + definition-load wiring lives at the
 * composition root (`lifecycle`/`app`, which may depend on both `core` and `config`) at
 * app-bootstrap. These plain {@code String} constants carry no config dependency, so the
 * schema text itself stays near the automation module that owns its shape. The composition
 * root performs, when config + the registries are assembled:</p>
 *
 * <pre>{@code
 * schemaRegistry.registerCoreSchema(AutomationSchema.SCHEMA_SECTION, AutomationSchema.SCHEMA_JSON);
 * LoadResult result = loader.load(configurationService.getCurrentModel().rawMap());
 * }</pre>
 *
 * <p>Deep per-definition validation is performed fail-closed by the
 * {@link AutomationDefinitionLoader} (Java-side, SD-9); this fragment guards the document
 * shape registered through the M6.1 composite-validation pipeline.</p>
 */
public final class AutomationSchema {

    /** Schema-fragment section name for {@code SchemaRegistry.registerCoreSchema}. */
    public static final String SCHEMA_SECTION = "automation";

    /** Minimal structural JSON-Schema fragment for {@code automations.yaml}. */
    public static final String SCHEMA_JSON = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "schema_version": {
                  "type": "object",
                  "properties": {
                    "major": { "type": "integer", "minimum": 1 },
                    "minor": { "type": "integer", "minimum": 0 }
                  }
                },
                "automations": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["name", "triggers", "actions"],
                    "properties": {
                      "name": { "type": "string" },
                      "triggers": { "type": "array", "minItems": 1 },
                      "actions": { "type": "array", "minItems": 1 }
                    }
                  }
                }
              }
            }
            """;

    private AutomationSchema() {
        // Constant holder — non-instantiable.
    }
}

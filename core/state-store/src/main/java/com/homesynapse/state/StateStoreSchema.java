/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

/**
 * The {@code state_store} section's JSON-Schema fragment and its registration section name
 * (IR-61b; Doc 03 §9; Doc 06 §3.2) — config-free constants in the {@code AutomationSchema}
 * form: the composition root registers the fragment as a core schema before
 * {@code load()}, so the section validates against it instead of drawing the root's
 * unknown-property WARNING at every boot. This module carries no dependency on the
 * configuration module.
 *
 * <p>Inside {@code staleness} the three keys are typed and no other key is allowed:
 * {@code default_staleness_threshold} a string or {@code null} (an ISO-8601 duration that
 * {@link StalenessConfig} parses), {@code staleness_overrides} an object of string values
 * keyed by entity ULID, {@code scan_interval_seconds} an integer 5–300 (accepted, no effect —
 * the passive scanner is not built). A YAML {@code null} for the section, the block or the
 * override map validates, as {@link StalenessConfig} reads it as absent. The section itself
 * keeps {@code additionalProperties: true}: Doc 03 §9 documents {@code state_store} keys this
 * codebase does not read yet, and they do not become warnings here. No {@code default} is
 * declared — Doc 06 §3.1 stage 4 would merge it into every boot's model.</p>
 */
public final class StateStoreSchema {

    /** Schema-fragment section name for {@code SchemaRegistry.registerCoreSchema}. */
    public static final String SCHEMA_SECTION = "state_store";

    /** The structural JSON-Schema fragment for the {@code state_store} section. */
    public static final String SCHEMA_JSON = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": ["object", "null"],
              "properties": {
                "staleness": {
                  "type": ["object", "null"],
                  "properties": {
                    "default_staleness_threshold": { "type": ["string", "null"] },
                    "staleness_overrides": {
                      "type": ["object", "null"],
                      "additionalProperties": { "type": "string" }
                    },
                    "scan_interval_seconds": { "type": "integer", "minimum": 5, "maximum": 300 }
                  },
                  "additionalProperties": false
                }
              },
              "additionalProperties": true
            }
            """;

    private StateStoreSchema() {
        // Constant holder — non-instantiable.
    }
}

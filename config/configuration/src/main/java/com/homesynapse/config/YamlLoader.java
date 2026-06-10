/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import org.snakeyaml.engine.v2.api.ConstructNode;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.ConstructorException;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stages 1–2 of the configuration loading pipeline (Doc 06 §3.1): reads the
 * AMD-71 root document {@code homesynapse.yaml} and parses it as YAML 1.2
 * via snakeyaml-engine (LTD-09), splicing one-level {@code !include}
 * directives from the {@code integrations/} directory.
 *
 * <h2>Safety properties</h2>
 *
 * <ul>
 *   <li><strong>Safe-by-default parse.</strong> The engine's standard
 *       constructor set builds only maps, lists, and scalars — an unknown
 *       tag is a constructor error (FATAL), never a Java instantiation.
 *       {@code !secret}/{@code !env} resolution is the M6.2 SecretStore
 *       seam; until it lands those tags fail as unknown.</li>
 *   <li><strong>YAML 1.2 Core-schema scalar resolution (LTD-09).</strong>
 *       Root and included files are both parsed with an explicit
 *       {@link CoreSchema} — the engine's default is the JSON schema,
 *       under which {@code ~} is the string {@code "~"} rather than null.
 *       With the Core schema, {@code ~}, {@code null} (any case), and the
 *       empty scalar all resolve to null = unset, so normalization drops
 *       the key and the schema default applies downstream.</li>
 *   <li><strong>One-level include (AMD-71-INV-02).</strong> Included files
 *       are parsed with NO {@code !include} constructor registered, so a
 *       nested include surfaces structurally as an unknown-tag error.</li>
 *   <li><strong>Path-traversal guard (AMD-71-INV-01).</strong> Every
 *       include target is canonicalized with {@link Path#toRealPath} and
 *       must be contained in {@code ${config_dir}/integrations/} after
 *       symlink resolution. Containment is path-based, never
 *       string-prefix-based. Violations are rejected fail-closed with the
 *       offending path named.</li>
 * </ul>
 *
 * <p>All failures are reported as {@link Severity#FATAL} {@link ConfigIssue}
 * records — structural errors per the §3.6 three-tier model. The caller
 * (the loading pipeline) aborts when {@link Result#issues()} is non-empty;
 * a validation pass never runs over a structurally broken document.</p>
 *
 * <p>Instances are single-use per load: construct, call {@link #load()},
 * discard. No state is retained between loads.</p>
 */
final class YamlLoader {

    /** Root document file name fixed by the AMD-71 §2.1 layout. */
    static final String ROOT_DOCUMENT_NAME = "homesynapse.yaml";

    /** Per-integration include directory fixed by the AMD-71 §2.1 layout. */
    static final String INTEGRATIONS_DIRECTORY = "integrations";

    private static final Tag INCLUDE_TAG = new Tag("!include");

    private final Path configDir;

    /**
     * Creates a loader rooted at the resolved configuration directory.
     *
     * @param configDir the resolved {@code PlatformPaths.configDir()} path,
     *                  injected by the composition root (DP-3 / AMD-71-A —
     *                  this module takes the {@code Path}, not a platform
     *                  edge); never {@code null}
     */
    YamlLoader(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir, "configDir must not be null");
    }

    /**
     * Outcome of one parse pass.
     *
     * <p>When {@code issues} is non-empty every entry is FATAL and
     * {@code document} is empty — a structurally broken document is never
     * partially returned. The document map is freshly built and mutable so
     * the pipeline can migrate and merge without copying.</p>
     *
     * @param document the parsed root document with includes spliced;
     *                 string-keyed throughout, {@code null} values dropped
     * @param issues   FATAL structural issues; empty on success
     */
    record Result(Map<String, Object> document, List<ConfigIssue> issues) {

        /** Makes the issue list unmodifiable. */
        Result {
            issues = List.copyOf(issues);
        }
    }

    /**
     * Reads and parses the root document, splicing includes.
     *
     * <p>An absent, empty, or comment-only root document yields an empty
     * map with no issues — zero-configuration is valid (INV-CE-02); the
     * schema-default merge produces the complete model downstream.</p>
     *
     * @return the parse outcome; never {@code null}
     */
    Result load() {
        Path rootFile = configDir.resolve(ROOT_DOCUMENT_NAME);
        if (!Files.exists(rootFile)) {
            return new Result(new LinkedHashMap<>(), List.of());
        }

        String text;
        try {
            text = Files.readString(rootFile);
        } catch (IOException e) {
            return fatal(ROOT_DOCUMENT_NAME,
                    "configuration file cannot be read: " + e.getMessage(), null);
        }
        if (text.isBlank()) {
            return new Result(new LinkedHashMap<>(), List.of());
        }

        Object raw;
        try {
            raw = new Load(rootSettings()).loadFromString(text);
        } catch (IncludeException e) {
            return fatal(e.includePath, e.getMessage(), null);
        } catch (MarkedYamlEngineException e) {
            Integer line = e.getProblemMark()
                    .map(mark -> mark.getLine() + 1)
                    .orElse(null);
            return fatal(ROOT_DOCUMENT_NAME, problemOf(e), line);
        } catch (YamlEngineException e) {
            return fatal(ROOT_DOCUMENT_NAME, e.getMessage(), null);
        }

        if (raw == null) {
            return new Result(new LinkedHashMap<>(), List.of());
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return fatal(ROOT_DOCUMENT_NAME,
                    "root of " + ROOT_DOCUMENT_NAME + " must be a YAML mapping", null);
        }

        try {
            return new Result(normalize(rawMap), List.of());
        } catch (NormalizationException e) {
            return fatal(e.path, e.getMessage(), null);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Engine settings
    // ──────────────────────────────────────────────────────────────────

    private LoadSettings rootSettings() {
        return LoadSettings.builder()
                .setLabel(ROOT_DOCUMENT_NAME)
                .setAllowDuplicateKeys(false)
                .setSchema(new CoreSchema())
                .setTagConstructors(Map.of(INCLUDE_TAG, new IncludeConstructor()))
                .build();
    }

    /**
     * Settings for parsing an included file. Deliberately registers NO
     * {@code !include} constructor so a nested include is an unknown-tag
     * constructor error — the one-level restriction is structural, not a
     * depth counter (AMD-71 §4). The schema MUST match {@link #rootSettings()}
     * — root and included files resolve scalars identically or the same
     * literal means different things depending on which file it sits in.
     */
    private static LoadSettings includedSettings(String label) {
        return LoadSettings.builder()
                .setLabel(label)
                .setAllowDuplicateKeys(false)
                .setSchema(new CoreSchema())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────
    // !include constructor (AMD-71 §2.2 / §2.3)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Resolves an {@code !include <relative-path>} directive: containment
     * check first (fail closed), then a one-level parse of the target.
     */
    private final class IncludeConstructor implements ConstructNode {

        /** Creates the include constructor. */
        IncludeConstructor() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public Object construct(Node node) {
            if (!(node instanceof ScalarNode scalar)) {
                throw new IncludeException(INCLUDE_TAG.getValue(),
                        "!include requires a single relative path scalar");
            }
            String includePath = scalar.getValue();
            Path target = configDir.resolve(includePath).normalize();
            Path realTarget;
            try {
                if (!Files.exists(target)) {
                    throw new IncludeException(includePath,
                            "include target does not exist: " + includePath);
                }
                // toRealPath resolves symlinks on BOTH sides, so containment
                // holds against symlink escape, ../ traversal, and absolute
                // paths alike (AMD-71 §2.3 — canonicalization, not string
                // prefix matching).
                Path realIntegrations = configDir
                        .resolve(INTEGRATIONS_DIRECTORY).toRealPath();
                realTarget = target.toRealPath();
                if (!realTarget.startsWith(realIntegrations)) {
                    throw new IncludeException(includePath,
                            "include target escapes " + INTEGRATIONS_DIRECTORY
                                    + "/ and is rejected fail-closed: " + includePath
                                    + " (resolved to " + realTarget + ")");
                }
            } catch (IOException e) {
                throw new IncludeException(includePath,
                        "include target cannot be resolved: " + includePath
                                + " (" + e.getMessage() + ")");
            }

            String text;
            try {
                text = Files.readString(realTarget);
            } catch (IOException e) {
                throw new IncludeException(includePath,
                        "include target cannot be read: " + includePath
                                + " (" + e.getMessage() + ")");
            }
            try {
                return new Load(includedSettings(includePath)).loadFromString(text);
            } catch (ConstructorException e) {
                // No !include constructor is registered for included files;
                // a nested include surfaces here as an unknown-tag error.
                String problem = problemOf(e);
                if (problem.contains(INCLUDE_TAG.getValue())) {
                    throw new IncludeException(includePath,
                            "nested !include is not permitted — includes are one"
                                    + " level deep (AMD-71): " + includePath);
                }
                throw new IncludeException(includePath,
                        "included file failed to parse: " + includePath
                                + " (" + problem + ")");
            } catch (MarkedYamlEngineException e) {
                throw new IncludeException(includePath,
                        "included file failed to parse: " + includePath
                                + " (" + problemOf(e) + ")");
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Normalization
    // ──────────────────────────────────────────────────────────────────

    /**
     * Rebuilds the parsed tree as string-keyed {@link LinkedHashMap}s,
     * dropping {@code null}-valued keys: a YAML null means "unset", and an
     * unset key takes its schema default in the merge stage. Dropping (not
     * carrying) nulls also satisfies the null-hostile {@code Map.copyOf}
     * defensive copies in the frozen {@code ConfigModel}/{@code ConfigSection}
     * shapes.
     */
    private static Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new NormalizationException(String.valueOf(entry.getKey()),
                        "configuration keys must be strings: " + entry.getKey());
            }
            Object value = normalizeValue(entry.getValue());
            if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalize(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalizeValue(item));
            }
            return out;
        }
        return value;
    }

    // ──────────────────────────────────────────────────────────────────
    // Issue plumbing
    // ──────────────────────────────────────────────────────────────────

    private static Result fatal(String path, String message, Integer yamlLine) {
        return new Result(new LinkedHashMap<>(), List.of(
                new ConfigIssue(Severity.FATAL, path, message, null, null, yamlLine)));
    }

    private static String problemOf(MarkedYamlEngineException e) {
        String problem = e.getProblem();
        return problem != null && !problem.isBlank() ? problem : e.getMessage();
    }

    /**
     * Include-mechanism failure. Extends {@link YamlEngineException} so the
     * engine's construct phase propagates it unwrapped (the engine rethrows
     * {@code YamlEngineException} as-is but wraps other runtime exceptions).
     */
    private static final class IncludeException extends YamlEngineException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /** The offending include path, named in the FATAL issue (AMD-71 §2.3). */
        final transient String includePath;

        IncludeException(String includePath, String message) {
            super(message);
            this.includePath = includePath;
        }
    }

    /** Non-string-key failure during tree normalization. */
    private static final class NormalizationException extends RuntimeException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        final transient String path;

        NormalizationException(String path, String message) {
            super(message);
            this.path = path;
        }
    }
}

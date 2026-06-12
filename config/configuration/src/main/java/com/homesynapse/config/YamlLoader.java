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
import java.util.function.Function;

/**
 * Stages 1–3 of the configuration loading pipeline (Doc 06 §3.1): reads the
 * AMD-71 root document {@code homesynapse.yaml} and parses it as YAML 1.2
 * via snakeyaml-engine (LTD-09), splicing one-level {@code !include}
 * directives from the {@code integrations/} directory and resolving the
 * stage-3 {@code !secret}/{@code !env} tags (M6.2, DP-10).
 *
 * <h2>Safety properties</h2>
 *
 * <ul>
 *   <li><strong>Safe-by-default parse.</strong> The engine's standard
 *       constructor set builds only maps, lists, and scalars — an unknown
 *       tag is a constructor error (FATAL), never a Java instantiation.</li>
 *   <li><strong>Stage-3 tag resolution (Doc 06 §3.1/§3.4 — M6.2).</strong>
 *       In the resolving form, {@code !secret <key>} resolves through
 *       {@link SecretStore#resolve} and {@code !env <VAR>} /
 *       {@code !env <VAR>:<default>} through the injected environment
 *       lookup, in the root AND in included documents. A missing secret
 *       key or unset variable without a default is a FATAL
 *       {@link ConfigIssue} naming the key or variable — never any value
 *       (LTD-15/§12.3) — and failures are collected across the whole
 *       parse, not thrown at the first one. Resolved values exist only in
 *       the parse tree (§3.4).</li>
 *   <li><strong>Write-path form rejects tags fail-closed.</strong> The
 *       one-argument constructor performs NO tag resolution: a document
 *       carrying {@code !secret}/{@code !env} is rejected FATAL, because
 *       the §3.5 programmatic rewrite re-emits the parsed tree — resolving
 *       first would bake plaintext secrets into the file (INV-SE-03).</li>
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
 * discard. No state is retained between loads — the collected tag issues
 * make reuse incorrect by construction.</p>
 */
final class YamlLoader {

    /** Root document file name fixed by the AMD-71 §2.1 layout. */
    static final String ROOT_DOCUMENT_NAME = "homesynapse.yaml";

    /** Per-integration include directory fixed by the AMD-71 §2.1 layout. */
    static final String INTEGRATIONS_DIRECTORY = "integrations";

    private static final Tag INCLUDE_TAG = new Tag("!include");
    private static final Tag SECRET_TAG = new Tag("!secret");
    private static final Tag ENV_TAG = new Tag("!env");

    private final Path configDir;
    private final SecretStore secretStore;
    private final Function<String, String> envLookup;
    private final boolean resolving;

    /**
     * Stage-3 tag failures collected across the whole parse (DP-10 —
     * every missing key/variable is reported in one pass, matching the
     * §3.6 comprehensive-not-fatal validation spirit).
     */
    private final List<ConfigIssue> tagIssues = new ArrayList<>();

    /**
     * Creates the NON-resolving write-path loader (Doc 06 §3.5). A
     * {@code !secret}/{@code !env} tag is a FATAL issue under this form —
     * the write path re-emits the parsed tree, and a resolved tag would be
     * re-emitted as its plaintext value (INV-SE-03 fail-closed).
     *
     * @param configDir the resolved {@code PlatformPaths.configDir()} path,
     *                  injected by the composition root (DP-3 / AMD-71-A —
     *                  this module takes the {@code Path}, not a platform
     *                  edge); never {@code null}
     */
    YamlLoader(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir, "configDir must not be null");
        this.secretStore = null;
        this.envLookup = null;
        this.resolving = false;
    }

    /**
     * Creates the resolving load/reload-pipeline loader (Doc 06 §3.1
     * stage 3, M6.2).
     *
     * @param configDir   the resolved configuration directory;
     *                    never {@code null}
     * @param secretStore resolver for {@code !secret} tags; the decrypted
     *                    store is consulted per tag and discarded (§3.4);
     *                    never {@code null}
     * @param envLookup   resolver for {@code !env} tags — the composition
     *                    root passes {@code System::getenv}, tests pass a
     *                    map; never {@code null}
     */
    YamlLoader(Path configDir, SecretStore secretStore,
               Function<String, String> envLookup) {
        this.configDir = Objects.requireNonNull(configDir, "configDir must not be null");
        this.secretStore =
                Objects.requireNonNull(secretStore, "secretStore must not be null");
        this.envLookup = Objects.requireNonNull(envLookup, "envLookup must not be null");
        this.resolving = true;
    }

    /**
     * Outcome of one parse pass.
     *
     * <p>When {@code issues} is non-empty every entry is FATAL and
     * {@code document} is empty — a structurally broken document is never
     * partially returned. The document map is freshly built and mutable so
     * the pipeline can migrate and merge without copying.</p>
     *
     * @param document the parsed root document with includes spliced and
     *                 (in the resolving form) tags resolved; string-keyed
     *                 throughout, {@code null} values dropped
     * @param issues   FATAL structural issues; empty on success
     */
    record Result(Map<String, Object> document, List<ConfigIssue> issues) {

        /** Makes the issue list unmodifiable. */
        Result {
            issues = List.copyOf(issues);
        }
    }

    /**
     * Reads and parses the root document, splicing includes and resolving
     * stage-3 tags in the resolving form.
     *
     * <p>An absent, empty, or comment-only root document yields an empty
     * map with no issues — zero-configuration is valid (INV-CE-02); the
     * schema-default merge produces the complete model downstream. A
     * tag-free document consults neither the secret store nor the
     * environment, so a no-secrets install touches no key files.</p>
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

        // Stage-3 failures are collected, not thrown — report them all in
        // one pass (DP-10). The document is discarded: a partially
        // resolved tree must never reach validation.
        if (!tagIssues.isEmpty()) {
            return new Result(new LinkedHashMap<>(), tagIssues);
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
                .setTagConstructors(Map.of(
                        INCLUDE_TAG, new IncludeConstructor(),
                        SECRET_TAG, secretConstructor(),
                        ENV_TAG, envConstructor()))
                .build();
    }

    /**
     * Settings for parsing an included file. Deliberately registers NO
     * {@code !include} constructor so a nested include is an unknown-tag
     * constructor error — the one-level restriction is structural, not a
     * depth counter (AMD-71 §4). {@code !secret}/{@code !env} ARE
     * registered: stage-3 resolution applies to included documents too
     * (DP-10). The schema MUST match {@link #rootSettings()} — root and
     * included files resolve scalars identically or the same literal means
     * different things depending on which file it sits in.
     */
    private LoadSettings includedSettings(String label) {
        return LoadSettings.builder()
                .setLabel(label)
                .setAllowDuplicateKeys(false)
                .setSchema(new CoreSchema())
                .setTagConstructors(Map.of(
                        SECRET_TAG, secretConstructor(),
                        ENV_TAG, envConstructor()))
                .build();
    }

    private ConstructNode secretConstructor() {
        return resolving
                ? new SecretConstructor()
                : new WritePathRejectingConstructor(SECRET_TAG.getValue());
    }

    private ConstructNode envConstructor() {
        return resolving
                ? new EnvConstructor()
                : new WritePathRejectingConstructor(ENV_TAG.getValue());
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
    // Stage-3 tag constructors (Doc 06 §3.1/§3.4 — M6.2, DP-10)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Resolves {@code !secret <key>} through the secret store. A missing
     * key collects a FATAL issue naming the KEY — never any value
     * (LTD-15/§12.3) — and the parse continues so every failure is
     * reported in one pass.
     */
    private final class SecretConstructor implements ConstructNode {

        /** Creates the secret constructor. */
        SecretConstructor() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public Object construct(Node node) {
            if (!(node instanceof ScalarNode scalar) || scalar.getValue().isBlank()) {
                return collectTagIssue(SECRET_TAG.getValue(),
                        "!secret requires a single secret-key scalar");
            }
            String key = scalar.getValue();
            try {
                return secretStore.resolve(key);
            } catch (IllegalArgumentException e) {
                return collectTagIssue(key,
                        "secret key is not in the secret store: " + key);
            }
        }
    }

    /**
     * Resolves {@code !env <VAR>} or {@code !env <VAR>:<default>} through
     * the injected environment lookup. The default is everything after the
     * FIRST colon (it may itself contain colons, or be empty). An unset
     * variable without a default collects a FATAL issue naming the
     * variable.
     */
    private final class EnvConstructor implements ConstructNode {

        /** Creates the env constructor. */
        EnvConstructor() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public Object construct(Node node) {
            if (!(node instanceof ScalarNode scalar)) {
                return collectTagIssue(ENV_TAG.getValue(),
                        "!env requires a single VAR or VAR:default scalar");
            }
            String spec = scalar.getValue();
            int separator = spec.indexOf(':');
            String variable = separator >= 0 ? spec.substring(0, separator) : spec;
            String fallback = separator >= 0 ? spec.substring(separator + 1) : null;
            if (variable.isBlank()) {
                return collectTagIssue(ENV_TAG.getValue(),
                        "!env requires a variable name before the default");
            }
            String value = envLookup.apply(variable);
            if (value != null) {
                return value;
            }
            if (fallback != null) {
                return fallback;
            }
            return collectTagIssue(variable,
                    "environment variable is not set and no default is given: "
                            + variable);
        }
    }

    /**
     * The write-path stance on stage-3 tags (Doc 06 §3.5): the
     * programmatic rewrite re-emits the parsed tree, so a resolved tag
     * would be re-emitted as its plaintext value. Rejected fail-closed
     * (INV-SE-03) — documents carrying these tags are edited in the file,
     * not through the UI/API write path.
     */
    private final class WritePathRejectingConstructor implements ConstructNode {

        private final String tagName;

        WritePathRejectingConstructor(String tagName) {
            this.tagName = tagName;
        }

        @Override
        public Object construct(Node node) {
            return collectTagIssue(tagName,
                    tagName + " tags cannot be preserved by a programmatic"
                            + " rewrite; the UI/API write path rejects documents"
                            + " carrying secret/environment tags fail-closed"
                            + " (Doc 06 §3.5, INV-SE-03) — edit "
                            + ROOT_DOCUMENT_NAME + " directly");
        }
    }

    /**
     * Records one stage-3 FATAL and returns {@code null} as the construct
     * placeholder — the document is discarded once any tag issue exists,
     * so the placeholder never reaches a consumer.
     */
    private Object collectTagIssue(String path, String message) {
        tagIssues.add(new ConfigIssue(Severity.FATAL, path, message, null, null, null));
        return null;
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

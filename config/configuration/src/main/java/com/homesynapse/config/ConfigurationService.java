/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.ConfigurationValidationException;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;

/**
 * Central coordination point for configuration loading, validation, reload,
 * and write-path operations (Doc 06 §8.1, §8.3).
 *
 * <p>{@code ConfigurationService} orchestrates the full configuration
 * lifecycle. It is consumed by the Startup Sequencer (Doc 12) for initial
 * loading, by the REST API (Doc 09) for read/write/reload endpoints, and
 * by the {@code validate-config} CLI command for offline validation.</p>
 *
 * <h2>Startup vs. Reload Error Handling (Doc 06 §3.6)</h2>
 *
 * <p>On startup ({@link #load()}), {@link Severity#ERROR} issues cause the
 * offending key to revert to its JSON Schema default — the system starts
 * with degraded but functional configuration. {@link Severity#FATAL} issues
 * abort startup with a {@link ConfigurationLoadException}.</p>
 *
 * <p>On reload ({@link #reload()}), both {@link Severity#FATAL} and
 * {@link Severity#ERROR} issues cause the entire candidate to be rejected
 * with a {@link ConfigurationReloadException}. This stricter policy exists
 * because on reload there is a prior good state to preserve — the active
 * {@link ConfigModel} remains unchanged.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. {@link #getCurrentModel()} and
 * {@link #getSection(String)} are non-blocking reads of the active model.
 * {@link #load()}, {@link #reload()}, and {@link #write(List, Instant)}
 * are serialized internally to prevent concurrent modifications.</p>
 *
 * @see ConfigModel
 * @see ConfigurationAccess
 * @see ConfigurationLoadException
 * @see ConfigurationReloadException
 * @see com.homesynapse.event.EventPublisher
 */
public interface ConfigurationService {

    /**
     * Executes the full loading pipeline (Doc 06 §3.1) and returns the
     * validated configuration model.
     *
     * <p>This method is called exactly once during startup. It reads the
     * YAML file, resolves {@code !secret} and {@code !env} tags, validates
     * against the composed JSON Schema, applies defaults for
     * {@link Severity#ERROR} keys, and constructs the initial
     * {@link ConfigModel}.</p>
     *
     * @return the loaded and validated configuration model; never {@code null}
     * @throws ConfigurationLoadException if the configuration contains
     *         {@link Severity#FATAL} issues that prevent startup
     */
    ConfigModel load() throws ConfigurationLoadException;

    /**
     * Executes the reload pipeline (Doc 06 §3.3) and returns the new model
     * with the diff from the previous model.
     *
     * <p>The reload pipeline re-reads the YAML file, validates the candidate,
     * computes the diff against the active model, and atomically replaces
     * the active model if validation passes. If the candidate contains
     * {@link Severity#FATAL} or {@link Severity#ERROR} issues, the active
     * model remains unchanged.</p>
     *
     * @return the reload result containing the new model, change set, and
     *         any warning-level issues; never {@code null}
     * @throws ConfigurationReloadException if the candidate configuration
     *         is invalid
     */
    ReloadResult reload() throws ConfigurationReloadException;

    /**
     * Returns the active configuration model.
     *
     * <p>This method is non-blocking and returns the most recently loaded
     * or reloaded model. It is the primary read path for subsystems that
     * need the full configuration.</p>
     *
     * @return the active configuration model; never {@code null}
     */
    ConfigModel getCurrentModel();

    /**
     * Returns a configuration section by its dotted path.
     *
     * @param path the dotted section path (e.g., {@code "persistence.retention"});
     *             never {@code null}
     * @return the configuration section, or empty if the path does not exist
     *         in the active model
     */
    Optional<ConfigSection> getSection(String path);

    /**
     * Applies a list of mutations through the write path (Doc 06 §3.5).
     *
     * <p>The write path validates the mutated configuration against the
     * composed JSON Schema, checks the optimistic concurrency token
     * ({@code fileModifiedAt}), and atomically writes the updated YAML
     * file. On success, the active {@link ConfigModel} is updated.</p>
     *
     * @param mutations      the list of key-level mutations to apply;
     *                       never {@code null}
     * @param fileModifiedAt the expected file modification time (optimistic
     *                       concurrency token from the current model);
     *                       never {@code null}
     * @throws ConfigurationValidationException if the mutated configuration
     *         fails schema validation
     * @throws ConcurrentModificationException if the file was externally
     *         modified (the file's current mtime differs from
     *         {@code fileModifiedAt})
     */
    void write(List<ConfigMutation> mutations, Instant fileModifiedAt)
            throws ConfigurationValidationException, ConcurrentModificationException;
}

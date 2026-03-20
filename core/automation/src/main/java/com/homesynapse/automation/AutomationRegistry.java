/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Optional;

import com.homesynapse.platform.identity.AutomationId;

/**
 * In-memory registry of automation definitions populated from {@code automations.yaml}.
 *
 * <p>The registry is the single source of truth for automation definitions at runtime.
 * It maintains a trigger index (event type &rarr; matching automations) for efficient
 * trigger evaluation. Identity is managed via the {@code automations.ids.yaml} companion
 * file — automation names are mapped to stable ULIDs across configuration reloads.</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §8.1.</p>
 *
 * @see AutomationDefinition
 * @see TriggerEvaluator
 */
public interface AutomationRegistry {

    /**
     * Loads validated automation definitions from the Configuration System.
     *
     * <p>Replaces all previously loaded definitions. Typically called once at startup.</p>
     *
     * @param definitions the validated automation definitions to load,
     *                    never {@code null}
     */
    void load(List<AutomationDefinition> definitions);

    /**
     * Looks up an automation by its stable identity.
     *
     * @param id the automation identifier, never {@code null}
     * @return the automation definition, or empty if not found
     */
    Optional<AutomationDefinition> get(AutomationId id);

    /**
     * Looks up an automation by its human-readable slug.
     *
     * @param slug the automation slug, never {@code null}
     * @return the automation definition, or empty if not found
     */
    Optional<AutomationDefinition> getBySlug(String slug);

    /**
     * Returns all loaded automation definitions.
     *
     * @return an unmodifiable list of all definitions, never {@code null}
     */
    List<AutomationDefinition> getAll();

    /**
     * Hot-reloads automation definitions with in-progress Run preservation (C7).
     *
     * <p>Automations that are unchanged continue executing. Automations that are
     * modified or removed are handled according to the reload protocol — in-progress
     * Runs complete against their original definition.</p>
     *
     * @param definitions the new set of validated automation definitions,
     *                    never {@code null}
     */
    void reload(List<AutomationDefinition> definitions);
}

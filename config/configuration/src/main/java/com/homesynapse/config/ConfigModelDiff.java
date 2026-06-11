/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The reload diff engine (Doc 06 §3.3 step 3, §4.3, DP-5): compares the
 * active and candidate {@link ConfigModel}s section-by-section over their
 * {@code values} maps and produces the key-level {@link ConfigChange}s,
 * grouped by changed section.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>Post-pipeline comparison.</strong> Both models are
 *       post-migration, post-default-merge — a cosmetic file edit that
 *       parses to the same effective configuration produces no changes,
 *       and a removed key surfaces as a change to its schema default.</li>
 *   <li><strong>Key-by-key {@code Objects.equals}.</strong> A removed key
 *       has a {@code null} {@code newValue}; an added key has a
 *       {@code null} {@code oldValue} (both nullable on
 *       {@link ConfigChange}). Sections present in only one model diff
 *       against an empty section and so count as changed.</li>
 *   <li><strong>Nested containers belong to the nested section.</strong>
 *       When a key's value is a map on BOTH sides, the key is skipped at
 *       this level: the flattened section inventory already carries the
 *       nested path as its own {@link ConfigSection}, whose own diff owns
 *       every leaf change. This attributes each leaf edit to exactly one
 *       section (its immediate container) — the parent stays silent unless
 *       a subtree appears or disappears wholesale.</li>
 *   <li><strong>Per-key classification at diff time.</strong> Each change
 *       carries the property's {@code x-reload} classification from the
 *       composed schema, defaulting to
 *       {@link ReloadClassification#PROCESS_RESTART} for unannotated
 *       properties (AMD-66 §2.3). The AMD-66 listener's section-level
 *       override is layered on AFTER the diff and never rewrites the
 *       per-key values (DP-5).</li>
 *   <li><strong>Deterministic order.</strong> Changed sections are sorted
 *       by path; keys follow active-then-candidate insertion order. Stable
 *       output makes the per-section event sequence reproducible.</li>
 * </ul>
 */
final class ConfigModelDiff {

    private ConfigModelDiff() {
        // Utility class — non-instantiable
    }

    /**
     * Computes the section-grouped diff between two models.
     *
     * @param active      the currently active model; never {@code null}
     * @param candidate   the candidate model from the reload re-parse;
     *                    never {@code null}
     * @param annotations the composed schema's annotation index supplying
     *                    per-key {@code x-reload} classifications; never
     *                    {@code null}
     * @return changed section path → its key-level changes (every list
     *         non-empty), ordered by section path; empty when the models
     *         are effectively identical
     */
    static Map<String, List<ConfigChange>> diff(ConfigModel active,
                                                ConfigModel candidate,
                                                SchemaAnnotationIndex annotations) {
        Objects.requireNonNull(active, "active must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(annotations, "annotations must not be null");

        Map<String, List<ConfigChange>> changedSections = new TreeMap<>();
        Set<String> sectionPaths = new LinkedHashSet<>(active.sections().keySet());
        sectionPaths.addAll(candidate.sections().keySet());

        for (String path : sectionPaths) {
            List<ConfigChange> changes = diffSection(path,
                    valuesOf(active, path), valuesOf(candidate, path), annotations);
            if (!changes.isEmpty()) {
                changedSections.put(path, changes);
            }
        }
        return changedSections;
    }

    private static Map<String, Object> valuesOf(ConfigModel model, String path) {
        ConfigSection section = model.sections().get(path);
        return section != null ? section.values() : Map.of();
    }

    private static List<ConfigChange> diffSection(String path,
                                                  Map<String, Object> active,
                                                  Map<String, Object> candidate,
                                                  SchemaAnnotationIndex annotations) {
        Set<String> keys = new LinkedHashSet<>(active.keySet());
        keys.addAll(candidate.keySet());

        List<ConfigChange> changes = new ArrayList<>();
        for (String key : keys) {
            Object oldValue = active.get(key);
            Object newValue = candidate.get(key);
            if (oldValue instanceof Map<?, ?> && newValue instanceof Map<?, ?>) {
                // Both sides are containers — the nested section's own diff
                // owns the leaf changes (see class Javadoc).
                continue;
            }
            if (!Objects.equals(oldValue, newValue)) {
                changes.add(new ConfigChange(path, key, oldValue, newValue,
                        annotations.reloadClassificationAt(path, key)
                                .orElse(ReloadClassification.PROCESS_RESTART)));
            }
        }
        return changes;
    }
}

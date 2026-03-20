/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Selector expressions written by users in automation targets, conditions, and triggers.
 *
 * <p>Selectors identify one or more entities by various criteria. At trigger evaluation
 * time, each selector is resolved to a {@code Set<EntityId>} using the Identity Model's
 * entity registry (Doc 06 §7.2). The resolved sets are captured in
 * {@link RunContext#resolvedTargets()} and frozen for the duration of the Run — no
 * re-resolution occurs during action execution (C4).</p>
 *
 * <p>This sealed hierarchy permits six selector types, all Tier 1:</p>
 * <ul>
 *   <li>{@link DirectRefSelector} — exact entity reference by ULID</li>
 *   <li>{@link SlugSelector} — human-readable slug lookup</li>
 *   <li>{@link AreaSelector} — all entities in a named area</li>
 *   <li>{@link LabelSelector} — all entities with a given label</li>
 *   <li>{@link TypeSelector} — all entities of a given type</li>
 *   <li>{@link CompoundSelector} — intersection of multiple selectors ({@code all_of})</li>
 * </ul>
 *
 * <p>All implementations are immutable records. Thread-safe.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.2.</p>
 *
 * @see SelectorResolver
 * @see RunContext
 */
public sealed interface Selector
        permits DirectRefSelector, SlugSelector, AreaSelector,
                LabelSelector, TypeSelector, CompoundSelector {
}

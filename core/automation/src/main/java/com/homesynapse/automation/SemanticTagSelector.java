/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Set;
import java.util.Objects;

import com.homesynapse.device.EntityRole;

/**
 * Selector that resolves to all entities carrying a namespaced tag (AMD-89 §2.1).
 *
 * <p>Entities carry tags as {@code namespace:value} strings in their label set
 * (e.g. {@code room:kitchen}, {@code safety:critical}). This selector matches by
 * {@code namespace} and {@code value} according to {@link MatchMode}:
 * {@link MatchMode#EXACT} matches the literal {@code namespace:value} label;
 * {@link MatchMode#NAMESPACE_PREFIX} matches any label in the namespace.</p>
 *
 * <p>As a group-resolving selector, it is role-filtered: only entities whose
 * {@link EntityRole} is contained in {@code includedRoles} are resolved
 * (AMD-89-INV-01). The YAML default applied at definition load is
 * {@code Set.of(EntityRole.PRIMARY)}; an empty {@code includedRoles} is a
 * per-definition load failure (AMD-89 §4, Doc 07 §6.1), not a construction-time
 * error.</p>
 *
 * <p>Defined in AMD-89 §2.1; Doc 07 §3.12, §8.2.</p>
 *
 * @param namespace     the tag namespace to match (e.g. {@code "room"}), never {@code null}
 * @param value         the tag value to match (e.g. {@code "kitchen"}); ignored for
 *                      {@link MatchMode#NAMESPACE_PREFIX} but never {@code null}
 * @param matchMode     the match strategy, never {@code null}
 * @param includedRoles the entity roles to include in resolution, unmodifiable,
 *                      never {@code null}
 * @see Selector
 * @see SelectorResolver
 * @see MatchMode
 */
public record SemanticTagSelector(
        String namespace,
        String value,
        MatchMode matchMode,
        Set<EntityRole> includedRoles
) implements Selector {

    /**
     * Validates non-null fields and makes {@code includedRoles} unmodifiable.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public SemanticTagSelector {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(matchMode, "matchMode must not be null");
        Objects.requireNonNull(includedRoles, "includedRoles must not be null");
        includedRoles = Set.copyOf(includedRoles);
    }
}

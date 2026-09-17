/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.Objects;

import com.homesynapse.device.EntityRole;

/**
 * Selector that resolves to all entities in the named area.
 *
 * <p>Areas are organizational groupings defined in the home configuration.
 * This selector resolves to zero or more entities that belong to the
 * specified area.</p>
 *
 * <p>As a group-resolving selector, it is role-filtered: only entities whose
 * {@link EntityRole} is contained in {@code includedRoles} are resolved
 * (AMD-89-INV-01). The YAML default applied at definition load is
 * {@code Set.of(EntityRole.PRIMARY)}; an empty {@code includedRoles} is a
 * per-definition load failure (Doc 07 §6.1), not a construction-time error.</p>
 *
 * <p>Defined in Doc 07 §3.12, §8.2; {@code includedRoles} added by AMD-89 §2.2.</p>
 *
 * @param areaSlug      the slug identifying the area, never {@code null}
 * @param includedRoles the entity roles to include in resolution, unmodifiable and
 *                      deterministically ordered (ordinal), never {@code null}
 * @see Selector
 * @see SelectorResolver
 */
public record AreaSelector(
        String areaSlug,
        Set<EntityRole> includedRoles
) implements Selector {

    /**
     * Validates non-null fields and stores {@code includedRoles} as an unmodifiable,
     * deterministically ordered (ordinal) copy — {@code AutomationDefinition.toString()}
     * renders it and {@code DefinitionHashes} hashes that rendering (HASH-1).
     *
     * @throws NullPointerException if {@code areaSlug} or {@code includedRoles}
     *                              is {@code null}
     */
    public AreaSelector {
        Objects.requireNonNull(areaSlug, "areaSlug must not be null");
        Objects.requireNonNull(includedRoles, "includedRoles must not be null");
        Set<EntityRole> roles = Set.copyOf(includedRoles); // today's null/duplicate handling
        includedRoles = Collections.unmodifiableSet(
                roles.isEmpty() ? EnumSet.noneOf(EntityRole.class) : EnumSet.copyOf(roles));
    }
}

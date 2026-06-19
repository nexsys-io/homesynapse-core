/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.homesynapse.device.Area;
import com.homesynapse.device.AreaRegistry;
import com.homesynapse.device.Device;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.EntityRole;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.EntityId;

/**
 * Production {@link SelectorResolver}: resolves selector expressions to entity-ID sets
 * against the device/entity/area registries (Doc 07 §3.12; AMD-89 role filtering).
 *
 * <p>Thread-safe and stateless — resolution reads the injected registries and holds no
 * mutable state, so no locking is required (LTD-11). The registries are themselves the
 * single source of truth; resolution at trigger time follows Identity Model §7.2.</p>
 *
 * <p><strong>Role filtering (AMD-89-INV-01).</strong> The group-resolving permits
 * ({@link AreaSelector}, {@link LabelSelector}, {@link TypeSelector},
 * {@link SemanticTagSelector}) filter to entities whose {@link EntityRole} is in the
 * selector's {@code includedRoles} (PRIMARY-only by default). {@link DirectRefSelector}
 * and {@link SlugSelector} are never role-filtered (explicit single-entity intent).</p>
 *
 * <p><strong>Substrate gaps (flagged for follow-up, see MODULE_CONTEXT):</strong>
 * (1) Slug-tombstone chains (Identity Model §7.5) and the
 * {@code automation_slug_redirect} diagnostic are dormant — no slug-tombstone substrate
 * exists at this baseline, so {@link SlugSelector} resolves current slugs only.
 * (2) {@code Area} carries no first-class slug at this baseline (AMD-44 Stage-1 minimal),
 * so {@link AreaSelector} keys on the area's display name (case-insensitive, plus a
 * space→underscore slugification fallback).</p>
 */
public final class StandardSelectorResolver implements SelectorResolver {

    private final EntityRegistry entityRegistry;
    private final AreaRegistry areaRegistry;
    private final DeviceRegistry deviceRegistry;

    /**
     * Constructs a resolver over the given registries.
     *
     * @param entityRegistry the entity registry, never {@code null}
     * @param areaRegistry   the area registry, never {@code null}
     * @param deviceRegistry the device registry (for entity→device area inheritance),
     *                       never {@code null}
     */
    public StandardSelectorResolver(EntityRegistry entityRegistry,
                                    AreaRegistry areaRegistry,
                                    DeviceRegistry deviceRegistry) {
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.areaRegistry = Objects.requireNonNull(areaRegistry, "areaRegistry");
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
    }

    @Override
    public Set<EntityId> resolve(Selector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case DirectRefSelector direct -> resolveDirectRef(direct);
            case SlugSelector slug -> resolveSlug(slug);
            case AreaSelector area -> resolveArea(area);
            case LabelSelector label -> resolveLabel(label);
            case TypeSelector type -> resolveType(type);
            case SemanticTagSelector tag -> resolveSemanticTag(tag);
            case CompoundSelector compound -> resolveCompound(compound);
        };
    }

    private Set<EntityId> resolveDirectRef(DirectRefSelector selector) {
        return entityRegistry.findEntity(selector.entityId())
                .map(entity -> Set.of(entity.entityId()))
                .orElseGet(Set::of);
    }

    private Set<EntityId> resolveSlug(SlugSelector selector) {
        Set<EntityId> result = new LinkedHashSet<>();
        for (Entity entity : entityRegistry.listAllEntities()) {
            if (entity.entitySlug().equals(selector.slug())) {
                result.add(entity.entityId());
                break; // slug is unique → at most one entity
            }
        }
        return result;
    }

    private Set<EntityId> resolveArea(AreaSelector selector) {
        AreaId target = areaIdForSlug(selector.areaSlug());
        Set<EntityId> result = new LinkedHashSet<>();
        if (target == null) {
            return result;
        }
        for (Entity entity : entityRegistry.listAllEntities()) {
            if (target.equals(effectiveArea(entity))
                    && roleIncluded(entity, selector.includedRoles())) {
                result.add(entity.entityId());
            }
        }
        return result;
    }

    private Set<EntityId> resolveLabel(LabelSelector selector) {
        Set<EntityId> result = new LinkedHashSet<>();
        for (Entity entity : entityRegistry.listAllEntities()) {
            if (entity.labels().contains(selector.label())
                    && roleIncluded(entity, selector.includedRoles())) {
                result.add(entity.entityId());
            }
        }
        return result;
    }

    private Set<EntityId> resolveType(TypeSelector selector) {
        Set<EntityId> result = new LinkedHashSet<>();
        for (Entity entity : entityRegistry.listAllEntities()) {
            if (entity.entityType().name().equalsIgnoreCase(selector.entityType())
                    && roleIncluded(entity, selector.includedRoles())) {
                result.add(entity.entityId());
            }
        }
        return result;
    }

    private Set<EntityId> resolveSemanticTag(SemanticTagSelector selector) {
        String exact = selector.namespace() + ":" + selector.value();
        String prefix = selector.namespace() + ":";
        Set<EntityId> result = new LinkedHashSet<>();
        for (Entity entity : entityRegistry.listAllEntities()) {
            if (!roleIncluded(entity, selector.includedRoles())) {
                continue;
            }
            boolean matched = switch (selector.matchMode()) {
                case EXACT -> entity.labels().contains(exact);
                case NAMESPACE_PREFIX -> entity.labels().stream()
                        .anyMatch(label -> label.startsWith(prefix));
            };
            if (matched) {
                result.add(entity.entityId());
            }
        }
        return result;
    }

    private Set<EntityId> resolveCompound(CompoundSelector selector) {
        List<Selector> operands = selector.selectors();
        if (operands.isEmpty()) {
            return Set.of();
        }
        Set<EntityId> intersection = new LinkedHashSet<>(resolve(operands.get(0)));
        for (int i = 1; i < operands.size() && !intersection.isEmpty(); i++) {
            intersection.retainAll(resolve(operands.get(i)));
        }
        return intersection;
    }

    private static boolean roleIncluded(Entity entity, Set<EntityRole> includedRoles) {
        return includedRoles.contains(entity.entityRole());
    }

    /**
     * Resolves an entity's effective area, inheriting the owning device's area when the
     * entity has no area override of its own.
     */
    private AreaId effectiveArea(Entity entity) {
        if (entity.areaId() != null) {
            return entity.areaId();
        }
        if (entity.deviceId() == null) {
            return null;
        }
        return deviceRegistry.findDevice(entity.deviceId())
                .map(Device::areaId)
                .orElse(null);
    }

    /**
     * Resolves an area slug to its {@link AreaId}. Until {@code Area} gains a first-class
     * slug (AMD-44 Stage-1 minimal), this keys on the area's display name
     * (case-insensitive) with a space→underscore slugification fallback.
     */
    private AreaId areaIdForSlug(String areaSlug) {
        for (Area area : areaRegistry.getAll()) {
            String name = area.name();
            if (name.equalsIgnoreCase(areaSlug) || slugify(name).equals(areaSlug)) {
                return area.id();
            }
        }
        return null;
    }

    private static String slugify(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}

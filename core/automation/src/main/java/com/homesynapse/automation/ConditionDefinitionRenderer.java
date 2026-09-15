/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.homesynapse.device.EntityRole;

/**
 * Renders a {@link ConditionDefinition} to the read side's structured
 * {@link RunExplanation.ConditionDefinitionView} (v1.1.5, EXPLAIN-114c K3, SD-2): a pure
 * function over the definition — no I/O, no clock, no registry — exhaustive over the sealed
 * {@link ConditionDefinition} and {@link Selector} hierarchies with no {@code default}, so a new
 * permit is a compile error here, never a silently missing operand. The caller
 * ({@code StandardExplanationService.buildConditions}) decides WHETHER a definition may be
 * rendered (the hash guard); this class only decides HOW.
 *
 * <p>The selector is rendered to ONE string so the wire stays flat: a
 * {@link DirectRefSelector} is its entity ULID text (the same rendering the chain's
 * {@code observedState[].entityId} and {@code targetRef.id} use), a {@link SlugSelector} its
 * slug, the four group permits {@code <kind>:<value>/<roles>} ({@code area} / {@code label} /
 * {@code type} / {@code tag}; a {@link SemanticTagSelector} carries its namespace, value and
 * match mode as {@code tag:<namespace>/<value>/<matchMode>/<roles>}); the roles are the
 * {@code includedRoles} names sorted, joined by {@code ,} — a {@code Set} has no order of its
 * own, and the loader defaults it to {@code PRIMARY} (AMD-89 §2.2). A {@link CompoundSelector}
 * is its parts joined by {@code +} (the all-of intersection, Doc 07 §3.12).</p>
 */
final class ConditionDefinitionRenderer {

    private static final String AREA = "area:";
    private static final String LABEL = "label:";
    private static final String TYPE = "type:";
    private static final String TAG = "tag:";
    private static final String PART = "/";
    private static final String ROLE_SEPARATOR = ",";
    private static final String COMPOUND_JOIN = "+";

    private ConditionDefinitionRenderer() {
        // Utility class — non-instantiable.
    }

    /**
     * The structured view of {@code condition}, rendered recursively for the compound permits.
     *
     * @param condition the definition to render; never {@code null}
     * @return the view; never {@code null}
     */
    static RunExplanation.ConditionDefinitionView render(ConditionDefinition condition) {
        String type = condition.getClass().getSimpleName();
        return switch (condition) {
            case StateCondition c -> new RunExplanation.ConditionDefinitionView(type,
                    selector(c.selector()), c.attribute(), c.value(), null, null, null, null,
                    List.of());
            case NumericCondition c -> new RunExplanation.ConditionDefinitionView(type,
                    selector(c.selector()), c.attribute(), null, c.above(), c.below(), null, null,
                    List.of());
            case TimeCondition c -> new RunExplanation.ConditionDefinitionView(type,
                    null, null, null, null, null, c.after(), c.before(), List.of());
            case AndCondition c -> compound(type, c.conditions());
            case OrCondition c -> compound(type, c.conditions());
            case NotCondition c -> compound(type, List.of(c.condition()));
            case ZoneCondition c -> new RunExplanation.ConditionDefinitionView(type,
                    null, null, null, null, null, null, null, List.of());
        };
    }

    private static RunExplanation.ConditionDefinitionView compound(String type,
                                                                   List<ConditionDefinition> operands) {
        List<RunExplanation.ConditionDefinitionView> children = new ArrayList<>(operands.size());
        for (ConditionDefinition operand : operands) {
            children.add(render(operand));
        }
        return new RunExplanation.ConditionDefinitionView(type, null, null, null, null, null,
                null, null, children);
    }

    private static String selector(Selector selector) {
        return switch (selector) {
            case DirectRefSelector s -> s.entityId().toString();
            case SlugSelector s -> s.slug();
            case AreaSelector s -> AREA + s.areaSlug() + PART + roles(s.includedRoles());
            case LabelSelector s -> LABEL + s.label() + PART + roles(s.includedRoles());
            case TypeSelector s -> TYPE + s.entityType() + PART + roles(s.includedRoles());
            case SemanticTagSelector s -> TAG + s.namespace() + PART + s.value() + PART
                    + s.matchMode().name() + PART + roles(s.includedRoles());
            case CompoundSelector s -> s.selectors().stream()
                    .map(ConditionDefinitionRenderer::selector)
                    .collect(Collectors.joining(COMPOUND_JOIN));
        };
    }

    /** The role names sorted and joined — a deterministic rendering of an unordered set. */
    private static String roles(Set<EntityRole> includedRoles) {
        return includedRoles.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(ROLE_SEPARATOR));
    }
}

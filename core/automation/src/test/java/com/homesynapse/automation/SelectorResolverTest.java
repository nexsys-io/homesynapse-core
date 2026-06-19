/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.area;
import static com.homesynapse.automation.AutomationTestSupport.entity;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.ulid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@code SemanticTagResolutionTest}, {@code RoleFilterTest}, and
 * {@code CompoundIntersectionTest} for {@link StandardSelectorResolver} (AMD-89).
 */
@DisplayName("StandardSelectorResolver (AMD-89)")
class SelectorResolverTest {

    private EntityId kitchenLight;
    private EntityId kitchenSensorDiag;
    private EntityId bedroomLight;
    private StandardSelectorResolver resolver;

    @BeforeEach
    void setUp() {
        AreaId kitchen = new AreaId(ulid());
        kitchenLight = entityId();
        kitchenSensorDiag = entityId();
        bedroomLight = entityId();

        Entity light = entity(kitchenLight, "kitchen.light", EntityType.LIGHT, kitchen,
                EntityRole.PRIMARY, List.of("room:kitchen", "downstairs"));
        Entity sensor = entity(kitchenSensorDiag, "kitchen.sensor", EntityType.SENSOR, kitchen,
                EntityRole.DIAGNOSTIC, List.of("room:kitchen"));
        Entity bedroom = entity(bedroomLight, "bedroom.light", EntityType.LIGHT, null,
                EntityRole.PRIMARY, List.of("room:bedroom"));

        resolver = new StandardSelectorResolver(
                new AutomationTestSupport.StubEntityRegistry(List.of(light, sensor, bedroom)),
                new AutomationTestSupport.StubAreaRegistry(List.of(area(kitchen, "Kitchen"))),
                new AutomationTestSupport.StubDeviceRegistry());
    }

    @Test
    @DisplayName("DirectRef and Slug resolve to a single entity (never role-filtered)")
    void directAndSlug() {
        assertThat(resolver.resolve(new DirectRefSelector(kitchenLight)))
                .containsExactly(kitchenLight);
        assertThat(resolver.resolve(new SlugSelector("kitchen.sensor")))
                .containsExactly(kitchenSensorDiag); // DIAGNOSTIC role not filtered for slug
        assertThat(resolver.resolve(new SlugSelector("does.not.exist"))).isEmpty();
    }

    @Test
    @DisplayName("role filtering excludes DIAGNOSTIC by default, includes it on opt-in")
    void roleFiltering() {
        assertThat(resolver.resolve(new AreaSelector("kitchen", Set.of(EntityRole.PRIMARY))))
                .containsExactly(kitchenLight);
        assertThat(resolver.resolve(new AreaSelector("kitchen",
                Set.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC))))
                .containsExactlyInAnyOrder(kitchenLight, kitchenSensorDiag);
    }

    @Test
    @DisplayName("Type and Label resolve groups with role filtering")
    void typeAndLabel() {
        assertThat(resolver.resolve(new TypeSelector("light", Set.of(EntityRole.PRIMARY))))
                .containsExactlyInAnyOrder(kitchenLight, bedroomLight);
        assertThat(resolver.resolve(new LabelSelector("downstairs", Set.of(EntityRole.PRIMARY))))
                .containsExactly(kitchenLight);
    }

    @Test
    @DisplayName("SemanticTag EXACT vs NAMESPACE_PREFIX over Entity.labels")
    void semanticTag() {
        assertThat(resolver.resolve(new SemanticTagSelector("room", "kitchen", MatchMode.EXACT,
                Set.of(EntityRole.PRIMARY)))).containsExactly(kitchenLight);
        assertThat(resolver.resolve(new SemanticTagSelector("room", "kitchen", MatchMode.EXACT,
                Set.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC))))
                .containsExactlyInAnyOrder(kitchenLight, kitchenSensorDiag);
        assertThat(resolver.resolve(new SemanticTagSelector("room", "ignored",
                MatchMode.NAMESPACE_PREFIX, Set.of(EntityRole.PRIMARY))))
                .containsExactlyInAnyOrder(kitchenLight, bedroomLight);
        assertThat(resolver.resolve(new SemanticTagSelector("safety", "critical", MatchMode.EXACT,
                Set.of(EntityRole.PRIMARY)))).isEmpty();
    }

    @Test
    @DisplayName("Compound uses intersection semantics over post-filter operand sets")
    void compoundIntersection() {
        Selector kitchenLights = new CompoundSelector(List.of(
                new AreaSelector("kitchen", Set.of(EntityRole.PRIMARY)),
                new TypeSelector("light", Set.of(EntityRole.PRIMARY))));
        assertThat(resolver.resolve(kitchenLights)).containsExactly(kitchenLight);
    }
}

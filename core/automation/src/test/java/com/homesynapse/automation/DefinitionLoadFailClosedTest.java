/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_CLOCK;
import static com.homesynapse.automation.AutomationTestSupport.area;
import static com.homesynapse.automation.AutomationTestSupport.entity;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.ulid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.platform.identity.AreaId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R-δ AX-5 / SD-9 fail-closed-at-load: a definition with an unresolvable reference is
 * rejected and surfaced, while valid sibling definitions still load (Doc 07 §6.1
 * valid-subset semantics).
 *
 * <blockquote><strong>Tests must inject {@code Clock}.</strong> Identity is minted from
 * {@code Clock.fixed(...)}; no wall-clock access appears here.</blockquote>
 */
@DisplayName("Fail-closed definition load (SD-9 / §6.1)")
class DefinitionLoadFailClosedTest {

    private AutomationDefinitionLoader loader;

    @BeforeEach
    void setUp() {
        AreaId knownArea = new AreaId(ulid());
        loader = new AutomationDefinitionLoader(
                new InMemoryAutomationIdentityStore(FIXED_CLOCK),
                new AutomationTestSupport.StubEntityRegistry(List.of(
                        entity(entityId(), "known.entity", EntityType.LIGHT, knownArea,
                                EntityRole.PRIMARY, List.of()))),
                new AutomationTestSupport.StubAreaRegistry(List.of(area(knownArea, "Known"))));
    }

    private static Map<String, Object> emitAction() {
        return Map.of("type", "emit_event", "event_type", "custom.notify");
    }

    @Test
    @DisplayName("invalid definitions are rejected and surfaced; valid siblings still load")
    void validSubsetLoads() {
        Map<String, Object> valid = Map.of("name", "valid",
                "triggers", List.of(Map.of("type", "state", "entity", "known.entity",
                        "attribute", "on_off", "value", "on")),
                "actions", List.of(emitAction()));

        Map<String, Object> unknownEvent = Map.of("name", "unknown-event",
                "triggers", List.of(Map.of("type", "event", "event_type", "bogus_event")),
                "actions", List.of(emitAction()));

        Map<String, Object> danglingSlug = Map.of("name", "dangling-slug",
                "triggers", List.of(Map.of("type", "state", "entity", "ghost.slug",
                        "attribute", "on_off", "value", "on")),
                "actions", List.of(emitAction()));

        Map<String, Object> emptyRoles = Map.of("name", "empty-roles",
                "triggers", List.of(Map.of("type", "state", "area", "Known",
                        "included_roles", List.of(), "attribute", "on_off", "value", "on")),
                "actions", List.of(emitAction()));

        Map<String, Object> unknownTriggerType = Map.of("name", "unknown-trigger",
                "triggers", List.of(Map.of("type", "telepathy")),
                "actions", List.of(emitAction()));

        LoadResult result = loader.load(Map.of("automations",
                List.of(valid, unknownEvent, danglingSlug, emptyRoles, unknownTriggerType)));

        assertThat(result.loaded()).singleElement()
                .satisfies(def -> assertThat(def.name()).isEqualTo("valid"));
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.failures()).extracting(LoadFailure::automationName)
                .containsExactlyInAnyOrder("unknown-event", "dangling-slug", "empty-roles",
                        "unknown-trigger");
        assertThat(result.failures()).extracting(LoadFailure::detail)
                .anySatisfy(detail -> assertThat(detail).contains("unknown event_type"))
                .anySatisfy(detail -> assertThat(detail).contains("unknown entity slug"))
                .anySatisfy(detail -> assertThat(detail).contains("included_roles must not be empty"));
    }

    @Test
    @DisplayName("a schema_version major newer than supported fails the whole file")
    void tooNewSchemaMajorFailsFile() {
        Map<String, Object> document = Map.of(
                "schema_version", Map.of("major", 2, "minor", 0),
                "automations", List.of(Map.of("name", "x",
                        "triggers", List.of(Map.of("type", "manual")),
                        "actions", List.of(emitAction()))));

        LoadResult result = loader.load(document);
        assertThat(result.loaded()).isEmpty();
        assertThat(result.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.detail()).contains("newer than supported"));
    }
}

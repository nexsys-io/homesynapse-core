/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Trigger-identity assignment (AMD-88 §2.5): an unset {@code trigger_id} is assigned a
 * load-time ULID that is stable across reloads, and duplicate ids within one automation
 * are rejected.
 *
 * <blockquote><strong>Tests must inject {@code Clock}.</strong> The identity store mints
 * ULIDs from {@code Clock.fixed(...)}; no wall-clock access appears here.</blockquote>
 */
@DisplayName("Trigger ID assignment (AMD-88)")
class TriggerIdAssignmentTest {

    private AutomationDefinitionLoader loader;

    @BeforeEach
    void setUp() {
        loader = new AutomationDefinitionLoader(
                new InMemoryAutomationIdentityStore(FIXED_CLOCK),
                new AutomationTestSupport.StubEntityRegistry(List.of()),
                new AutomationTestSupport.StubAreaRegistry(List.of()));
    }

    private static Map<String, Object> documentWithManualTrigger() {
        return Map.of("automations", List.of(Map.of(
                "name", "scene",
                "triggers", List.of(Map.of("type", "manual")),
                "actions", List.of(Map.of("type", "emit_event", "event_type", "custom.notify")))));
    }

    @Test
    @DisplayName("an unset trigger_id is assigned a ULID, stable across reload")
    void unsetTriggerIdAssignedAndStable() {
        LoadResult first = loader.load(documentWithManualTrigger());
        assertThat(first.failures()).isEmpty();
        String assigned = ((ManualTrigger) first.loaded().get(0).triggers().get(0)).triggerId();
        assertThat(Ulid.isValid(assigned)).isTrue();

        LoadResult reload = loader.load(documentWithManualTrigger());
        String afterReload = ((ManualTrigger) reload.loaded().get(0).triggers().get(0)).triggerId();
        assertThat(afterReload).isEqualTo(assigned);
    }

    @Test
    @DisplayName("a user-supplied trigger_id is preserved verbatim")
    void userTriggerIdPreserved() {
        Map<String, Object> document = Map.of("automations", List.of(Map.of(
                "name", "scene",
                "triggers", List.of(Map.of("type", "manual", "trigger_id", "morning")),
                "actions", List.of(Map.of("type", "emit_event", "event_type", "custom.notify")))));

        LoadResult result = loader.load(document);
        assertThat(((ManualTrigger) result.loaded().get(0).triggers().get(0)).triggerId())
                .isEqualTo("morning");
    }

    @Test
    @DisplayName("duplicate trigger_ids within one automation are rejected at load")
    void duplicateTriggerIdsRejected() {
        Map<String, Object> document = Map.of("automations", List.of(Map.of(
                "name", "dup",
                "triggers", List.of(
                        Map.of("type", "manual", "trigger_id", "x"),
                        Map.of("type", "manual", "trigger_id", "x")),
                "actions", List.of(Map.of("type", "emit_event", "event_type", "custom.notify")))));

        LoadResult result = loader.load(document);
        assertThat(result.loaded()).isEmpty();
        assertThat(result.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.detail()).contains("duplicate trigger_id"));
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.DefinitionHashOrderProbeTest.ENTITY;
import static com.homesynapse.automation.DefinitionHashOrderProbeTest.twoParameterAction;
import static com.homesynapse.automation.DefinitionHashOrderProbeTest.twoRoleSelector;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.homesynapse.device.EntityRole;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HASH-1 (IR-23): {@link DefinitionHashes#forDefinition} is a function of a definition's CONTENT,
 * not of the JVM launch or the insertion order of its sets and maps — the eight definition records
 * that once stored a salted {@code Set.copyOf}/{@code Map.copyOf} store an ordered immutable view
 * (roles in ordinal order, string keys and methods in natural order), rendered in the same
 * {@code toString()} format. T3 pins the equality across insertion orders for both measured
 * sources (HASH-0's A and B, the probe's fixtures) and the golden values HASH-0 measured for the
 * ordinal / key-ordered renderings; T4 pins that a definition whose collections hold at most one
 * element hashes byte-identical to its BASELINE value — the frozen {@code definitionKey} of every
 * run logged before HASH-1 is preserved.
 *
 * <p>Every identity is a fixed, parsed ULID: {@code UlidFactory} draws from {@code SecureRandom},
 * so a factory-minted id alone would change the hash per launch. No clock is read.</p>
 */
@DisplayName("Definition hash stability (HASH-1) and the single-element preservation pins")
class DefinitionHashStabilityTest {

    private static final AutomationId AUTOMATION_C =
            AutomationId.of(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAY"));
    private static final AutomationId AUTOMATION_D =
            AutomationId.of(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAZ"));

    /**
     * HASH-0's measured values for the ORDERED renderings (2026-09-15, eight launches at
     * {@code 3d40b5f}): A with {@code includedRoles=[PRIMARY, DIAGNOSTIC]} hashed to this value in
     * 3 of 8 launches, B with {@code parameters={brightness=50, transition=2}} in 5 of 8; with the
     * ordered views every launch hashes to exactly these.
     */
    static final String GOLDEN_HASH_A =
            "202ada4d4127421514e68c0f9d88ff860c25070d33dc6ca28fe8054fcd8798a1";
    static final String GOLDEN_HASH_B =
            "ba46bcf19b358144d93f2ab62cb80a93b5a7a6f1e5811d7f9c1026fec0a6eec9";

    /**
     * Recorded at the BASELINE — {@code 3d40b5f} with MEASURE-1's two test files and no record
     * edited — by running T4 with a placeholder literal and reading the actual hash from the
     * failure message (the DUR-1 + HASH-1 return, §0; 2026-09-15T23:46Z and 23:48Z). Collections of
     * at most one element render identically before and after the ordered views, so these values
     * must never move.
     */
    static final String BASELINE_HASH_C =
            "6fa283b7872a425ea96cb8b0c8908604f80c61aa0ad21282cc48b68fe5e6f8d4";
    static final String BASELINE_HASH_D =
            "c419d1c40579526e4694b81480bd0fc662450dc854178edabe17325fef2d9f2e";

    @Test
    @DisplayName("HASH-1: a two-role selector hashes the same whichever order the roles were inserted, rendered in ordinal order (T3)")
    void twoRoleSelector_hashesEqualAcrossInsertionOrders() {
        AutomationDefinition a = twoRoleSelector(EntityRole.DIAGNOSTIC, EntityRole.PRIMARY);
        AutomationDefinition twin = twoRoleSelector(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC);

        assertThat(DefinitionHashes.forDefinition(a))
                .isEqualTo(DefinitionHashes.forDefinition(twin))
                .isEqualTo(GOLDEN_HASH_A);
        assertThat(a.conditions().get(0).toString())
                .contains("includedRoles=[PRIMARY, DIAGNOSTIC]");
        assertThat(twin.conditions().get(0).toString())
                .contains("includedRoles=[PRIMARY, DIAGNOSTIC]");
    }

    @Test
    @DisplayName("HASH-1: a two-parameter command action hashes the same whichever order the keys were put, rendered in key order (T3)")
    void twoParameterAction_hashesEqualAcrossInsertionOrders() {
        AutomationDefinition b = twoParameterAction("transition", 2, "brightness", 50);
        AutomationDefinition twin = twoParameterAction("brightness", 50, "transition", 2);

        assertThat(DefinitionHashes.forDefinition(b))
                .isEqualTo(DefinitionHashes.forDefinition(twin))
                .isEqualTo(GOLDEN_HASH_B);
        assertThat(b.actions().get(0).toString())
                .contains("parameters={brightness=50, transition=2}");
        assertThat(twin.actions().get(0).toString())
                .contains("parameters={brightness=50, transition=2}");
    }

    @Test
    @DisplayName("HASH-1 preservation: single-element (and empty) collections hash byte-identical to their baseline (T4)")
    void singleElementHashes_preserved() {
        // Soft, so a moved pin reports BOTH definitions' actual hashes in one run.
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(DefinitionHashes.forDefinition(definitionC()))
                    .as("C: a single-role AreaSelector, a one-parameter CommandAction, "
                            + "an EventTrigger with one filter")
                    .isEqualTo(BASELINE_HASH_C);
            softly.assertThat(DefinitionHashes.forDefinition(definitionD()))
                    .as("D: Label/Type/SemanticTag single-role, an empty-role TypeSelector, "
                            + "a one-method WebhookTrigger, a one-entry EmitEventAction, "
                            + "an empty-parameter CommandAction")
                    .isEqualTo(BASELINE_HASH_D);
        });
    }

    /** C — the instruction's preservation definition: one element on three of the eight records. */
    static AutomationDefinition definitionC() {
        Selector kitchen = new AreaSelector("kitchen", Set.of(EntityRole.PRIMARY));
        return new AutomationDefinition(AUTOMATION_C, "preserve-c", "preserve-c", null, true,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new EventTrigger("custom.ping", Map.of("source", "probe"), "t1")),
                List.of(new StateCondition(kitchen, "on_off", "on")),
                List.of(new CommandAction(kitchen, "set_level", Map.of("brightness", 50),
                        UnavailablePolicy.SKIP)));
    }

    /** D — the other five records at one element, plus the empty role set and the empty map. */
    static AutomationDefinition definitionD() {
        return new AutomationDefinition(AUTOMATION_D, "preserve-d", "preserve-d", null, true,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new WebhookTrigger("hook-d", Set.of("POST"), true, "t1")),
                List.of(new StateCondition(new LabelSelector("porch", Set.of(EntityRole.DIAGNOSTIC)),
                                "on_off", "on"),
                        new StateCondition(new TypeSelector("LIGHT", Set.of(EntityRole.CONFIG)),
                                "on_off", "on"),
                        new StateCondition(new TypeSelector("SENSOR", Set.of()), "on_off", "on"),
                        new StateCondition(new SemanticTagSelector("room", "porch", MatchMode.EXACT,
                                Set.of(EntityRole.PRIMARY)), "on_off", "on")),
                List.of(new EmitEventAction("custom.pong", Map.of("reason", "probe")),
                        new CommandAction(new DirectRefSelector(ENTITY), "turn_off", Map.of(),
                                UnavailablePolicy.SKIP)));
    }
}

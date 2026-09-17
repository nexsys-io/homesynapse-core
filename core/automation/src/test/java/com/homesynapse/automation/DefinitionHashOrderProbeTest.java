/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.homesynapse.device.EntityRole;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HASH-0 / HASH-1 (MEASURE-1, DUR-1 + HASH-1; IR-23) — a PROBE, not a pin. Prints
 * {@link DefinitionHashes#forDefinition} and the once-salted record's rendering for two
 * definitions and their insertion-order twins, so a loop of fresh test JVMs can read whether the
 * hash is stable across launches: <strong>A</strong> — one {@link StateCondition} over a two-role
 * {@link AreaSelector}; <strong>B</strong> — one {@link CommandAction} with two parameters. The
 * {@code HASH0} lines are MEASURE-1's four, byte-compatible with the 2026-09-15 HASH-0 loop file
 * (two hashes per definition across eight launches at {@code 3d40b5f}); the {@code HASH1} lines
 * print the twins built in the REVERSE insertion order — with the ordered views every launch
 * prints ONE hash per definition and each twin hashes equal to its original. The only assertion is
 * the hash's shape (64 lowercase hex); stability is MEASURED by the loop and PINNED by
 * {@link DefinitionHashStabilityTest}.
 *
 * <p>The {@code System.out} lines are test code under the loop's {@code -i} grep contract
 * (LTD-15 binds production). Every identity is a fixed, parsed ULID ({@code UlidFactory} draws
 * from {@code SecureRandom}, which would vary the rendering per launch on its own). The builders
 * are package-private so the stability test reuses the exact fixtures. No clock is read.</p>
 */
@DisplayName("Definition hash order probe (HASH-0 / HASH-1)")
class DefinitionHashOrderProbeTest {

    static final AutomationId AUTOMATION_A =
            AutomationId.of(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
    static final AutomationId AUTOMATION_B =
            AutomationId.of(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW"));
    static final EntityId ENTITY =
            EntityId.of(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX"));

    @Test
    @DisplayName("prints each definition's hash and the once-salted record's rendering, and its twin's (HASH-0 / HASH-1 probe)")
    void printsHashAndRendering() {
        long pid = ProcessHandle.current().pid();
        AutomationDefinition a = twoRoleSelector(EntityRole.DIAGNOSTIC, EntityRole.PRIMARY);
        AutomationDefinition b = twoParameterAction("transition", 2, "brightness", 50);
        AutomationDefinition aTwin = twoRoleSelector(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC);
        AutomationDefinition bTwin = twoParameterAction("brightness", 50, "transition", 2);

        String hashA = DefinitionHashes.forDefinition(a);
        String hashB = DefinitionHashes.forDefinition(b);
        String hashATwin = DefinitionHashes.forDefinition(aTwin);
        String hashBTwin = DefinitionHashes.forDefinition(bTwin);

        // HASH0 — MEASURE-1's four lines, unchanged; HASH1 — the insertion-order twins.
        System.out.println("HASH0 jvm=" + pid + " def=A hash=" + hashA);
        System.out.println("HASH0 def=A selector=" + a.conditions().get(0));
        System.out.println("HASH0 jvm=" + pid + " def=B hash=" + hashB);
        System.out.println("HASH0 def=B action=" + b.actions().get(0));
        System.out.println("HASH1 jvm=" + pid + " def=A-twin hash=" + hashATwin);
        System.out.println("HASH1 def=A-twin selector=" + aTwin.conditions().get(0));
        System.out.println("HASH1 jvm=" + pid + " def=B-twin hash=" + hashBTwin);
        System.out.println("HASH1 def=B-twin action=" + bTwin.actions().get(0));

        for (String hash : List.of(hashA, hashB, hashATwin, hashBTwin)) {
            assertThat(hash).matches("[0-9a-f]{64}");
        }
    }

    /** A's shape: the two-role selector — {@code first} inserted before {@code second}. */
    static AutomationDefinition twoRoleSelector(EntityRole first, EntityRole second) {
        Set<EntityRole> roles = new LinkedHashSet<>(List.of(first, second));
        return definition(AUTOMATION_A, "probe-a",
                new StateCondition(new AreaSelector("kitchen", roles), "on_off", "on"),
                new CommandAction(new DirectRefSelector(ENTITY), "turn_on", Map.of(),
                        UnavailablePolicy.SKIP));
    }

    /** B's shape: the two-parameter command action — {@code firstKey} put before {@code secondKey}. */
    static AutomationDefinition twoParameterAction(String firstKey, int firstValue,
                                                   String secondKey, int secondValue) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(firstKey, firstValue);
        parameters.put(secondKey, secondValue);
        Selector single = new AreaSelector("kitchen", Set.of(EntityRole.PRIMARY));
        return definition(AUTOMATION_B, "probe-b",
                new StateCondition(single, "on_off", "on"),
                new CommandAction(single, "set_level", parameters, UnavailablePolicy.SKIP));
    }

    static AutomationDefinition definition(AutomationId id, String slug,
                                           ConditionDefinition condition, ActionDefinition action) {
        return new AutomationDefinition(id, slug, slug, null, true, ConcurrencyMode.SINGLE, 1,
                MaxExceededSeverity.INFO, 0,
                List.of(new StateChangeTrigger(new DirectRefSelector(ENTITY), "on_off", null, "on",
                        null, "t1")),
                List.of(condition), List.of(action));
    }
}

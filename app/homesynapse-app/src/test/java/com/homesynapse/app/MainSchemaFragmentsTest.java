/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PKG-SEC-2 — the composition root's supply side: {@code Main} hands
 * {@code HomeSynapseCore.registerIntegrationSchema} every hosted integration's
 * config-schema fragment BEFORE {@code start()}, so Phase-1 validation composes
 * the real {@code integrations.{type}} schemas (R-4 C-1). The map is the one
 * seam {@code main()} iterates; this pins what it carries.
 *
 * <p>The third test pins the fragment at the point of supply since AMD-102
 * (CONFIG-ERROR-1): it declares NEITHER {@code permit_join_duration} (IGNORED
 * since PJ-2; REMOVED) NOR {@code availability} (IR-122: no reader; REMOVED) —
 * a composed fragment's every property is OPERATIVE at Phase-1 validation, and
 * under AMD-102 a configuration carrying an undeclared key fails the boot naming
 * it, so a key that is dead in the code must be absent from the fragment.</p>
 */
@DisplayName("Main -- the pre-start integration schema fragments (PKG-SEC-2)")
final class MainSchemaFragmentsTest {

    /** The {@code permit_join_duration} property object in the fragment text. */
    private static final Pattern PERMIT_JOIN_PROPERTY =
            Pattern.compile("\"permit_join_duration\"\\s*:\\s*\\{");
    /** The {@code availability} property object in the fragment text. */
    private static final Pattern AVAILABILITY_PROPERTY =
            Pattern.compile("\"availability\"\\s*:\\s*\\{");

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    MainSchemaFragmentsTest() {
    }

    @Test
    @DisplayName("the composition root supplies exactly the zigbee fragment, keyed by its "
            + "integrations.{type} key, as the shipped resource text")
    void suppliesTheZigbeeFragmentKeyedByType() {
        Map<String, String> fragments = Main.integrationSchemaFragments();

        assertThat(fragments).containsOnlyKeys(ZigbeeIntegrationFactory.INTEGRATION_TYPE);
        assertThat(fragments.get(ZigbeeIntegrationFactory.INTEGRATION_TYPE))
                .isEqualTo(ZigbeeIntegrationFactory.configSchemaJson())
                .doesNotContain("\"permit_join_duration\"")
                .doesNotContain("\"availability\"")
                .containsPattern("\"maximum\"\\s*:\\s*26");
    }

    @Test
    @DisplayName("the supplied map is unmodifiable (the supply is fixed at construction)")
    void suppliedMapIsUnmodifiable() {
        assertThatThrownBy(() -> Main.integrationSchemaFragments().put("x", "{}"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the supplied zigbee fragment declares NEITHER permit_join_duration NOR "
            + "availability (REMOVED, AMD-102 R-D / IR-122) — a configuration carrying either "
            + "fails the boot naming it; the 254 maximum left with the key (the channel's 26 "
            + "remains)")
    void zigbeeFragment_declaresNoRemovedKeys() {
        String fragment = Main.integrationSchemaFragments()
                .get(ZigbeeIntegrationFactory.INTEGRATION_TYPE);

        assertThat(PERMIT_JOIN_PROPERTY.matcher(fragment).find())
                .as("permit_join_duration left the fragment (AMD-102 R-D)").isFalse();
        assertThat(AVAILABILITY_PROPERTY.matcher(fragment).find())
                .as("availability left the fragment (IR-122)").isFalse();
        assertThat(fragment)
                .doesNotContainPattern("\"maximum\"\\s*:\\s*254")
                .containsPattern("\"maximum\"\\s*:\\s*26");
    }
}

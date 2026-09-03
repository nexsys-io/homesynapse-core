/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
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
 * <p>The second test pins the fragment's {@code permit_join_duration} shape at
 * the point of supply: a composed fragment's every {@code default} is OPERATIVE
 * (Doc 06 §3.1 stage 4 merges it into the model on every boot), and the M9.4-PJ
 * law is that an ABSENT key opens no join window — so the supplied fragment must
 * declare no default for that key, or every unconfigured boot would open the
 * network for joins.</p>
 */
@DisplayName("Main -- the pre-start integration schema fragments (PKG-SEC-2)")
final class MainSchemaFragmentsTest {

    /** The {@code permit_join_duration} property object in the fragment text. */
    private static final Pattern PERMIT_JOIN_PROPERTY =
            Pattern.compile("\"permit_join_duration\"\\s*:\\s*\\{([^}]*)\\}");

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
                .contains("\"permit_join_duration\"")
                .containsPattern("\"maximum\"\\s*:\\s*254");
    }

    @Test
    @DisplayName("the supplied map is unmodifiable (the supply is fixed at construction)")
    void suppliedMapIsUnmodifiable() {
        assertThatThrownBy(() -> Main.integrationSchemaFragments().put("x", "{}"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the supplied zigbee fragment declares NO default for permit_join_duration — "
            + "absent ⇒ no join window (M9.4-PJ); a default here would open the door on every "
            + "unconfigured boot once the fragment composes at Phase 1")
    void zigbeeFragment_declaresNoPermitJoinDefault() {
        String fragment = Main.integrationSchemaFragments()
                .get(ZigbeeIntegrationFactory.INTEGRATION_TYPE);
        Matcher property = PERMIT_JOIN_PROPERTY.matcher(fragment);

        assertThat(property.find()).as("the key is declared").isTrue();
        assertThat(property.group(1))
                .as("the property object carries min/max but no default")
                .containsPattern("\"minimum\"\\s*:\\s*1")
                .containsPattern("\"maximum\"\\s*:\\s*254")
                .doesNotContain("\"default\"");
    }
}

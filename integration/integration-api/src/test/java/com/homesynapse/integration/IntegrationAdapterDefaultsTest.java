/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.config.ConfigChangeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies the four AMD-55 {@code default} lifecycle hooks on
 * {@link IntegrationAdapter}: a minimal adapter that implements only the four
 * abstract methods inherits the conservative defaults, proving source/binary
 * compatibility (AMD-55-INV-01). Also pins the post-AMD-55 interface shape:
 * 8 declared methods (4 abstract + 4 default).
 *
 * <p>The {@link ConfigChangeSet} timestamp uses a literal
 * {@link Instant#parse(CharSequence)} so the {@code NO_DIRECT_TIME_ACCESS} arch
 * rule passes on this test source.</p>
 */
@DisplayName("IntegrationAdapter default hooks")
class IntegrationAdapterDefaultsTest {

    private static final ConfigChangeSet EMPTY_CHANGES =
            new ConfigChangeSet(Instant.parse("2026-01-01T00:00:00Z"), List.of());

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    IntegrationAdapterDefaultsTest() {
        // Defaults are sufficient.
    }

    /** A minimal adapter implementing only the four abstract methods. */
    private static IntegrationAdapter minimalAdapter() {
        return new IntegrationAdapter() {
            @Override
            public void initialize() {
                // no-op
            }

            @Override
            public void run() {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }

            @Override
            public CommandHandler commandHandler() {
                return null;
            }
        };
    }

    @Test
    @DisplayName("onConfigUpdated and onOptionsUpdated default to RESTART_REQUIRED")
    void configDefaultIsRestartRequired() {
        IntegrationAdapter adapter = minimalAdapter();

        assertThat(adapter.onConfigUpdated(EMPTY_CHANGES))
                .isEqualTo(ConfigUpdateOutcome.RESTART_REQUIRED);
        assertThat(adapter.onOptionsUpdated(EMPTY_CHANGES))
                .isEqualTo(ConfigUpdateOutcome.RESTART_REQUIRED);
    }

    @Test
    @DisplayName("onReauthRequired defaults to ReauthOutcome.UNSUPPORTED")
    void reauthDefaultIsUnsupported() {
        assertThat(minimalAdapter().onReauthRequired()).isEqualTo(ReauthOutcome.UNSUPPORTED);
    }

    @Test
    @DisplayName("migrate defaults to NOT_REQUIRED without throwing")
    void migrateDefaultIsNotRequired() throws PermanentIntegrationException {
        assertThat(minimalAdapter().migrate(1, 0)).isEqualTo(MigrationOutcome.NOT_REQUIRED);
    }

    @Test
    @DisplayName("interface declares 8 methods (4 abstract + 4 default)")
    void interfaceShape_declaredMethodCount() {
        assertThat(IntegrationAdapter.class.getDeclaredMethods()).hasSize(8);
    }

    @Test
    @DisplayName("interface declares exactly 4 abstract methods")
    void interfaceShape_abstractMethodCount() {
        long abstractCount = Arrays.stream(IntegrationAdapter.class.getDeclaredMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .count();

        assertThat(abstractCount).isEqualTo(4L);
    }
}

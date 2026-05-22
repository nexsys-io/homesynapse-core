/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DeploymentProfile} — verifies the per-profile tuning knobs
 * documented in the enum's Javadoc.
 *
 * <p>M3.6e.1 adds the {@code javalinMinThreads} and {@code javalinMaxThreads}
 * accessors that configure the embedded Jetty thread pool size for each
 * deployment profile. The tests below assert the exact magnitudes specified
 * in PLAN-M3-CONSOLIDATED-02 §10:</p>
 *
 * <ul>
 *   <li>{@link DeploymentProfile#STUDIO} — 1 / 4 (Pi 4 / SD card)</li>
 *   <li>{@link DeploymentProfile#HOME} — 2 / 8 (Pi 5 / NVMe, MVP default)</li>
 *   <li>{@link DeploymentProfile#PERFORMANCE} — 4 / 16 (x86 mini-PC / NVMe)</li>
 * </ul>
 */
@DisplayName("DeploymentProfile")
final class DeploymentProfileTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    DeploymentProfileTest() {
    }

    @Nested
    @DisplayName("Javalin thread pool sizing (M3.6e.1)")
    final class JavalinThreadPool {

        /** Explicit no-arg constructor. */
        JavalinThreadPool() {
        }

        @Test
        @DisplayName("STUDIO — 1 min, 4 max")
        void studioJavalinThreadPool() {
            assertThat(DeploymentProfile.STUDIO.javalinMinThreads()).isEqualTo(1);
            assertThat(DeploymentProfile.STUDIO.javalinMaxThreads()).isEqualTo(4);
        }

        @Test
        @DisplayName("HOME — 2 min, 8 max")
        void homeJavalinThreadPool() {
            assertThat(DeploymentProfile.HOME.javalinMinThreads()).isEqualTo(2);
            assertThat(DeploymentProfile.HOME.javalinMaxThreads()).isEqualTo(8);
        }

        @Test
        @DisplayName("PERFORMANCE — 4 min, 16 max")
        void performanceJavalinThreadPool() {
            assertThat(DeploymentProfile.PERFORMANCE.javalinMinThreads()).isEqualTo(4);
            assertThat(DeploymentProfile.PERFORMANCE.javalinMaxThreads()).isEqualTo(16);
        }

        @Test
        @DisplayName("minThreads <= maxThreads for every profile")
        void minNeverExceedsMax() {
            for (DeploymentProfile profile : DeploymentProfile.values()) {
                assertThat(profile.javalinMinThreads())
                        .as("min threads for %s", profile)
                        .isLessThanOrEqualTo(profile.javalinMaxThreads());
            }
        }
    }

    @Nested
    @DisplayName("Existing SQLite-PRAGMA knobs remain stable")
    final class ExistingFields {

        /** Explicit no-arg constructor. */
        ExistingFields() {
        }

        @Test
        @DisplayName("HOME is the MVP default with the documented values")
        void homeDefaults() {
            DeploymentProfile p = DeploymentProfile.HOME;
            assertThat(p.cacheSizeKiB()).isEqualTo(16_000);
            assertThat(p.mmapSizeBytes()).isEqualTo(268_435_456L);
            assertThat(p.journalSizeLimitBytes()).isEqualTo(6_144_000L);
            assertThat(p.busyTimeoutMs()).isEqualTo(5_000L);
            assertThat(p.readThreadCount()).isEqualTo(2);
        }
    }
}

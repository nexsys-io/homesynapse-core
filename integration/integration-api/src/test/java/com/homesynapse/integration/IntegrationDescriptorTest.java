/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Verifies the AMD-54/61/62/63/64 evolution of {@link IntegrationDescriptor}:
 * the {@code schemaVersion} &rarr; {@code descriptorSchemaVersion} rename, the
 * 8 &rarr; 14 component growth with the 8-arg convenience constructor, and every
 * new guard.
 */
@DisplayName("IntegrationDescriptor")
class IntegrationDescriptorTest {

    private static final Set<RequiredService> SERVICES = Set.of(RequiredService.SCHEDULER);
    private static final Set<DataPath> PATHS = Set.of(DataPath.DOMAIN);
    private static final HealthParameters HEALTH = HealthParameters.defaults();

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    IntegrationDescriptorTest() {
        // Defaults are sufficient.
    }

    /** A valid canonical (14-arg) descriptor with the given soft dependencies and timeout. */
    private static IntegrationDescriptor canonical(
            Set<String> dependsOn, Set<String> softDependencies, Duration plannedRestartTimeout) {
        return new IntegrationDescriptor(
                "zigbee", "Zigbee Adapter", IoType.SERIAL, SERVICES, PATHS, HEALTH,
                dependsOn, 1, 1, 0, softDependencies,
                BackoffParameters.defaults(), IsolationLevel.IN_JVM, plannedRestartTimeout);
    }

    /** A valid 8-arg (convenience) descriptor. */
    private static IntegrationDescriptor convenience() {
        return new IntegrationDescriptor(
                "zigbee", "Zigbee Adapter", IoType.SERIAL, SERVICES, PATHS, HEALTH, Set.of(), 1);
    }

    @Test
    @DisplayName("record has exactly 14 components")
    void hasFourteenComponents() {
        assertThat(IntegrationDescriptor.class.getRecordComponents()).hasSize(14);
    }

    @Nested
    @DisplayName("AMD-54: descriptor/config schema versioning")
    class Amd54 {

        /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
        Amd54() {
            // Defaults are sufficient.
        }

        @Test
        @DisplayName("descriptorSchemaVersion() exists; no schemaVersion() accessor remains")
        void renamedAccessor() throws NoSuchMethodException {
            assertThat(IntegrationDescriptor.class.getMethod("descriptorSchemaVersion"))
                    .isNotNull();
            assertThatThrownBy(() -> IntegrationDescriptor.class.getMethod("schemaVersion"))
                    .isInstanceOf(NoSuchMethodException.class);
        }

        @Test
        @DisplayName("8-arg convenience ctor defaults configSchemaMajor=1, configSchemaMinor=0")
        void convenienceCtorDefaults() {
            IntegrationDescriptor d = convenience();

            assertThat(d.configSchemaMajor()).isEqualTo(1);
            assertThat(d.configSchemaMinor()).isEqualTo(0);
            assertThat(d.descriptorSchemaVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("descriptorSchemaVersion < 1 is rejected")
        void descriptorSchemaVersionBelowOne_throws() {
            assertThatThrownBy(() -> new IntegrationDescriptor(
                    "zigbee", "Zigbee Adapter", IoType.SERIAL, SERVICES, PATHS, HEALTH, Set.of(), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("descriptorSchemaVersion");
        }

        @Test
        @DisplayName("configSchemaMajor < 1 is rejected")
        void configSchemaMajorBelowOne_throws() {
            assertThatThrownBy(() -> new IntegrationDescriptor(
                    "zigbee", "Zigbee Adapter", IoType.SERIAL, SERVICES, PATHS, HEALTH,
                    Set.of(), 1, 0, 0, Set.of(),
                    BackoffParameters.defaults(), IsolationLevel.IN_JVM, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configSchemaMajor");
        }

        @Test
        @DisplayName("configSchemaMinor < 0 is rejected")
        void configSchemaMinorNegative_throws() {
            assertThatThrownBy(() -> new IntegrationDescriptor(
                    "zigbee", "Zigbee Adapter", IoType.SERIAL, SERVICES, PATHS, HEALTH,
                    Set.of(), 1, 1, -1, Set.of(),
                    BackoffParameters.defaults(), IsolationLevel.IN_JVM, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configSchemaMinor");
        }
    }

    @Nested
    @DisplayName("AMD-61: soft dependencies")
    class Amd61 {

        /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
        Amd61() {
            // Defaults are sufficient.
        }

        @Test
        @DisplayName("convenience ctor defaults softDependencies to an empty set")
        void convenienceDefaultsEmpty() {
            assertThat(convenience().softDependencies()).isEmpty();
        }

        @Test
        @DisplayName("softDependencies is defensively copied")
        void defensivelyCopied() {
            Set<String> mutable = new HashSet<>();
            mutable.add("mqtt");
            IntegrationDescriptor d = canonical(Set.of(), mutable, null);

            mutable.add("extra");

            assertThat(d.softDependencies()).containsExactly("mqtt");
        }

        @Test
        @DisplayName("softDependencies is unmodifiable")
        void unmodifiable() {
            IntegrationDescriptor d = canonical(Set.of(), Set.of("mqtt"), null);

            assertThatThrownBy(() -> d.softDependencies().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a type in both dependsOn and softDependencies is rejected")
        void overlapWithDependsOn_throws() {
            assertThatThrownBy(() -> canonical(Set.of("mqtt"), Set.of("mqtt"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mqtt")
                    .hasMessageContaining("dependsOn")
                    .hasMessageContaining("softDependencies");
        }
    }

    @Nested
    @DisplayName("AMD-62/63/64: backoff, isolation, planned-restart timeout")
    class Amd62To64 {

        /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
        Amd62To64() {
            // Defaults are sufficient.
        }

        @Test
        @DisplayName("convenience ctor defaults backoff/isolation/plannedRestartTimeout")
        void convenienceDefaults() {
            IntegrationDescriptor d = convenience();

            assertThat(d.backoffParameters()).isEqualTo(BackoffParameters.defaults());
            assertThat(d.isolationLevel()).isEqualTo(IsolationLevel.IN_JVM);
            assertThat(d.plannedRestartTimeout()).isNull();
        }

        @Test
        @DisplayName("null backoffParameters is rejected")
        void nullBackoff_throws() {
            assertThatThrownBy(() -> new IntegrationDescriptor(
                    "zigbee", "Zigbee Adapter", IoType.SERIAL, SERVICES, PATHS, HEALTH,
                    Set.of(), 1, 1, 0, Set.of(), null, IsolationLevel.IN_JVM, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("backoffParameters");
        }

        @Test
        @DisplayName("null isolationLevel is rejected")
        void nullIsolation_throws() {
            assertThatThrownBy(() -> new IntegrationDescriptor(
                    "zigbee", "Zigbee Adapter", IoType.SERIAL, SERVICES, PATHS, HEALTH,
                    Set.of(), 1, 1, 0, Set.of(), BackoffParameters.defaults(), null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("isolationLevel");
        }

        @Test
        @DisplayName("null plannedRestartTimeout is accepted (= use global default)")
        void nullPlannedRestartTimeout_accepted() {
            assertThat(canonical(Set.of(), Set.of(), null).plannedRestartTimeout()).isNull();
        }

        @Test
        @DisplayName("a positive plannedRestartTimeout is accepted")
        void positivePlannedRestartTimeout_accepted() {
            assertThat(canonical(Set.of(), Set.of(), Duration.ofSeconds(90)).plannedRestartTimeout())
                    .isEqualTo(Duration.ofSeconds(90));
        }

        @Test
        @DisplayName("zero plannedRestartTimeout is rejected")
        void zeroPlannedRestartTimeout_throws() {
            assertThatThrownBy(() -> canonical(Set.of(), Set.of(), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("plannedRestartTimeout");
        }

        @Test
        @DisplayName("negative plannedRestartTimeout is rejected")
        void negativePlannedRestartTimeout_throws() {
            assertThatThrownBy(() -> canonical(Set.of(), Set.of(), Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("plannedRestartTimeout");
        }
    }
}

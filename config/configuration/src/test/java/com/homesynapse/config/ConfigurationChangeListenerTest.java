/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ConfigurationChangeListener} interface contract
 * (AMD-66): the plain, non-generic, non-sealed per-section reload-reaction
 * seam.
 *
 * <p>M6.1 defines the interface; the reload pipeline that <em>invokes</em>
 * registered listeners under the atomic swap is M6.4. Registration-surface
 * tests (constructor-time listener map, duplicate-section-path rejection —
 * AMD-66 §2.4) land with {@code StandardConfigurationService} in M6.1a,
 * because the registration surface lives on the service.</p>
 */
@DisplayName("ConfigurationChangeListener (AMD-66)")
class ConfigurationChangeListenerTest {

    /** Creates a new test instance. */
    ConfigurationChangeListenerTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    /** A recording listener returning a fixed classification. */
    private static final class RecordingListener implements ConfigurationChangeListener {

        private final String sectionPath;
        private final ReloadClassification classification;
        private final List<ConfigSection[]> invocations = new ArrayList<>();

        RecordingListener(String sectionPath, ReloadClassification classification) {
            this.sectionPath = sectionPath;
            this.classification = classification;
        }

        @Override
        public String sectionPath() {
            return sectionPath;
        }

        @Override
        public ReloadClassification onSectionChanged(ConfigSection previous,
                                                     ConfigSection candidate) {
            invocations.add(new ConfigSection[] {previous, candidate});
            return classification;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Interface shape (AMD-66 §2.2 — F7 corrected shape)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Interface shape")
    class InterfaceShapeTests {

        /** Creates a new test instance. */
        InterfaceShapeTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("interface declares exactly 2 methods, both abstract")
        void exactlyTwoAbstractMethods() {
            Method[] methods = ConfigurationChangeListener.class.getDeclaredMethods();

            assertThat(methods).hasSize(2);
            long abstractCount = java.util.Arrays.stream(methods)
                    .filter(m -> Modifier.isAbstract(m.getModifiers()))
                    .count();
            assertThat(abstractCount).isEqualTo(2L);
        }

        @Test
        @DisplayName("interface is plain: non-sealed and non-generic (F7)")
        void plainNonGenericNonSealed() {
            assertThat(ConfigurationChangeListener.class.isSealed()).isFalse();
            assertThat(ConfigurationChangeListener.class.getTypeParameters()).isEmpty();
        }

        @Test
        @DisplayName("onSectionChanged takes (ConfigSection, ConfigSection) and returns ReloadClassification")
        void onSectionChangedSignature() throws NoSuchMethodException {
            Method method = ConfigurationChangeListener.class.getMethod(
                    "onSectionChanged", ConfigSection.class, ConfigSection.class);

            assertThat(method.getReturnType()).isEqualTo(ReloadClassification.class);
        }

        @Test
        @DisplayName("sectionPath returns String")
        void sectionPathSignature() throws NoSuchMethodException {
            assertThat(ConfigurationChangeListener.class.getMethod("sectionPath")
                    .getReturnType()).isEqualTo(String.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Behavioral contract
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Behavioral contract")
    class BehavioralContractTests {

        /** Creates a new test instance. */
        BehavioralContractTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("listener returns its classification for a section change")
        void returnsClassification() {
            RecordingListener listener =
                    new RecordingListener("event_bus", ReloadClassification.HOT);
            ConfigSection previous = new ConfigSection(
                    "event_bus", Map.of("dispatch_parallelism", 4), Map.of());
            ConfigSection candidate = new ConfigSection(
                    "event_bus", Map.of("dispatch_parallelism", 8), Map.of());

            ReloadClassification result = listener.onSectionChanged(previous, candidate);

            assertThat(result).isEqualTo(ReloadClassification.HOT);
        }

        @Test
        @DisplayName("listener receives the exact previous and candidate sections")
        void receivesPreviousAndCandidate() {
            RecordingListener listener = new RecordingListener(
                    "integrations.zigbee", ReloadClassification.INTEGRATION_RESTART);
            ConfigSection previous = new ConfigSection(
                    "integrations.zigbee", Map.of("channel", 15), Map.of());
            ConfigSection candidate = new ConfigSection(
                    "integrations.zigbee", Map.of("channel", 20), Map.of());

            listener.onSectionChanged(previous, candidate);

            assertThat(listener.invocations).hasSize(1);
            assertThat(listener.invocations.get(0)[0]).isSameAs(previous);
            assertThat(listener.invocations.get(0)[1]).isSameAs(candidate);
        }

        @Test
        @DisplayName("sectionPath identifies the listener's section (matches ConfigSection.path())")
        void sectionPathMatchesSectionConvention() {
            RecordingListener listener = new RecordingListener(
                    "persistence.retention", ReloadClassification.PROCESS_RESTART);

            assertThat(listener.sectionPath()).isEqualTo("persistence.retention");
        }

        @Test
        @DisplayName("every ReloadClassification value is expressible as a listener result")
        void allClassificationsExpressible() {
            ConfigSection section = new ConfigSection("s", Map.of(), Map.of());

            for (ReloadClassification classification : ReloadClassification.values()) {
                RecordingListener listener = new RecordingListener("s", classification);

                assertThat(listener.onSectionChanged(section, section))
                        .isEqualTo(classification);
            }
        }
    }
}

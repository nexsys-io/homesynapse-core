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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the AMD-67 {@link ConfigMigrator} {@code (major, minor)} contract:
 * a migrator triggers only on a <em>major</em> mismatch (AMD-67-INV-02), a
 * minor-only mismatch never migrates, and the chain orders by
 * {@code (major, minor)}.
 *
 * <p>No production migrator exists yet (source-verified at AMD-67 authoring);
 * the chain selection below is the reference semantics the M6.1a loading
 * pipeline must implement. The {@link #chainFor(int, int, List)} helper is the
 * executable form of AMD-67-INV-02 and Doc 06 §3.7's linear-chain rule.</p>
 */
@DisplayName("ConfigMigrator chain (AMD-67)")
class ConfigMigratorChainTest {

    /** Creates a new test instance. */
    ConfigMigratorChainTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // Reference chain semantics (AMD-67-INV-02, Doc 06 §3.7)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Selects the migrators to apply for a persisted {@code (major, minor)}
     * pair: nothing when the persisted major equals the expected major
     * (minor-only mismatches never migrate — AMD-67-INV-02); otherwise every
     * registered migrator whose {@code fromMajor} lies in
     * {@code [persistedMajor, expectedMajor)}, ordered by
     * {@code (fromMajor, fromMinor)}.
     */
    private static List<ConfigMigrator> chainFor(int persistedMajor, int expectedMajor,
                                                 List<ConfigMigrator> registered) {
        if (persistedMajor >= expectedMajor) {
            return List.of();
        }
        return registered.stream()
                .filter(m -> m.fromMajor() >= persistedMajor && m.fromMajor() < expectedMajor)
                .sorted(Comparator.comparingInt(ConfigMigrator::fromMajor)
                        .thenComparingInt(ConfigMigrator::fromMinor))
                .toList();
    }

    /** A recording test migrator that stamps its identity into the map. */
    private static final class StubMigrator implements ConfigMigrator {

        private final int fromMajor;
        private final int fromMinor;
        private final int toMajor;
        private final int toMinor;
        private final List<String> applications;

        StubMigrator(int fromMajor, int fromMinor, int toMajor, int toMinor,
                     List<String> applications) {
            this.fromMajor = fromMajor;
            this.fromMinor = fromMinor;
            this.toMajor = toMajor;
            this.toMinor = toMinor;
            this.applications = applications;
        }

        @Override
        public int fromMajor() {
            return fromMajor;
        }

        @Override
        public int fromMinor() {
            return fromMinor;
        }

        @Override
        public int toMajor() {
            return toMajor;
        }

        @Override
        public int toMinor() {
            return toMinor;
        }

        @Override
        public MigrationResult migrate(Map<String, Object> rawConfig) {
            applications.add(fromMajor + "." + fromMinor + "->" + toMajor + "." + toMinor);
            Map<String, Object> migrated = new HashMap<>(rawConfig);
            migrated.put("migrated_to", toMajor + "." + toMinor);
            return new MigrationResult(migrated, List.of(new MigrationChange(
                    ChangeType.VALUE_TRANSFORMED,
                    "migrated_to",
                    fromMajor + "." + fromMinor,
                    toMajor + "." + toMinor,
                    "test migration step")));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Interface shape (AMD-67: 3 -> 5 methods)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Interface shape")
    class InterfaceShapeTests {

        /** Creates a new test instance. */
        InterfaceShapeTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("interface declares exactly 5 methods, all abstract")
        void exactlyFiveAbstractMethods() {
            Method[] methods = ConfigMigrator.class.getDeclaredMethods();

            assertThat(methods).hasSize(5);
            long abstractCount = java.util.Arrays.stream(methods)
                    .filter(m -> Modifier.isAbstract(m.getModifiers()))
                    .count();
            assertThat(abstractCount).isEqualTo(5L);
        }

        @Test
        @DisplayName("declares fromMajor/fromMinor/toMajor/toMinor accessors")
        void declaresMajorMinorAccessors() throws NoSuchMethodException {
            assertThat(ConfigMigrator.class.getMethod("fromMajor").getReturnType())
                    .isEqualTo(int.class);
            assertThat(ConfigMigrator.class.getMethod("fromMinor").getReturnType())
                    .isEqualTo(int.class);
            assertThat(ConfigMigrator.class.getMethod("toMajor").getReturnType())
                    .isEqualTo(int.class);
            assertThat(ConfigMigrator.class.getMethod("toMinor").getReturnType())
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("migrate(Map) signature is unchanged by AMD-67")
        void migrateSignatureUnchanged() throws NoSuchMethodException {
            Method migrate = ConfigMigrator.class.getMethod("migrate", Map.class);

            assertThat(migrate.getReturnType()).isEqualTo(MigrationResult.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-67 §5 — trigger and ordering semantics
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Trigger and ordering semantics")
    class TriggerSemanticsTests {

        /** Creates a new test instance. */
        TriggerSemanticsTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("a lower persisted major triggers migration")
        void majorMismatchMigrates() {
            List<String> applications = new ArrayList<>();
            List<ConfigMigrator> registered = List.of(
                    new StubMigrator(1, 0, 2, 0, applications));

            List<ConfigMigrator> chain = chainFor(1, 2, registered);
            Map<String, Object> result = applyChain(chain, Map.of("schema_version", "1.0"));

            assertThat(applications).containsExactly("1.0->2.0");
            assertThat(result).containsEntry("migrated_to", "2.0");
        }

        @Test
        @DisplayName("same major, lower minor does NOT migrate (AMD-67-INV-02)")
        void minorOnlyMismatchDoesNotMigrate() {
            List<String> applications = new ArrayList<>();
            List<ConfigMigrator> registered = List.of(
                    new StubMigrator(2, 0, 3, 0, applications));

            // Persisted (2, 0) under an expected (2, 1): same major, older minor.
            List<ConfigMigrator> chain = chainFor(2, 2, registered);

            assertThat(chain).isEmpty();
            assertThat(applications).isEmpty();
        }

        @Test
        @DisplayName("multi-step migration applies in (major, minor) order")
        void chainOrdersByMajorMinor() {
            List<String> applications = new ArrayList<>();
            // Registered deliberately out of order.
            List<ConfigMigrator> registered = List.of(
                    new StubMigrator(3, 0, 4, 0, applications),
                    new StubMigrator(1, 0, 2, 0, applications),
                    new StubMigrator(2, 1, 3, 0, applications),
                    new StubMigrator(2, 0, 2, 1, applications));

            List<ConfigMigrator> chain = chainFor(1, 4, registered);
            applyChain(chain, Map.of());

            assertThat(applications).containsExactly(
                    "1.0->2.0", "2.0->2.1", "2.1->3.0", "3.0->4.0");
        }

        @Test
        @DisplayName("persisted version at expected version selects no migrators")
        void upToDateSelectsNothing() {
            List<String> applications = new ArrayList<>();
            List<ConfigMigrator> registered = List.of(
                    new StubMigrator(1, 0, 2, 0, applications));

            assertThat(chainFor(2, 2, registered)).isEmpty();
        }

        @Test
        @DisplayName("migrate does not modify the input map")
        void migrateDoesNotModifyInput() {
            List<String> applications = new ArrayList<>();
            StubMigrator migrator = new StubMigrator(1, 0, 2, 0, applications);
            Map<String, Object> input = Map.of("key", "value");

            MigrationResult result = migrator.migrate(input);

            assertThat(input).containsOnlyKeys("key");
            assertThat(result.migratedConfig()).containsKeys("key", "migrated_to");
        }

        private Map<String, Object> applyChain(List<ConfigMigrator> chain,
                                               Map<String, Object> initial) {
            Map<String, Object> current = initial;
            for (ConfigMigrator migrator : chain) {
                current = migrator.migrate(current).migratedConfig();
            }
            return current;
        }
    }
}

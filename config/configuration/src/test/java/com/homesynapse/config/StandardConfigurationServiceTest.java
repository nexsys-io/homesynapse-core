/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.ConfigValidationCompletedEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectType;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link StandardConfigurationService} — the M6.1a load path:
 * the Doc 06 §3.1 pipeline (parse, migrate, default-merge, validate,
 * model construction), DP-2 startup error handling (ERROR reverts to the
 * schema default, FATAL aborts), the AMD-67 migration-trigger semantics
 * pinned by {@link ConfigMigratorChainTest}, AMD-66 §2.4 listener
 * registration, and the AMD-70 {@code config.validation_completed}
 * publish path (DIAGNOSTIC, SYSTEM origin, null eventTime, system subject,
 * via {@code publishRoot}).
 *
 * <p>The reload pipeline and the write path are M6.4 — both methods are
 * staged as {@link UnsupportedOperationException} here and asserted as
 * such.</p>
 */
@DisplayName("StandardConfigurationService (M6.1a load path)")
class StandardConfigurationServiceTest {

    private static final Instant CLOCK_INSTANT = Instant.parse("2026-06-10T00:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final Instant FILE_TIME = Instant.parse("2025-12-31T08:30:00Z");
    private static final SystemId SYSTEM_ID = SystemId.of(new Ulid(7L, 7L));

    private static final String EVENT_BUS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "queue_capacity": { "type": "integer", "minimum": 1, "default": 1024 },
                "dispatch_mode": {
                  "type": "string",
                  "enum": ["serial", "parallel"],
                  "default": "serial"
                }
              },
              "additionalProperties": false
            }
            """;

    private static final String PERSISTENCE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "retention": {
                  "type": "object",
                  "properties": {
                    "max_days": { "type": "integer", "minimum": 1, "default": 30 }
                  },
                  "additionalProperties": false
                }
              },
              "additionalProperties": false
            }
            """;

    private static final String SECURITY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "api_token": { "type": "string" }
              },
              "required": ["api_token"],
              "additionalProperties": false
            }
            """;

    @TempDir
    Path configDir;

    private final RecordingEventPublisher publisher = new RecordingEventPublisher();

    /** Creates a new test instance. */
    StandardConfigurationServiceTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ──────────────────────────────────────────────────────────────────

    private StandardSchemaRegistry registry(int major, int minor) {
        StandardSchemaRegistry registry = new StandardSchemaRegistry(major, minor);
        registry.registerCoreSchema("event_bus", EVENT_BUS_SCHEMA);
        registry.registerCoreSchema("persistence", PERSISTENCE_SCHEMA);
        return registry;
    }

    private StandardConfigurationService service() {
        return service(1, 0, List.of(), List.of());
    }

    private StandardConfigurationService service(int expectedMajor, int expectedMinor,
                                                 List<ConfigMigrator> migrators,
                                                 List<ConfigurationChangeListener> listeners) {
        return new StandardConfigurationService(
                configDir, expectedMajor, expectedMinor, FIXED_CLOCK, SYSTEM_ID,
                publisher, registry(expectedMajor, expectedMinor),
                new JsonSchemaCompositeValidator(), migrators, listeners);
    }

    private void writeRoot(String yaml) throws IOException {
        Path root = configDir.resolve("homesynapse.yaml");
        Files.writeString(root, yaml);
        Files.setLastModifiedTime(root, FileTime.from(FILE_TIME));
    }

    private ConfigValidationCompletedEvent publishedEvent() {
        assertThat(publisher.rootDrafts).hasSize(1);
        return (ConfigValidationCompletedEvent) publisher.rootDrafts.get(0).payload();
    }

    /** Listener stub for registration tests; classification is M6.4. */
    private static ConfigurationChangeListener listenerFor(String sectionPath) {
        return new ConfigurationChangeListener() {
            @Override
            public String sectionPath() {
                return sectionPath;
            }

            @Override
            public ReloadClassification onSectionChanged(ConfigSection previous,
                                                         ConfigSection candidate) {
                return ReloadClassification.HOT;
            }
        };
    }

    /** Recording migrator mirroring the ConfigMigratorChainTest stub. */
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
            return new MigrationResult(migrated, List.of());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Construction (AMD-66 §2.4 registration; ruled List parameter)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        /** Creates a new test instance. */
        ConstructionTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("two listeners for one section path are rejected at construction (AMD-66 §2.4)")
        void duplicateRegistrationRejected() {
            List<ConfigurationChangeListener> duplicates =
                    List.of(listenerFor("event_bus"), listenerFor("event_bus"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service(1, 0, List.of(), duplicates))
                    .withMessageContaining("event_bus");
        }

        @Test
        @DisplayName("distinct listener section paths are accepted")
        void distinctListenersAccepted() {
            List<ConfigurationChangeListener> listeners =
                    List.of(listenerFor("event_bus"), listenerFor("persistence"));

            ConfigurationService accepted = service(1, 0, List.of(), listeners);

            assertThat(accepted).isNotNull();
        }

        @Test
        @DisplayName("declared schema pair is guarded (major >= 1, minor >= 0)")
        void declaredPairGuarded() {
            // Construct directly with a VALID registry so the rejection under
            // test is the service's own guard, not the registry's.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StandardConfigurationService(
                            configDir, 0, 0, FIXED_CLOCK, SYSTEM_ID, publisher,
                            registry(1, 0), new JsonSchemaCompositeValidator(),
                            List.of(), List.of()))
                    .withMessageContaining("Major");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StandardConfigurationService(
                            configDir, 1, -1, FIXED_CLOCK, SYSTEM_ID, publisher,
                            registry(1, 0), new JsonSchemaCompositeValidator(),
                            List.of(), List.of()))
                    .withMessageContaining("Minor");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // load() — happy paths (Doc 06 §3.1, INV-CE-02)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load() happy paths")
    class LoadHappyPathTests {

        /** Creates a new test instance. */
        LoadHappyPathTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("zero-config: absent file yields a complete model from schema defaults (INV-CE-02)")
        void zeroConfigLoadsFromDefaults() throws Exception {
            ConfigModel model = service().load();

            assertThat(model.configSchemaMajor()).isEqualTo(1);
            assertThat(model.configSchemaMinor()).isEqualTo(0);
            assertThat(model.sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 1024)
                    .containsEntry("dispatch_mode", "serial");
            assertThat(model.loadedAt()).isEqualTo(CLOCK_INSTANT);
            assertThat(publishedEvent().issueCount()).isZero();
        }

        @Test
        @DisplayName("populated config: user values override defaults; defaults fill gaps")
        void populatedConfigOverridesDefaults() throws Exception {
            writeRoot("""
                    event_bus:
                      queue_capacity: 64
                    """);

            ConfigModel model = service().load();

            ConfigSection eventBus = model.sections().get("event_bus");
            assertThat(eventBus.values())
                    .containsEntry("queue_capacity", 64)
                    .containsEntry("dispatch_mode", "serial");
            assertThat(eventBus.defaults()).containsEntry("queue_capacity", 1024);
            assertThat(model.rawMap()).containsKey("schema_version");
        }

        @Test
        @DisplayName("loadedAt comes from the injected clock; fileModifiedAt from the file mtime")
        void timestampsAreSourcedCorrectly() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");

            ConfigModel model = service().load();

            assertThat(model.loadedAt()).isEqualTo(CLOCK_INSTANT);
            assertThat(model.fileModifiedAt()).isEqualTo(FILE_TIME);
        }

        @Test
        @DisplayName("nested maps surface as dotted-path sections")
        void nestedSectionsExposedByDottedPath() throws Exception {
            writeRoot("""
                    persistence:
                      retention:
                        max_days: 14
                    """);

            ConfigModel model = service().load();

            assertThat(model.sections()).containsKeys("persistence", "persistence.retention");
            assertThat(model.sections().get("persistence.retention").values())
                    .containsEntry("max_days", 14);
        }

        @Test
        @DisplayName("a second load() re-runs the pipeline and swaps the active model")
        void reloadingSwapsActiveModel() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            svc.load();

            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 128);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // load() — DP-2 error handling (Doc 06 §3.6 startup semantics)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load() error handling")
    class LoadErrorHandlingTests {

        /** Creates a new test instance. */
        LoadErrorHandlingTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("ERROR issue reverts the key to its schema default (degraded-but-functional)")
        void errorRevertsKeyToDefault() throws Exception {
            writeRoot("""
                    event_bus:
                      queue_capacity: -5
                    """);

            ConfigModel model = service().load();

            assertThat(model.sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 1024);
            ConfigValidationCompletedEvent event = publishedEvent();
            assertThat(event.issueCount()).isEqualTo(1);
            assertThat(event.severityCounts()).containsEntry("ERROR", 1);
        }

        @Test
        @DisplayName("WARNING issue leaves the value untouched and load succeeds")
        void warningKeepsValue() throws Exception {
            writeRoot("""
                    event_bus:
                      qeue_capacity: 64
                    """);

            ConfigModel model = service().load();

            assertThat(model.sections().get("event_bus").values())
                    .containsEntry("qeue_capacity", 64);
            assertThat(publishedEvent().severityCounts()).containsEntry("WARNING", 1);
        }

        @Test
        @DisplayName("FATAL issue aborts the load with ConfigurationLoadException")
        void fatalAbortsLoad() throws Exception {
            StandardSchemaRegistry registry = registry(1, 0);
            registry.registerCoreSchema("security", SECURITY_SCHEMA);
            StandardConfigurationService svc = new StandardConfigurationService(
                    configDir, 1, 0, FIXED_CLOCK, SYSTEM_ID, publisher, registry,
                    new JsonSchemaCompositeValidator(), List.of(), List.of());
            writeRoot("""
                    security:
                      api_token: null
                    """);

            assertThatThrownBy(svc::load)
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("security.api_token");
        }

        @Test
        @DisplayName("the validation event is still published when the pass found FATAL issues")
        void fatalPassStillPublishesEvent() throws Exception {
            StandardSchemaRegistry registry = registry(1, 0);
            registry.registerCoreSchema("security", SECURITY_SCHEMA);
            StandardConfigurationService svc = new StandardConfigurationService(
                    configDir, 1, 0, FIXED_CLOCK, SYSTEM_ID, publisher, registry,
                    new JsonSchemaCompositeValidator(), List.of(), List.of());
            writeRoot("security: {}\n");

            assertThatThrownBy(svc::load)
                    .isInstanceOf(ConfigurationLoadException.class);

            assertThat(publishedEvent().severityCounts()).containsEntry("FATAL", 1);
        }

        @Test
        @DisplayName("a parse-level FATAL aborts before any validation pass — no event")
        void parseFatalPublishesNoEvent() throws Exception {
            writeRoot("event_bus: [unclosed\n");

            assertThatThrownBy(() -> service().load())
                    .isInstanceOf(ConfigurationLoadException.class);

            assertThat(publisher.rootDrafts).isEmpty();
        }

        @Test
        @DisplayName("a failing event publisher never fails the load (observability-only, AMD-70-INV-01)")
        void publisherFailureDoesNotFailLoad() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            publisher.throwOnPublish = true;
            StandardConfigurationService svc = service();

            ConfigModel model = svc.load();

            assertThat(model).isNotNull();
            assertThat(svc.getCurrentModel()).isSameAs(model);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-70 publish metadata (ruled 2026-06-10)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("config.validation_completed publishes once: DIAGNOSTIC, SYSTEM origin, "
            + "null eventTime, system subject, null actor, via publishRoot")
    void validationCompletedPublishMetadata() throws Exception {
        writeRoot("event_bus:\n  queue_capacity: 64\n");

        service().load();

        assertThat(publisher.rootDrafts).hasSize(1);
        assertThat(publisher.chainedDrafts).isEmpty();
        EventDraft draft = publisher.rootDrafts.get(0);
        assertThat(draft.eventType()).isEqualTo(EventTypes.CONFIG_VALIDATION_COMPLETED);
        assertThat(draft.schemaVersion()).isEqualTo(1);
        assertThat(draft.eventTime()).isNull();
        assertThat(draft.priority()).isEqualTo(EventPriority.DIAGNOSTIC);
        assertThat(draft.origin()).isEqualTo(EventOrigin.SYSTEM);
        assertThat(draft.subjectRef().type()).isEqualTo(SubjectType.SYSTEM);
        assertThat(draft.subjectRef().id()).isEqualTo(SYSTEM_ID.value());
        assertThat(draft.actorRef()).isNull();
        assertThat(draft.idempotencyKey()).isNull();

        ConfigValidationCompletedEvent event =
                (ConfigValidationCompletedEvent) draft.payload();
        assertThat(event.configSchemaMajor()).isEqualTo(1);
        assertThat(event.configSchemaMinor()).isZero();
        assertThat(event.issueCount()).isZero();
        assertThat(event.severityCounts()).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-67 migration semantics (ConfigMigratorChainTest reference)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Migration trigger and chain")
    class MigrationTests {

        /** Creates a new test instance. */
        MigrationTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("a lower persisted major triggers the chain in (major, minor) order")
        void majorMismatchMigratesInOrder() throws Exception {
            writeRoot("""
                    schema_version: { major: 1, minor: 0 }
                    event_bus:
                      queue_capacity: 64
                    """);
            List<String> applications = new ArrayList<>();
            // Registered deliberately out of order (chainOrdersByMajorMinor).
            List<ConfigMigrator> migrators = List.of(
                    new StubMigrator(2, 1, 3, 0, applications),
                    new StubMigrator(1, 0, 2, 0, applications),
                    new StubMigrator(2, 0, 2, 1, applications));

            ConfigModel model = service(3, 0, migrators, List.of()).load();

            assertThat(applications).containsExactly("1.0->2.0", "2.0->2.1", "2.1->3.0");
            assertThat(model.configSchemaMajor()).isEqualTo(3);
            assertThat(model.configSchemaMinor()).isZero();
            assertThat(model.rawMap().get("schema_version"))
                    .isEqualTo(Map.of("major", 3, "minor", 0));
        }

        @Test
        @DisplayName("same major, older minor does NOT migrate (AMD-67-INV-02)")
        void minorOnlyMismatchDoesNotMigrate() throws Exception {
            writeRoot("""
                    schema_version: { major: 1, minor: 0 }
                    event_bus:
                      queue_capacity: 64
                    """);
            List<String> applications = new ArrayList<>();
            List<ConfigMigrator> migrators = List.of(
                    new StubMigrator(1, 0, 2, 0, applications));

            ConfigModel model = service(1, 5, migrators, List.of()).load();

            assertThat(applications).isEmpty();
            assertThat(model.configSchemaMajor()).isEqualTo(1);
            assertThat(model.configSchemaMinor()).isZero();
        }

        @Test
        @DisplayName("absent schema_version is treated as the expected pair (zero-config)")
        void absentSchemaVersionAssumesExpectedPair() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            List<String> applications = new ArrayList<>();
            List<ConfigMigrator> migrators = List.of(
                    new StubMigrator(1, 0, 2, 0, applications));

            ConfigModel model = service(2, 0, migrators, List.of()).load();

            assertThat(applications).isEmpty();
            assertThat(model.configSchemaMajor()).isEqualTo(2);
        }

        @Test
        @DisplayName("a persisted major NEWER than the runtime is FATAL (forward-only)")
        void newerPersistedMajorIsFatal() throws Exception {
            writeRoot("""
                    schema_version: { major: 3, minor: 0 }
                    """);

            assertThatThrownBy(() -> service().load())
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("schema_version");
        }

        @Test
        @DisplayName("a chain gap that cannot reach the expected major is FATAL")
        void chainGapIsFatal() throws Exception {
            writeRoot("""
                    schema_version: { major: 1, minor: 0 }
                    """);
            List<ConfigMigrator> migrators = List.of(
                    new StubMigrator(1, 0, 2, 0, new ArrayList<>()));

            assertThatThrownBy(() -> service(3, 0, migrators, List.of()).load())
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("migration");
        }

        @Test
        @DisplayName("a non-object schema_version is FATAL and no event is published (DP-1)")
        void malformedSchemaVersionIsFatal() throws Exception {
            writeRoot("schema_version: 2\n");

            assertThatThrownBy(() -> service().load())
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("schema_version");
            assertThat(publisher.rootDrafts).isEmpty();
        }

        @Test
        @DisplayName("a schema_version major below 1 is FATAL")
        void outOfRangeSchemaVersionIsFatal() throws Exception {
            writeRoot("schema_version: { major: 0, minor: 0 }\n");

            assertThatThrownBy(() -> service().load())
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("schema_version");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Read surface
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Read surface")
    class ReadSurfaceTests {

        /** Creates a new test instance. */
        ReadSurfaceTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("getCurrentModel before load() is an IllegalStateException")
        void getCurrentModelBeforeLoadRejected() {
            assertThatThrownBy(() -> service().getCurrentModel())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("load");
        }

        @Test
        @DisplayName("getSection returns the section for a known path")
        void getSectionReturnsKnownPath() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            assertThat(svc.getSection("event_bus")).isPresent();
            assertThat(svc.getSection("event_bus").orElseThrow().values())
                    .containsEntry("queue_capacity", 64);
        }

        @Test
        @DisplayName("getSection returns empty for an unknown path")
        void getSectionReturnsEmptyForUnknownPath() throws Exception {
            StandardConfigurationService svc = service();
            svc.load();

            assertThat(svc.getSection("does.not.exist")).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // M6.4 staging
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("M6.4 staging")
    class StagingTests {

        /** Creates a new test instance. */
        StagingTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("reload() is staged until M6.4")
        void reloadStagedUntilM64() {
            assertThatThrownBy(() -> service().reload())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("M6.4");
        }

        @Test
        @DisplayName("write() is staged until M6.4")
        void writeStagedUntilM64() {
            assertThatThrownBy(() -> service().write(List.of(), CLOCK_INSTANT))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("M6.4");
        }
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.ConfigErrorEvent;
import com.homesynapse.event.ConfigSectionReloadedEvent;
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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for {@link StandardConfigurationService#reload()} — the M6.4
 * hot-reload pipeline (Doc 06 §3.3): re-parse, diff (DP-5), AMD-66 listener
 * classification with the per-property {@code x-reload} fallback (DP-4),
 * the C5/INV-RF-06 atomicity rule (FATAL or ERROR rejects the whole
 * candidate, active model untouched, no events), the DP-1 volatile
 * reference swap (torn-read freedom, REC-133), and the AMD-70
 * {@code config.section_reloaded} publication per changed section with the
 * DP-9 ruled metadata.
 */
@DisplayName("StandardConfigurationService.reload() (M6.4)")
class StandardConfigurationServiceReloadTest {

    private static final Instant CLOCK_INSTANT = Instant.parse("2026-06-11T00:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final SystemId SYSTEM_ID = SystemId.of(new Ulid(7L, 7L));

    /**
     * Schema exercising all three classification sources: an {@code x-reload}
     * HOT key, an {@code x-reload} integration-restart key, and an
     * unannotated key (AMD-66 §2.3 PROCESS_RESTART fallback).
     */
    private static final String EVENT_BUS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "queue_capacity": {
                  "type": "integer", "minimum": 1, "default": 1024,
                  "x-reload": "hot"
                },
                "poll_interval": {
                  "type": "integer", "minimum": 1, "default": 30,
                  "x-reload": "integration-restart"
                },
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

    /** Monotonic mtime source — filesystem granularity is too coarse to rely on. */
    private final AtomicInteger mtimeTick = new AtomicInteger();

    /** Creates a new test instance. */
    StandardConfigurationServiceReloadTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ──────────────────────────────────────────────────────────────────

    private StandardSchemaRegistry registry() {
        StandardSchemaRegistry registry = new StandardSchemaRegistry(1, 0);
        registry.registerCoreSchema("event_bus", EVENT_BUS_SCHEMA);
        registry.registerCoreSchema("persistence", PERSISTENCE_SCHEMA);
        return registry;
    }

    /** Real-but-empty secret machinery (M6.2) — lazy, so tag-free loads
     * touch no key files (INV-CE-02). Tag behavior is covered by
     * {@link StandardConfigurationServiceSecretsTest}. */
    private SecretStore noSecrets() {
        return SecretStore.create(configDir,
                ScopeKeyManager.create(configDir, FIXED_CLOCK), FIXED_CLOCK);
    }

    private StandardConfigurationService service(
            List<ConfigurationChangeListener> listeners) {
        return new StandardConfigurationService(
                configDir, 1, 0, FIXED_CLOCK, SYSTEM_ID, publisher, registry(),
                new JsonSchemaCompositeValidator(), List.of(), listeners,
                noSecrets(), key -> null);
    }

    private StandardConfigurationService service() {
        return service(List.of());
    }

    /**
     * Writes the root document with an explicitly advancing mtime so the
     * write-path token and reload re-reads never collide on coarse
     * filesystem timestamp granularity (M6.4 instruction: set
     * {@code Files.setLastModifiedTime}, never sleep).
     */
    private void writeRoot(String yaml) throws IOException {
        Path root = configDir.resolve("homesynapse.yaml");
        Files.writeString(root, yaml);
        Files.setLastModifiedTime(root, FileTime.from(
                Instant.parse("2026-01-01T00:00:00Z")
                        .plusSeconds(mtimeTick.incrementAndGet())));
    }

    private List<ConfigSectionReloadedEvent> sectionReloadedEvents() {
        return publisher.rootDrafts.stream()
                .map(EventDraft::payload)
                .filter(ConfigSectionReloadedEvent.class::isInstance)
                .map(ConfigSectionReloadedEvent.class::cast)
                .toList();
    }

    private List<EventDraft> sectionReloadedDrafts() {
        return publisher.rootDrafts.stream()
                .filter(draft -> EventTypes.CONFIG_SECTION_RELOADED
                        .equals(draft.eventType()))
                .toList();
    }

    private static ConfigurationChangeListener listener(
            String sectionPath, ReloadClassification result) {
        return new ConfigurationChangeListener() {
            @Override
            public String sectionPath() {
                return sectionPath;
            }

            @Override
            public ReloadClassification onSectionChanged(ConfigSection previous,
                                                         ConfigSection candidate) {
                return result;
            }
        };
    }

    // ──────────────────────────────────────────────────────────────────
    // Happy path + classification sources (DP-3 / DP-4, REC-132)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Classification")
    class ClassificationTests {

        /** Creates a new test instance. */
        ClassificationTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("HOT x-reload change is applied and published with appliedClassification=HOT")
        void hotChangeAppliedAndPublished() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            ReloadResult result = svc.reload();

            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 128);
            assertThat(result.newModel()).isSameAs(svc.getCurrentModel());
            assertThat(result.changeSet().changes()).hasSize(1);
            ConfigChange change = result.changeSet().changes().get(0);
            assertThat(change.sectionPath()).isEqualTo("event_bus");
            assertThat(change.key()).isEqualTo("queue_capacity");
            assertThat(change.oldValue()).isEqualTo(64);
            assertThat(change.newValue()).isEqualTo(128);
            assertThat(change.reload()).isEqualTo(ReloadClassification.HOT);

            assertThat(sectionReloadedEvents()).hasSize(1);
            ConfigSectionReloadedEvent event = sectionReloadedEvents().get(0);
            assertThat(event.sectionPath()).isEqualTo("event_bus");
            assertThat(event.changeCount()).isEqualTo(1);
            assertThat(event.appliedClassification()).isEqualTo("HOT");
        }

        @Test
        @DisplayName("INTEGRATION_RESTART is reported, not acted on — the model still swaps")
        void integrationRestartReportedNotActedOn() throws Exception {
            writeRoot("event_bus:\n  poll_interval: 30\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("event_bus:\n  poll_interval: 60\n");
            svc.reload();

            // M6.4 reports classifications; the M9 supervisor consumes them.
            // Nothing restarts here — the new value is simply active.
            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("poll_interval", 60);
            assertThat(sectionReloadedEvents()).hasSize(1);
            assertThat(sectionReloadedEvents().get(0).appliedClassification())
                    .isEqualTo("INTEGRATION_RESTART");
        }

        @Test
        @DisplayName("unannotated property with no listener falls back to PROCESS_RESTART (AMD-66 §2.3)")
        void unannotatedFallsBackToProcessRestart() throws Exception {
            writeRoot("event_bus:\n  dispatch_mode: serial\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("event_bus:\n  dispatch_mode: parallel\n");
            ReloadResult result = svc.reload();

            assertThat(result.changeSet().changes().get(0).reload())
                    .isEqualTo(ReloadClassification.PROCESS_RESTART);
            assertThat(sectionReloadedEvents().get(0).appliedClassification())
                    .isEqualTo("PROCESS_RESTART");
        }

        @Test
        @DisplayName("section classification is the most restrictive among its changed keys")
        void mostRestrictiveAmongChangedKeys() throws Exception {
            writeRoot("""
                    event_bus:
                      queue_capacity: 64
                      dispatch_mode: serial
                    """);
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("""
                    event_bus:
                      queue_capacity: 128
                      dispatch_mode: parallel
                    """);
            ReloadResult result = svc.reload();

            // Per-key classifications stay independent (DP-5)…
            assertThat(result.changeSet().changes())
                    .extracting(ConfigChange::key, ConfigChange::reload)
                    .containsExactlyInAnyOrder(
                            tuple("queue_capacity", ReloadClassification.HOT),
                            tuple("dispatch_mode", ReloadClassification.PROCESS_RESTART));
            // …while the section-level fallback aggregates most-restrictive.
            assertThat(sectionReloadedEvents()).hasSize(1);
            assertThat(sectionReloadedEvents().get(0).appliedClassification())
                    .isEqualTo("PROCESS_RESTART");
        }

        @Test
        @DisplayName("a registered listener's return drives the section's appliedClassification")
        void listenerReturnDrivesClassification() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service(List.of(
                    listener("event_bus", ReloadClassification.INTEGRATION_RESTART)));
            svc.load();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            svc.reload();

            // The x-reload annotation says HOT; the listener overrides the
            // section-level classification (DP-4).
            assertThat(sectionReloadedEvents().get(0).appliedClassification())
                    .isEqualTo("INTEGRATION_RESTART");
        }

        @Test
        @DisplayName("the listener receives the previous and candidate sections")
        void listenerReceivesPreviousAndCandidate() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            AtomicReference<ConfigSection> seenPrevious = new AtomicReference<>();
            AtomicReference<ConfigSection> seenCandidate = new AtomicReference<>();
            ConfigurationChangeListener capturing = new ConfigurationChangeListener() {
                @Override
                public String sectionPath() {
                    return "event_bus";
                }

                @Override
                public ReloadClassification onSectionChanged(ConfigSection previous,
                                                             ConfigSection candidate) {
                    seenPrevious.set(previous);
                    seenCandidate.set(candidate);
                    return ReloadClassification.HOT;
                }
            };
            StandardConfigurationService svc = service(List.of(capturing));
            svc.load();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            svc.reload();

            assertThat(seenPrevious.get().values()).containsEntry("queue_capacity", 64);
            assertThat(seenCandidate.get().values()).containsEntry("queue_capacity", 128);
        }

        @Test
        @DisplayName("the listener is invoked synchronously BEFORE any publish (AMD-66-INV-02)")
        void listenerInvokedBeforePublish() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            AtomicInteger draftsSeenAtClassification = new AtomicInteger(-1);
            ConfigurationChangeListener ordering = new ConfigurationChangeListener() {
                @Override
                public String sectionPath() {
                    return "event_bus";
                }

                @Override
                public ReloadClassification onSectionChanged(ConfigSection previous,
                                                             ConfigSection candidate) {
                    draftsSeenAtClassification.set(sectionReloadedDrafts().size());
                    return ReloadClassification.HOT;
                }
            };
            StandardConfigurationService svc = service(List.of(ordering));
            svc.load();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            svc.reload();

            assertThat(draftsSeenAtClassification.get())
                    .as("no config.section_reloaded may be published before"
                            + " classification completes")
                    .isZero();
            assertThat(sectionReloadedDrafts()).hasSize(1);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Atomicity (C5 / INV-RF-06; AMD-66 §4)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Atomicity and rejection")
    class AtomicityTests {

        /** Creates a new test instance. */
        AtomicityTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("an ERROR in the candidate rejects the whole reload; the active model reference survives")
        void errorRejectsWholeCandidate() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel active = svc.load();
            int draftsAfterLoad = publisher.rootDrafts.size();

            writeRoot("event_bus:\n  queue_capacity: -5\n");

            assertThatThrownBy(svc::reload)
                    .isInstanceOf(ConfigurationReloadException.class)
                    .hasMessageContaining("queue_capacity");
            assertThat(svc.getCurrentModel())
                    .as("C5: the pre-reload model must survive by reference")
                    .isSameAs(active);
            assertThat(publisher.rootDrafts)
                    .as("a rejected reload publishes NO events (DP-3)")
                    .hasSize(draftsAfterLoad);
        }

        @Test
        @DisplayName("a FATAL in the candidate rejects the whole reload")
        void fatalRejectsWholeCandidate() throws Exception {
            StandardSchemaRegistry withSecurity = registry();
            withSecurity.registerCoreSchema("security", SECURITY_SCHEMA);
            StandardConfigurationService svc = new StandardConfigurationService(
                    configDir, 1, 0, FIXED_CLOCK, SYSTEM_ID, publisher, withSecurity,
                    new JsonSchemaCompositeValidator(), List.of(), List.of(),
                    noSecrets(), key -> null);
            writeRoot("security:\n  api_token: abc\n");
            ConfigModel active = svc.load();
            int draftsAfterLoad = publisher.rootDrafts.size();

            writeRoot("security: {}\n");

            assertThatThrownBy(svc::reload)
                    .isInstanceOf(ConfigurationReloadException.class)
                    .hasMessageContaining("api_token");
            assertThat(svc.getCurrentModel()).isSameAs(active);
            assertThat(publisher.rootDrafts).hasSize(draftsAfterLoad);
        }

        @Test
        @DisplayName("a parse-stage FATAL rejects the reload with the active model intact")
        void parseFatalRejectsReload() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel active = svc.load();

            writeRoot("event_bus: [unclosed\n");

            assertThatThrownBy(svc::reload)
                    .isInstanceOf(ConfigurationReloadException.class);
            assertThat(svc.getCurrentModel()).isSameAs(active);
        }

        @Test
        @DisplayName("a throwing listener rejects the candidate and preserves the active model (AMD-66 §4)")
        void throwingListenerRejectsCandidate() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            ConfigurationChangeListener throwing = new ConfigurationChangeListener() {
                @Override
                public String sectionPath() {
                    return "event_bus";
                }

                @Override
                public ReloadClassification onSectionChanged(ConfigSection previous,
                                                             ConfigSection candidate) {
                    throw new IllegalStateException("subsystem cannot accept this change");
                }
            };
            StandardConfigurationService svc = service(List.of(throwing));
            ConfigModel active = svc.load();
            int draftsAfterLoad = publisher.rootDrafts.size();

            writeRoot("event_bus:\n  queue_capacity: 128\n");

            assertThatThrownBy(svc::reload)
                    .isInstanceOf(ConfigurationReloadException.class)
                    .hasMessageContaining("event_bus");
            assertThat(svc.getCurrentModel()).isSameAs(active);
            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 64);
            assertThat(publisher.rootDrafts).hasSize(draftsAfterLoad);
        }

        @Test
        @DisplayName("reload() before load() is an IllegalStateException")
        void reloadBeforeLoadRejected() {
            assertThatThrownBy(() -> service().reload())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("load");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Events (AMD-70 §4, DP-7..DP-9)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("config.section_reloaded publication")
    class PublicationTests {

        /** Creates a new test instance. */
        PublicationTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("one event per changed section; unchanged sections are silent")
        void oneEventPerChangedSection() throws Exception {
            writeRoot("""
                    event_bus:
                      queue_capacity: 64
                    persistence:
                      retention:
                        max_days: 10
                    """);
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("""
                    event_bus:
                      queue_capacity: 128
                    persistence:
                      retention:
                        max_days: 20
                    """);
            svc.reload();

            // The leaf change belongs to "persistence.retention"; the parent
            // "persistence" has no directly-changed key (its only delta is
            // the nested map, owned by the nested section's own diff) and
            // must stay silent.
            assertThat(sectionReloadedEvents())
                    .extracting(ConfigSectionReloadedEvent::sectionPath)
                    .containsExactly("event_bus", "persistence.retention");
            assertThat(sectionReloadedEvents())
                    .allSatisfy(event -> assertThat(event.changeCount()).isEqualTo(1));
        }

        @Test
        @DisplayName("publish metadata is the DP-9 ruling: DIAGNOSTIC, SYSTEM origin, "
                + "null eventTime, system subject, null actor, via publishRoot")
        void publishMetadataMatchesRuling() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            svc.reload();

            assertThat(publisher.chainedDrafts).isEmpty();
            EventDraft draft = sectionReloadedDrafts().get(0);
            assertThat(draft.eventType()).isEqualTo(EventTypes.CONFIG_SECTION_RELOADED);
            assertThat(draft.schemaVersion()).isEqualTo(1);
            assertThat(draft.eventTime()).isNull();
            assertThat(draft.priority()).isEqualTo(EventPriority.DIAGNOSTIC);
            assertThat(draft.origin()).isEqualTo(EventOrigin.SYSTEM);
            assertThat(draft.subjectRef().type()).isEqualTo(SubjectType.SYSTEM);
            assertThat(draft.subjectRef().id()).isEqualTo(SYSTEM_ID.value());
            assertThat(draft.actorRef()).isNull();
            assertThat(draft.idempotencyKey()).isNull();
        }

        @Test
        @DisplayName("ReloadResult.issues carries WARNINGs only and the event's issueCount reflects them")
        void warningsSurviveInResultAndEvent() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("""
                    event_bus:
                      queue_capacity: 128
                      qeue_capacity: 9
                    """);
            ReloadResult result = svc.reload();

            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).severity()).isEqualTo(Severity.WARNING);
            assertThat(sectionReloadedEvents().get(0).issueCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a failing publisher never fails the reload (AMD-70-INV-01)")
        void publishFailureDoesNotFailReload() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            publisher.throwOnPublish = true;
            ReloadResult result = svc.reload();

            assertThat(result.newModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 128);
            assertThat(svc.getCurrentModel()).isSameAs(result.newModel());
        }

        @Test
        @DisplayName("reload publishes config.section_reloaded ONLY — no validation_completed, "
                + "no config_error, no config_changed (DP-7/DP-10)")
        void reloadPublishesSectionReloadedOnly() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();
            int draftsAfterLoad = publisher.rootDrafts.size();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            svc.reload();

            List<EventDraft> reloadDrafts =
                    publisher.rootDrafts.subList(draftsAfterLoad, publisher.rootDrafts.size());
            assertThat(reloadDrafts)
                    .extracting(EventDraft::eventType)
                    .containsExactly(EventTypes.CONFIG_SECTION_RELOADED);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Pipeline edges (DP-3, DP-12, INV-CE-01/02)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Pipeline edges")
    class PipelineEdgeTests {

        /** Creates a new test instance. */
        PipelineEdgeTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("zero-config absent-file reload yields the defaults model with no changed sections")
        void zeroConfigReloadHasNoChanges() throws Exception {
            StandardConfigurationService svc = service();
            svc.load();
            int draftsAfterLoad = publisher.rootDrafts.size();

            ReloadResult result = svc.reload();

            assertThat(result.changeSet().changes()).isEmpty();
            assertThat(result.newModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 1024);
            assertThat(publisher.rootDrafts).hasSize(draftsAfterLoad);
        }

        @Test
        @DisplayName("an unchanged file reloads with an empty change set and no events")
        void unchangedFileProducesNoChanges() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();
            int draftsAfterLoad = publisher.rootDrafts.size();

            ReloadResult result = svc.reload();

            assertThat(result.changeSet().changes()).isEmpty();
            assertThat(publisher.rootDrafts).hasSize(draftsAfterLoad);
        }

        @Test
        @DisplayName("a removed key surfaces as a change to the schema default (newValue from defaults)")
        void removedKeyRevertsToDefault() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("event_bus: {}\n");
            ReloadResult result = svc.reload();

            // Post-pipeline diff (the merged candidate carries the default
            // 1024 where the file no longer sets a value).
            assertThat(result.changeSet().changes()).hasSize(1);
            ConfigChange change = result.changeSet().changes().get(0);
            assertThat(change.oldValue()).isEqualTo(64);
            assertThat(change.newValue()).isEqualTo(1024);
        }

        @Test
        @DisplayName("the reload re-parse honors the YAML 1.2 CoreSchema: a ~-valued key is unset (DP-12)")
        void reloadReparseHonorsCoreSchema() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            // Under the engine-default JSON schema "~" would parse as the
            // string "~" and the integer-typed key would ERROR-reject the
            // candidate. Under the Core schema it is null = unset, the key
            // is dropped, and the schema default applies (MODULE_CONTEXT
            // Core-schema gotcha; pinned obligation #3).
            writeRoot("event_bus:\n  queue_capacity: ~\n");
            ReloadResult result = svc.reload();

            assertThat(result.newModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 1024);
        }

        @Test
        @DisplayName("the changeSet timestamp comes from the injected clock")
        void changeSetTimestampFromClock() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.load();

            writeRoot("event_bus:\n  queue_capacity: 128\n");
            ReloadResult result = svc.reload();

            assertThat(result.changeSet().timestamp()).isEqualTo(CLOCK_INSTANT);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Torn-read freedom (DP-1, REC-133 — the charter done-when)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrent readers across reloads observe wholly-old or wholly-new, never a torn mix")
    void concurrentReadersNeverObserveTornModel() throws Exception {
        String variantA = """
                event_bus:
                  queue_capacity: 64
                persistence:
                  retention:
                    max_days: 10
                """;
        String variantB = """
                event_bus:
                  queue_capacity: 128
                persistence:
                  retention:
                    max_days: 20
                """;
        writeRoot(variantA);
        StandardConfigurationService svc = service();
        svc.load();

        int reloads = 50;
        Queue<String> tornPairs = new ConcurrentLinkedQueue<>();
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch readerStarted = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            while (!done.get()) {
                // One volatile read; both values must come from the same
                // immutable model — (64,10) or (128,20), never mixed.
                ConfigModel model = svc.getCurrentModel();
                Object capacity = model.sections().get("event_bus")
                        .values().get("queue_capacity");
                Object maxDays = model.sections().get("persistence.retention")
                        .values().get("max_days");
                boolean consistentA = Integer.valueOf(64).equals(capacity)
                        && Integer.valueOf(10).equals(maxDays);
                boolean consistentB = Integer.valueOf(128).equals(capacity)
                        && Integer.valueOf(20).equals(maxDays);
                if (!consistentA && !consistentB) {
                    tornPairs.add(capacity + "/" + maxDays);
                }
            }
        }, "reload-torn-read-reader");
        reader.start();
        readerStarted.await();

        for (int i = 0; i < reloads; i++) {
            writeRoot(i % 2 == 0 ? variantB : variantA);
            svc.reload();
        }
        done.set(true);
        reader.join(30_000);

        assertThat(reader.isAlive()).as("reader thread must terminate").isFalse();
        assertThat(tornPairs)
                .as("a reader observed a torn (capacity/max_days) pair")
                .isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // DP-10 fence: reload never publishes config_error
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a rejected reload publishes no config_error — per-ERROR publication is load()-only (DP-10)")
    void rejectedReloadPublishesNoConfigError() throws Exception {
        writeRoot("event_bus:\n  queue_capacity: 64\n");
        StandardConfigurationService svc = service();
        svc.load();

        writeRoot("event_bus:\n  queue_capacity: -5\n");
        assertThatThrownBy(svc::reload)
                .isInstanceOf(ConfigurationReloadException.class);

        assertThat(publisher.rootDrafts.stream()
                .map(EventDraft::payload)
                .filter(ConfigErrorEvent.class::isInstance))
                .isEmpty();
    }
}

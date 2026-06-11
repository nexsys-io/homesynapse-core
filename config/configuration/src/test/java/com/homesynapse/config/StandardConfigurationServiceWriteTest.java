/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.ConfigurationValidationException;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link StandardConfigurationService#write(List, Instant)} — the
 * M6.4 UI/API write path (Doc 06 §3.5): optimistic concurrency on the
 * {@code fileModifiedAt} token (§6.7), validate-the-mutated-copy with the
 * file untouched on rejection, the REC-131 first-write backup, the atomic
 * write-temp-fsync-rename flush, the §6.8 prior-file-intact guarantee on
 * write failure, and the DP-7 single-publish rule (events fire from the
 * reload leg only).
 */
@DisplayName("StandardConfigurationService.write() (M6.4)")
class StandardConfigurationServiceWriteTest {

    private static final Instant CLOCK_INSTANT = Instant.parse("2026-06-11T00:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final SystemId SYSTEM_ID = SystemId.of(new Ulid(7L, 7L));

    private static final String EVENT_BUS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "queue_capacity": {
                  "type": "integer", "minimum": 1, "default": 1024,
                  "x-reload": "hot"
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

    @TempDir
    Path configDir;

    private final RecordingEventPublisher publisher = new RecordingEventPublisher();
    private final AtomicInteger mtimeTick = new AtomicInteger();

    /** Creates a new test instance. */
    StandardConfigurationServiceWriteTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ──────────────────────────────────────────────────────────────────

    private StandardConfigurationService service() {
        StandardSchemaRegistry registry = new StandardSchemaRegistry(1, 0);
        registry.registerCoreSchema("event_bus", EVENT_BUS_SCHEMA);
        return new StandardConfigurationService(
                configDir, 1, 0, FIXED_CLOCK, SYSTEM_ID, publisher, registry,
                new JsonSchemaCompositeValidator(), List.of(), List.of());
    }

    private Path rootFile() {
        return configDir.resolve("homesynapse.yaml");
    }

    private void writeRoot(String yaml) throws IOException {
        Files.writeString(rootFile(), yaml);
        Files.setLastModifiedTime(rootFile(), FileTime.from(
                Instant.parse("2026-01-01T00:00:00Z")
                        .plusSeconds(mtimeTick.incrementAndGet())));
    }

    private List<Path> backupFiles() throws IOException {
        try (Stream<Path> entries = Files.list(configDir)) {
            return entries
                    .filter(p -> p.getFileName().toString()
                            .startsWith("homesynapse.yaml.bak."))
                    .toList();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Round-trip (REC-130)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mutation round-trip")
    class RoundTripTests {

        /** Creates a new test instance. */
        RoundTripTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("a mutation lands in the file and the re-read model (REC-130)")
        void mutationRoundTripsThroughFileAndModel() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel model = svc.load();

            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    model.fileModifiedAt());

            // INV-CE-01: the file is mutated, then the model derives from it.
            assertThat(Files.readString(rootFile())).contains("queue_capacity");
            assertThat(Files.readString(rootFile())).contains("99");
            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 99);
        }

        @Test
        @DisplayName("a mutation into a previously absent section creates it")
        void mutationCreatesAbsentSection() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel model = svc.load();

            svc.write(List.of(new ConfigMutation("event_bus", "dispatch_mode", "parallel")),
                    model.fileModifiedAt());

            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("dispatch_mode", "parallel")
                    .containsEntry("queue_capacity", 64);
        }

        @Test
        @DisplayName("a null newValue removes the key; the default is observable after the reload")
        void nullValueRemovesKeyAndRevertsToDefault() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel model = svc.load();

            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", null)),
                    model.fileModifiedAt());

            assertThat(Files.readString(rootFile())).doesNotContain("queue_capacity");
            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 1024);
        }

        @Test
        @DisplayName("the write updates the concurrency token to the new file mtime")
        void writeRefreshesToken() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel before = svc.load();

            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    before.fileModifiedAt());

            Instant fileMtime = Files.getLastModifiedTime(rootFile()).toInstant();
            assertThat(svc.getCurrentModel().fileModifiedAt()).isEqualTo(fileMtime);
        }

        @Test
        @DisplayName("a second write with the refreshed token succeeds")
        void secondWriteWithRefreshedTokenSucceeds() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    svc.load().fileModifiedAt());

            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 256)),
                    svc.getCurrentModel().fileModifiedAt());

            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 256);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Optimistic concurrency (§6.7, REC-130)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Optimistic concurrency")
    class ConcurrencyTokenTests {

        /** Creates a new test instance. */
        ConcurrencyTokenTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("a stale fileModifiedAt token is a ConcurrentModificationException; the file is untouched")
        void staleTokenRejected() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel model = svc.load();
            String contentBefore = Files.readString(rootFile());

            // External edit: a text editor / scp / git pull touched the file.
            Files.setLastModifiedTime(rootFile(), FileTime.from(
                    Instant.parse("2026-02-02T00:00:00Z")));

            assertThatThrownBy(() -> svc.write(
                    List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    model.fileModifiedAt()))
                    .isInstanceOf(ConcurrentModificationException.class);
            assertThat(Files.readString(rootFile())).isEqualTo(contentBefore);
            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 64);
        }

        @Test
        @DisplayName("write() before load() is an IllegalStateException")
        void writeBeforeLoadRejected() {
            assertThatThrownBy(() -> service().write(
                    List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    CLOCK_INSTANT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("load");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Validation rejection (§3.5 step 5)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Validation rejection")
    class ValidationRejectionTests {

        /** Creates a new test instance. */
        ValidationRejectionTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("an invalid mutation is a ConfigurationValidationException; the file is untouched")
        void invalidMutationRejected() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel model = svc.load();
            String contentBefore = Files.readString(rootFile());

            assertThatThrownBy(() -> svc.write(
                    List.of(new ConfigMutation("event_bus", "queue_capacity", -5)),
                    model.fileModifiedAt()))
                    .isInstanceOf(ConfigurationValidationException.class)
                    .hasMessageContaining("queue_capacity");
            assertThat(Files.readString(rootFile())).isEqualTo(contentBefore);
            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 64);
        }

        @Test
        @DisplayName("a rejected write creates no backup and publishes nothing")
        void rejectedWriteHasNoSideEffects() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            ConfigModel model = svc.load();
            int draftsAfterLoad = publisher.rootDrafts.size();

            assertThatThrownBy(() -> svc.write(
                    List.of(new ConfigMutation("event_bus", "queue_capacity", -5)),
                    model.fileModifiedAt()))
                    .isInstanceOf(ConfigurationValidationException.class);

            assertThat(backupFiles()).isEmpty();
            assertThat(publisher.rootDrafts).hasSize(draftsAfterLoad);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // First-write backup (§3.5 mitigation, REC-131)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("First-write backup")
    class FirstWriteBackupTests {

        /** Creates a new test instance. */
        FirstWriteBackupTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("the first UI/API write backs up the original file once (REC-131)")
        void firstWriteCreatesBackupWithOriginalContent() throws Exception {
            String original = "event_bus:\n  queue_capacity: 64\n";
            writeRoot(original);
            StandardConfigurationService svc = service();

            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    svc.load().fileModifiedAt());

            List<Path> backups = backupFiles();
            assertThat(backups).hasSize(1);
            assertThat(Files.readString(backups.get(0))).isEqualTo(original);
            // The stamp comes from the injected clock as a
            // filesystem-safe ISO-8601 basic-format timestamp.
            assertThat(backups.get(0).getFileName().toString())
                    .isEqualTo("homesynapse.yaml.bak.20260611T000000Z");
        }

        @Test
        @DisplayName("a second write creates no second backup")
        void secondWriteCreatesNoSecondBackup() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();
            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    svc.load().fileModifiedAt());

            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 256)),
                    svc.getCurrentModel().fileModifiedAt());

            assertThat(backupFiles()).hasSize(1);
        }

        @Test
        @DisplayName("a zero-config first write has nothing to back up")
        void zeroConfigFirstWriteSkipsBackup() throws Exception {
            StandardConfigurationService svc = service();

            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    svc.load().fileModifiedAt());

            assertThat(backupFiles()).isEmpty();
            assertThat(Files.readString(rootFile())).contains("99");
            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 99);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Atomic flush (§3.5 step 6, §6.8)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Atomic flush")
    class AtomicFlushTests {

        /** Creates a new test instance. */
        AtomicFlushTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("a successful write leaves no .tmp residue")
        void noTmpResidueOnSuccess() throws Exception {
            writeRoot("event_bus:\n  queue_capacity: 64\n");
            StandardConfigurationService svc = service();

            svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    svc.load().fileModifiedAt());

            assertThat(Files.exists(configDir.resolve("homesynapse.yaml.tmp")))
                    .isFalse();
        }

        @Test
        @DisplayName("a write failure leaves the prior file intact (§6.8)")
        void writeFailureLeavesPriorFileIntact() throws Exception {
            String original = "event_bus:\n  queue_capacity: 64\n";
            writeRoot(original);
            StandardConfigurationService svc = service();
            ConfigModel model = svc.load();
            // A directory squatting on the temp-file name makes the
            // tmp-file open fail — the §6.8 disk-trouble stand-in.
            Files.createDirectory(configDir.resolve("homesynapse.yaml.tmp"));

            assertThatThrownBy(() -> svc.write(
                    List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                    model.fileModifiedAt()))
                    .isInstanceOf(UncheckedIOException.class);

            assertThat(Files.readString(rootFile())).isEqualTo(original);
            assertThat(svc.getCurrentModel().sections().get("event_bus").values())
                    .containsEntry("queue_capacity", 64);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Event discipline (DP-7)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("write publishes via the reload leg only — one config.section_reloaded, nothing else")
    void writePublishesViaReloadLegOnly() throws Exception {
        writeRoot("event_bus:\n  queue_capacity: 64\n");
        StandardConfigurationService svc = service();
        ConfigModel model = svc.load();
        int draftsAfterLoad = publisher.rootDrafts.size();

        svc.write(List.of(new ConfigMutation("event_bus", "queue_capacity", 99)),
                model.fileModifiedAt());

        List<EventDraft> writeDrafts =
                publisher.rootDrafts.subList(draftsAfterLoad, publisher.rootDrafts.size());
        assertThat(writeDrafts)
                .extracting(EventDraft::eventType)
                .containsExactly(EventTypes.CONFIG_SECTION_RELOADED);
    }
}

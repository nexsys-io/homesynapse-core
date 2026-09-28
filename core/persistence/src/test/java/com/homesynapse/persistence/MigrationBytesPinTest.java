/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LOCK-1: the bytes of each event-store migration are pinned by SHA-256. {@link MigrationRunner}
 * records every applied migration's checksum and halts every boot on drift, so a changed byte in
 * V001–V005 is a stopped core, not a migration. T2 reads each file through the class loader the
 * runner reads it through ({@code db/migration/events/<name>}) and hashes the raw bytes — no
 * line-ending normalization: a normalizing test would pass over a CRLF corruption the runner
 * halts on — and asserts the directory holds exactly these five names.
 *
 * <p>This module's test task puts the module's jar on the classpath, not the exploded resources
 * directory, so both reads see the artifact that boots; the listing reads a {@code file:}
 * directory as well, should that change. The digests were computed at {@code 1f1d1e0}. Change a
 * pinned value only through a WU that names the migration or re-derivation it performs. No
 * clock is read.</p>
 */
@DisplayName("Event-store migrations -- the five files pinned at their bytes (LOCK-1)")
final class MigrationBytesPinTest {

    private static final String EVENTS_DIR = "db/migration/events";

    /** The SHA-256 of each migration file's bytes at {@code 1f1d1e0}, in manifest order. */
    private static final Map<String, String> PINNED = pinned();

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    MigrationBytesPinTest() {
    }

    @Test
    @DisplayName("T2: each of V001-V005 hashes to its pinned SHA-256")
    void eachMigration_hashesToItsPinnedDigest() {
        // Soft, so a moved pin reports every file's actual digest in one run.
        SoftAssertions.assertSoftly(softly -> PINNED.forEach((name, digest) ->
                softly.assertThat(sha256Hex(readResource(EVENTS_DIR + "/" + name)))
                        .as("%s: MigrationRunner halts every boot on checksum drift -- a changed "
                                + "byte is a stopped core, not a migration", name)
                        .isEqualTo(digest)));
    }

    @Test
    @DisplayName("T2: the events directory holds exactly the five pinned names")
    void eventsDirectory_holdsExactlyThePinnedNames() throws IOException, URISyntaxException {
        URL dir = MigrationRunner.class.getClassLoader().getResource(EVENTS_DIR);
        assertThat(dir).as("%s is on the runner's classpath", EVENTS_DIR).isNotNull();

        assertThat(listNames(dir.toURI()))
                .as("a sixth migration is a new WU with its own pin row, not a silent addition "
                        + "(listed from %s)", dir)
                .containsExactlyElementsOf(PINNED.keySet());
    }

    private static Map<String, String> pinned() {
        Map<String, String> pins = new LinkedHashMap<>();
        pins.put("V001__initial_event_store_schema.sql",
                "4fa9e1008d9e4c145461063d6474501c2d5ad45d7bcebe4c567154f0a54362a3");
        pins.put("V002__subscriber_dead_letter_queue.sql",
                "7cd7d62261b1c2b18300531eaff14f2e09d1b8108ddc03a35f358885ca3b84dc");
        pins.put("V003__add_snapshots_and_drop_redundant_index.sql",
                "3d9bf80d3700a0c8f2df6a2cb65633787e014b48aceae410d1c8c984c7e3b6cf");
        pins.put("V004__dlq_operational_indices.sql",
                "5cf593d5d3f1956290af9fd1e2f22297cc29a047bb0d0b74605dbdf47b87c6b0");
        pins.put("V005__at_rest_payload_encryption_columns.sql",
                "a11cec73095fe9e4fd95450e5498d58e5ad74784ddb6b9f2b6b0d4f41c607ea6");
        return Collections.unmodifiableMap(pins);
    }

    /** Reads a resource the way {@link MigrationRunner} opens it: through its class loader. */
    private static byte[] readResource(String path) {
        InputStream in = MigrationRunner.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException(path + " is not on the runner's classpath");
        }
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }

    /** The sorted file names directly under a classpath directory, in a jar or on disk. */
    private static List<String> listNames(URI dir) throws IOException {
        if (!"jar".equals(dir.getScheme())) {
            return listNames(Path.of(dir));
        }
        try (FileSystem jar = FileSystems.newFileSystem(dir, Map.of())) {
            return listNames(jar.getPath("/" + EVENTS_DIR));
        }
    }

    private static List<String> listNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", impossible);
        }
    }
}

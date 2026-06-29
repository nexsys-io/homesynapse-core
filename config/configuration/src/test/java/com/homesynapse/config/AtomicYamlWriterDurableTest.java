/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F13b (AB-4) tests for {@link AtomicYamlWriter#writeAtomicallyDurable} — the
 * fail-closed durable write the nonce-counter store opts into so a lost
 * directory-entry fsync cannot silently un-durable the OR-M6-NONCE high-water
 * mark (a replayed nonce would be catastrophic GCM {@code (key, nonce)} reuse).
 *
 * <p>The genuine fsync-failure branch cannot be provoked on a healthy
 * directory, so the directory-force step is injected via the package-private
 * test seam — the production strategy ({@code fsyncDirectoryOrThrow}) is
 * exercised separately by the real {@link AtomicYamlWriter#writeAtomicallyDurable(Path, String)}
 * overload, which must NOT break the Windows/non-POSIX dev path (where a
 * directory channel cannot be opened).</p>
 */
@DisplayName("AtomicYamlWriter.writeAtomicallyDurable — F13b fail-closed directory fsync")
class AtomicYamlWriterDurableTest {

    @TempDir
    Path dir;

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    AtomicYamlWriterDurableTest() {
    }

    @Test
    @DisplayName("a successful directory fsync writes the content durably")
    void durableWrite_fsyncOk_writesContent() throws IOException {
        Path target = dir.resolve("counters.json");

        // Inject a no-op (successful) directory force.
        AtomicYamlWriter.writeAtomicallyDurable(target, "durable-content", directory -> {
            // fsync succeeds — nothing to do
        });

        assertThat(Files.readString(target)).isEqualTo("durable-content");
    }

    @Test
    @DisplayName("a confirmed directory-entry fsync failure FAILS CLOSED (UncheckedIOException) —"
            + " the encrypt must not proceed on a possibly non-durable high-water mark")
    void durableWrite_fsyncFails_failsClosed() {
        Path target = dir.resolve("counters.json");

        assertThatThrownBy(() ->
                AtomicYamlWriter.writeAtomicallyDurable(target, "content", directory -> {
                    throw new IOException("simulated POSIX directory fsync failure");
                }))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("durable")
                .hasRootCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("the Windows/non-POSIX path is NOT broken: the real production durable write"
            + " completes on this platform regardless of directory-channel support")
    void productionDurableWrite_doesNotBreakOnThisPlatform() throws IOException {
        Path target = dir.resolve("counters.json");

        // The real strategy: Windows/non-POSIX tolerates an unopenable directory
        // channel; POSIX performs the fsync. Either way the write must succeed.
        assertThatCode(() ->
                AtomicYamlWriter.writeAtomicallyDurable(target, "platform-content"))
                .doesNotThrowAnyException();
        assertThat(Files.readString(target)).isEqualTo("platform-content");
    }

    @Test
    @DisplayName("an existing target is replaced atomically by a durable write")
    void durableWrite_replacesExistingTarget() throws IOException {
        Path target = dir.resolve("counters.json");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));

        AtomicYamlWriter.writeAtomicallyDurable(target, "new", directory -> { });

        assertThat(Files.readString(target)).isEqualTo("new");
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.systemd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link LocalPaths}. The package-private base-directory constructor roots
 * the development layout under a {@link TempDir} sandbox, demonstrating the CWD-relative
 * layout without creating directories in the real working tree.
 */
@DisplayName("LocalPaths")
class LocalPathsTest {

    @Test
    @DisplayName("resolves all directories under the base directory")
    void resolvesUnderBase(@TempDir Path base) throws IOException {
        var paths = new LocalPaths(base);

        assertThat(paths.binaryDir()).isEqualTo(base);
        assertThat(paths.configDir()).isEqualTo(base.resolve("config"));
        assertThat(paths.dataDir()).isEqualTo(base.resolve("data"));
        assertThat(paths.logDir()).isEqualTo(base.resolve("logs"));
        assertThat(paths.backupDir()).isEqualTo(paths.dataDir().resolve("backups"));
        assertThat(paths.tempDir()).isEqualTo(paths.dataDir().resolve("tmp"));
    }

    @Test
    @DisplayName("all paths are absolute")
    void pathsAreAbsolute(@TempDir Path base) throws IOException {
        var paths = new LocalPaths(base);

        assertThat(paths.binaryDir()).isAbsolute();
        assertThat(paths.configDir()).isAbsolute();
        assertThat(paths.dataDir()).isAbsolute();
        assertThat(paths.logDir()).isAbsolute();
        assertThat(paths.backupDir()).isAbsolute();
        assertThat(paths.tempDir()).isAbsolute();
    }

    @Test
    @DisplayName("creates the writable directories")
    void createsWritableDirectories(@TempDir Path base) throws IOException {
        var paths = new LocalPaths(base);

        assertThat(paths.configDir()).isDirectory();
        assertThat(paths.dataDir()).isDirectory();
        assertThat(paths.logDir()).isDirectory();
        assertThat(paths.backupDir()).isDirectory();
        assertThat(paths.tempDir()).isDirectory();
    }

    @Test
    @DisplayName("clears stale contents of tempDir on construction")
    void clearsTempDir(@TempDir Path base) throws IOException {
        Path temp = base.resolve("data/tmp");
        Files.createDirectories(temp);
        Path staleFile = Files.writeString(temp.resolve("stale.tmp"), "leftover");

        var paths = new LocalPaths(base);

        assertThat(staleFile).doesNotExist();
        assertThat(paths.tempDir()).isEmptyDirectory();
    }

    @Test
    @DisplayName("caches each path — repeated calls return the same instance")
    void cachesPaths(@TempDir Path base) throws IOException {
        var paths = new LocalPaths(base);

        assertThat(paths.dataDir()).isSameAs(paths.dataDir());
        assertThat(paths.tempDir()).isSameAs(paths.tempDir());
    }

    @Test
    @DisplayName("rejects a null base directory")
    void rejectsNullBase() {
        assertThatThrownBy(() -> new LocalPaths(null))
                .isInstanceOf(NullPointerException.class);
    }
}

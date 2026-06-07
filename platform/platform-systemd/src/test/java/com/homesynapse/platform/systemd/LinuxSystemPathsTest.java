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
 * Unit tests for {@link LinuxSystemPaths}. The package-private filesystem-root constructor
 * resolves the FHS layout under a {@link TempDir} sandbox so directory creation and
 * {@code tempDir()} clearing can be verified without touching real system directories.
 */
@DisplayName("LinuxSystemPaths")
class LinuxSystemPathsTest {

    @Test
    @DisplayName("resolves the six FHS paths under the filesystem root")
    void resolvesFhsLayout(@TempDir Path fsRoot) throws IOException {
        var paths = new LinuxSystemPaths(fsRoot);

        assertThat(paths.binaryDir()).isEqualTo(fsRoot.resolve("opt/homesynapse"));
        assertThat(paths.configDir()).isEqualTo(fsRoot.resolve("etc/homesynapse"));
        assertThat(paths.dataDir()).isEqualTo(fsRoot.resolve("var/lib/homesynapse"));
        assertThat(paths.logDir()).isEqualTo(fsRoot.resolve("var/log/homesynapse"));
        assertThat(paths.backupDir()).isEqualTo(fsRoot.resolve("var/lib/homesynapse/backups"));
        assertThat(paths.tempDir()).isEqualTo(fsRoot.resolve("var/lib/homesynapse/tmp"));
    }

    @Test
    @DisplayName("creates the five writable directories")
    void createsWritableDirectories(@TempDir Path fsRoot) throws IOException {
        var paths = new LinuxSystemPaths(fsRoot);

        assertThat(paths.configDir()).isDirectory();
        assertThat(paths.dataDir()).isDirectory();
        assertThat(paths.logDir()).isDirectory();
        assertThat(paths.backupDir()).isDirectory();
        assertThat(paths.tempDir()).isDirectory();
    }

    @Test
    @DisplayName("does not create the read-only binary directory")
    void doesNotCreateBinaryDir(@TempDir Path fsRoot) throws IOException {
        var paths = new LinuxSystemPaths(fsRoot);

        assertThat(paths.binaryDir()).doesNotExist();
    }

    @Test
    @DisplayName("clears stale contents of tempDir on construction")
    void clearsTempDir(@TempDir Path fsRoot) throws IOException {
        Path temp = fsRoot.resolve("var/lib/homesynapse/tmp");
        Files.createDirectories(temp);
        Path staleFile = Files.writeString(temp.resolve("stale.tmp"), "leftover");
        Path staleSubDir = Files.createDirectories(temp.resolve("sub"));
        Files.writeString(staleSubDir.resolve("nested.tmp"), "leftover");

        var paths = new LinuxSystemPaths(fsRoot);

        assertThat(staleFile).doesNotExist();
        assertThat(staleSubDir).doesNotExist();
        assertThat(paths.tempDir()).isEmptyDirectory();
    }

    @Test
    @DisplayName("caches each path — repeated calls return the same instance")
    void cachesPaths(@TempDir Path fsRoot) throws IOException {
        var paths = new LinuxSystemPaths(fsRoot);

        assertThat(paths.dataDir()).isSameAs(paths.dataDir());
        assertThat(paths.tempDir()).isSameAs(paths.tempDir());
    }

    @Test
    @DisplayName("all paths are absolute")
    void pathsAreAbsolute(@TempDir Path fsRoot) throws IOException {
        var paths = new LinuxSystemPaths(fsRoot);

        assertThat(paths.binaryDir()).isAbsolute();
        assertThat(paths.configDir()).isAbsolute();
        assertThat(paths.dataDir()).isAbsolute();
        assertThat(paths.logDir()).isAbsolute();
        assertThat(paths.backupDir()).isAbsolute();
        assertThat(paths.tempDir()).isAbsolute();
    }

    @Test
    @DisplayName("rejects a null filesystem root")
    void rejectsNullRoot() {
        assertThatThrownBy(() -> new LinuxSystemPaths(null))
                .isInstanceOf(NullPointerException.class);
    }
}

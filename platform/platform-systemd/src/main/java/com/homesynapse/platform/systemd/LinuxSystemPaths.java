/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.systemd;

import com.homesynapse.platform.PlatformPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Tier-1 (Linux / systemd) {@link PlatformPaths} implementation using the Filesystem
 * Hierarchy Standard layout of a packaged system service.
 *
 * <ul>
 *   <li>{@code binaryDir()} → {@code /opt/homesynapse} (read-only runtime image)</li>
 *   <li>{@code configDir()} → {@code /etc/homesynapse}</li>
 *   <li>{@code dataDir()}   → {@code /var/lib/homesynapse}</li>
 *   <li>{@code logDir()}    → {@code /var/log/homesynapse}</li>
 *   <li>{@code backupDir()} → {@code /var/lib/homesynapse/backups}</li>
 *   <li>{@code tempDir()}   → {@code /var/lib/homesynapse/tmp}</li>
 * </ul>
 *
 * <p>All paths are resolved once at construction and cached (constraint C12-10): each
 * accessor returns the same {@link Path} instance for the lifetime of this object. The
 * five writable directories — everything except the read-only {@code binaryDir()} — are
 * created during construction when absent, and {@code tempDir()} has its contents cleared
 * so no transient state survives a restart.</p>
 *
 * <p>Choosing this implementation over {@link LocalPaths} is the composition root's
 * responsibility (lifecycle / M13) and is deliberately not performed here.</p>
 *
 * <p>Thread-safe: immutable after construction.</p>
 *
 * @see PlatformPaths
 * @see LocalPaths
 */
public final class LinuxSystemPaths implements PlatformPaths {

    private final Path binaryDir;
    private final Path configDir;
    private final Path dataDir;
    private final Path logDir;
    private final Path backupDir;
    private final Path tempDir;

    /**
     * Resolves the production FHS layout under the real filesystem root ({@code /}) and
     * prepares the writable directories.
     *
     * @throws IOException if a writable directory cannot be created or {@code tempDir()}
     *                     cannot be cleared
     */
    public LinuxSystemPaths() throws IOException {
        this(Path.of("/"));
    }

    /**
     * Resolves the FHS layout under an arbitrary filesystem root. The production
     * {@link #LinuxSystemPaths()} constructor passes {@code /}; tests pass a sandbox root
     * so directory creation and {@code tempDir()} clearing can be exercised without
     * touching the real system directories.
     *
     * @param fsRoot the filesystem root under which the FHS layout is resolved
     * @throws IOException          if a writable directory cannot be created or cleared
     * @throws NullPointerException if {@code fsRoot} is {@code null}
     */
    LinuxSystemPaths(Path fsRoot) throws IOException {
        Objects.requireNonNull(fsRoot, "fsRoot");
        this.binaryDir = fsRoot.resolve("opt/homesynapse");
        this.configDir = fsRoot.resolve("etc/homesynapse");
        this.dataDir = fsRoot.resolve("var/lib/homesynapse");
        this.logDir = fsRoot.resolve("var/log/homesynapse");
        this.backupDir = this.dataDir.resolve("backups");
        this.tempDir = this.dataDir.resolve("tmp");
        prepareWritableDirectories();
    }

    @Override
    public Path binaryDir() {
        return binaryDir;
    }

    @Override
    public Path configDir() {
        return configDir;
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public Path logDir() {
        return logDir;
    }

    @Override
    public Path backupDir() {
        return backupDir;
    }

    @Override
    public Path tempDir() {
        return tempDir;
    }

    private void prepareWritableDirectories() throws IOException {
        for (Path dir : List.of(configDir, dataDir, logDir, backupDir, tempDir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IOException(
                        "Failed to create platform directory: " + dir
                                + " (required: read/write access for the service user)", e);
            }
        }
        clearDirectoryContents(tempDir);
    }

    private static void clearDirectoryContents(Path dir) throws IOException {
        try (Stream<Path> tree = Files.walk(dir)) {
            List<Path> contents = tree
                    .filter(path -> !path.equals(dir))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : contents) {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new IOException(
                            "Failed to clear temporary directory entry: " + path, e);
                }
            }
        }
    }
}

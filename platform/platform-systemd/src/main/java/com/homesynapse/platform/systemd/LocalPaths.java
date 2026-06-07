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
 * Development-tier {@link PlatformPaths} implementation that roots every directory under
 * a single base directory (the process working directory in production), so HomeSynapse
 * can run from a checkout without the system-wide FHS locations.
 *
 * <ul>
 *   <li>{@code binaryDir()} → the base directory (read-only runtime image)</li>
 *   <li>{@code configDir()} → {@code <base>/config}</li>
 *   <li>{@code dataDir()}   → {@code <base>/data}</li>
 *   <li>{@code logDir()}    → {@code <base>/logs}</li>
 *   <li>{@code backupDir()} → {@code <base>/data/backups}</li>
 *   <li>{@code tempDir()}   → {@code <base>/data/tmp}</li>
 * </ul>
 *
 * <p>Behaviour mirrors {@link LinuxSystemPaths}: paths are resolved once and cached
 * (constraint C12-10), the writable directories are created on construction, and
 * {@code tempDir()} is cleared so no transient state survives a restart.</p>
 *
 * <p>Choosing this implementation over {@link LinuxSystemPaths} is the composition root's
 * responsibility (lifecycle / M13) and is deliberately not performed here.</p>
 *
 * <p>Thread-safe: immutable after construction.</p>
 *
 * @see PlatformPaths
 * @see LinuxSystemPaths
 */
public final class LocalPaths implements PlatformPaths {

    private final Path binaryDir;
    private final Path configDir;
    private final Path dataDir;
    private final Path logDir;
    private final Path backupDir;
    private final Path tempDir;

    /**
     * Roots the development layout at the current working directory.
     *
     * @throws IOException if a writable directory cannot be created or {@code tempDir()}
     *                     cannot be cleared
     */
    public LocalPaths() throws IOException {
        this(Path.of(""));
    }

    /**
     * Roots the development layout at the given base directory. The production
     * {@link #LocalPaths()} constructor passes the working directory; tests pass a sandbox
     * directory. The base is normalised to an absolute path so every accessor honours the
     * {@link PlatformPaths} absolute-path contract.
     *
     * @param baseDir the directory under which all platform directories are resolved
     * @throws IOException          if a writable directory cannot be created or cleared
     * @throws NullPointerException if {@code baseDir} is {@code null}
     */
    LocalPaths(Path baseDir) throws IOException {
        Objects.requireNonNull(baseDir, "baseDir");
        Path base = baseDir.toAbsolutePath();
        this.binaryDir = base;
        this.configDir = base.resolve("config");
        this.dataDir = base.resolve("data");
        this.logDir = base.resolve("logs");
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

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.platform;

import java.nio.file.Path;

/**
 * Abstracts platform-specific directory conventions, allowing HomeSynapse Core
 * to run across deployment tiers without hard-coding filesystem paths.
 *
 * <p>All methods return absolute {@link Path} instances. Paths are resolved once
 * during Phase 0 (platform detection) and cached — subsequent calls return the
 * same {@code Path} object (constraint C12-10: immutable after Phase 0).
 *
 * <p>All writable directories are verified to exist and be writable during Phase 0.
 * If a directory does not exist, it is created. If creation fails (for example,
 * due to insufficient permissions), initialization fails with a diagnostic naming
 * the missing directory and the required permissions.
 *
 * <p>{@link #tempDir()} contents are deleted at the start of each process run
 * (Phase 0). No other directory is cleaned on startup.
 *
 * <p>Implementations are selected based on deployment tier detection: on Linux
 * Tier 1, {@code LinuxSystemPaths} is used when {@code /opt/homesynapse/} exists
 * and the effective user is {@code homesynapse}; otherwise, a development-mode
 * {@code LocalPaths} implementation resolves all directories under the current
 * working directory.
 *
 * <p>Thread-safe: implementations must be safe for concurrent access from any
 * subsystem.
 *
 * @see HealthReporter
 * @see <a href="doc12-section-8.3">Doc 12 §8.3 — PlatformPaths Interface</a>
 * @see <a href="doc12-section-7.2">Doc 12 §7.2 — Portability Architecture</a>
 */
public interface PlatformPaths {

    /**
     * Returns the read-only runtime image location.
     *
     * <p>Contains the application JAR/image and static resources. This directory
     * is not written to at runtime.
     *
     * @return absolute path to the binary directory;
     *         on Linux Tier 1: {@code /opt/homesynapse/}
     */
    Path binaryDir();

    /**
     * Returns the writable configuration directory.
     *
     * <p>Contains {@code config.yaml} and any user-edited configuration files.
     *
     * @return absolute path to the configuration directory;
     *         on Linux Tier 1: {@code /etc/homesynapse/}
     */
    Path configDir();

    /**
     * Returns the writable persistent data directory.
     *
     * <p>Contains SQLite databases ({@code homesynapse-events.db},
     * {@code homesynapse-telemetry.db}) and the unclean shutdown marker.
     *
     * @return absolute path to the data directory;
     *         on Linux Tier 1: {@code /var/lib/homesynapse/}
     */
    Path dataDir();

    /**
     * Returns the writable log directory.
     *
     * <p>Contains {@code homesynapse.log} and JFR recordings
     * ({@code flight.jfr}).
     *
     * @return absolute path to the log directory;
     *         on Linux Tier 1: {@code /var/log/homesynapse/}
     */
    Path logDir();

    /**
     * Returns the writable pre-update snapshot directory.
     *
     * <p>Used for backup snapshots taken before applying updates.
     *
     * @return absolute path to the backup directory;
     *         on Linux Tier 1: {@code /var/lib/homesynapse/backups/}
     */
    Path backupDir();

    /**
     * Returns the writable temporary file directory.
     *
     * <p>Contents are deleted at the start of each process run (Phase 0).
     * No other directory is cleaned on startup. Suitable for transient files
     * that do not need to survive a restart.
     *
     * @return absolute path to the temporary directory;
     *         on Linux Tier 1: {@code /var/lib/homesynapse/tmp/}
     */
    Path tempDir();
}

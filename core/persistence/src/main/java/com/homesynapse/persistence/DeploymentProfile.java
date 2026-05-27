/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Hardware deployment profile, detected at first boot and used to select
 * PRAGMA values, checkpoint policies, and retention defaults.
 *
 * <p>Auto-detection (post-M3.6 deliverable) inspects storage type, available
 * RAM, and CPU architecture to pick a profile. Until auto-detection is
 * implemented, {@link #HOME} is the default — operators may override via
 * configuration.
 *
 * <p>Each profile carries pre-validated values for the eight tuning knobs:
 * <ul>
 *   <li>{@link #cacheSizeKiB()} — per-connection page cache magnitude in KiB
 *       (PRAGMA syntax requires negation)</li>
 *   <li>{@link #mmapSizeBytes()} — memory-mapped region size in bytes</li>
 *   <li>{@link #journalSizeLimitBytes()} — soft cap on WAL/journal growth in
 *       bytes</li>
 *   <li>{@link #busyTimeoutMs()} — milliseconds a contended connection waits
 *       before raising {@code SQLITE_BUSY}</li>
 *   <li>{@link #lockingMode()} — locking_mode selection
 *       ({@link LockingMode#NORMAL}/{@link LockingMode#EXCLUSIVE})</li>
 *   <li>{@link #readThreadCount()} — number of platform-thread read workers
 *       in {@code DatabaseExecutor}</li>
 *   <li>{@link #javalinMinThreads()} — minimum embedded Jetty thread pool size
 *       for the Javalin HTTP server (M3.6e.1)</li>
 *   <li>{@link #javalinMaxThreads()} — maximum embedded Jetty thread pool size
 *       for the Javalin HTTP server (M3.6e.1)</li>
 * </ul>
 *
 * <p>The {@code journalSizeLimitBytes} value is uniform across profiles at
 * 6,144,000 bytes (6 MB) per LTD-03, empirically validated by the D1 WAL
 * Pathology Validation Spike (2026-05-15): the bounded-window reader pattern
 * (AMD-38) keeps the WAL at ~4 MB peak under nominal load, so the 6 MB
 * ceiling remains correct. AMD-39 (proposed raise to 64 MB) was WITHDRAWN
 * on the same date as unnecessary.
 *
 * <p>The values are designed for the following hardware targets:
 * <ul>
 *   <li>{@link #STUDIO} — Pi 4 / SD card / 4 GB RAM</li>
 *   <li>{@link #HOME} — Pi 5 / NVMe / 4–8 GB RAM (MVP default)</li>
 *   <li>{@link #PERFORMANCE} — x86 mini-PC / NVMe-SATA SSD / ≥16 GB RAM</li>
 *   <li>{@link #TESTING} — M3.7 E2E test profile (1 read thread, Javalin 1/2)</li>
 * </ul>
 */
public enum DeploymentProfile {

    /**
     * Pi 4 or equivalent with SD card / USB storage, ≤4 GB RAM.
     * Optimized for write endurance and minimal memory footprint.
     *
     * <p>PRAGMA values: {@code cache_size=-2000} (2 MB),
     * {@code mmap_size=67108864} (64 MB),
     * {@code journal_size_limit=6144000} (6 MB, LTD-03 validated by D1 spike),
     * {@code busy_timeout=5000} (5 s),
     * {@code locking_mode=NORMAL},
     * 2 read threads (AMD-27 default for constrained I/O),
     * Javalin pool 1/4 (minimal admin/probe traffic on resource-constrained hardware).
     */
    STUDIO(2_000, 67_108_864L, 6_144_000L, 5_000L, LockingMode.NORMAL, 2, 1, 4),

    /**
     * Pi 5 or equivalent with NVMe SSD, 4–8 GB RAM.
     * Balanced performance and longevity. This is the MVP default.
     *
     * <p>PRAGMA values: {@code cache_size=-16000} (16 MB),
     * {@code mmap_size=268435456} (256 MB),
     * {@code journal_size_limit=6144000} (6 MB, LTD-03 validated by D1 spike),
     * {@code busy_timeout=5000} (5 s),
     * {@code locking_mode=NORMAL},
     * 2 read threads (AMD-27 default for constrained I/O),
     * Javalin pool 2/8 (typical household dashboard + automation REST load).
     */
    HOME(16_000, 268_435_456L, 6_144_000L, 5_000L, LockingMode.NORMAL, 2, 2, 8),

    /**
     * x86 mini-PC or high-spec ARM, ≥16 GB RAM, NVMe/SATA SSD.
     * Maximum throughput for large device counts and analytics workloads.
     *
     * <p>PRAGMA values: {@code cache_size=-65536} (64 MB),
     * {@code mmap_size=1073741824} (1 GB),
     * {@code journal_size_limit=6144000} (6 MB, LTD-03 validated by D1 spike),
     * {@code busy_timeout=5000} (5 s),
     * {@code locking_mode=NORMAL},
     * 4 read threads (doubled vs. the constrained-I/O profiles),
     * Javalin pool 4/16 (doubled vs. HOME for bulk analytics + multi-client deployments).
     */
    PERFORMANCE(65_536, 1_073_741_824L, 6_144_000L, 5_000L, LockingMode.NORMAL, 4, 4, 16),

    /**
     * M3.7 E2E test profile — tight SQLite bounds for fast end-to-end test
     * startup with a HOME-equivalent Javalin pool. Not intended for hardware
     * deployment.
     *
     * <p>PRAGMA values: {@code cache_size=-2000} (2 MB),
     * {@code mmap_size=33554432} (32 MB),
     * {@code journal_size_limit=6144000} (6 MB, LTD-03 validated by D1 spike),
     * {@code busy_timeout=5000} (5 s),
     * {@code locking_mode=NORMAL},
     * 1 read thread (single-test workload — one writer + one reader suffices),
     * Javalin pool 2/8 (matches {@link #HOME} — Jetty's
     * {@code QueuedThreadPool.doStart()} requires
     * {@code minThreads >= acceptors + selectors + 1}; the original M3.7
     * 1/2 sizing was below Jetty's floor on multi-core dev hosts and
     * threw {@code IllegalStateException} at server bind).
     *
     * <p>Selected by {@code HomeSynapseConfig.testing()} (Research 3 REC-15).
     */
    TESTING(2_000, 33_554_432L, 6_144_000L, 5_000L, LockingMode.NORMAL, 1, 2, 8);

    private final int cacheSizeKiB;
    private final long mmapSizeBytes;
    private final long journalSizeLimitBytes;
    private final long busyTimeoutMs;
    private final LockingMode lockingMode;
    private final int readThreadCount;
    private final int javalinMinThreads;
    private final int javalinMaxThreads;

    DeploymentProfile(
            int cacheSizeKiB,
            long mmapSizeBytes,
            long journalSizeLimitBytes,
            long busyTimeoutMs,
            LockingMode lockingMode,
            int readThreadCount,
            int javalinMinThreads,
            int javalinMaxThreads) {
        this.cacheSizeKiB = cacheSizeKiB;
        this.mmapSizeBytes = mmapSizeBytes;
        this.journalSizeLimitBytes = journalSizeLimitBytes;
        this.busyTimeoutMs = busyTimeoutMs;
        this.lockingMode = lockingMode;
        this.readThreadCount = readThreadCount;
        this.javalinMinThreads = javalinMinThreads;
        this.javalinMaxThreads = javalinMaxThreads;
    }

    /**
     * Returns the magnitude (in KiB) used for {@code PRAGMA cache_size}. The
     * PRAGMA syntax expects a negative number for a KiB-denominated cache, so
     * Phase 3 code applying this value should issue
     * {@code "PRAGMA cache_size = -" + profile.cacheSizeKiB()}.
     *
     * @return the cache size magnitude in KiB
     */
    public int cacheSizeKiB() {
        return cacheSizeKiB;
    }

    /**
     * Returns the value for {@code PRAGMA mmap_size} in bytes.
     *
     * @return the memory-map size in bytes
     */
    public long mmapSizeBytes() {
        return mmapSizeBytes;
    }

    /**
     * Returns the value for {@code PRAGMA journal_size_limit} in bytes.
     *
     * <p>Uniform across all profiles at 6,144,000 bytes (6 MB) per LTD-03,
     * empirically validated by the D1 WAL Pathology Validation Spike
     * (2026-05-15). The bounded-window reader pattern (AMD-38) keeps the WAL
     * within this ceiling under nominal load.
     *
     * @return the journal size limit in bytes
     */
    public long journalSizeLimitBytes() {
        return journalSizeLimitBytes;
    }

    /**
     * Returns the value for {@code PRAGMA busy_timeout} in milliseconds — the
     * time a connection blocks awaiting a database lock before raising
     * {@code SQLITE_BUSY}.
     *
     * <p>Uniform across all profiles at 5,000 ms — the value previously
     * hardcoded in {@code DatabaseExecutor}. Future profiles may tune this
     * (e.g., higher for NFS-backed storage with longer round-trips).
     *
     * @return the busy timeout in milliseconds
     */
    public long busyTimeoutMs() {
        return busyTimeoutMs;
    }

    /**
     * Returns the {@link LockingMode} for connections opened against this
     * profile.
     *
     * <p>Uniform across all current profiles at {@link LockingMode#NORMAL}.
     * Docker Desktop / VirtioFS / NFS deployments will override to
     * {@link LockingMode#EXCLUSIVE} via a post-M3.6 {@code PersistenceConfig}
     * builder; the {@code EXCLUSIVE} value exists today so that custom
     * profiles can already select it.
     *
     * @return the SQLite locking_mode for this profile
     */
    LockingMode lockingMode() {
        return lockingMode;
    }

    /**
     * Returns the number of platform-thread read workers
     * {@link DatabaseExecutor} should open for this profile.
     *
     * <p>{@link #STUDIO} and {@link #HOME} (Pi-class hardware with constrained
     * I/O) use 2 read threads, matching the AMD-27 default.
     * {@link #PERFORMANCE} (NVMe x86 server) doubles to 4. Custom profiles
     * may select any value in {@code [1, 8]}; the upper bound of 8 is the
     * LTD-03 reader-budget ceiling and is enforced by
     * {@link DatabaseExecutor}'s constructor.
     *
     * @return the read thread count
     */
    public int readThreadCount() {
        return readThreadCount;
    }

    /**
     * Returns the minimum embedded Jetty thread pool size for the Javalin
     * HTTP server (M3.6e.1).
     *
     * <p>Per-profile values: STUDIO 1, HOME 2, PERFORMANCE 4. The pool grows
     * up to {@link #javalinMaxThreads()} under load and shrinks back to this
     * floor when idle.
     *
     * @return the minimum Javalin thread count for this profile
     */
    public int javalinMinThreads() {
        return javalinMinThreads;
    }

    /**
     * Returns the maximum embedded Jetty thread pool size for the Javalin
     * HTTP server (M3.6e.1).
     *
     * <p>Per-profile values: STUDIO 4, HOME 8, PERFORMANCE 16. Sized to
     * absorb typical request bursts without exhausting the host's carrier
     * thread budget; Pi-class profiles deliberately stay small to leave
     * headroom for SQLite write and read executors.
     *
     * @return the maximum Javalin thread count for this profile
     */
    public int javalinMaxThreads() {
        return javalinMaxThreads;
    }
}

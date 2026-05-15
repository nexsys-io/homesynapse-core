/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Hardware deployment profile, detected at first boot and used to select
 * PRAGMA values, checkpoint policies, and retention defaults.
 *
 * <p>Auto-detection (M3 deliverable) inspects storage type, available RAM,
 * and CPU architecture to pick a profile. Until auto-detection is implemented,
 * {@link #HOME} is the default — operators may override via configuration.
 *
 * <p>Each profile carries pre-validated PRAGMA values derived from the
 * M2→M3 storage efficiency research. The {@link #HOME} profile's
 * {@code journalSizeLimitBytes} reflects AMD-39 (provisional pending D1
 * spike validation on hs-dev-1).
 *
 * <p>The values are designed for the following hardware targets:
 * <ul>
 *   <li>{@link #STUDIO} — Pi 4 / SD card / 4 GB RAM</li>
 *   <li>{@link #HOME} — Pi 5 / NVMe / 4–8 GB RAM (MVP default)</li>
 *   <li>{@link #PERFORMANCE} — x86 mini-PC / NVMe-SATA SSD / ≥16 GB RAM</li>
 * </ul>
 */
public enum DeploymentProfile {

    /**
     * Pi 4 or equivalent with SD card / USB storage, ≤4 GB RAM.
     * Optimized for write endurance and minimal memory footprint.
     *
     * <p>PRAGMA values: {@code cache_size=-2000} (2 MB),
     * {@code mmap_size=67108864} (64 MB),
     * {@code journal_size_limit=33554432} (32 MB).
     */
    STUDIO(2_000, 67_108_864L, 33_554_432L),

    /**
     * Pi 5 or equivalent with NVMe SSD, 4–8 GB RAM.
     * Balanced performance and longevity. This is the MVP default.
     *
     * <p>PRAGMA values: {@code cache_size=-16000} (16 MB),
     * {@code mmap_size=268435456} (256 MB),
     * {@code journal_size_limit=67108864} (64 MB, per AMD-39 provisional).
     */
    HOME(16_000, 268_435_456L, 67_108_864L),

    /**
     * x86 mini-PC or high-spec ARM, ≥16 GB RAM, NVMe/SATA SSD.
     * Maximum throughput for large device counts and analytics workloads.
     *
     * <p>PRAGMA values: {@code cache_size=-65536} (64 MB),
     * {@code mmap_size=1073741824} (1 GB),
     * {@code journal_size_limit=268435456} (256 MB).
     */
    PERFORMANCE(65_536, 1_073_741_824L, 268_435_456L);

    private final int cacheSizeKiB;
    private final long mmapSizeBytes;
    private final long journalSizeLimitBytes;

    DeploymentProfile(int cacheSizeKiB, long mmapSizeBytes,
                      long journalSizeLimitBytes) {
        this.cacheSizeKiB = cacheSizeKiB;
        this.mmapSizeBytes = mmapSizeBytes;
        this.journalSizeLimitBytes = journalSizeLimitBytes;
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
     * <p>The {@link #HOME} value (64 MB) reflects AMD-39 (provisional pending
     * D1 WAL pathology spike validation).
     *
     * @return the journal size limit in bytes
     */
    public long journalSizeLimitBytes() {
        return journalSizeLimitBytes;
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * SQLite {@code locking_mode} values relevant to HomeSynapse persistence.
 *
 * <p>The locking mode interacts with WAL-shm (the shared-memory companion
 * file SQLite uses to coordinate WAL readers and writers). On native
 * filesystems WAL-shm works as designed and {@link #NORMAL} permits
 * concurrent readers alongside the single writer. On certain virtualised or
 * networked filesystems — most commonly Docker Desktop's VirtioFS bind
 * mounts on macOS and NFS exports — WAL-shm shared memory is broken and
 * WAL mode silently fails to function. For those environments the database
 * must be opened with {@link #EXCLUSIVE}, which takes the file lock for the
 * lifetime of the connection and bypasses WAL-shm entirely (LTD-03 +
 * Portability Architecture v1 §2.1).
 *
 * <p>This enum is package-private by design: external modules should never
 * reference SQLite locking modes directly. The value is supplied via
 * {@link DeploymentProfile#lockingMode()} and consumed only inside
 * {@link DatabaseExecutor} when rendering PRAGMA statements.
 *
 * @see DeploymentProfile#lockingMode()
 */
enum LockingMode {

    /**
     * Default SQLite behaviour. WAL-shm shared memory is used for
     * reader/writer coordination, so multiple concurrent readers may run
     * alongside the single writer. Required for any deployment on a native
     * filesystem (Linux ext4/xfs, macOS APFS local disk, Windows NTFS).
     */
    NORMAL,

    /**
     * The connection takes an exclusive file lock for its lifetime and
     * bypasses WAL-shm. Required for Docker Desktop / VirtioFS / NFS mounts
     * where the WAL-shm shared memory mechanism is broken. Eliminates
     * concurrent reader support but makes WAL mode work at all.
     *
     * <p><strong>Sticky:</strong> Once a connection enters {@code EXCLUSIVE}
     * mode, the lock persists for the connection's lifetime and cannot be
     * downgraded back to {@link #NORMAL} without closing and reopening.
     */
    EXCLUSIVE
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

/**
 * Process exit codes for HomeSynapse Core.
 *
 * <p>Each exit code maps to a specific fatal failure category,
 * enabling deterministic diagnosis of startup failures from
 * the process exit status alone. The systemd unit file can
 * use these codes to distinguish between restartable and
 * non-restartable failures.
 *
 * <p>Exit code 0 is never used explicitly — a clean shutdown
 * returns 0 via normal JVM exit. All codes in this enum
 * represent abnormal termination.
 *
 * @see com.homesynapse.lifecycle.SystemLifecycleManager
 */
public enum ExitCode {

    /** Configuration file missing, unparseable, or fails schema validation. */
    CONFIGURATION_FAILURE(10),

    /** SQLite database cannot be opened, migrated, or passes integrity check. */
    PERSISTENCE_FAILURE(11),

    /** Event bus initialization failed — cannot distribute events. */
    EVENT_BUS_FAILURE(12),

    /** A required core subsystem failed to initialize within its timeout. */
    SUBSYSTEM_INIT_TIMEOUT(13),

    /** Unhandled exception escaped the startup sequence. */
    UNEXPECTED_ERROR(99);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric exit code for use with {@link System#exit(int)}.
     *
     * @return the process exit code, always positive and non-zero
     */
    public int code() {
        return code;
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for the HomeSynapse system instance.
 *
 * <p>The system ID identifies the running HomeSynapse instance itself. It serves as the
 * subject reference for system lifecycle events ({@code system_started},
 * {@code system_stopped}, {@code migration_applied}, {@code snapshot_created},
 * {@code config_changed}) and for system-level health and storage events.</p>
 *
 * <p>A single HomeSynapse installation has one system ID, assigned at first startup and
 * stable for the lifetime of the installation. The system ID is recorded in the
 * configuration store and used as the system-subject reference for all system events.</p>
 *
 * <p>The wrapped value is a 26-character ULID string in canonical Crockford Base32 encoding
 * per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID string identifying this system instance, never {@code null} or blank
 */
public record SystemId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public SystemId {
        Objects.requireNonNull(value, "SystemId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("SystemId value must not be blank");
        }
    }

    /**
     * Creates a {@code SystemId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code SystemId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static SystemId of(String value) {
        return new SystemId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

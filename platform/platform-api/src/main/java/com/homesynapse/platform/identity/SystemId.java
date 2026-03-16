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
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID identifying this system instance, never {@code null}
 */
public record SystemId(Ulid value) implements Comparable<SystemId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public SystemId {
        Objects.requireNonNull(value, "SystemId value must not be null");
    }

    /**
     * Creates a {@code SystemId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code SystemId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static SystemId of(Ulid value) {
        return new SystemId(value);
    }

    /**
     * Creates a {@code SystemId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code SystemId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static SystemId parse(String crockford) {
        return new SystemId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(SystemId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

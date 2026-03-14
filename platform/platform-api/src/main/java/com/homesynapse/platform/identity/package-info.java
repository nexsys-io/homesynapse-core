/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Typed identity types for HomeSynapse domain objects.
 *
 * <p>Every domain object in HomeSynapse (entity, device, area, automation, person, home,
 * integration, system) is identified by a globally unique, monotonically sortable
 * <a href="https://github.com/ulid/spec">ULID</a> (Universally Unique Lexicographically
 * Sortable Identifier) per LTD-04.</p>
 *
 * <p>Each identity type is a single-field {@code record} wrapping the ULID string
 * representation. Typed wrappers prevent accidental cross-domain identity confusion
 * at compile time — passing a {@link DeviceId} where an {@link EntityId} is expected
 * is a type error, not a runtime surprise.</p>
 *
 * <p><strong>Storage convention:</strong> ULIDs are stored as {@code BLOB(16)} in SQLite
 * (16-byte binary Crockford Base32 decoding). The {@code String} representation in these
 * records is the 26-character canonical text encoding used on the wire and in Java code.
 * Conversion between text and binary is handled by the persistence layer.</p>
 *
 * @see <a href="https://github.com/ulid/spec">ULID Specification</a>
 */
package com.homesynapse.platform.identity;

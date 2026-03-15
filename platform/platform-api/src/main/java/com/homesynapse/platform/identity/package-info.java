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
 * <p>Each identity type is a single-field {@code record} wrapping a {@link Ulid} value.
 * Typed wrappers prevent accidental cross-domain identity confusion at compile time —
 * passing a {@link DeviceId} where an {@link EntityId} is expected is a type error, not
 * a runtime surprise.</p>
 *
 * <p>The {@link Ulid} type provides the canonical 26-character Crockford Base32 text
 * encoding via {@link Ulid#toString()} and 16-byte big-endian binary encoding via
 * {@link Ulid#toBytes()} for {@code BLOB(16)} storage in SQLite. The {@link UlidFactory}
 * generates monotonically increasing ULIDs using a thread-safe, virtual-thread-compatible
 * algorithm backed by {@link java.util.concurrent.locks.ReentrantLock}.</p>
 *
 * @see <a href="https://github.com/ulid/spec">ULID Specification</a>
 */
package com.homesynapse.platform.identity;

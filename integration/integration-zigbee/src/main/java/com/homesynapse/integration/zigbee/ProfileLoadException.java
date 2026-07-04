/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * A device-profile document failed to load: unknown schema major version
 * (fail-closed, §H), duplicate profile ids within one load (§C), malformed
 * criteria, or a profile body that does not materialize into a valid
 * {@link DeviceProfile}.
 *
 * <p>Interior to the adapter: profile loading happens at adapter initialization
 * and on explicit user-file loads; the failure is terminal for that load, never
 * a silent partial acceptance (no eval-in-data, no best-effort schema guessing).
 *
 * <p>Thread-safe: exceptions are effectively immutable.
 */
class ProfileLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a Register C description of the load failure.
     *
     * @param message what failed, what was expected, and which profile/document
     */
    ProfileLoadException(String message) {
        super(message);
    }

    /**
     * Creates the exception wrapping the underlying parse failure.
     *
     * @param message what failed, what was expected, and which profile/document
     * @param cause the underlying failure
     */
    ProfileLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}

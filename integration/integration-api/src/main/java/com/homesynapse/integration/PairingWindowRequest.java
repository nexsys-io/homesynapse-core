/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.util.Objects;

/**
 * An operator's request to open an integration's pairing window (PJ-2, IR-63): a
 * declared, time-boxed act with a reason and an actor of record. Validated at
 * construction so no adapter ever sees an out-of-range duration — the API rejects
 * what this record rejects.
 *
 * @param durationSeconds the window length, {@value #MIN_DURATION_SECONDS}–{@value
 *                        #MAX_DURATION_SECONDS} seconds (the Zigbee permit-join maximum
 *                        is 254; the bounds moved here from the adapter's former clamp)
 * @param reason          why the window opens (surfaces in the event of record and the
 *                        log); non-blank, at most {@value #MAX_REASON_LENGTH} characters
 * @param actor           who asked — the API key id of the caller; non-blank
 * @see PairingWindowControl
 * @see PermitJoinOpened
 */
public record PairingWindowRequest(int durationSeconds, String reason, String actor) {

    /** The shortest window an adapter opens. */
    public static final int MIN_DURATION_SECONDS = 1;

    /** The longest window an adapter opens (the Zigbee spec maximum for permit-join). */
    public static final int MAX_DURATION_SECONDS = 254;

    /** The longest reason carried on the wire and in the event of record. */
    public static final int MAX_REASON_LENGTH = 120;

    public PairingWindowRequest {
        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("durationSeconds must be between "
                    + MIN_DURATION_SECONDS + " and " + MAX_DURATION_SECONDS + ", got "
                    + durationSeconds);
        }
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason must be at most " + MAX_REASON_LENGTH
                    + " characters, got " + reason.length());
        }
        Objects.requireNonNull(actor, "actor must not be null");
        if (actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An operator's request to open an integration's pairing window (PJ-2, IR-63): a
 * declared, time-boxed act with a reason and an actor of record — and, since J2b, an
 * optional DEVICE SCOPE. Validated at construction so no adapter ever sees an
 * out-of-range duration or a malformed scope — the API rejects what this record rejects.
 *
 * <p>The scope names the ONE device the window is for: its IEEE address as text,
 * {@code 0x} + 16 hex digits (the protocol-typed address lives in the adapter module, so
 * the contract crosses as a {@code String}). A scoped window is the recovery window of
 * D-v94-24 — the adapter installs the transient key for that partner alone and the trust
 * center DENIES any other joiner; "a device that is not yours tried to join" becomes a
 * {@link JoinRejected} event. {@code null} is the un-scoped window, PJ-2's behavior
 * unchanged. A non-null scope is CANONICALIZED here, once, to {@code 0x} + upper-case
 * hex — the form the window, the event of record and the REST view all carry.
 *
 * @param durationSeconds the window length, {@value #MIN_DURATION_SECONDS}–{@value
 *                        #MAX_DURATION_SECONDS} seconds (the Zigbee permit-join maximum
 *                        is 254; the bounds moved here from the adapter's former clamp)
 * @param reason          why the window opens (surfaces in the event of record and the
 *                        log); non-blank, at most {@value #MAX_REASON_LENGTH} characters
 * @param actor           who asked — the API key id of the caller; non-blank
 * @param scope           the one device admitted, {@code 0x} + 16 hex digits (stored
 *                        canonical: upper-case), or {@code null} for an un-scoped window
 * @see PairingWindowControl
 * @see PermitJoinOpened
 * @see JoinRejected
 */
public record PairingWindowRequest(int durationSeconds, String reason, String actor,
                                   String scope) {

    /** The shortest window an adapter opens. */
    public static final int MIN_DURATION_SECONDS = 1;

    /** The longest window an adapter opens (the Zigbee spec maximum for permit-join). */
    public static final int MAX_DURATION_SECONDS = 254;

    /** The longest reason carried on the wire and in the event of record. */
    public static final int MAX_REASON_LENGTH = 120;

    /**
     * The scope's shape — {@code 0x} or {@code 0X}, then exactly 16 hex digits (an IEEE
     * EUI-64). The REST endpoint gates on the same shape before the port is called; this
     * record is the ONE place the text is canonicalized.
     */
    public static final String SCOPE_PATTERN = "^0[xX][0-9a-fA-F]{16}$";

    private static final Pattern SCOPE_SHAPE = Pattern.compile(SCOPE_PATTERN);

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
        if (scope != null) {
            if (!SCOPE_SHAPE.matcher(scope).matches()) {
                throw new IllegalArgumentException(
                        "scope must be 0x followed by 16 hex digits, got '" + scope + "'");
            }
            scope = "0x" + scope.substring(2).toUpperCase(Locale.ROOT);
        }
    }
}

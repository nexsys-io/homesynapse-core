/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.time.Instant;
import java.util.Objects;

/**
 * Response-level metadata included in State Query plane API responses.
 *
 * <p>The {@link #viewPosition()} enables staleness detection at the API boundary
 * (Doc 09 §3.7, §5). Clients that issue a command (which returns a
 * {@code viewPosition}) and then query state can compare the two positions to
 * determine whether the State Store projection has caught up to include the
 * command's effects. This satisfies INV-TO-03 (no hidden state) at the API
 * boundary.</p>
 *
 * <p>Event History plane responses do not include this metadata — they read
 * directly from the immutable event store and are strongly consistent. The
 * {@code meta} field in {@link PagedResponse} is {@code null} for Event History
 * plane responses.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param viewPosition the State Store's current view position at response time;
 *                     used for staleness detection by comparing against previously
 *                     returned positions
 * @param timestamp    the response generation time, never {@code null}
 *
 * @see PagedResponse
 * @see ETagProvider#fromViewPosition(long)
 * @see <a href="Doc 09 §3.5">Response Envelope</a>
 * @see <a href="Doc 09 §3.7">Staleness Detection</a>
 */
public record ResponseMeta(long viewPosition, Instant timestamp) {

    /**
     * Creates a new response metadata with validation of required fields.
     *
     * @throws NullPointerException if {@code timestamp} is {@code null}
     */
    public ResponseMeta {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}

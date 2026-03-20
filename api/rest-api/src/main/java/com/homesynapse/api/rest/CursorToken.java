/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.Objects;

/**
 * Decoded cursor representing a pagination position within a sorted result set.
 *
 * <p>Cursors enable efficient keyset pagination across all list endpoints (Doc 09 §3.5,
 * §8.2). The cursor encodes the sort key value of the last item on the previous page,
 * the field name being sorted on, and the sort direction. This allows the database
 * query to resume from the exact position without offset-based scanning.</p>
 *
 * <p>Cursor content is opaque to API clients — they receive an encoded URL-safe Base64
 * string from the {@code next_cursor} field in paginated responses and pass it back
 * as the {@code cursor} query parameter on subsequent requests. The
 * {@link PaginationCodec} handles encoding and decoding.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param sortValue     the sort key value of the last item on the previous page
 *                      (e.g., a {@code global_position} as a string for events, or an
 *                      {@code entity_id} for entities), never {@code null}
 * @param sortDimension the field name being sorted on (e.g., {@code "global_position"}
 *                      or {@code "entity_id"}), never {@code null}
 * @param direction     the sort direction for this pagination sequence,
 *                      never {@code null}
 *
 * @see PaginationCodec
 * @see PaginationMeta
 * @see SortDirection
 * @see <a href="Doc 09 §3.5">Cursor-Based Pagination</a>
 */
public record CursorToken(String sortValue, String sortDimension, SortDirection direction) {

    /**
     * Creates a new cursor token with validation of required fields.
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public CursorToken {
        Objects.requireNonNull(sortValue, "sortValue must not be null");
        Objects.requireNonNull(sortDimension, "sortDimension must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
    }
}

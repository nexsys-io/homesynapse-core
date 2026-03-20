/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.List;
import java.util.Objects;

/**
 * Paginated response envelope for list endpoints.
 *
 * <p>Corresponds to the JSON response envelope with {@code data}, {@code pagination},
 * and {@code meta} top-level objects (Doc 09 §3.5, §8.2). The generic parameter
 * {@code T} provides compile-time type safety for endpoint handlers —
 * {@code PagedResponse<EntitySummary>} and {@code PagedResponse<EventSummary>}
 * are distinct types even though the pagination mechanics are shared.</p>
 *
 * <p>The {@link #meta()} field is present for State Query plane responses (which
 * include {@link ResponseMeta#viewPosition()} for staleness detection) and
 * {@code null} for Event History plane responses (which are strongly consistent
 * from the immutable event store).</p>
 *
 * <p>Thread-safe (immutable record). The data list is unmodifiable.</p>
 *
 * @param data       the page items (unmodifiable), never {@code null}
 * @param pagination pagination metadata for navigating to subsequent pages,
 *                   never {@code null}
 * @param meta       response-level metadata for State Query plane responses,
 *                   {@code null} for Event History plane responses
 * @param <T>        the item type (e.g., entity summary records, event records)
 *
 * @see PaginationMeta
 * @see ResponseMeta
 * @see PaginationCodec
 * @see <a href="Doc 09 §3.5">Cursor-Based Pagination</a>
 */
public record PagedResponse<T>(List<T> data, PaginationMeta pagination, ResponseMeta meta) {

    /**
     * Creates a new paged response with validation of required fields.
     *
     * <p>The {@code data} list is made unmodifiable via
     * {@link List#copyOf(java.util.Collection)}.</p>
     *
     * @throws NullPointerException if {@code data} or {@code pagination} is {@code null}
     */
    public PagedResponse {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(pagination, "pagination must not be null");
        data = List.copyOf(data);
    }
}

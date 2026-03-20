/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Pagination metadata included in paginated API responses.
 *
 * <p>Corresponds to the {@code pagination} object in the JSON response envelope
 * (Doc 09 §3.5). Every paginated response includes this metadata alongside the
 * {@code data} array, enabling clients to navigate through result sets.</p>
 *
 * <p>When {@link #hasMore()} is {@code true}, the {@link #nextCursor()} contains
 * a URL-safe Base64 encoded cursor that the client passes as the {@code cursor}
 * query parameter to fetch the next page. When {@code hasMore} is {@code false},
 * {@code nextCursor} is {@code null} — the client has reached the end of the
 * result set.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param nextCursor URL-safe Base64 cursor for the next page, {@code null} when
 *                   {@code hasMore} is {@code false}
 * @param hasMore    {@code true} if additional pages exist beyond the current page
 * @param limit      the page size used for this response
 *
 * @see CursorToken
 * @see PaginationCodec
 * @see PagedResponse
 * @see <a href="Doc 09 §3.5">Cursor-Based Pagination</a>
 */
public record PaginationMeta(String nextCursor, boolean hasMore, int limit) {
}

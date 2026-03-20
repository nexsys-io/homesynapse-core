/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Encodes and decodes opaque pagination cursors for keyset pagination.
 *
 * <p>Cursor content is opaque to API clients (Doc 09 §3.5) — they receive a
 * URL-safe Base64 encoded string from the {@code next_cursor} field in a paginated
 * response and pass it back as the {@code cursor} query parameter on the next
 * request. The cursor contains the sort key value, sort dimension, and direction
 * needed for efficient keyset pagination.</p>
 *
 * <p>Thread-safe and stateless.</p>
 *
 * @see CursorToken
 * @see PaginationMeta
 * @see PagedResponse
 * @see <a href="Doc 09 §3.5">Cursor-Based Pagination</a>
 */
public interface PaginationCodec {

    /**
     * Encodes a cursor position as a URL-safe Base64 string.
     *
     * @param token the cursor position to encode, never {@code null}
     * @return the URL-safe Base64 encoded cursor string, never {@code null}
     */
    String encode(CursorToken token);

    /**
     * Decodes a cursor string back to a {@link CursorToken}.
     *
     * @param cursor the URL-safe Base64 encoded cursor string from the client,
     *               never {@code null}
     * @return the decoded cursor token, never {@code null}
     * @throws ApiException with {@link ProblemType#INVALID_PARAMETERS} if the cursor
     *                      is malformed, tampered, or expired
     */
    CursorToken decode(String cursor) throws ApiException;
}

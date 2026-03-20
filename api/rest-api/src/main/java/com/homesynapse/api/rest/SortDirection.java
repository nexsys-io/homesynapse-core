/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Pagination sort direction for list endpoints.
 *
 * <p>Controls the ordering of items in paginated API responses (Doc 09 §3.6).
 * Each list endpoint has a default direction:</p>
 * <ul>
 *   <li>Entity and device lists default to {@link #ASC} (ULID chronological order —
 *       oldest first).</li>
 *   <li>Event lists default to {@link #DESC} (newest first — most recent events
 *       are typically most relevant).</li>
 * </ul>
 *
 * <p>The {@code sort} query parameter overrides the default direction on any
 * list endpoint. The direction is encoded into the {@link CursorToken} so that
 * subsequent pages maintain the same ordering without requiring the client to
 * re-specify the direction.</p>
 *
 * @see CursorToken
 * @see PaginationMeta
 * @see <a href="Doc 09 §3.6">Sort Direction</a>
 */
public enum SortDirection {

    /** Ascending order — oldest or lowest value first. */
    ASC,

    /** Descending order — newest or highest value first. */
    DESC
}

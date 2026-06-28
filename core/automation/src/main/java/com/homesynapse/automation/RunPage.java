/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Objects;

/**
 * A bounded page of {@link RunSummary} entries returned by
 * {@link ExplanationService#listRuns}, newest-first.
 *
 * <p>This is the projection's own pagination carrier — deliberately <em>not</em> the
 * rest-api {@code PagedResponse}, so {@code com.homesynapse.automation} carries no edge to
 * the api layer. The rest-api boundary maps {@link #nextCursorPosition()} to the frozen wire
 * {@code pagination.nextCursor} by encoding it as an opaque cursor, and surfaces
 * {@link #hasMore()} and the request {@code limit} alongside it.</p>
 *
 * <p>{@code nextCursorPosition} is the global log position of the oldest run on this page —
 * the exclusive upper bound for the next (older) page. It is only meaningful when
 * {@link #hasMore()} is {@code true}; when there are no older runs the rest-api boundary
 * emits a {@code null} cursor.</p>
 *
 * @param runs               the page's run summaries, newest-first; never {@code null}
 * @param nextCursorPosition the global position to page below for the next older page
 * @param hasMore            whether older terminal runs exist beyond this page
 */
public record RunPage(List<RunSummary> runs, long nextCursorPosition, boolean hasMore) {

    /**
     * Validates and defensively copies {@code runs}.
     *
     * @throws NullPointerException if {@code runs} is {@code null}
     */
    public RunPage {
        Objects.requireNonNull(runs, "runs must not be null");
        runs = List.copyOf(runs);
    }
}

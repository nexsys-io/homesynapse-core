/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Optional;

import com.homesynapse.event.EventStore;
import com.homesynapse.platform.identity.AutomationId;

/**
 * The automation read-side explainability projection (Doc 16 §3.3, LOCKED): answers
 * "list recent runs" and "explain one run" purely from the immutable event log.
 *
 * <p>Per INV-SA-03 ("explanation is a projection of the log; no parallel trace store") and
 * SP2, every result is reconstructable solely from persisted events: the service performs
 * <strong>zero writes</strong>, persists nothing, and mints no event. Completed Runs live
 * only in the log (the {@link RunManager} exposes active Runs only), so this projection reads
 * the {@link EventStore} directly rather than any in-memory run state.</p>
 *
 * <p>The rest-api layer consumes this service across the query-service boundary (the
 * {@code StateQueryService} precedent; the {@code QUERY_SERVICE_READ_ONLY} arch rule forbids
 * rest-api touching persistence directly) and maps the projection records to the frozen v1.1
 * wire JSON. The status vocabulary mapping (DP-A1) and per-action outcome rendering (DP-A2)
 * happen at that boundary; the internal {@link RunStatus} enum never appears on the wire.</p>
 *
 * <p><strong>Thread-safety:</strong> implementations are stateless over an injected
 * thread-safe {@link EventStore} and {@link AutomationRegistry}, safe for concurrent reads
 * (each request runs on its own virtual thread, LTD-01).</p>
 */
public interface ExplanationService {

    /**
     * Lists recent <em>terminal</em> Runs, newest-first, optionally filtered by automation —
     * a bounded page. Pure projection: performs no writes.
     *
     * <p>Non-terminal (in-flight) Runs are out of V1 scope and never appear. Pagination is
     * cursor-style by global log position: {@code beforePosition} is the exclusive upper bound
     * (pass {@code 0} or any non-positive value for the first/newest page); the returned
     * {@link RunPage#nextCursorPosition()} is the bound for the next older page.</p>
     *
     * @param automationId  if present, only this automation's Runs; otherwise all automations
     * @param beforePosition the exclusive upper-bound global position; {@code <= 0} means newest
     * @param limit          the maximum number of Runs to return; clamped to {@code >= 1} by the impl
     * @return a bounded page of run summaries, newest-first; never {@code null} (empty when none match)
     */
    RunPage listRuns(Optional<AutomationId> automationId, long beforePosition, int limit);

    /**
     * Assembles the causal-chain explanation for one terminal Run, or empty if no terminal
     * Run with this id is found in the log. Pure projection: performs no writes.
     *
     * @param runId the Run to explain; never {@code null}
     * @return the assembled explanation, or {@link Optional#empty()} if not found / not terminal
     */
    Optional<RunExplanation> explainRun(RunId runId);

    /**
     * The "why did this <em>not</em> fire?" projection for one automation (Doc 16 §3.3; the
     * frozen v1.1 §B3 verdict surface). Pure projection — no writes, mints no event
     * (INV-SA-03 / SP2). The verdict is derived from existing run records + config + absence
     * within the "expected since" window (DP-B2), never from a parallel suppression-diagnostic
     * store.
     *
     * <p>Returns empty if no automation with this id is known to the {@link AutomationRegistry}
     * (→ 404 at the boundary). When the automation is known, the result always carries a
     * {@link NonFiringExplanation.NonFiringVerdict} from the frozen 4-value vocabulary; the
     * Doc-16 §4 {@code SuppressionReason} (7-value, per-triggering-event) deep diagnosis is a
     * <strong>post-V1</strong> enrichment of this same surface (DP-B1) and is not derived here.</p>
     *
     * @param automationId          the automation to diagnose; never {@code null}
     * @param expectedSincePosition the inclusive lower-bound global log position defining the
     *                              "expected since" window; {@code <= 0} means the default window
     *                              (the whole retained log — "have you ever fired?"); rendered as a
     *                              position bound, never a wall-clock filter, to stay replay-deterministic
     * @return the assembled non-firing explanation, or empty if the automation is unknown
     */
    Optional<NonFiringExplanation> explainNonFiring(AutomationId automationId, long expectedSincePosition);

    /**
     * Lists all loaded automations as component-based summaries (the frozen v1.1 §B3 list). Pure
     * projection over the {@link AutomationRegistry} (+ a best-effort most-recent-run lookup per
     * automation, nullable). Registry order; never {@code null} (empty when none are loaded).
     * Performs no writes and mints no event (INV-SA-03 / SP2).
     *
     * @return the automation summaries in registry order; never {@code null}
     */
    List<AutomationSummary> listAutomations();

    /**
     * Construction seam (mirrors {@code StateQueryService.materialized(...)}): builds the
     * standard log-derived projection over the given event store and automation registry. The
     * registry is consulted only for best-effort display labels (automation name, trigger
     * type); the correctness-bearing outcome derivation is purely log-driven, so the
     * projection stays a deterministic function of the immutable log.
     *
     * @param eventStore         the immutable event log to project from; never {@code null}
     * @param automationRegistry the registry for best-effort name/trigger-type lookups; never {@code null}
     * @return a new {@link ExplanationService}
     */
    static ExplanationService over(EventStore eventStore, AutomationRegistry automationRegistry) {
        return new StandardExplanationService(eventStore, automationRegistry);
    }
}

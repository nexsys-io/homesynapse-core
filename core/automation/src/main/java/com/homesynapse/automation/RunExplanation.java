/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * The assembled causal-chain explanation for one terminal automation Run — the
 * "why did it fire? / did it actually confirm?" tree served by
 * {@code GET /api/v1/runs/{runId}/causal-chain} (Doc 16 §3.3).
 *
 * <p>This is a pure read-side projection of the immutable event log (INV-SA-03 / SP2): it is
 * assembled by {@link ExplanationService} from the run-lifecycle events
 * ({@code automation_triggered} / {@code automation_condition_evaluated} /
 * {@code automation_action_started} / {@code automation_action_completed} /
 * {@code automation_completed}) and the command-confirmation events
 * ({@code command_issued} / {@code command_dispatched} / {@code command_result} /
 * {@code state_confirmed} / {@code command_confirmation_timed_out}). It is never persisted
 * and mints no event.</p>
 *
 * <p><strong>It is NOT a serialization of {@code RunCausalChain}.</strong> {@code RunCausalChain}
 * is only the cascade ancestry and never crosses the event boundary; the trigger / conditions /
 * actions / outcome tree is projected from the AMD-92 lifecycle events, and {@link CascadeView}
 * carries only the flattened {@code cascadeDepth} the events do expose.</p>
 *
 * <p>The nested view records flatten the projection to exactly the frozen v1.1 wire shape; the
 * rest-api boundary maps {@link OutcomeView#status()} to the public status vocabulary (DP-A1)
 * and renders each {@link ActionView#outcome()} verbatim (DP-A2). The internal
 * {@link RunStatus} enum never appears on the wire.</p>
 *
 * @param runId          the Run's identifier, never {@code null}
 * @param automationId   the owning automation's identifier (from the event subject), never {@code null}
 * @param automationName the automation's display name, or {@code null} if the definition is gone
 * @param trigger        the firing trigger view, never {@code null}
 * @param conditions     the evaluated conditions, oldest-first; never {@code null} (may be empty)
 * @param actions        the device-command actions and their outcomes, in issue order; never
 *                       {@code null} (may be empty)
 * @param outcome        the terminal Run outcome, never {@code null}
 * @param cascade        the cascade position view, never {@code null}
 * @param definitionKey  the v1.1.4 (EXPLAIN-114a) stable definition key: the
 *                       {@code definitionHash} the engine stamped on this run's
 *                       {@code automation_triggered} (SHA-256 hex over the definition, Doc 07
 *                       §3.7) — the same function the non-firing read and the automation list
 *                       serve over the registry definition, so a consumer can tell "the same
 *                       definition" across reloads and across reads; {@code null} only when the
 *                       log carries none. Nullable by contract; NOT null-checked
 */
public record RunExplanation(
        RunId runId,
        AutomationId automationId,
        String automationName,
        TriggerView trigger,
        List<ConditionView> conditions,
        List<ActionView> actions,
        OutcomeView outcome,
        CascadeView cascade,
        String definitionKey) {

    /**
     * Validates the structural components and defensively copies the lists.
     * {@code definitionKey} is nullable by contract (the additive-nullable idiom) and is NOT
     * null-checked.
     *
     * @throws NullPointerException if any non-nullable component is {@code null}
     */
    public RunExplanation {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
        Objects.requireNonNull(conditions, "conditions must not be null");
        Objects.requireNonNull(actions, "actions must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(cascade, "cascade must not be null");
        conditions = List.copyOf(conditions);
        actions = List.copyOf(actions);
    }

    /**
     * Convenience constructor for the pre-v1.1.4 eight-component form (test-convenience;
     * production constructs the canonical form): delegates to the canonical constructor with
     * {@code definitionKey = null} (validation lives ONLY in the canonical constructor).
     */
    public RunExplanation(RunId runId, AutomationId automationId, String automationName,
                          TriggerView trigger, List<ConditionView> conditions,
                          List<ActionView> actions, OutcomeView outcome, CascadeView cascade) {
        this(runId, automationId, automationName, trigger, conditions, actions, outcome, cascade,
                null);
    }

    /**
     * The firing trigger. {@code type} is best-effort, resolved from the automation
     * definition's matched trigger (or {@code null} if the definition is gone);
     * {@code subjectRef} is the triggering event's subject; {@code matchedAt} is the trigger
     * time from the {@code automation_triggered} envelope; {@code firingValue} is, since v1.1.4
     * (EXPLAIN-114a), the value the triggering event carried — read from that event's payload
     * when it is in the run's correlation: a {@code state_changed}'s {@code newValue} in the
     * module's one string dialect ({@code AttributeValues.asString}, the same rendering the
     * chain's {@code observedState[].value} already uses), or a {@code state_reported}'s
     * {@code value} as recorded. Any other payload, or a triggering event the correlation read
     * cannot see, yields {@code null} — a fact the log does not carry is never guessed.
     *
     * @param type        the trigger type label, or {@code null}
     * @param subjectRef  the subject the trigger is about, or {@code null} if not recoverable
     * @param matchedAt   when the trigger matched, never {@code null}
     * @param firingValue the value that fired the trigger, or {@code null}
     */
    public record TriggerView(String type, SubjectRefView subjectRef, Instant matchedAt,
                              String firingValue) {
    }

    /**
     * The flattened {@code {type, id}} subject reference the wire uses for trigger subjects
     * and action targets.
     *
     * @param type the subject type (lowercase category, e.g. {@code "entity"}), or {@code null}
     * @param id   the subject's Crockford Base32 id, or {@code null}
     */
    public record SubjectRefView(String type, String id) {
    }

    /**
     * One evaluated condition. {@code evaluated} is always {@code true} (the
     * {@code automation_condition_evaluated} event is only emitted for an evaluated
     * condition; short-circuited conditions emit none). {@code expression} is a human
     * rendering derived from the event's {@code conditionType} (the YAML expression text is
     * not carried on the event). {@code definition} is, since v1.1.5 (EXPLAIN-114c), the
     * condition as STRUCTURED data, rendered from the registry definition the run ran under:
     * present exactly when the registry still holds that definition (the run's stamped
     * {@code definitionHash} equals {@code DefinitionHashes} over the current definition) and
     * the event's {@code conditionIndex} addresses one of its conditions; {@code null} when the
     * definition changed, the automation is gone, or the index is out of range — never a guess.
     *
     * @param expression    a human rendering of the condition, never {@code null}
     * @param evaluated     always {@code true} by construction
     * @param result        whether the condition held
     * @param observedState the entity state observed during evaluation; never {@code null}
     *                      (may be empty)
     * @param definition    the structured definition the registry vouches for, or {@code null}.
     *                      Additive v1.1.5 component (EXPLAIN-114c); nullable by contract, NOT
     *                      null-checked
     */
    public record ConditionView(String expression, boolean evaluated, boolean result,
                                List<ObservedStateEntry> observedState,
                                ConditionDefinitionView definition) {

        /**
         * Defensively copies {@code observedState}. {@code definition} is nullable by contract
         * (the additive-nullable idiom) and is NOT null-checked.
         *
         * @throws NullPointerException if {@code expression} or {@code observedState} is
         *                              {@code null}
         */
        public ConditionView {
            Objects.requireNonNull(expression, "expression must not be null");
            Objects.requireNonNull(observedState, "observedState must not be null");
            observedState = List.copyOf(observedState);
        }

        /**
         * Convenience constructor for the pre-v1.1.5 four-component form (test-convenience;
         * production constructs the canonical form): delegates to the canonical constructor
         * with {@code definition = null} (validation lives ONLY in the canonical constructor).
         */
        public ConditionView(String expression, boolean evaluated, boolean result,
                             List<ObservedStateEntry> observedState) {
            this(expression, evaluated, result, observedState, null);
        }
    }

    /**
     * The structured rendering of one {@code ConditionDefinition} (v1.1.5, EXPLAIN-114c) — the
     * operands a consumer can render from its own catalog instead of a sentence Core wrote. One
     * flat shape covers the seven permits: the components a permit does not carry are
     * {@code null}, and {@code children} is empty (never {@code null}) for every leaf. Rendered
     * by {@code ConditionDefinitionRenderer}, exhaustively over the sealed hierarchies.
     *
     * @param type      the definition's simple class name ({@code "StateCondition"} …
     *                  {@code "ZoneCondition"}) — the vocabulary {@link ConditionView#expression}
     *                  already carries; never {@code null}
     * @param selector  the selector rendered to ONE string — a {@code DirectRefSelector}'s entity
     *                  ULID text; a {@code SlugSelector}'s slug; the group permits as
     *                  {@code <kind>:<value>/<roles>} with {@code kind} in
     *                  {@code area|label|type|tag} (a {@code SemanticTagSelector} as
     *                  {@code tag:<namespace>/<value>/<matchMode>/<roles>}; the roles sorted by
     *                  name, joined by {@code ,}); a {@code CompoundSelector}'s parts joined by
     *                  {@code +} — or {@code null} for a permit with no selector
     * @param attribute a {@code StateCondition} / {@code NumericCondition}'s attribute, or {@code null}
     * @param value     a {@code StateCondition}'s expected value, or {@code null}
     * @param above     a {@code NumericCondition}'s lower bound, or {@code null}
     * @param below     a {@code NumericCondition}'s upper bound, or {@code null}
     * @param after     a {@code TimeCondition}'s {@code after}, or {@code null}
     * @param before    a {@code TimeCondition}'s {@code before}, or {@code null}
     * @param children  the rendered operands of an {@code AndCondition} / {@code OrCondition} in
     *                  definition order, the one operand of a {@code NotCondition}, or empty;
     *                  never {@code null}
     */
    public record ConditionDefinitionView(String type, String selector, String attribute,
                                          String value, Double above, Double below,
                                          String after, String before,
                                          List<ConditionDefinitionView> children) {

        /**
         * Validates {@code type} and defensively copies {@code children}; every other component
         * is nullable by contract.
         *
         * @throws NullPointerException if {@code type} or {@code children} is {@code null}
         */
        public ConditionDefinitionView {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(children, "children must not be null");
            children = List.copyOf(children);
        }
    }

    /**
     * One entity-attribute state observed during condition evaluation (projected from the
     * row-4 {@code EvaluatedEntityState}). {@code value} is {@code null} when the entity had
     * no value for the attribute.
     *
     * @param entityId  the entity's Crockford Base32 id, never {@code null}
     * @param attribute the attribute key, never {@code null}
     * @param value     the observed value, or {@code null} if unreported
     */
    public record ObservedStateEntry(String entityId, String attribute, String value) {
    }

    /**
     * One device-command action and its confirmation outcome (DP-A2). Anchored on a
     * {@code command_issued} event (the only source of command name + parameters + a single
     * target), or — for a command action skipped or failed before any command was issued — on
     * the {@code automation_action_completed} event (in which case {@code command} is
     * {@code null}).
     *
     * <p>{@code paramsJson} is the raw JSON-object string carried on {@code command_issued}
     * ({@code "{}"} when parameterless). The automation module owns no JSON library, so the
     * rest-api boundary parses it into the wire {@code params} object.</p>
     *
     * @param type          the action type label (e.g. {@code "CommandAction"}), never {@code null}
     * @param targetRef     the target entity, or {@code null} if not recoverable
     * @param command       the command name, or {@code null} for a skipped/failed action with no command
     * @param paramsJson    the raw command-parameter JSON string ({@code "{}"} when none), never {@code null}
     * @param outcome       the confirmation outcome (DP-A2), never {@code null}
     * @param reason        a human reason (failure/timeout detail), or {@code null}
     * @param resultOutcome the raw {@code command_result.outcome} string associated with this
     *                      action's command (the ten-value vocabulary plus adapter-specific
     *                      strings), or {@code null} when no {@code command_result} exists in the
     *                      chain. Additive v1.1.2 field (GAP-1) — a pure fact-carry, independent
     *                      of which precedence branch classified {@code outcome}
     * @param settled       whether this action's outcome can no longer change as late events
     *                      append to its (immutable, still-growing) correlation: {@code false}
     *                      exactly while the action is {@code DISPATCHED} with no settling record
     *                      ({@code resultOutcome} {@code null} or {@code "acknowledged"}); a
     *                      superseded {@code DISPATCHED} is settled — the ledger dropped it,
     *                      nothing further will arrive. Derived, never stored (INV-SA-03).
     *                      Additive v1.1.2 field (Q1b)
     * @param settledAt     the instant of the event that classified {@code outcome} — the
     *                      {@code state_confirmed} (CONFIRMED), the last classifying
     *                      {@code command_result} (FAILED, or UNCONFIRMED by result), the
     *                      {@code command_confirmation_timed_out} (UNCONFIRMED by timeout), or
     *                      the {@code automation_action_completed} of a command action that
     *                      issued no command (SKIPPED / FAILED); the envelope's {@code eventTime}
     *                      when present, else its {@code ingestTime}. {@code null} for
     *                      {@code DISPATCHED} (no classifying event — the command's own instant
     *                      is never used). Additive v1.1.4 field (EXPLAIN-114a); nullable
     * @param confirmedAt   the {@code state_confirmed} envelope's instant when one joined the
     *                      command (then {@code outcome == CONFIRMED} and
     *                      {@code confirmedAt == settledAt}); {@code null} otherwise. Additive
     *                      v1.1.4 field (EXPLAIN-114a); nullable
     */
    public record ActionView(String type, SubjectRefView targetRef, String command,
                             String paramsJson, ActionOutcome outcome, String reason,
                             String resultOutcome, boolean settled, Instant settledAt,
                             Instant confirmedAt) {

        /**
         * Validates the structural components. {@code resultOutcome} is intentionally nullable
         * (absent means no {@code command_result} in the chain); {@code settledAt} and
         * {@code confirmedAt} are nullable by contract (no classifying / confirming event) and
         * are NOT null-checked.
         *
         * @throws NullPointerException if {@code type}, {@code paramsJson}, or {@code outcome}
         *                              is {@code null}
         */
        public ActionView {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(paramsJson, "paramsJson must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /**
     * The terminal Run outcome. {@code status} is the internal {@link RunStatus}; the rest-api
     * boundary maps it to the wire vocabulary (DP-A1).
     *
     * @param status       the terminal {@link RunStatus}, never {@code null}
     * @param reason       the precise engine reason (failure/abort detail), or {@code null}
     * @param durationMs   the Run duration in milliseconds
     * @param actionCount  the number of actions executed
     * @param commandCount the number of commands issued
     */
    public record OutcomeView(RunStatus status, String reason, long durationMs, int actionCount,
                              int commandCount) {

        /**
         * Validates {@code status}.
         *
         * @throws NullPointerException if {@code status} is {@code null}
         */
        public OutcomeView {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /**
     * The Run's cascade position. {@code depth} is the flattened {@code cascadeDepth} the
     * {@code automation_triggered} event carries (0 for user/device-initiated Runs).
     * {@code parentRunId} is always {@code null} in V1: the events carry only the flattened
     * depth, never a parent Run id ({@code RunCausalChain} does not cross the event boundary).
     *
     * @param parentRunId the parent Run id, always {@code null} in V1
     * @param depth        the cascade depth (0 at the root)
     */
    public record CascadeView(RunId parentRunId, int depth) {
    }

    /**
     * The per-action confirmation outcome vocabulary (DP-A2, frozen v1.1 wire enum).
     *
     * <ul>
     *   <li>{@code CONFIRMED} — a {@code state_confirmed} event correlates the command to its
     *       expected outcome.</li>
     *   <li>{@code UNCONFIRMED} — dispatched but no confirmation by the deadline (a
     *       {@code command_confirmation_timed_out}).</li>
     *   <li>{@code FAILED} — a {@code command_result} failure.</li>
     *   <li>{@code DISPATCHED} — sent and still in-flight at read time, or terminal-by-design
     *       for a confirmation-disabled command (no confirmation expected). Never a false
     *       {@code CONFIRMED}.</li>
     *   <li>{@code SKIPPED} — the action was not executed (e.g. an offline target under
     *       {@code SKIP}, or fail-fast earlier in the sequence).</li>
     * </ul>
     */
    public enum ActionOutcome {
        DISPATCHED,
        CONFIRMED,
        UNCONFIRMED,
        FAILED,
        SKIPPED
    }
}

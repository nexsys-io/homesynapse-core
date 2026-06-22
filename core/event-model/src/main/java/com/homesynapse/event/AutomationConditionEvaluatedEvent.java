/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code automation_condition_evaluated} (AMD-92 row 4) — the explainability
 * record for one top-level condition of a Run: whether it held, and the entity states the
 * condition read against the trigger-time {@code StateSnapshot} (AMD-03).
 *
 * <p>Produced by the Automation Engine's condition gate during the EVALUATING phase, one
 * event per evaluated top-level condition (conditions short-circuit left-to-right; an
 * unevaluated condition produces no event — critical-review 2.3). This is the payload the
 * explainability hero view's causal chain reads to answer "why did (or didn't) this Run
 * proceed" (Doc 16 §3.3).</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> The payload references only
 * event-resident-or-below types. {@code runId} is a bare {@link Ulid} (the
 * automation-resident {@code RunId} is flattened); {@code conditionType} is the flattened
 * condition class name carried as a {@code String}. The nested {@link EvaluatedEntityState}
 * is an event-resident value record (the AMD-92 R92-2 nested-payload precedent), not a
 * top-level {@code @EventType} — it carries no automation-resident type.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}.</p>
 *
 * @param runId          the Run instance identifier (bare ULID — flattened
 *                       {@code RunId}); never {@code null}
 * @param conditionIndex the 0-based index of this condition in the automation's condition
 *                       list; {@code >= 0}
 * @param conditionType  the condition's flattened type name (e.g. {@code "StateCondition"});
 *                       never {@code null} or blank
 * @param result         whether the condition held
 * @param evaluatedState the entity states the condition read, unmodifiable (defensively
 *                       copied); never {@code null} (may be empty, e.g. for a time condition)
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_CONDITION_EVALUATED
 */
@EventType(EventTypes.AUTOMATION_CONDITION_EVALUATED)
public record AutomationConditionEvaluatedEvent(
        Ulid runId,
        int conditionIndex,
        String conditionType,
        boolean result,
        List<EvaluatedEntityState> evaluatedState
) implements DomainEvent {

    /**
     * Validates required fields, non-negative index, and deep-copies the evaluated-state
     * list to guarantee immutability.
     *
     * @throws NullPointerException     if {@code runId}, {@code conditionType}, or
     *                                  {@code evaluatedState} is {@code null}
     * @throws IllegalArgumentException if {@code conditionType} is blank or
     *                                  {@code conditionIndex} is negative
     */
    public AutomationConditionEvaluatedEvent {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(conditionType, "conditionType must not be null");
        Objects.requireNonNull(evaluatedState, "evaluatedState must not be null");
        if (conditionType.isBlank()) {
            throw new IllegalArgumentException("conditionType must not be blank");
        }
        if (conditionIndex < 0) {
            throw new IllegalArgumentException(
                    "conditionIndex must be >= 0: " + conditionIndex);
        }
        evaluatedState = List.copyOf(evaluatedState);
    }

    /**
     * The read-time state of one entity a condition observed (critical-review 2.2 read-time
     * version tracking). An event-resident value record (AMD-92 R92-2) — not a top-level
     * {@code @EventType}.
     *
     * @param entityRef           the observed entity, never {@code null}
     * @param attribute           the attribute the condition read, never {@code null}
     * @param value               the String projection of the observed attribute value
     *                            (AMD-52 not triggered — a plain {@code String}, never a typed
     *                            {@code AttributeValue}); {@code null} if the entity has no
     *                            value for that attribute
     * @param lastChangedAt       when the entity's state last changed, never {@code null}
     * @param lastChangedByEventId the event that last changed the entity's state;
     *                            {@code null} at this baseline — the materialized
     *                            {@code EntityState} does not yet track the last-changing
     *                            event id (populated when the state-store surfaces it)
     */
    public record EvaluatedEntityState(
            EntityId entityRef,
            String attribute,
            String value,
            Instant lastChangedAt,
            EventId lastChangedByEventId
    ) {

        /**
         * Validates the non-nullable fields. {@code value} and {@code lastChangedByEventId}
         * are nullable (an unreported attribute and the not-yet-tracked last-changing event
         * id, respectively).
         *
         * @throws NullPointerException if {@code entityRef}, {@code attribute}, or
         *                              {@code lastChangedAt} is {@code null}
         */
        public EvaluatedEntityState {
            Objects.requireNonNull(entityRef, "entityRef must not be null");
            Objects.requireNonNull(attribute, "attribute must not be null");
            Objects.requireNonNull(lastChangedAt, "lastChangedAt must not be null");
        }
    }
}

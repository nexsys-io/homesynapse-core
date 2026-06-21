/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.List;
import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code cascade_loop_detected} events — a candidate cascade Run was
 * suppressed because its automation already appears in the causal chain (Doc 07 §3.7.1;
 * AMD-92 row 18, AMD-91).
 *
 * <p>Emitted when initiating a Run whose automation is already a member of the candidate
 * causal chain (the deterministic chain-membership cycle test, AMD-91 — replacing AMD-04's
 * windowed suppression set). This catches the direct {@code A -> B -> A} cycle that the
 * depth ceiling alone permits. The candidate Run is suppressed (terminal-absent — no Run
 * object, never retried); this diagnostic is its only trace.</p>
 *
 * <p>The {@code chain} is the flattened cycle path: the chain's ancestor
 * {@link AutomationId}s in order, with the repeating automation appended — the projection
 * of AMD-91's {@code RunCausalChain} per the FLATTEN rule (AMD-92 §2.1).</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> {@code correlationId} and
 * {@code originalRunId} are bare {@link Ulid}s; {@code chain} is a {@code List} of the
 * platform-resident {@link AutomationId}. The automation-resident {@code RunCausalChain}
 * is consumed to derive {@code chain}, never referenced.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}.</p>
 *
 * @param automationId      the automation whose re-entry was suppressed; never
 *                          {@code null}
 * @param triggeringEventId the event that would have triggered the suppressed Run;
 *                          never {@code null}
 * @param correlationId     the causal chain's correlation id (bare ULID); never
 *                          {@code null}
 * @param originalRunId     the Run where the automation first entered the chain (bare
 *                          ULID — flattened {@code RunId}); never {@code null}
 * @param chain             the cycle path as ordered {@link AutomationId}s, unmodifiable
 *                          (defensively copied); never {@code null}
 * @see DomainEvent
 * @see CascadeDepthExceededEvent
 * @see EventTypes#CASCADE_LOOP_DETECTED
 */
@EventType(EventTypes.CASCADE_LOOP_DETECTED)
public record CascadeLoopDetectedEvent(
        AutomationId automationId,
        EventId triggeringEventId,
        Ulid correlationId,
        Ulid originalRunId,
        List<AutomationId> chain
) implements DomainEvent {

    /**
     * Validates that all fields are non-null and defensively copies {@code chain} to
     * guarantee immutability.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public CascadeLoopDetectedEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggeringEventId, "triggeringEventId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(originalRunId, "originalRunId must not be null");
        Objects.requireNonNull(chain, "chain must not be null");
        chain = List.copyOf(chain);
    }
}

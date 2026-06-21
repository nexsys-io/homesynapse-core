/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code cascade_depth_exceeded} events — a candidate cascade Run was
 * suppressed because its causal chain reached the depth ceiling (Doc 07 §3.7.1; AMD-92
 * row 17, AMD-91).
 *
 * <p>Emitted when a Run triggered by an event whose causal chain already spans
 * {@code automation.max_cascade_depth} hops would deepen the chain past the ceiling. The
 * candidate Run is suppressed (terminal-absent — no Run object, never retried); this
 * diagnostic is its only trace. The {@code cascadeDepth} value is the candidate chain's
 * derived depth (AMD-91 {@code RunCausalChain.depth()}).</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> Depth values are plain {@code int}s
 * and {@code correlationId} is a bare {@link Ulid}. No automation-resident type appears
 * here — the AMD-91 {@code RunCausalChain} is consumed to derive the flattened
 * {@code cascadeDepth}, never referenced.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}.</p>
 *
 * @param automationId      the automation whose cascade Run was suppressed; never
 *                          {@code null}
 * @param triggeringEventId the event that would have triggered the suppressed Run;
 *                          never {@code null}
 * @param cascadeDepth      the candidate chain depth that breached the ceiling;
 *                          {@code >= 0}
 * @param maxCascadeDepth   the configured cascade depth ceiling; {@code >= 1}
 * @param correlationId     the causal chain's correlation id (bare ULID); never
 *                          {@code null}
 * @see DomainEvent
 * @see CascadeLoopDetectedEvent
 * @see EventTypes#CASCADE_DEPTH_EXCEEDED
 */
@EventType(EventTypes.CASCADE_DEPTH_EXCEEDED)
public record CascadeDepthExceededEvent(
        AutomationId automationId,
        EventId triggeringEventId,
        int cascadeDepth,
        int maxCascadeDepth,
        Ulid correlationId
) implements DomainEvent {

    /**
     * Validates required fields and depth ranges.
     *
     * @throws NullPointerException     if {@code automationId}, {@code triggeringEventId},
     *                                  or {@code correlationId} is {@code null}
     * @throws IllegalArgumentException if {@code cascadeDepth} is negative or
     *                                  {@code maxCascadeDepth} is less than 1
     */
    public CascadeDepthExceededEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggeringEventId, "triggeringEventId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        if (cascadeDepth < 0) {
            throw new IllegalArgumentException("cascadeDepth must be >= 0: " + cascadeDepth);
        }
        if (maxCascadeDepth < 1) {
            throw new IllegalArgumentException(
                    "maxCascadeDepth must be >= 1: " + maxCascadeDepth);
        }
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * Tier 1 trigger that fires on explicit invocation (AMD-88 §2.2).
 *
 * <p>A {@code ManualTrigger} fires when an {@code automation_invoked} event is
 * received (produced by the REST {@code POST /automations/{id}/invoke} endpoint at
 * M10, a UI button, or voice). The invoking actor is carried on the envelope's
 * {@code actorRef}; the resulting Run's {@code triggeringEventId} references the
 * invocation event.</p>
 *
 * <p>An automation whose only trigger is a {@code ManualTrigger} is what other
 * platforms call a scene (the scenes-as-automations decision). This trigger has NO
 * {@code for_duration} — invocation is instantaneous.</p>
 *
 * <p>Defined in AMD-88 §2.2; Doc 07 §3.4, §8.2.</p>
 *
 * @param invocationContext a free-text origin note surfaced in the trace; {@code null}
 *                          when no context was supplied
 * @param triggerId         the stable, user-facing trigger identity (AMD-88 §2.5),
 *                          never {@code null}
 * @see TriggerDefinition
 */
public record ManualTrigger(
        String invocationContext,
        String triggerId
) implements TriggerDefinition {

    /**
     * Validates the trigger identity. The {@code invocationContext} is intentionally
     * nullable.
     *
     * @throws NullPointerException if {@code triggerId} is {@code null}
     */
    public ManualTrigger {
        Objects.requireNonNull(triggerId, "triggerId must not be null");
    }
}

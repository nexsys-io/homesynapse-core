/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Set;
import java.util.Objects;

/**
 * Tier 1 trigger that fires on an inbound webhook request (AMD-88 §2.3 — promoted
 * from Tier 2).
 *
 * <p>The {@code webhookId} is the path discriminator; {@code allowedMethods} carries
 * HTTP method names as plain strings (no HTTP-client type appears on the automation
 * API), defaulted at definition load to {@code {"POST"}}; {@code localOnly} defaults
 * to {@code true} (LAN-only exposure, consistent with the local-first posture).</p>
 *
 * <p>This trigger has NO {@code for_duration}. The producing event (the REST layer's
 * webhook-received publish) is named and minted by the M10 REST amendment — the
 * permit shape is frozen now so {@code automations.yaml} definitions are
 * forward-stable; the evaluator registers the route but matches nothing until M10
 * wires the producer (a benign no-match, not a Tier-2 fallback warning).</p>
 *
 * <p>Defined in AMD-88 §2.3; Doc 07 §3.4, §8.2.</p>
 *
 * @param webhookId      the path discriminator, never {@code null} or blank
 * @param allowedMethods the accepted HTTP method names, unmodifiable, never {@code null}
 * @param localOnly      whether the webhook is reachable only on the LAN
 * @param triggerId      the stable, user-facing trigger identity (AMD-88 §2.5),
 *                       never {@code null}
 * @see TriggerDefinition
 */
public record WebhookTrigger(
        String webhookId,
        Set<String> allowedMethods,
        boolean localOnly,
        String triggerId
) implements TriggerDefinition {

    /**
     * Validates non-null fields and makes {@code allowedMethods} unmodifiable.
     *
     * @throws NullPointerException     if {@code webhookId}, {@code allowedMethods},
     *                                  or {@code triggerId} is {@code null}
     * @throws IllegalArgumentException if {@code webhookId} is blank
     */
    public WebhookTrigger {
        Objects.requireNonNull(webhookId, "webhookId must not be null");
        if (webhookId.isBlank()) {
            throw new IllegalArgumentException("webhookId must not be blank");
        }
        Objects.requireNonNull(allowedMethods, "allowedMethods must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
        allowedMethods = Set.copyOf(allowedMethods);
    }
}

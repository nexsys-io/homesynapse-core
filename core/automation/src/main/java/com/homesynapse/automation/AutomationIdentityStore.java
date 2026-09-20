/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import com.homesynapse.platform.identity.AutomationId;

/**
 * Assigns and preserves automation and trigger identity across definition reloads
 * (Doc 07 §4.1; AMD-88 §2.5). An automation's {@link AutomationId} is assigned at first
 * load and matched by slug on reload; a trigger with no user-supplied {@code trigger_id}
 * is assigned a stable ULID keyed by {@code (automationSlug, triggerIndex)}.
 *
 * <p>The durable backing for these assignments is the {@code automations.ids.yaml}
 * companion file (AMD-93 §2.3): {@link CompanionAutomationIdentityStore} is the
 * implementation the composition root wires (AUTO-ID-1) — identity survives a restart —
 * over an {@link AutomationIdentityCompanion} whose file-backed form rides the
 * composition root with the configuration substrate.
 * {@link InMemoryAutomationIdentityStore} keeps the stability contract for one process
 * and is the test double.</p>
 *
 * <p>Thread-safe.</p>
 */
public interface AutomationIdentityStore {

    /**
     * Returns the stable {@link AutomationId} for an automation slug, assigning a new one
     * on first encounter and returning the same id on every subsequent call (reload).
     *
     * @param automationSlug the automation's slug, never {@code null}
     * @return the stable automation identity, never {@code null}
     */
    AutomationId automationIdFor(String automationSlug);

    /**
     * Returns the stable generated trigger ID for a trigger with no user-supplied
     * {@code trigger_id}, keyed by {@code (automationSlug, triggerIndex)}. The same key
     * yields the same ULID across reloads.
     *
     * @param automationSlug the owning automation's slug, never {@code null}
     * @param triggerIndex   the zero-based trigger position, {@code >= 0}
     * @return the stable generated trigger ID (a ULID string), never {@code null}
     */
    String generatedTriggerIdFor(String automationSlug, int triggerIndex);
}

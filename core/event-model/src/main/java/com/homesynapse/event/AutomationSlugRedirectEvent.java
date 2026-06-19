/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.EntityId;

/**
 * Diagnostic event emitted when a selector resolves a tombstoned (renamed) slug to a
 * current entity by following the tombstone chain (AMD-92 row 11; Identity Model §7.5).
 *
 * <p>Documents that an automation referenced an old slug that has since been renamed,
 * and the {@code SelectorResolver} transparently resolved it to the current entity
 * rather than failing. This prevents automations from silently breaking when entities
 * are renamed.</p>
 *
 * <p>Priority: DIAGNOSTIC. Subject: Entity.</p>
 *
 * @param requestedSlug   the original (tombstoned) slug the definition referenced,
 *                        never {@code null} or blank
 * @param resolvedSlug    the current slug the chain resolved to, never {@code null} or blank
 * @param resolvedEntityId the entity the chain resolved to, never {@code null}
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_SLUG_REDIRECT
 */
@EventType(EventTypes.AUTOMATION_SLUG_REDIRECT)
public record AutomationSlugRedirectEvent(
        String requestedSlug,
        String resolvedSlug,
        EntityId resolvedEntityId
) implements DomainEvent {

    /**
     * Validates required fields.
     *
     * @throws NullPointerException     if any field is {@code null}
     * @throws IllegalArgumentException if {@code requestedSlug} or {@code resolvedSlug}
     *                                  is blank
     */
    public AutomationSlugRedirectEvent {
        Objects.requireNonNull(requestedSlug, "requestedSlug must not be null");
        if (requestedSlug.isBlank()) {
            throw new IllegalArgumentException("requestedSlug must not be blank");
        }
        Objects.requireNonNull(resolvedSlug, "resolvedSlug must not be null");
        if (resolvedSlug.isBlank()) {
            throw new IllegalArgumentException("resolvedSlug must not be blank");
        }
        Objects.requireNonNull(resolvedEntityId, "resolvedEntityId must not be null");
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Fires on a specific event type, optionally filtered by payload key-value pairs.
 *
 * <p>Event triggers are inherently instantaneous — they match a specific event
 * occurrence, not a sustained state. Therefore, {@code for_duration} is NOT supported
 * on this trigger type. This is a deliberate design decision from AMD-25.</p>
 *
 * <p>Defined in Doc 07 §3.4, §8.2; {@code triggerId} added by AMD-88 §2.5.</p>
 *
 * @param eventType      the event type to match (e.g., {@code "state_changed"}),
 *                       never {@code null}
 * @param payloadFilters key-value pairs that must match in the event payload;
 *                       unmodifiable and deterministically ordered by key, possibly empty,
 *                       never {@code null}
 * @param triggerId      the stable, user-facing trigger identity (AMD-88 §2.5), never {@code null}
 * @see TriggerDefinition
 * @see TriggerEvaluator
 */
public record EventTrigger(
        String eventType,
        Map<String, Object> payloadFilters,
        String triggerId
) implements TriggerDefinition {

    /**
     * Validates non-null fields and stores the filters as an unmodifiable, deterministically
     * ordered (by key) copy — the rendering {@code DefinitionHashes} hashes (HASH-1).
     *
     * @throws NullPointerException if {@code eventType}, {@code payloadFilters},
     *                              or {@code triggerId} is {@code null}
     */
    public EventTrigger {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payloadFilters, "payloadFilters must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
        payloadFilters = Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(payloadFilters)));
    }
}

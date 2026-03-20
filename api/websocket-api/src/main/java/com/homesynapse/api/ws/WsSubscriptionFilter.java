/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.List;

/**
 * Wire-format subscription filter as received from the client in a
 * {@link SubscribeMsg}.
 *
 * <p>All fields are nullable. A {@code null} or empty filter receives all events.
 * Fields combine with AND semantics across fields and OR semantics within
 * array fields. For example, specifying both {@code eventTypes} and
 * {@code subjectRefs} delivers events that match any listed event type AND
 * reference any listed subject.</p>
 *
 * <p>Phase 3 resolves indirect filter fields ({@code areaRefs},
 * {@code labelRefs}, {@code entityTypes}, {@code capabilities}) to a
 * materialized set of subject ref ULIDs at subscription creation time.
 * The resolved set is cached and does not update dynamically — entities
 * added after subscription creation are not included (Glossary §1.5,
 * label resolution determinism). If the resolved set exceeds
 * {@code max_resolved_subjects} (default 500), the subscription is rejected
 * with a {@code filter-too-broad} error.</p>
 *
 * <p>All identifier fields use {@code String} at the wire boundary, consistent
 * with the REST API's approach (Doc 09 §3.10, LTD-04). Phase 3 validates
 * and converts these to typed identifiers.</p>
 *
 * <p>Thread-safe (immutable record with unmodifiable collections).</p>
 *
 * @param eventTypes    event type names to match (e.g., {@code "device.state_changed"}),
 *                      {@code null} or empty to match all types
 * @param subjectRefs   ULID strings of specific subjects to match,
 *                      {@code null} or empty to match all subjects
 * @param areaRefs      ULID strings of areas; resolved to subject refs at
 *                      subscription creation, {@code null} to skip
 * @param labelRefs     ULID strings of labels; resolved like {@code areaRefs},
 *                      {@code null} to skip
 * @param entityTypes   entity type names (e.g., {@code "light"}, {@code "sensor"}),
 *                      {@code null} to skip
 * @param capabilities  capability names (e.g., {@code "on_off"},
 *                      {@code "temperature_measurement"}), {@code null} to skip
 * @param minPriority   minimum event priority ({@code "CRITICAL"}, {@code "NORMAL"},
 *                      or {@code "DIAGNOSTIC"}); {@code null} to receive all priorities
 * @param stateChangeOnly if {@code true}, suppresses redundant {@code state_reported}
 *                        events where the value has not changed; {@code null} defaults
 *                        to {@code false}
 * @param minIntervalMs coalescing floor in milliseconds; {@code null} to use server
 *                      default
 * @param maxIntervalMs coalescing ceiling in milliseconds; {@code null} to use server
 *                      default
 *
 * @see SubscribeMsg
 * @see SubscriptionConfirmedMsg
 * @see WsSubscription
 * @see <a href="Doc 10 §3.4">Subscription Management</a>
 */
public record WsSubscriptionFilter(
        List<String> eventTypes,
        List<String> subjectRefs,
        List<String> areaRefs,
        List<String> labelRefs,
        List<String> entityTypes,
        List<String> capabilities,
        String minPriority,
        Boolean stateChangeOnly,
        Integer minIntervalMs,
        Integer maxIntervalMs
) {

    /**
     * Creates a subscription filter, making all non-null list fields unmodifiable.
     */
    public WsSubscriptionFilter {
        eventTypes = eventTypes != null ? List.copyOf(eventTypes) : null;
        subjectRefs = subjectRefs != null ? List.copyOf(subjectRefs) : null;
        areaRefs = areaRefs != null ? List.copyOf(areaRefs) : null;
        labelRefs = labelRefs != null ? List.copyOf(labelRefs) : null;
        entityTypes = entityTypes != null ? List.copyOf(entityTypes) : null;
        capabilities = capabilities != null ? List.copyOf(capabilities) : null;
    }
}
